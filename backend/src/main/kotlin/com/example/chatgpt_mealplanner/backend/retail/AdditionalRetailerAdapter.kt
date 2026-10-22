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
 * Best-effort public catalogue search. It never promotes generic catalogue results to
 * branch-specific prices, stock, or promotions.
 */
open class AdditionalRetailerAdapter(
    private val client: AdditionalRetailerSourceClient = AdditionalRetailerSourceClient()
) {
    private val supported = setOf(RetailerId.MORRISONS, RetailerId.SAINSBURYS, RetailerId.LIDL)

    fun getCapability(retailerId: RetailerId): RetailerCapabilityDto {
        require(retailerId in supported) { "Unsupported additional retailer: $retailerId" }
        return RetailerCapabilityDto(
            retailerId = retailerId,
            mode = RetailerCapabilityMode.LIVE_SEARCH_AVAILABLE,
            searchSupported = true,
            livePriceSupported = true,
            availabilitySupported = false,
            promotionsSupported = false,
            description = when (retailerId) {
                RetailerId.MORRISONS -> "Public Morrisons online grocery search; online guide prices only, branch stock and promotions not verified."
                RetailerId.SAINSBURYS -> "Public Sainsbury's grocery search; online catalogue prices only, selected-store stock is not integrated."
                RetailerId.LIDL -> "Public Lidl website search for published products/offers; not the full grocery range, branch prices and stock are not verified."
                else -> "Public retailer catalogue search; branch stock is not verified."
            }
        )
    }

    open suspend fun searchProducts(retailerId: RetailerId, query: String): RetailProductSearchResult {
        if (retailerId !in supported) return RetailProductSearchResult.Unavailable(
            reason = "No additional public search is configured for this retailer.",
            errorCode = "SOURCE_UNAVAILABLE",
            cachedFallbackAvailable = true
        )
        if (query.isBlank()) return RetailProductSearchResult.Unavailable(
            reason = "Enter a product to search for.",
            errorCode = "EMPTY_QUERY",
            cachedFallbackAvailable = true
        )

        val html = try {
            client.searchHtml(retailerId, query.trim())
        } catch (e: Exception) {
            return RetailProductSearchResult.Unavailable(
                reason = "${retailerId.name} public product search failed: ${e.message ?: "unknown source error"}",
                errorCode = "SOURCE_UNAVAILABLE",
                cachedFallbackAvailable = true
            )
        }

        val scope = if (retailerId == RetailerId.LIDL) RetailPriceScope.RETAILER_GENERAL else RetailPriceScope.ONLINE
        val parser = IcelandProductParser(
            retailerId = retailerId,
            priceScope = scope,
            useStructuredAvailability = false,
            usePageWidePromotionHeuristic = false
        )
        val records = parser.parseHtmlPage(html)
        if (records.isEmpty()) return RetailProductSearchResult.Unavailable(
            reason = "${retailerId.name} search returned no parseable structured product offers. Cached/manual data remains available as fallback.",
            errorCode = "SOURCE_FORMAT_CHANGED",
            cachedFallbackAvailable = true
        )

        val sourceUrl = when (retailerId) {
            RetailerId.MORRISONS -> URLBuilder("https://groceries.morrisons.com/search").apply { parameters.append("q", query.trim()) }.buildString()
            RetailerId.SAINSBURYS -> "https://www.sainsburys.co.uk/gol-ui/SearchResults/" + java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name()).replace("+", "%20")
            RetailerId.LIDL -> URLBuilder("https://www.lidl.co.uk/q/search").apply { parameters.append("q", query.trim()) }.buildString()
            else -> ""
        }
        val warning = when (retailerId) {
            RetailerId.MORRISONS -> "Online grocery catalogue prices may depend on delivery area; selected physical branch stock is unknown."
            RetailerId.SAINSBURYS -> "Prices may depend on online shopping context and Nectar eligibility; selected physical branch stock is unknown."
            RetailerId.LIDL -> "The Lidl website lists a changing subset of products and offers, not a complete grocery catalogue; branch price and stock are unknown."
            else -> "Branch stock is unknown."
        }
        val warnings = listOf(warning, "Promotions are not inferred from page-wide text; only parsed base prices are used.")
        val metadata = RetailSourceMetadata(
            retailerId = retailerId,
            sourceType = RetailOfferSourceType.LIVE,
            sourceReference = sourceUrl,
            observedAt = System.currentTimeMillis(),
            isLive = true,
            parseWarnings = warnings
        )
        return RetailProductSearchResult.PartialSuccess(records.map { it.productWithOffer }, warnings, metadata)
    }
}
