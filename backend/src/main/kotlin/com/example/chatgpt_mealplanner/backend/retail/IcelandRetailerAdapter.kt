package com.example.chatgpt_mealplanner.backend.retail

import com.example.chatgpt_mealplanner.retail.contract.RetailOfferSourceType
import com.example.chatgpt_mealplanner.retail.contract.RetailProductSearchResult
import com.example.chatgpt_mealplanner.retail.contract.RetailSourceMetadata
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import io.ktor.http.URLBuilder

open class IcelandRetailerAdapter(
    private val client: IcelandSourceClient,
    private val parser: IcelandProductParser = IcelandProductParser()
) {

    constructor() : this(
        client = IcelandSourceClient(
            httpClient = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp)
        ),
        parser = IcelandProductParser()
    )

    open suspend fun searchProducts(query: String): RetailProductSearchResult {
        val html = try {
            client.fetchSearchHtml(query)
        } catch (e: Exception) {
            return RetailProductSearchResult.Unavailable(
                reason = "Failed to fetch Iceland search page: ${e.message}",
                errorCode = "SOURCE_UNAVAILABLE",
                cachedFallbackAvailable = true
            )
        }

        val records = parser.parseHtmlPage(html)
        if (records.isEmpty()) {
            return RetailProductSearchResult.Unavailable(
                reason = "No parseable product cards found on Iceland search page.",
                errorCode = "SOURCE_FORMAT_CHANGED",
                cachedFallbackAvailable = true
            )
        }

        val productsWithOffers = records.map { it.productWithOffer }
        val allWarnings = records.flatMap { it.warnings }

        val metadata = RetailSourceMetadata(
            retailerId = RetailerId.ICELAND,
            sourceType = RetailOfferSourceType.LIVE,
            sourceReference = URLBuilder("https://www.iceland.co.uk/search").apply { parameters.append("q", query.trim()) }.buildString(),
            observedAt = System.currentTimeMillis(),
            isLive = true,
            parseWarnings = allWarnings
        )

        return if (allWarnings.isNotEmpty()) {
            RetailProductSearchResult.PartialSuccess(productsWithOffers, allWarnings, metadata)
        } else {
            RetailProductSearchResult.Success(productsWithOffers, metadata)
        }
    }
}
