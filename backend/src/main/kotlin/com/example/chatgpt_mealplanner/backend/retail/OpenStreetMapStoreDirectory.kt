package com.example.chatgpt_mealplanner.backend.retail

import com.example.chatgpt_mealplanner.retail.contract.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

fun interface StoreDirectoryProvider {
    suspend fun search(request: StoreDirectorySearchRequest): StoreDirectorySearchResponse
}

interface ApiKeyConfigurableStoreDirectoryProvider {
    fun configureApiKey(apiKey: String?)
}

interface StoreDirectoryProgressProvider : StoreDirectoryProvider {
    suspend fun search(
        request: StoreDirectorySearchRequest,
        onProgress: (String) -> Unit
    ): StoreDirectorySearchResponse

    override suspend fun search(request: StoreDirectorySearchRequest): StoreDirectorySearchResponse =
        search(request) { }
}

/** OSM IDs are directory IDs only; retailerStoreId is never inferred from them. */
class OpenStreetMapStoreDirectory(
    private val client: HttpClient,
    private val clock: () -> Long = System::currentTimeMillis
) : StoreDirectoryProgressProvider, ApiKeyConfigurableStoreDirectoryProvider {
    @Volatile private var configuredApiKey: String? = null

    override fun configureApiKey(apiKey: String?) {
        configuredApiKey = apiKey?.trim()?.takeIf { it.isNotBlank() }
    }

    constructor() : this(HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            // Allow time for a bounded Overpass query to wait for a worker and return its payload.
            socketTimeoutMillis = 75_000
            requestTimeoutMillis = 75_000
        }
    })

    private data class CacheEntry(val expiresAt: Long, val response: StoreDirectorySearchResponse)
    private data class GeocodeCacheEntry(val expiresAt: Long, val latitude: Double, val longitude: Double)
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val geocodeCache = ConcurrentHashMap<String, GeocodeCacheEntry>()
    private val overpassMutex = Mutex()
    private val geocodeMutex = Mutex()
    // Keep a small number of fallback requests in flight while the shared gate below
    // spaces request starts to respect the public Nominatim service's usage policy.
    private val nominatimFallbackSemaphore = Semaphore(2)
    @Volatile private var lastOverpassAt = 0L
    @Volatile private var lastGeocodeAt = 0L
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(
        request: StoreDirectorySearchRequest,
        onProgress: (String) -> Unit
    ): StoreDirectorySearchResponse {
        require(request.radiusMeters in 500..15_000) { "Radius must be between 500 and 15000 metres." }
        val locationStarted = System.nanoTime()
        val (lat, lon) = resolveCenter(request, onProgress)
        logTiming(if (request.postcode.isNullOrBlank()) "coordinate_resolution" else "postcode_lookup", locationStarted)
        // Shared cache is keyed only by location/radius because each Overpass request
        // now retrieves all supported retailers in the area.
        val key = "shops-v20:${"%.4f".format(Locale.US, lat)}:${"%.4f".format(Locale.US, lon)}:${request.radiusMeters}"
        onProgress("Checking cached nearby-store results…")
        val cached = cache[key]
        if (cached != null) {
            if (cached.expiresAt > clock()) {
                val matching = if (request.searchAllRetailers) cached.response.stores
                    else cached.response.stores.filter { it.retailerId == request.retailerId }
                if (matching.isNotEmpty() || request.searchAllRetailers) {
                    return cached.response.copy(stores = matching, fromCache = true)
                }
                // The area has already had its shared search. If this retailer was absent,
                // use only its targeted fallback rather than repeating the multi-retailer query.
                if (configuredApiKey == null) {
                    val fallbackStores = try {
                        kotlinx.coroutines.withTimeout(12_000L) {
                            searchNominatimFallback(request.retailerId, lat, lon, request.radiusMeters)
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                        emptyList()
                    }
                    val combined = (cached.response.stores + fallbackStores).distinctBy { it.directorySourceId }
                    cache[key] = cached.copy(response = cached.response.copy(stores = combined))
                    val found = fallbackStores.sortedBy { it.distanceMeters ?: Int.MAX_VALUE }.take(50)
                    return StoreDirectorySearchResponse(
                        stores = found,
                        observedAt = clock(),
                        warnings = if (found.isEmpty()) listOf("No matching branch was found nearby. Coverage may be incomplete; verify with the retailer.") else emptyList()
                    )
                }
                return StoreDirectorySearchResponse(
                    stores = emptyList(),
                    observedAt = cached.response.observedAt,
                    warnings = listOf("No matching branch was found nearby. Coverage may be incomplete; verify with the retailer."),
                    fromCache = true
                )
            } else {
                cache.remove(key, cached)
            }
        }

        onProgress("Searching once for all supported grocery retailers nearby…")
        overpassMutex.withLock {
            val wait = 2_100L - (clock() - lastOverpassAt)
            if (wait > 0) delay(wait)
            lastOverpassAt = clock()
        }

        var usedNominatimFallback = false
        var overpassSucceeded = false
        val allStores = try {
            val overpassStarted = System.nanoTime()
            val raw = fetchOverpassResponse(buildMultiRetailerQuery(lat, lon, request.radiusMeters), onProgress)
            overpassSucceeded = true
            logTiming("overpass_request", overpassStarted)
            val parseStarted = System.nanoTime()
            val mappedStores = parseAllRetailersOverpassResponse(raw, lat, lon)
            logTiming("overpass_parse", parseStarted)
            // Recover each missing retailer independently. A shared query can find ASDA or
            // Aldi while missing Tesco Express / Morrisons Daily, so falling back only when
            // the entire combined result is empty silently hides valid nearby branches.
            val fallbackStarted = System.nanoTime()
            val fallbackStores = when {
                request.searchAllRetailers -> {
                    // A retailer appearing in the shared map response does not mean all
                    // of its nearby branches were found. Supplement every supported retailer,
                    // not just retailers absent from the response, so smaller formats and
                    // branches omitted by the bounded map query can still be discovered.
                    val retailersToSupplement = supportedBranchRetailers()
                    if (retailersToSupplement.isEmpty()) {
                        emptyList()
                    } else {
                        onProgress("Checking nearby listings for smaller and missing store formats…")
                        try {
                            kotlinx.coroutines.withTimeout(30_000L) {
                                searchNominatimMissingRetailersFallback(
                                    lat, lon, request.radiusMeters, retailersToSupplement
                                )
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                            emptyList()
                        }
                    }
                }
                else -> {
                    // Even when the shared query found one branch for the selected retailer,
                    // supplement it: nearby branches can be missing independently (e.g. one
                    // Morrisons Daily is mapped while Cromwell Road is absent).
                    onProgress("Checking for additional ${displayName(request.retailerId)} branches…")
                    try {
                        kotlinx.coroutines.withTimeout(12_000L) {
                            searchNominatimFallback(request.retailerId, lat, lon, request.radiusMeters)
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                        emptyList()
                    }
                }
            }
            logTiming("nominatim_fallbacks", fallbackStarted)
            if (fallbackStores.isNotEmpty()) usedNominatimFallback = true
            (mappedStores + fallbackStores).distinctBy { it.directorySourceId }
        } catch (e: Exception) {
            logTiming("overpass_failed_or_fallback_failed", locationStarted)
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (configuredApiKey != null) throw e
            usedNominatimFallback = true
            if (request.searchAllRetailers) {
                throw IllegalStateException(
                    "The shared nearby-store search is unavailable. Please retry later or add a branch manually.",
                    e
                )
            }
            onProgress("The shared branch search is unavailable; checking ${displayName(request.retailerId)} listings…")
            val fallbackStores = try {
                kotlinx.coroutines.withTimeout(12_000L) {
                    searchNominatimFallback(request.retailerId, lat, lon, request.radiusMeters)
                }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                throw IllegalStateException(
                    "The public OpenStreetMap fallback timed out. Please retry later or add the branch manually.",
                    timeout
                )
            }
            if (fallbackStores.isEmpty()) {
                throw IllegalStateException(
                    "Branch search failed and OpenStreetMap did not return matching branch details. You can retry or enter a branch manually.",
                    e
                )
            }
            fallbackStores
        }.sortedBy { it.distanceMeters ?: Int.MAX_VALUE }

        // Morrisons' official store finder confirms this branch, but its OSM listing is
        // missing or inconsistently tagged in some map results. Keep this verified official
        // directory entry as a local fallback until retailer-wide directory adapters exist.
        val officialSeeds = if (request.searchAllRetailers || request.retailerId == RetailerId.MORRISONS) {
            val distance = distanceMeters(lat, lon, 53.280218, -2.894101)
            if (distance <= request.radiusMeters) {
                listOf(
                    StoreDirectoryEntry(
                        directorySource = StoreDirectorySource.RETAILER_DIRECTORY,
                        directorySourceId = "morrisons:61527",
                        retailerId = RetailerId.MORRISONS,
                        name = "Morrisons Daily",
                        address = "6 Cromwell Road, Ellesmere Port, CH65 4AA",
                        postcode = "CH65 4AA",
                        latitude = 53.280218,
                        longitude = -2.894101,
                        format = RetailStoreFormat.MORRISONS_DAILY,
                        distanceMeters = distance
                    )
                )
            } else emptyList()
        } else emptyList()
        // Prefer the verified retailer-directory record when OSM reports the same branch.
        val discoveredStores = (officialSeeds + allStores).distinctBy { store ->
            if (store.retailerId == RetailerId.MORRISONS &&
                store.postcode.equals("CH65 4AA", ignoreCase = true) &&
                store.name.equals("Morrisons Daily", ignoreCase = true)
            ) "morrisons:daily:CH65 4AA" else store.directorySourceId
        }.sortedBy { it.distanceMeters ?: Int.MAX_VALUE }
        val requestedStores = (if (request.searchAllRetailers) discoveredStores else discoveredStores.filter { it.retailerId == request.retailerId })
            .take(if (request.searchAllRetailers) 100 else 50)
        val response = StoreDirectorySearchResponse(
            stores = requestedStores,
            observedAt = clock(),
            warnings = when {
                usedNominatimFallback && overpassSucceeded -> listOf("Some branches were found using OpenStreetMap search listings because the main map query may miss smaller stores. Branch coverage and details may be incomplete; check the branch name before saving.")
                usedNominatimFallback -> listOf("The main branch map search was unavailable. These results came from OpenStreetMap search listings and may be incomplete; check the branch name before saving.")
                requestedStores.isEmpty() -> listOf("No matching supported retailer branches were found nearby. Coverage may be incomplete; verify with the retailer.")
                else -> emptyList()
            }
        )
        // Only cache a complete multi-retailer map result. If Overpass failed, the fallback
        // contains only one retailer and must not be mistaken for the whole area's inventory.
        if (overpassSucceeded && allStores.isNotEmpty()) {
            val combined = (cached?.response?.stores.orEmpty() + discoveredStores).distinctBy { store ->
                if (store.retailerId == RetailerId.MORRISONS &&
                    store.postcode.equals("CH65 4AA", ignoreCase = true) &&
                    store.name.equals("Morrisons Daily", ignoreCase = true)
                ) "morrisons:daily:CH65 4AA" else store.directorySourceId
            }
            cache[key] = CacheEntry(
                clock() + 6 * 60 * 60 * 1000L,
                StoreDirectorySearchResponse(stores = combined, observedAt = clock())
            )
            if (cache.size > 200) cache.entries.sortedBy { it.value.expiresAt }
                .take(cache.size - 200).forEach { cache.remove(it.key, it.value) }
        }
        return response
    }

    private fun parseAllRetailersOverpassResponse(
        raw: String,
        originLat: Double,
        originLon: Double
    ): List<StoreDirectoryEntry> {
        val supportedRetailers = listOf(
            RetailerId.ASDA,
            RetailerId.TESCO,
            RetailerId.MORRISONS,
            RetailerId.SAINSBURYS,
            RetailerId.ICELAND,
            RetailerId.ALDI,
            RetailerId.LIDL
        )
        return supportedRetailers.flatMap { retailerId ->
            parseOverpassResponse(raw, retailerId, originLat, originLon)
        }.distinctBy { it.directorySourceId }
    }

    internal fun buildMultiRetailerQuery(lat: Double, lon: Double, radiusMeters: Int): String {
        val allRetailersPattern = "ASDA|Iceland|Food.*Warehouse|Tesco|Morrisons|McColl.?s|Sainsbury|Aldi|Lidl"
        return """[out:json][timeout:60];
          area["ISO3166-1"="GB"][admin_level=2]->.uk;
          (
            nwr["shop"]["name"~"$allRetailersPattern",i](around:$radiusMeters,$lat,$lon)(area.uk);
            nwr["shop"]["brand"~"$allRetailersPattern",i](around:$radiusMeters,$lat,$lon)(area.uk);
            nwr["shop"]["operator"~"$allRetailersPattern",i](around:$radiusMeters,$lat,$lon)(area.uk);
            nwr["name"~"$allRetailersPattern",i](around:$radiusMeters,$lat,$lon)(area.uk);
            nwr["brand"~"$allRetailersPattern",i](around:$radiusMeters,$lat,$lon)(area.uk);
            nwr["operator"~"$allRetailersPattern",i](around:$radiusMeters,$lat,$lon)(area.uk);
          );
          out center tags;"""
    }

    private suspend fun fetchOverpassResponse(query: String, onProgress: (String) -> Unit): String {
        val failures = mutableListOf<String>()
        val key = configuredApiKey
        val endpoints = if (key != null) {
            require(key.matches(Regex("^[A-Za-z0-9._~-]+$"))) {
                "The Overpass.ch API key contains unsupported characters. Check the key in Settings."
            }
            // A configured key is an explicit choice: don't silently send a slow query to
            // unrelated public instances if the keyed service rejects or times out.
            listOf("https://api.overpass.ch/v1/$key/interpreter")
        } else {
            OVERPASS_ENDPOINTS
        }
        endpoints.forEachIndexed { index, endpoint ->
            val serviceName = if (index == 0 && key != null) "api.overpass.ch" else endpoint.removePrefix("https://").substringBefore('/')
            try {
                if (index > 0) {
                    onProgress("The first branch-search service did not respond; trying backup service ${index} of ${OVERPASS_ENDPOINTS.lastIndex}…")
                }
                val response = client.post(endpoint) {
                    header(HttpHeaders.UserAgent, USER_AGENT)
                    // Overpass quick start: POST the query as URL-encoded form field "data".
                    setBody(FormDataContent(Parameters.build { append("data", query) }))
                }
                if (response.status.value !in 200..299) {
                    val detail = response.bodyAsText().lineSequence().firstOrNull()
                        ?.replace(Regex("\\s+"), " ")
                        ?.let { redactApiKey(it, key) }
                        ?.take(160)
                        ?.takeIf { it.isNotBlank() }
                    failures += buildString {
                        append("$serviceName: HTTP ${response.status.value}")
                        if (detail != null) append(" ($detail)")
                    }
                    return@forEachIndexed
                }
                return response.bodyAsText()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val reason = e.message?.lineSequence()?.firstOrNull()
                    ?.let { redactApiKey(it, key) }
                    ?.take(120)
                    ?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "request failed"
                failures += "$serviceName: $reason"
            }
        }
        throw IllegalStateException(
            "All branch directory services failed (${failures.joinToString("; ")}). " +
                "Please retry later, or add the branch manually."
        )
    }

    /** Prevent the keyed endpoint path from leaking through server or exception diagnostics. */
    private fun redactApiKey(message: String, apiKey: String?): String {
        if (apiKey.isNullOrBlank()) return message
        return message.replace(apiKey, "[redacted]")
    }

    internal fun buildQuery(request: StoreDirectorySearchRequest, lat: Double, lon: Double): String {
        // Match retailer identity against every shop type, without restricting the branch format.
        // Search retail shop features only. Petrol-station amenities and fuel forecourts are
        // intentionally excluded because they are not useful branch matches for this finder.
        val radius = request.radiusMeters
        val retailerPattern = overpassRetailerPattern(request.retailerId)
        // Food Warehouse is Iceland's larger-format grocery brand. Some branches are
        // mapped only as "The Food Warehouse", with no Iceland brand/operator tag.
        val relatedIcelandBrand = if (request.retailerId == RetailerId.ICELAND) {
            """
            nwr["shop"]["name"~"Food.*Warehouse",i](around:$radius,$lat,$lon)(area.uk);
            nwr["shop"]["brand"~"Food.*Warehouse",i](around:$radius,$lat,$lon)(area.uk);
            nwr["shop"]["operator"~"Food.*Warehouse",i](around:$radius,$lat,$lon)(area.uk);
            nwr["name"~"Food.*Warehouse",i](around:$radius,$lat,$lon)(area.uk);
            nwr["brand"~"Food.*Warehouse",i](around:$radius,$lat,$lon)(area.uk);
            nwr["operator"~"Food.*Warehouse",i](around:$radius,$lat,$lon)(area.uk);
            """
        } else ""
        // Search retailer identity tags even when shop=* is missing. The parser filters
        // non-retail matches and the known petrol-station variants.
        return """[out:json][timeout:60];
          area["ISO3166-1"="GB"][admin_level=2]->.uk;
          (
            nwr["shop"]["name"~"$retailerPattern",i](around:$radius,$lat,$lon)(area.uk);
            nwr["shop"]["brand"~"$retailerPattern",i](around:$radius,$lat,$lon)(area.uk);
            nwr["shop"]["operator"~"$retailerPattern",i](around:$radius,$lat,$lon)(area.uk);
            nwr["name"~"$retailerPattern",i](around:$radius,$lat,$lon)(area.uk);
            nwr["brand"~"$retailerPattern",i](around:$radius,$lat,$lon)(area.uk);
            nwr["operator"~"$retailerPattern",i](around:$radius,$lat,$lon)(area.uk);
            $relatedIcelandBrand
          );
          out center tags;"""
    }

    private suspend fun resolveCenter(
        request: StoreDirectorySearchRequest,
        onProgress: (String) -> Unit
    ): Pair<Double, Double> {
        val geocodeStarted = System.nanoTime()
        val postcodeCacheKey = request.postcode?.trim()?.uppercase(Locale.UK)?.filterNot(Char::isWhitespace)
        if (postcodeCacheKey != null) {
            val cachedLocation = geocodeCache[postcodeCacheKey]
            if (cachedLocation != null && cachedLocation.expiresAt > clock()) {
                logTiming("postcode_lookup_cache_hit", geocodeStarted)
                return cachedLocation.latitude to cachedLocation.longitude
            }
            if (cachedLocation != null) geocodeCache.remove(postcodeCacheKey, cachedLocation)
        }
        val hasLat = request.latitude != null
        val hasLon = request.longitude != null
        require(hasLat == hasLon) { "Provide both coordinates, or a UK postcode." }
        if (hasLat && hasLon) {
            val lat = requireNotNull(request.latitude)
            val lon = requireNotNull(request.longitude)
            require(lat in -90.0..90.0 && lon in -180.0..180.0) { "Coordinates are out of range." }
            require(request.postcode.isNullOrBlank()) { "Use postcode or coordinates, not both." }
            return lat to lon
        }
        val postcode = request.postcode?.trim()?.uppercase()
        require(!postcode.isNullOrBlank() && postcode.length <= 12 && postcode.matches(Regex("^[A-Z0-9 ]+$"))) {
            "A valid UK postcode or coordinates are required."
        }
        onProgress("Finding the location for the UK postcode…")
        geocodeMutex.withLock {
            val wait = 1_100L - (clock() - lastGeocodeAt)
            if (wait > 0) delay(wait)
            lastGeocodeAt = clock()
        }
        val raw = try {
            kotlinx.coroutines.withTimeout(10_000L) {
                client.get("https://nominatim.openstreetmap.org/search") {
                    parameter("q", "$postcode, United Kingdom")
                    parameter("format", "jsonv2")
                    parameter("limit", "1")
                    header(HttpHeaders.UserAgent, USER_AGENT)
                }.bodyAsText()
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw IllegalStateException(
                "Postcode lookup timed out while contacting OpenStreetMap. Please retry or use coordinates.",
                timeout
            )
        }
        val first = parseJsonResponse(raw, "Postcode lookup").jsonArray.firstOrNull()?.jsonObject
            ?: throw IllegalArgumentException("Postcode could not be located. Try coordinates instead.")
        val lat = first["lat"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            ?: throw IllegalStateException("Postcode lookup returned no latitude.")
        val lon = first["lon"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            ?: throw IllegalStateException("Postcode lookup returned no longitude.")
        if (postcodeCacheKey != null) {
            geocodeCache[postcodeCacheKey] = GeocodeCacheEntry(
                expiresAt = clock() + 30L * 24 * 60 * 60 * 1000,
                latitude = lat,
                longitude = lon
            )
            if (geocodeCache.size > 500) {
                geocodeCache.entries.sortedBy { it.value.expiresAt }
                    .take(geocodeCache.size - 500).forEach { geocodeCache.remove(it.key, it.value) }
            }
        }
        return lat to lon
    }

    private fun supportedBranchRetailers() = listOf(
        RetailerId.ASDA,
        RetailerId.TESCO,
        RetailerId.MORRISONS,
        RetailerId.SAINSBURYS,
        RetailerId.ICELAND,
        RetailerId.ALDI,
        RetailerId.LIDL
    )

    private suspend fun searchNominatimMissingRetailersFallback(
        originLat: Double,
        originLon: Double,
        radiusMeters: Int,
        missingRetailers: List<RetailerId>
    ): List<StoreDirectoryEntry> = coroutineScope {
        missingRetailers.map { retailerId ->
            async {
                nominatimFallbackSemaphore.withPermit {
                    searchNominatimFallback(retailerId, originLat, originLon, radiusMeters)
                }
            }
        }.awaitAll().flatten().distinctBy { it.directorySourceId }
    }

    private suspend fun searchNominatimFallback(
        retailerId: RetailerId,
        originLat: Double,
        originLon: Double,
        radiusMeters: Int
    ): List<StoreDirectoryEntry> {
        // Iceland searches must also query the related Food Warehouse brand: Nominatim
        // does not guarantee that an "Iceland" text search returns Food Warehouse branches.
        val searchTerms = when (retailerId) {
            RetailerId.ICELAND -> listOf("Iceland", "Food Warehouse")
            // A broad "Tesco" text search can be dominated by larger supermarkets and
            // may omit the local convenience branch from Nominatim's limited result set.
            RetailerId.TESCO -> listOf("Tesco Express", "Tesco")
            RetailerId.MORRISONS -> listOf("Morrisons Daily", "Morrisons", "McColl's")
            else -> listOf(displayName(retailerId))
        }
        return searchTerms.flatMap { term ->
            searchNominatimTerm(retailerId, originLat, originLon, radiusMeters, term)
        }.distinctBy { it.directorySourceId }
    }

    private suspend fun searchNominatimTerm(
        retailerId: RetailerId,
        originLat: Double,
        originLon: Double,
        radiusMeters: Int,
        searchTerm: String
    ): List<StoreDirectoryEntry> {
        // Nominatim is a fallback search, not an Overpass interpreter. Only use actual
        // OSM results and their address tags; never copy the user's search postcode into
        // a returned branch record.
        geocodeMutex.withLock {
            val wait = 1_100L - (clock() - lastGeocodeAt)
            if (wait > 0) delay(wait)
            lastGeocodeAt = clock()
        }
        val latDelta = radiusMeters / 111_000.0
        val lonDelta = radiusMeters / (111_000.0 * cos(Math.toRadians(originLat)).coerceAtLeast(0.1))
        val raw = client.get("https://nominatim.openstreetmap.org/search") {
            parameter("q", searchTerm)
            parameter("countrycodes", "gb")
            parameter("format", "jsonv2")
            parameter("addressdetails", "1")
            parameter("extratags", "1")
            parameter("limit", "20")
            parameter("viewbox", "${originLon - lonDelta},${originLat + latDelta},${originLon + lonDelta},${originLat - latDelta}")
            parameter("bounded", "1")
            header(HttpHeaders.UserAgent, USER_AGENT)
        }.bodyAsText()
        return parseJsonResponse(raw, "OpenStreetMap fallback search").jsonArray.mapNotNull { result ->
            val item = result.jsonObject
            val listingClass = item.text("category") ?: item.text("class")
            val listingType = item.text("type")?.lowercase()
            val osmType = item.text("osm_type")?.lowercase() ?: return@mapNotNull null
            val osmId = item.text("osm_id") ?: return@mapNotNull null
            val lat = item.text("lat")?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = item.text("lon")?.toDoubleOrNull() ?: return@mapNotNull null
            val extratags = item["extratags"]?.jsonObject
            val name = item.text("name")
                ?: item.text("display_name")?.substringBefore(",")?.trim()?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val identity = listOfNotNull(name, extratags?.text("brand"), extratags?.text("operator")).joinToString(" ")
            val effectiveShopType = extratags?.text("shop")?.lowercase() ?: listingType
            val amenity = extratags?.text("amenity")?.lowercase()
            if (isExcludedPetrolVariant(identity, retailerId, amenity, listingClass, listingType)) return@mapNotNull null
            val isMappedShop = listingClass == "shop" && effectiveShopType !in NON_RETAIL_SHOP_TYPES
            val isNamedRetailFeature = effectiveShopType == null &&
                listingType !in NON_RETAIL_FEATURE_TYPES &&
                matchesRetailer(identity, retailerId)
            // If the only mapped feature is an ATM but its name explicitly identifies a
            // convenience/supermarket branch, retain it as a branch candidate. Do not turn
            // generic retailer-branded ATMs into stores.
            val isBrandedStoreAtm = listingClass == "amenity" && listingType == "atm" &&
                Regex("\\b(express|daily|local|superstore|supermarket|extra|metro)\\b", RegexOption.IGNORE_CASE)
                    .containsMatchIn(name) &&
                matchesRetailer(identity, retailerId)
            if (!isMappedShop && !isNamedRetailFeature && !isBrandedStoreAtm) return@mapNotNull null
            if (!matchesRetailer(identity, retailerId)) return@mapNotNull null
            val addressTags = item["address"]?.jsonObject
            val postcode = addressTags?.text("postcode")?.uppercase()
            val address = listOfNotNull(
                listOfNotNull(addressTags?.text("house_number"), addressTags?.text("road")).joinToString(" ").takeIf(String::isNotBlank),
                addressTags?.text("suburb") ?: addressTags?.text("neighbourhood"),
                addressTags?.text("city") ?: addressTags?.text("town") ?: addressTags?.text("village"),
                postcode
            ).joinToString(", ")
            StoreDirectoryEntry(
                directorySource = StoreDirectorySource.OPENSTREETMAP,
                directorySourceId = "$osmType/$osmId",
                retailerId = retailerId,
                name = when {
                    retailerId == RetailerId.ICELAND &&
                        name.contains("food warehouse", ignoreCase = true) &&
                        !name.contains("iceland", ignoreCase = true) -> "Iceland Food Warehouse"
                    retailerId == RetailerId.MORRISONS &&
                        Regex("\\bmccoll\\b", RegexOption.IGNORE_CASE).containsMatchIn(identity) &&
                        !identity.contains("morrisons", ignoreCase = true) -> "Morrisons Daily"
                    else -> name
                },
                address = address,
                postcode = postcode,
                latitude = lat,
                longitude = lon,
                format = inferFormat(retailerId, identity),
                distanceMeters = distanceMeters(originLat, originLon, lat, lon)
            )
        }.filter { (it.distanceMeters ?: Int.MAX_VALUE) <= radiusMeters }
            .distinctBy { it.directorySourceId }
    }

    internal fun parseOverpassResponse(raw: String, retailerId: RetailerId, originLat: Double, originLon: Double): List<StoreDirectoryEntry> {
        val elements = parseJsonResponse(raw, "Branch search").jsonObject["elements"]?.jsonArray ?: JsonArray(emptyList())
        return elements.mapNotNull { element ->
            val item = element.jsonObject
            val tags = item["tags"]?.jsonObject ?: return@mapNotNull null
            val shopType = tags.text("shop")?.lowercase()
            val identity = listOfNotNull(
                tags.text("brand"), tags.text("brand:en"),
                tags.text("operator"), tags.text("operator:en"),
                tags.text("name"), tags.text("name:en"),
                tags.text("official_name"), tags.text("alt_name")
            ).joinToString(" ")
            val amenity = tags.text("amenity")?.lowercase()
            val healthcare = tags.text("healthcare")?.lowercase()
            if (isExcludedPetrolVariant(identity, retailerId, amenity, null, null)) return@mapNotNull null
            if ((shopType != null && shopType in NON_RETAIL_SHOP_TYPES) || amenity in NON_RETAIL_FEATURE_TYPES ||
                healthcare in NON_RETAIL_FEATURE_TYPES) return@mapNotNull null
            val isMappedShop = shopType != null
            val isNamedRetailFeature = shopType == null &&
                amenity !in NON_RETAIL_FEATURE_TYPES &&
                healthcare !in NON_RETAIL_FEATURE_TYPES &&
                matchesRetailer(identity, retailerId)
            if (!isMappedShop && !isNamedRetailFeature) return@mapNotNull null
            if (!matchesRetailer(identity, retailerId)) return@mapNotNull null
            val lat = item.number("lat") ?: item["center"]?.jsonObject?.number("lat") ?: return@mapNotNull null
            val lon = item.number("lon") ?: item["center"]?.jsonObject?.number("lon") ?: return@mapNotNull null
            val type = item.text("type") ?: return@mapNotNull null
            val id = item.text("id") ?: return@mapNotNull null
            val postcode = tags.text("addr:postcode")?.uppercase()
            val address = listOfNotNull(
                listOfNotNull(tags.text("addr:housenumber"), tags.text("addr:street")).joinToString(" ").takeIf(String::isNotBlank),
                tags.text("addr:suburb"), tags.text("addr:city") ?: tags.text("addr:town"), postcode
            ).joinToString(", ")
            // Avoid low-information brand-only matches with neither a named branch nor address.
            val explicitName = listOf("name", "name:en", "official_name", "alt_name")
                .firstNotNullOfOrNull { tags.text(it) }
            if (explicitName == null && address.isBlank()) return@mapNotNull null
            StoreDirectoryEntry(
                directorySource = StoreDirectorySource.OPENSTREETMAP,
                directorySourceId = "$type/$id",
                retailerId = retailerId,
                name = if (
                    retailerId == RetailerId.ICELAND &&
                    identity.lowercase().contains("food warehouse") &&
                    !Regex("\\biceland\\b").containsMatchIn(identity.lowercase())
                ) "Iceland Food Warehouse" else tags.text("name") ?: tags.text("brand") ?: displayName(retailerId),
                address = address, postcode = postcode, latitude = lat, longitude = lon,
                format = inferFormat(retailerId, identity),
                distanceMeters = distanceMeters(originLat, originLon, lat, lon)
            )
        }.distinctBy { it.directorySourceId }
    }

    /** Some proxies/services prepend a UTF-8 BOM; trim it before JSON decoding. */
    private fun logTiming(stage: String, startedAtNanos: Long) {
        val elapsedMs = ((System.nanoTime() - startedAtNanos).coerceAtLeast(0L)) / 1_000_000L
        println("[StoreDirectoryTiming] stage=$stage elapsedMs=$elapsedMs")
    }

    private fun parseJsonResponse(raw: String, source: String): JsonElement {
        val normalized = raw.removePrefix("\uFEFF").trim()
        return try {
            json.parseToJsonElement(normalized)
        } catch (e: Exception) {
            throw IllegalStateException("$source returned an invalid JSON response. Please retry later.", e)
        }
    }

    private fun isExcludedPetrolVariant(
        identity: String,
        retailerId: RetailerId,
        amenity: String?,
        listingClass: String?,
        listingType: String?
    ): Boolean {
        val normalized = identity.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        // Drop every ASDA Express result, plus petrol/fuel station variants for any retailer.
        if (retailerId == RetailerId.ASDA && Regex("\\basda\\b.*\\bexpress\\b").containsMatchIn(normalized)) {
            return true
        }
        if (amenity == "fuel" || (listingClass == "amenity" && listingType == "fuel")) return true
        return Regex("\\b(petrol|fuel|forecourt|service station)\\b").containsMatchIn(normalized)
    }

    private fun matchesRetailer(value: String, retailerId: RetailerId): Boolean {
        val name = value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        return when (retailerId) {
            RetailerId.ASDA -> Regex("\\basda\\b").containsMatchIn(name)
            RetailerId.ICELAND -> Regex("\\biceland\\b").containsMatchIn(name) || name.contains("food warehouse")
            RetailerId.FOOD_WAREHOUSE -> name.contains("food warehouse")
            RetailerId.TESCO -> Regex("\\btesco\\b").containsMatchIn(name)
            RetailerId.MORRISONS -> Regex("\\bmorrisons\\b").containsMatchIn(name) || Regex("\\bmccoll\\b").containsMatchIn(name)
            RetailerId.SAINSBURYS -> name.contains("sainsbury")
            RetailerId.ALDI -> name.contains("aldi")
            RetailerId.LIDL -> name.contains("lidl")
        }
    }

    private fun inferFormat(id: RetailerId, identity: String): RetailStoreFormat {
        val name = identity.lowercase()
        return when (id) {
            RetailerId.ASDA -> if (name.contains("express")) RetailStoreFormat.ASDA_EXPRESS else RetailStoreFormat.ASDA_SUPERMARKET
            RetailerId.ICELAND -> RetailStoreFormat.ICELAND
            RetailerId.FOOD_WAREHOUSE -> RetailStoreFormat.FOOD_WAREHOUSE
            RetailerId.TESCO -> when {
                name.contains("express") -> RetailStoreFormat.TESCO_EXPRESS
                name.contains("metro") -> RetailStoreFormat.TESCO_METRO
                name.contains("extra") -> RetailStoreFormat.TESCO_EXTRA
                else -> RetailStoreFormat.TESCO_SUPERMARKET
            }
            RetailerId.MORRISONS -> if (name.contains("daily") || name.contains("mccoll")) RetailStoreFormat.MORRISONS_DAILY else RetailStoreFormat.MORRISONS_SUPERMARKET
            RetailerId.SAINSBURYS -> if (name.contains("local")) RetailStoreFormat.SAINSBURYS_LOCAL else RetailStoreFormat.SAINSBURYS_SUPERMARKET
            RetailerId.ALDI, RetailerId.LIDL -> RetailStoreFormat.UNKNOWN
        }
    }

    private fun displayName(id: RetailerId) = when (id) {
        RetailerId.ASDA -> "ASDA"
        RetailerId.ICELAND -> "Iceland"
        RetailerId.FOOD_WAREHOUSE -> "The Food Warehouse"
        RetailerId.TESCO -> "Tesco"
        RetailerId.MORRISONS -> "Morrisons|McColl.?s"
        RetailerId.SAINSBURYS -> "Sainsbury's"
        RetailerId.ALDI -> "Aldi"
        RetailerId.LIDL -> "Lidl"
    }

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
    private fun JsonObject.number(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return (6_371_000.0 * 2 * atan2(sqrt(a), sqrt(1 - a))).roundToInt()
    }

    companion object {
        // Exclude known non-retail services even when they carry a retailer brand.
        // Other OSM shop types remain eligible so the search supports new store formats.
        private val NON_RETAIL_SHOP_TYPES = setOf(
            "optician", "medical_supply", "hearing_aids", "doctor", "clinic", "dentist",
            "cafe", "restaurant", "fast_food", "food_court", "bar", "pub", "ice_cream"
        )
        private val NON_RETAIL_FEATURE_TYPES = setOf(
            "optician", "doctors", "doctor", "clinic", "dentist", "hospital", "pharmacy", "parking",
            "cafe", "restaurant", "fast_food", "food_court", "bar", "pub", "ice_cream"
        )

        private fun overpassRetailerPattern(id: RetailerId): String = when (id) {
            RetailerId.ASDA -> "ASDA"
            RetailerId.ICELAND -> "Iceland"
            RetailerId.FOOD_WAREHOUSE -> "Food.*Warehouse"
            RetailerId.TESCO -> "Tesco"
            RetailerId.MORRISONS -> "Morrisons"
            RetailerId.SAINSBURYS -> "Sainsbury"
            RetailerId.ALDI -> "Aldi"
            RetailerId.LIDL -> "Lidl"
        }

    private val OVERPASS_ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter"
        )
        private const val USER_AGENT = "PantryAssistantStoreFinder/1.0 (https://github.com/MatthewGriffin/MealPlanner)"
    }
}
