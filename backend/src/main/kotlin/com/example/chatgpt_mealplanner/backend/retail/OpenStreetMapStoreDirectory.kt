package com.example.chatgpt_mealplanner.backend.retail

import com.example.chatgpt_mealplanner.retail.contract.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

fun interface StoreDirectoryProvider {
    suspend fun search(request: StoreDirectorySearchRequest): StoreDirectorySearchResponse
}

/** OSM IDs are directory IDs only; retailerStoreId is never inferred from them. */
class OpenStreetMapStoreDirectory(
    private val client: HttpClient,
    private val clock: () -> Long = System::currentTimeMillis
) : StoreDirectoryProvider {
    constructor() : this(HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 45_000
            requestTimeoutMillis = 45_000
        }
    })

    private data class CacheEntry(val expiresAt: Long, val response: StoreDirectorySearchResponse)
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val overpassMutex = Mutex()
    private val geocodeMutex = Mutex()
    @Volatile private var lastOverpassAt = 0L
    @Volatile private var lastGeocodeAt = 0L
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(request: StoreDirectorySearchRequest): StoreDirectorySearchResponse {
        require(request.radiusMeters in 500..15_000) { "Radius must be between 500 and 15000 metres." }
        val (lat, lon) = resolveCenter(request)
        val key = "${request.retailerId}:${"%.4f".format(Locale.US, lat)}:${"%.4f".format(Locale.US, lon)}:${request.radiusMeters}"
        cache[key]?.let {
            if (it.expiresAt > clock()) return it.response.copy(fromCache = true)
            cache.remove(key, it)
        }
        overpassMutex.withLock {
            val wait = 1_100L - (clock() - lastOverpassAt)
            if (wait > 0) delay(wait)
            lastOverpassAt = clock()
        }
        val query = buildQuery(request, lat, lon)
        val raw = client.post("https://overpass-api.de/api/interpreter") {
            contentType(ContentType.Text.Plain)
            header(HttpHeaders.UserAgent, USER_AGENT)
            setBody(query)
        }.bodyAsText()
        val stores = parseOverpassResponse(raw, request.retailerId, lat, lon)
            .sortedBy { it.distanceMeters ?: Int.MAX_VALUE }.take(50)
        val response = StoreDirectorySearchResponse(
            stores = stores,
            observedAt = clock(),
            warnings = if (stores.isEmpty()) listOf("No matching branch was found in OpenStreetMap. Coverage may be incomplete; verify with the retailer.") else emptyList()
        )
        cache[key] = CacheEntry(clock() + 6 * 60 * 60 * 1000L, response)
        if (cache.size > 200) cache.entries.sortedBy { it.value.expiresAt }
            .take(cache.size - 200).forEach { cache.remove(it.key, it.value) }
        return response
    }

    internal fun buildQuery(request: StoreDirectorySearchRequest, lat: Double, lon: Double): String {
        // Filter by retailer on the Overpass side; downloading every supermarket in a
        // large radius made requests needlessly slow and prone to socket timeouts.
        val retailerPattern = when (request.retailerId) {
            RetailerId.ASDA -> "asda"
            RetailerId.ICELAND -> "iceland"
            RetailerId.FOOD_WAREHOUSE -> "food[[:space:]]*warehouse|foodwarehouse"
            RetailerId.TESCO -> "tesco"
            RetailerId.MORRISONS -> "morrisons"
            RetailerId.SAINSBURYS -> "sainsbury"
        }
        val radius = request.radiusMeters
        return """[out:json][timeout:25];(
          nwr["shop"~"^(supermarket|convenience)$"]["name"~"$retailerPattern",i](around:$radius,$lat,$lon);
          nwr["shop"~"^(supermarket|convenience)$"]["brand"~"$retailerPattern",i](around:$radius,$lat,$lon);
          nwr["shop"~"^(supermarket|convenience)$"]["operator"~"$retailerPattern",i](around:$radius,$lat,$lon);
        );out center tags;"""
    }

    private suspend fun resolveCenter(request: StoreDirectorySearchRequest): Pair<Double, Double> {
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
        geocodeMutex.withLock {
            val wait = 1_100L - (clock() - lastGeocodeAt)
            if (wait > 0) delay(wait)
            lastGeocodeAt = clock()
        }
        val raw = client.get("https://nominatim.openstreetmap.org/search") {
            parameter("q", "$postcode, United Kingdom")
            parameter("format", "jsonv2")
            parameter("limit", "1")
            header(HttpHeaders.UserAgent, USER_AGENT)
        }.bodyAsText()
        val first = json.parseToJsonElement(raw).jsonArray.firstOrNull()?.jsonObject
            ?: throw IllegalArgumentException("Postcode could not be located. Try coordinates instead.")
        val lat = first["lat"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            ?: throw IllegalStateException("Postcode lookup returned no latitude.")
        val lon = first["lon"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            ?: throw IllegalStateException("Postcode lookup returned no longitude.")
        return lat to lon
    }

    internal fun parseOverpassResponse(raw: String, retailerId: RetailerId, originLat: Double, originLon: Double): List<StoreDirectoryEntry> {
        val elements = json.parseToJsonElement(raw).jsonObject["elements"]?.jsonArray ?: JsonArray(emptyList())
        return elements.mapNotNull { element ->
            val item = element.jsonObject
            val tags = item["tags"]?.jsonObject ?: return@mapNotNull null
            val identity = listOfNotNull(tags.text("brand"), tags.text("operator"), tags.text("name")).joinToString(" ")
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
            StoreDirectoryEntry(
                directorySource = StoreDirectorySource.OPENSTREETMAP,
                directorySourceId = "$type/$id",
                retailerId = retailerId,
                name = tags.text("name") ?: tags.text("brand") ?: displayName(retailerId),
                address = address, postcode = postcode, latitude = lat, longitude = lon,
                format = inferFormat(retailerId, identity),
                distanceMeters = distanceMeters(originLat, originLon, lat, lon)
            )
        }.distinctBy { it.directorySourceId }
    }

    private fun matchesRetailer(value: String, retailerId: RetailerId): Boolean {
        val name = value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        return when (retailerId) {
            RetailerId.ASDA -> Regex("\\basda\\b").containsMatchIn(name)
            RetailerId.ICELAND -> Regex("\\biceland\\b").containsMatchIn(name) && !name.contains("food warehouse")
            RetailerId.FOOD_WAREHOUSE -> name.contains("food warehouse")
            RetailerId.TESCO -> Regex("\\btesco\\b").containsMatchIn(name)
            RetailerId.MORRISONS -> Regex("\\bmorrisons\\b").containsMatchIn(name)
            RetailerId.SAINSBURYS -> name.contains("sainsbury")
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
            RetailerId.MORRISONS -> if (name.contains("daily")) RetailStoreFormat.MORRISONS_DAILY else RetailStoreFormat.MORRISONS_SUPERMARKET
            RetailerId.SAINSBURYS -> if (name.contains("local")) RetailStoreFormat.SAINSBURYS_LOCAL else RetailStoreFormat.SAINSBURYS_SUPERMARKET
        }
    }

    private fun displayName(id: RetailerId) = when (id) {
        RetailerId.ASDA -> "ASDA"
        RetailerId.ICELAND -> "Iceland"
        RetailerId.FOOD_WAREHOUSE -> "The Food Warehouse"
        RetailerId.TESCO -> "Tesco"
        RetailerId.MORRISONS -> "Morrisons"
        RetailerId.SAINSBURYS -> "Sainsbury's"
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
        private const val USER_AGENT = "PantryAssistantStoreFinder/1.0 (https://github.com/MatthewGriffin/MealPlanner)"
    }
}
