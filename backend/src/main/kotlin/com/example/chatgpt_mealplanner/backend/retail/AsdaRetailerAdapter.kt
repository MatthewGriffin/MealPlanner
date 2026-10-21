package com.example.chatgpt_mealplanner.backend.retail

import com.example.chatgpt_mealplanner.retail.contract.RetailOfferSourceType
import com.example.chatgpt_mealplanner.retail.contract.RetailProductSearchResult
import com.example.chatgpt_mealplanner.retail.contract.RetailSourceMetadata
import com.example.chatgpt_mealplanner.retail.contract.RetailerCapabilityDto
import com.example.chatgpt_mealplanner.retail.contract.RetailerCapabilityMode
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * ASDA's website-backed Algolia search integration.
 * Prices are online catalogue prices; stock remains UNKNOWN without product- and
 * scope-specific availability evidence.
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
        description = "Live ASDA website-backed product search and online catalogue prices; branch stock is not confirmed."
    )

    open suspend fun searchProducts(query: String): RetailProductSearchResult {
        if (query.isBlank()) {
            return RetailProductSearchResult.Unavailable(
                reason = "Enter a product to search for.",
                errorCode = "EMPTY_QUERY",
                cachedFallbackAvailable = true
            )
        }

        val responseJson = try {
            client.search(query.trim())
        } catch (e: Exception) {
            val message = e.message ?: "Unknown source error"
            return RetailProductSearchResult.Unavailable(
                reason = "ASDA live search failed: " + message,
                errorCode = if (message.contains("not configured", ignoreCase = true)) "CONFIG_MISSING" else "SOURCE_UNAVAILABLE",
                cachedFallbackAvailable = true
            )
        }

        val parsed = parser.parseAlgoliaResponse(responseJson)
        if (parsed.productsWithOffers.isEmpty()) {
            return RetailProductSearchResult.Unavailable(
                reason = "ASDA returned no parseable product hits (" + parsed.hitsReceived + " received, " + parsed.hitsRejected + " rejected).",
                errorCode = if (parsed.hitsReceived == 0) "NO_HITS" else "SOURCE_FORMAT_CHANGED",
                cachedFallbackAvailable = true
            )
        }

        val encodedQuery = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
        val warnings = if (parsed.hitsRejected > 0) {
            listOf("ASDA returned " + parsed.hitsRejected + " product hits that could not be parsed.")
        } else {
            emptyList()
        }
        val metadata = RetailSourceMetadata(
            retailerId = RetailerId.ASDA,
            sourceType = RetailOfferSourceType.LIVE,
            sourceReference = "https://groceries.asda.com/search/" + encodedQuery,
            observedAt = System.currentTimeMillis(),
            isLive = true,
            parseWarnings = warnings
        )
        return if (warnings.isNotEmpty()) {
            RetailProductSearchResult.PartialSuccess(parsed.productsWithOffers, warnings, metadata)
        } else {
            RetailProductSearchResult.Success(parsed.productsWithOffers, metadata)
        }
    }
}
