package com.example.chatgpt_mealplanner.backend.retail

import com.example.chatgpt_mealplanner.retail.contract.RetailProductSearchResult
import com.example.chatgpt_mealplanner.retail.contract.RetailerCapabilityDto
import com.example.chatgpt_mealplanner.retail.contract.RetailerCapabilityMode
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

/**
 * ASDA live product retrieval is deliberately gated until ASDA grants permission for automated
 * third-party use of its product data or provides an approved API/feed.
 *
 * Keep the parser and client injectable for a future authorised integration, but do not call the
 * undocumented Algolia endpoint merely because it is technically reachable. Cached or manually
 * entered offers can still be used by the shopping optimizer.
 */
open class AsdaRetailerAdapter(
    @Suppress("unused") private val client: AsdaSourceClient = AsdaSourceClient(HttpClient(OkHttp)),
    @Suppress("unused") private val parser: AsdaProductParser = AsdaProductParser()
) {

    open fun getCapability(): RetailerCapabilityDto {
        return RetailerCapabilityDto(
            retailerId = RetailerId.ASDA,
            mode = RetailerCapabilityMode.MANUAL_AND_CACHED_ONLY,
            searchSupported = false,
            livePriceSupported = false,
            availabilitySupported = false,
            promotionsSupported = false,
            description = "Automated ASDA product-data retrieval is disabled pending explicit permission or an approved API/feed. Cached and manually entered offers remain available; branch stock is not confirmed."
        )
    }

    open suspend fun searchProducts(query: String): RetailProductSearchResult {
        return RetailProductSearchResult.Unavailable(
            reason = "Automated ASDA product-data retrieval requires explicit permission or an approved API/feed.",
            errorCode = "SOURCE_PERMISSION_REQUIRED",
            cachedFallbackAvailable = true
        )
    }
}
