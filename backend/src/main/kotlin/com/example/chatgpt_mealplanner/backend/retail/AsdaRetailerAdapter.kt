package com.example.chatgpt_mealplanner.backend.retail

import com.example.chatgpt_mealplanner.retail.contract.RetailOfferSourceType
import com.example.chatgpt_mealplanner.retail.contract.RetailProductSearchResult
import com.example.chatgpt_mealplanner.retail.contract.RetailSourceMetadata
import com.example.chatgpt_mealplanner.retail.contract.RetailerCapabilityDto
import com.example.chatgpt_mealplanner.retail.contract.RetailerCapabilityMode
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.URLBuilder

/**
 * ASDA catalogue search through its existing Algolia endpoint and configured API key.
 * Offers are online catalogue prices; product-level availability remains unknown.
 */
open class AsdaRetailerAdapter(
    private val client: AsdaSourceClient = AsdaSourceClient(HttpClient(OkHttp)),
    private val parser: AsdaProductParser = AsdaProductParser()
) {
    open fun getCapability(): RetailerCapabilityDto = RetailerCapabilityDto(
        retailerId = RetailerId.ASDA,
        mode = RetailerCapabilityMode.LIVE_SEARCH_AVAILABLE,
        searchSupported = true,
        livePriceSupported = true,
        availabilitySupported = false,
        promotionsSupported = true,
        description = "ASDA live Algolia catalogue search with online prices; product-level availability is unknown."
    )

    open suspend fun searchProducts(query: String): RetailProductSearchResult {
        if (query.isBlank()) {
            return RetailProductSearchResult.Unavailable(
                reason = "Enter a product to search for.",
                errorCode = "EMPTY_QUERY",
                cachedFallbackAvailable = true
            )
        }

        val response = try {
            client.search(query.trim())
        } catch (e: Exception) {
            return RetailProductSearchResult.Unavailable(
                reason = "ASDA live product search failed: ${e.message ?: "unknown source error"}",
                errorCode = if (e.message?.contains("API key not configured", ignoreCase = true) == true) {
                    "SOURCE_CONFIGURATION_REQUIRED"
                } else {
                    "SOURCE_UNAVAILABLE"
                },
                cachedFallbackAvailable = true
            )
        }

        val parsed = parser.parseAlgoliaResponse(response)
        if (parsed.productsWithOffers.isEmpty()) {
            return RetailProductSearchResult.Unavailable(
                reason = "ASDA search returned ${parsed.hitsReceived} hits but no parseable priced products.",
                errorCode = "SOURCE_FORMAT_CHANGED",
                cachedFallbackAvailable = true
            )
        }

        val warnings = buildList {
            add("Prices are online ASDA catalogue prices; store-specific prices may differ.")
            add("Product-level availability is not supplied by this search and remains unknown.")
            if (parsed.hitsRejected > 0) {
                add("${parsed.hitsRejected} of ${parsed.hitsReceived} catalogue hits could not be parsed.")
            }
        }
        val metadata = RetailSourceMetadata(
            retailerId = RetailerId.ASDA,
            sourceType = RetailOfferSourceType.LIVE,
            sourceReference = URLBuilder("https://groceries.asda.com/search/").apply {
                parameters.append("q", query.trim())
            }.buildString(),
            observedAt = System.currentTimeMillis(),
            isLive = true,
            parseWarnings = warnings
        )
        return RetailProductSearchResult.PartialSuccess(parsed.productsWithOffers, warnings, metadata)
    }
}
