package com.example.chatgpt_mealplanner.backend.retail

import com.example.chatgpt_mealplanner.retail.contract.RetailProductSearchResult
import com.example.chatgpt_mealplanner.retail.contract.RetailerCapabilityDto
import com.example.chatgpt_mealplanner.retail.contract.RetailerCapabilityMode
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

/**
 * ASDA's current Algolia client targets an undocumented endpoint and requires a key
 * whose approved source and permitted use have not been established. Keep it gated
 * until an approved feed or explicit source authorization is available.
 */
open class AsdaRetailerAdapter(
    @Suppress("UNUSED_PARAMETER") private val client: AsdaSourceClient = AsdaSourceClient(HttpClient(OkHttp)),
    @Suppress("UNUSED_PARAMETER") private val parser: AsdaProductParser = AsdaProductParser()
) {
    open fun getCapability(): RetailerCapabilityDto = RetailerCapabilityDto(
        retailerId = RetailerId.ASDA,
        mode = RetailerCapabilityMode.MANUAL_AND_CACHED_ONLY,
        searchSupported = false,
        livePriceSupported = false,
        availabilitySupported = false,
        promotionsSupported = false,
        description = "Live lookup is disabled until an approved catalogue source is available; saved and manual offers remain usable."
    )

    open suspend fun searchProducts(query: String): RetailProductSearchResult =
        if (query.isBlank()) {
            RetailProductSearchResult.Unavailable(
                reason = "Enter a product to search for.",
                errorCode = "EMPTY_QUERY",
                cachedFallbackAvailable = true
            )
        } else {
            RetailProductSearchResult.Unavailable(
                reason = "ASDA live lookup is not enabled because the current endpoint is undocumented and its permitted use has not been verified.",
                errorCode = "SOURCE_PERMISSION_REQUIRED",
                cachedFallbackAvailable = true
            )
        }
}
