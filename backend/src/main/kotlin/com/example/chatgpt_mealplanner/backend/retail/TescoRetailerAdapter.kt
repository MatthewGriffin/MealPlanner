package com.example.chatgpt_mealplanner.backend.retail

import com.example.chatgpt_mealplanner.retail.contract.RetailOfferSourceType
import com.example.chatgpt_mealplanner.retail.contract.RetailPriceScope
import com.example.chatgpt_mealplanner.retail.contract.RetailProductSearchResult
import com.example.chatgpt_mealplanner.retail.contract.RetailSourceMetadata
import com.example.chatgpt_mealplanner.retail.contract.RetailerCapabilityDto
import com.example.chatgpt_mealplanner.retail.contract.RetailerCapabilityMode
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import io.ktor.http.URLBuilder

/**
 * Parses public Tesco grocery search results when structured Product data is present.
 * The search is online scope only; store-level prices and availability remain unknown.
 */
open class TescoRetailerAdapter(
    private val client: TescoSourceClient = TescoSourceClient(),
    private val parser: IcelandProductParser = IcelandProductParser(
        retailerId = RetailerId.TESCO,
        priceScope = RetailPriceScope.ONLINE,
        useStructuredAvailability = false
    )
) {
    open fun getCapability() = RetailerCapabilityDto(
        retailerId = RetailerId.TESCO,
        mode = RetailerCapabilityMode.LIVE_SEARCH_AVAILABLE,
        searchSupported = true,
        livePriceSupported = true,
        availabilitySupported = false,
        promotionsSupported = true,
        description = "Public Tesco grocery search with online guide prices; selected-store prices and branch stock are not confirmed."
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
                reason = "Tesco public product search failed: ${e.message ?: "unknown source error"}",
                errorCode = "SOURCE_UNAVAILABLE",
                cachedFallbackAvailable = true
            )
        }

        val records = parser.parseHtmlPage(html)
        if (records.isEmpty()) {
            return RetailProductSearchResult.Unavailable(
                reason = "Tesco search returned no parseable structured product offers. Cached/manual data remains available as fallback.",
                errorCode = "SOURCE_FORMAT_CHANGED",
                cachedFallbackAvailable = true
            )
        }

        val warnings = listOf(
            "Prices are online guide prices and may differ in store or by selected delivery area.",
            "The public search result does not verify stock at the selected physical branch."
        )
        val metadata = RetailSourceMetadata(
            retailerId = RetailerId.TESCO,
            sourceType = RetailOfferSourceType.LIVE,
            sourceReference = URLBuilder("https://www.tesco.com/groceries/en-GB/search").apply {
                parameters.append("query", query.trim())
            }.buildString(),
            observedAt = System.currentTimeMillis(),
            isLive = true,
            parseWarnings = warnings
        )
        return RetailProductSearchResult.PartialSuccess(records.map { it.productWithOffer }, warnings, metadata)
    }
}
