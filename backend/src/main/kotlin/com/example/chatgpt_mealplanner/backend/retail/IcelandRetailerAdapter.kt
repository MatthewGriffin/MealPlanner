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
        if (query.isBlank()) {
            return RetailProductSearchResult.Unavailable(
                reason = "Iceland search query must not be blank.",
                errorCode = "INVALID_QUERY",
                cachedFallbackAvailable = true
            )
        }

        var records: List<ParsedProductRecord> = emptyList()
        var usedAlgolia = false
        val algoliaResponse = runCatching { client.fetchAlgoliaSearchJson(query) }.getOrNull()
        if (algoliaResponse != null) {
            records = parser.parseAlgoliaResponse(algoliaResponse)
            usedAlgolia = records.isNotEmpty()
        }

        // Retain the existing HTML/JSON-LD source as a fallback if Algolia config
        // cannot be discovered, the endpoint fails, or its response schema changes.
        if (records.isEmpty()) {
            val html = try {
                client.fetchSearchHtml(query)
            } catch (e: Exception) {
                return RetailProductSearchResult.Unavailable(
                    reason = "Iceland live search failed and HTML fallback was unavailable: ${e.message}",
                    errorCode = "SOURCE_UNAVAILABLE",
                    cachedFallbackAvailable = true
                )
            }
            records = parser.parseHtmlPage(html)
        }

        if (records.isEmpty()) {
            return RetailProductSearchResult.Unavailable(
                reason = "No parseable Iceland products found in Algolia or HTML/JSON-LD search results.",
                errorCode = "SOURCE_FORMAT_CHANGED",
                cachedFallbackAvailable = true
            )
        }

        val productsWithOffers = records.map { it.productWithOffer }
        val allWarnings = records.flatMap { it.warnings }
        val sourceReference = URLBuilder("https://www.iceland.co.uk/search").apply {
            parameters.append("q", query.trim())
        }.buildString()
        val metadata = RetailSourceMetadata(
            retailerId = RetailerId.ICELAND,
            sourceType = RetailOfferSourceType.LIVE,
            sourceReference = sourceReference + if (usedAlgolia) "#source=algolia" else "#source=html-jsonld",
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
