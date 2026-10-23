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
