package com.example.chatgpt_mealplanner.backend.retail

import com.example.chatgpt_mealplanner.retail.contract.RetailOfferSourceType
import com.example.chatgpt_mealplanner.retail.contract.RetailPriceScope
import com.example.chatgpt_mealplanner.retail.contract.RetailProductSearchResult
import com.example.chatgpt_mealplanner.retail.contract.RetailSourceMetadata
import com.example.chatgpt_mealplanner.retail.contract.RetailerCapabilityDto
import com.example.chatgpt_mealplanner.retail.contract.RetailerCapabilityMode
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import io.ktor.http.URLBuilder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * ALDI's public website search exposes retailer-wide product listings and prices.
 * Its stock checker is an estimate and is not integrated here, so stock stays UNKNOWN.
 */
open class AldiRetailerAdapter(
    private val client: AldiSourceClient = AldiSourceClient(),
    private val parser: IcelandProductParser = IcelandProductParser(
        retailerId = RetailerId.ALDI,
        priceScope = RetailPriceScope.RETAILER_GENERAL,
        useStructuredAvailability = false
    )
) {
    open fun getCapability() = RetailerCapabilityDto(
        retailerId = RetailerId.ALDI,
        mode = RetailerCapabilityMode.LIVE_SEARCH_AVAILABLE,
        searchSupported = true,
        livePriceSupported = true,
        availabilitySupported = false,
        promotionsSupported = true,
        description = "Public ALDI product search and retailer-wide catalogue prices; branch stock is not confirmed and remains unknown."
    )

    open suspend fun searchProducts(query: String): RetailProductSearchResult {
        if (query.isBlank()) {
            return RetailProductSearchResult.Unavailable(
                reason = "Enter a product to search for.",
                errorCode = "EMPTY_QUERY",
                cachedFallbackAvailable = true
            )
        }

        val html = try {
            client.searchHtml(query.trim())
        } catch (e: Exception) {
            return RetailProductSearchResult.Unavailable(
                reason = "ALDI public product search failed: ${e.message ?: "unknown source error"}",
                errorCode = "SOURCE_UNAVAILABLE",
                cachedFallbackAvailable = true
            )
        }

        val records = parser.parseHtmlPage(html)
        if (records.isEmpty()) {
            return RetailProductSearchResult.Unavailable(
                reason = "ALDI search returned no parseable structured product offers. Retailer-wide cached/manual data can still be used.",
                errorCode = "SOURCE_FORMAT_CHANGED",
                cachedFallbackAvailable = true
            )
        }

        val warnings = listOf(
            "Prices are retailer-wide public catalogue listings and may differ in store.",
            "ALDI stock-checker estimates are not integrated; availability is unknown."
        )
        val encodedQuery = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
        val metadata = RetailSourceMetadata(
            retailerId = RetailerId.ALDI,
            sourceType = RetailOfferSourceType.LIVE,
            sourceReference = URLBuilder("https://www.aldi.co.uk/results").apply {
                parameters.append("q", query.trim())
            }.buildString(),
            observedAt = System.currentTimeMillis(),
            isLive = true,
            parseWarnings = warnings
        )
        return RetailProductSearchResult.PartialSuccess(records.map { it.productWithOffer }, warnings, metadata)
    }
}
