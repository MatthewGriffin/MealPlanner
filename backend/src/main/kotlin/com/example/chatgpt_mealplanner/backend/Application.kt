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
import com.example.chatgpt_mealplanner.retail.contract.StoreDirectorySearchRequest
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
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RetailSearchBackendRequest(
    val retailerId: RetailerId,
    val query: String
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
            val branchOnlyCapabilities = listOf(
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
                call.respond(storeDirectory.search(request))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid store search request")))
            } catch (_: Exception) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    mapOf("error" to "Store directory temporarily unavailable. Try again later.")
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
