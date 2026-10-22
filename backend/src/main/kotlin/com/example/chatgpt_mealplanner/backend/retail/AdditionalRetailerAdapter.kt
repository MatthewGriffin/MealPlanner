package com.example.chatgpt_mealplanner.backend.retail

import com.example.chatgpt_mealplanner.retail.contract.Money
import com.example.chatgpt_mealplanner.retail.contract.RetailAvailability
import com.example.chatgpt_mealplanner.retail.contract.RetailOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailOfferSourceType
import com.example.chatgpt_mealplanner.retail.contract.RetailProduct
import com.example.chatgpt_mealplanner.retail.contract.RetailProductWithOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailStorageClass
import com.example.chatgpt_mealplanner.retail.contract.RetailPriceScope
import com.example.chatgpt_mealplanner.retail.contract.RetailProductSearchResult
import com.example.chatgpt_mealplanner.retail.contract.RetailSourceMetadata
import com.example.chatgpt_mealplanner.retail.contract.RetailerCapabilityDto
import com.example.chatgpt_mealplanner.retail.contract.RetailerCapabilityMode
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import io.ktor.http.URLBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Best-effort public catalogue search. It never promotes generic catalogue results to
 * branch-specific prices, stock, or promotions.
 */
open class AdditionalRetailerAdapter(
    private val client: AdditionalRetailerSourceClient = AdditionalRetailerSourceClient()
) {
    private fun parseSainsburysJson(raw: String): List<RetailProductWithOffer> {
        val root = runCatching { Json { ignoreUnknownKeys = true }.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return emptyList()
        val products = root["products"] as? JsonArray ?: return emptyList()
        val observedAt = System.currentTimeMillis()
        return products.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            fun field(name: String): String? = runCatching { obj[name]?.jsonPrimitive?.content }.getOrNull()
            val id = field("product_uid")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = field("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val priceText = field("retail_price") ?: return@mapNotNull null
            val priceValue = priceText.replace("£", "").trim().toBigDecimalOrNull() ?: return@mapNotNull null
            val pence = runCatching { priceValue.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact() }.getOrNull()
                ?: return@mapNotNull null
            if (pence < 0L) return@mapNotNull null
            val product = RetailProduct(
                retailerId = RetailerId.SAINSBURYS,
                retailerProductId = id,
                name = name,
                imageUrl = field("image_url"),
                storageClass = RetailStorageClass.UNKNOWN
            )
            val offer = RetailOffer(
                retailerId = RetailerId.SAINSBURYS,
                retailerProductId = id,
                price = Money(pence),
                availability = RetailAvailability.UNKNOWN,
                priceScope = RetailPriceScope.ONLINE,
                sourceType = RetailOfferSourceType.LIVE,
                observedAt = observedAt
            )
            RetailProductWithOffer(product, offer)
        }
    }

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
        val records = if (retailerId == RetailerId.SAINSBURYS) {
            parseSainsburysJson(html)
        } else {
            parser.parseHtmlPage(html).map { it.productWithOffer }
        }
        if (records.isEmpty()) return RetailProductSearchResult.Unavailable(
            reason = "${retailerId.name} search returned no parseable structured product offers. Cached/manual data remains available as fallback.",
            errorCode = "SOURCE_FORMAT_CHANGED",
            cachedFallbackAvailable = true
        )

        val sourceUrl = when (retailerId) {
            RetailerId.MORRISONS -> URLBuilder("https://groceries.morrisons.com/search").apply { parameters.append("q", query.trim()) }.buildString()
            RetailerId.SAINSBURYS -> URLBuilder("https://www.sainsburys.co.uk/groceries-api/gol-services/product/v1/product").apply {
                parameters.append("filter[keyword]", query.trim())
                parameters.append("page_number", "1")
                parameters.append("page_size", "24")
            }.buildString()
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
        return RetailProductSearchResult.PartialSuccess(records, warnings, metadata)
    }
}
