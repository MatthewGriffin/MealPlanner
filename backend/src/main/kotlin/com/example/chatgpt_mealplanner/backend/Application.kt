package com.example.chatgpt_mealplanner.backend

import com.example.chatgpt_mealplanner.backend.config.BackendConfig
import com.example.chatgpt_mealplanner.backend.retail.AldiRetailerAdapter
import com.example.chatgpt_mealplanner.backend.retail.AdditionalRetailerAdapter
import com.example.chatgpt_mealplanner.backend.retail.AsdaRetailerAdapter
import com.example.chatgpt_mealplanner.backend.retail.FoodWarehouseRetailerAdapter
import com.example.chatgpt_mealplanner.backend.retail.IcelandProductParser
import com.example.chatgpt_mealplanner.backend.retail.IcelandRetailerAdapter
import com.example.chatgpt_mealplanner.backend.retail.IcelandSourceClient
import com.example.chatgpt_mealplanner.backend.retail.OpenStreetMapStoreDirectory
import com.example.chatgpt_mealplanner.backend.retail.TescoRetailerAdapter
import com.example.chatgpt_mealplanner.backend.retail.StoreDirectoryProvider
import com.example.chatgpt_mealplanner.retail.contract.RetailerCapabilityDto
import com.example.chatgpt_mealplanner.retail.contract.RetailerCapabilityMode
import com.example.chatgpt_mealplanner.retail.contract.RetailerCapabilitiesResponse
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import com.example.chatgpt_mealplanner.retail.contract.RetailProductWithOffer
import com.example.chatgpt_mealplanner.retail.contract.StoreDirectorySearchRequest
import com.example.chatgpt_mealplanner.retail.contract.StoreDirectorySearchResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class RetailSearchBackendRequest(
    val retailerId: RetailerId,
    val query: String
)

@Serializable
data class RetailCataloguePageResponse(
    val retailerId: RetailerId,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int,
    val products: List<RetailProductWithOffer>,
    val observedAt: Long,
    val priceScopeNote: String
)

@Serializable
data class SainsburysCataloguePageResponse(
    val retailerId: RetailerId,
    val page: Int,
    val pageSize: Int,
    val totalProducts: Int,
    val totalPages: Int,
    val products: List<RetailProductWithOffer>,
    val observedAt: Long,
    val priceScopeNote: String,
    val completenessNote: String
)

fun main() {
    val config = BackendConfig()
    embeddedServer(Netty, port = config.port, host = "0.0.0.0", module = { module(config) }).start(wait = true)
}

fun Application.module(
    config: BackendConfig = BackendConfig(),
    storeDirectoryProvider: StoreDirectoryProvider? = null
) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }

    val httpClient = HttpClient(OkHttp)
    val icelandClient = IcelandSourceClient(httpClient)
    val icelandParser = IcelandProductParser()
    val icelandAdapter = IcelandRetailerAdapter(icelandClient, icelandParser)
    val asdaAdapter = AsdaRetailerAdapter()
    val aldiAdapter = AldiRetailerAdapter()
    val tescoAdapter = TescoRetailerAdapter()
    val additionalRetailerAdapter = AdditionalRetailerAdapter()
    val foodWarehouseAdapter = FoodWarehouseRetailerAdapter()
    val storeDirectory = storeDirectoryProvider ?: OpenStreetMapStoreDirectory(httpClient)

    routing {
        get("/health") {
            call.respond(mapOf("status" to "OK", "version" to config.systemPromptVersion))
        }

        get("/v1/retail/capabilities") {
            val icelandCap = RetailerCapabilityDto(
                retailerId = RetailerId.ICELAND,
                mode = RetailerCapabilityMode.LIVE_SEARCH_AVAILABLE,
                searchSupported = true,
                livePriceSupported = true,
                availabilitySupported = true,
                promotionsSupported = true,
                description = "Iceland live HTML / JSON-LD catalog search available."
            )
            val response = RetailerCapabilitiesResponse(
                capabilities = listOf(
                    icelandCap,
                    asdaAdapter.getCapability(),
                    aldiAdapter.getCapability(),
                    tescoAdapter.getCapability(),
                    foodWarehouseAdapter.getCapability()
                ) + listOf(RetailerId.MORRISONS, RetailerId.SAINSBURYS, RetailerId.LIDL).map(additionalRetailerAdapter::getCapability)
            )
            call.respond(response)
        }

        post("/v1/retail/stores/search") {
            val request = runCatching { call.receive<StoreDirectorySearchRequest>() }.getOrNull()
            if (request == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid store directory search request"))
                return@post
            }
            try {
                val result = storeDirectory.search(request)
                // Keep required attribution metadata in the public API even when its values
                // match the data class defaults (Kotlin serialization omits defaults by default).
                call.respondText(
                    Json { encodeDefaults = true }.encodeToString(StoreDirectorySearchResponse.serializer(), result),
                    io.ktor.http.ContentType.Application.Json
                )
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid store search request")))
            } catch (_: Exception) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    mapOf("error" to "Store directory temporarily unavailable. Try again later.")
                )
            }
        }

        get("/v1/retail/catalogue/iceland") {
            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
            val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull() ?: 100
            if (page < 0 || page > 10000 || pageSize !in 1..100) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "page must be 0..10000 and pageSize must be 1..100")
                )
                return@get
            }

            try {
                val raw = icelandClient.fetchAlgoliaCataloguePageJson(page, pageSize)
                if (raw == null) {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "Iceland's public catalogue index could not be discovered; no catalogue data was returned.")
                    )
                    return@get
                }
                val root = Json.parseToJsonElement(raw).jsonObject
                val totalPages = root["nbPages"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: run {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "Iceland's catalogue response did not include pagination metadata.")
                        )
                        return@get
                    }
                val products = icelandParser.parseAlgoliaResponse(raw).map { it.productWithOffer }
                call.respond(
                    RetailCataloguePageResponse(
                        retailerId = RetailerId.ICELAND,
                        page = page,
                        pageSize = pageSize,
                        totalPages = totalPages,
                        products = products,
                        observedAt = System.currentTimeMillis(),
                        priceScopeNote = "Online catalogue prices; not confirmation of physical-branch price or stock."
                    )
                )
            } catch (_: Exception) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    mapOf("error" to "Iceland catalogue page could not be retrieved or parsed. Keep the previous cache and retry later.")
                )
            }
        }

        get("/v1/retail/catalogue/sainsburys") {
            // The public endpoint is known to block some automated clients and its
            // catalogue/pagination behavior is not yet verified end-to-end. Expose one
            // bounded page at a time; never silently return a partial "full catalogue".
            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
            val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull() ?: 60
            if (page < 1 || page > 165 || pageSize !in 1..60) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "page must be 1..165 and pageSize must be 1..60")
                )
                return@get
            }

            try {
                val raw = additionalRetailerAdapter.fetchSainsburysCataloguePageJson(page, pageSize)
                val root = Json.parseToJsonElement(raw).jsonObject
                // Current Sainsbury's responses put pagination counts under "controls";
                // tolerate the older/alternate top-level shapes as well.
                val controls = root["controls"] as? kotlinx.serialization.json.JsonObject
                val totalProducts = controls?.get("total_record_count")?.jsonPrimitive?.content?.toIntOrNull()
                    ?: controls?.get("totalRecordCount")?.jsonPrimitive?.content?.toIntOrNull()
                    ?: root["total_record_count"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: root["totalRecordCount"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: run {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "Sainsbury's response did not include supported total-count metadata; no catalogue page was published.")
                        )
                        return@get
                    }
                if (totalProducts < 0 || totalProducts > 9900) {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "Sainsbury's catalogue count is outside the supported 0..9900 range; completeness cannot be established.")
                    )
                    return@get
                }
                val totalPages = if (totalProducts == 0) 0 else (totalProducts + pageSize - 1) / pageSize
                if (totalPages > 165 || (totalPages == 0 && page != 1) || (totalPages > 0 && page > totalPages)) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Requested page is outside the advertised Sainsbury's catalogue range.")
                    )
                    return@get
                }
                val rawProducts = root["products"] as? kotlinx.serialization.json.JsonArray
                    ?: run {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "Sainsbury's response did not contain a products array; no catalogue page was published.")
                        )
                        return@get
                    }
                if (totalProducts > 0 && rawProducts.isEmpty()) {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "Sainsbury's returned an empty page despite advertising products; completeness cannot be established.")
                    )
                    return@get
                }
                call.respond(
                    SainsburysCataloguePageResponse(
                        retailerId = RetailerId.SAINSBURYS,
                        page = page,
                        pageSize = pageSize,
                        totalProducts = totalProducts,
                        totalPages = totalPages,
                        products = additionalRetailerAdapter.parseSainsburysJson(raw),
                        observedAt = System.currentTimeMillis(),
                        priceScopeNote = "Online catalogue prices only; not physical-branch prices or stock.",
                        completenessNote = "This is one source page, not a verified complete catalogue. A collector must fetch every advertised page and confirm stable totalProducts and page counts before replacing cached data."
                    )
                )
            } catch (_: Exception) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    mapOf("error" to "Sainsbury's catalogue page could not be retrieved or validated. Keep the previous cache and retry later.")
                )
            }
        }

        post("/v1/retail/search") {
            val req = runCatching { call.receive<RetailSearchBackendRequest>() }.getOrNull()
            if (req == null) {
                call.respond(mapOf("error" to "Invalid search request"))
                return@post
            }

            val result = when (req.retailerId) {
                RetailerId.ICELAND -> icelandAdapter.searchProducts(req.query)
                RetailerId.ASDA -> asdaAdapter.searchProducts(req.query)
                RetailerId.ALDI -> aldiAdapter.searchProducts(req.query)
                RetailerId.TESCO -> tescoAdapter.searchProducts(req.query)
                RetailerId.FOOD_WAREHOUSE -> foodWarehouseAdapter.searchProducts(req.query)
                RetailerId.MORRISONS, RetailerId.SAINSBURYS, RetailerId.LIDL -> additionalRetailerAdapter.searchProducts(req.retailerId, req.query)
            }
            call.respond(result)
        }
    }
}
