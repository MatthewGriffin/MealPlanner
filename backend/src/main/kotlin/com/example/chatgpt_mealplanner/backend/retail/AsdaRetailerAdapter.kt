package com.example.chatgpt_mealplanner.backend.retail

import com.example.chatgpt_mealplanner.retail.contract.RetailProductSearchResult
import com.example.chatgpt_mealplanner.retail.contract.RetailSourceMetadata
import com.example.chatgpt_mealplanner.retail.contract.RetailerCapabilityDto
import com.example.chatgpt_mealplanner.retail.contract.RetailerCapabilityMode
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

open class AsdaRetailerAdapter(
    private val client: AsdaSourceClient = AsdaSourceClient(HttpClient(OkHttp)),
    private val parser: AsdaProductParser = AsdaProductParser()
) {

    open fun getCapability(): RetailerCapabilityDto {
        return RetailerCapabilityDto(
            retailerId = RetailerId.ASDA,
            mode = RetailerCapabilityMode.LIVE_SEARCH_AVAILABLE,
            searchSupported = true,
            livePriceSupported = true,
            availabilitySupported = false,
            promotionsSupported = true,
            description = "Asda live search via Algolia API."
        )
    }

    open suspend fun searchProducts(query: String): RetailProductSearchResult {
        safeLog("[ASDA_LIVE_SEARCH_START] query=\"$query\"")
        val startTime = System.currentTimeMillis()

        val responseJson = try {
            client.search(query)
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown error"
            safeLog("[ASDA_LIVE_SEARCH_ERROR] category=\"NETWORK_ERROR\" message=\"$msg\"")
            return RetailProductSearchResult.Unavailable(
                reason = "Failed to fetch Asda search results: $msg",
                errorCode = if (msg.contains("not configured", true)) "CONFIG_MISSING" else "SOURCE_UNAVAILABLE",
                cachedFallbackAvailable = true
            )
        }

        val parseResult = parser.parseAlgoliaResponse(responseJson)
        val duration = System.currentTimeMillis() - startTime
        safeLog("[ASDA_LIVE_SEARCH_HTTP] status=200 durationMs=$duration")
        safeLog("[ASDA_LIVE_SEARCH_RESULT] query=\"$query\" hitsReceived=${parseResult.hitsReceived} hitsAccepted=${parseResult.hitsAccepted} hitsRejected=${parseResult.hitsRejected}")

        val metadata = RetailSourceMetadata(
            retailerId = RetailerId.ASDA,
            sourceType = com.example.chatgpt_mealplanner.retail.contract.RetailOfferSourceType.LIVE,
            sourceReference = "https://groceries.asda.com/search/$query",
            observedAt = System.currentTimeMillis(),
            isLive = true,
            parseWarnings = emptyList()
        )

        return if (parseResult.productsWithOffers.isEmpty()) {
            RetailProductSearchResult.Unavailable(
                reason = "No parseable product hits found in Asda Algolia response.",
                errorCode = "NO_HITS",
                cachedFallbackAvailable = true
            )
        } else {
            RetailProductSearchResult.Success(parseResult.productsWithOffers, metadata)
        }
    }

    private fun safeLog(msg: String) {
        try {
            println("[AI_MEALPLANNER] $msg")
        } catch (_: Throwable) {}
    }
}
