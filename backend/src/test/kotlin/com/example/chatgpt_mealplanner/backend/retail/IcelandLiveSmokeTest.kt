package com.example.chatgpt_mealplanner.backend.retail

import com.example.chatgpt_mealplanner.retail.contract.RetailProductSearchResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class IcelandLiveSmokeTest {

    @Test
    fun optionalLiveSmokeTest_executesOnlyWhenExplicitlyEnabled() = runBlocking {
        val liveEnabled = System.getenv("ICELAND_LIVE_TESTS") == "true"
        if (!liveEnabled) {
            println("Skipping IcelandLiveSmokeTest because ICELAND_LIVE_TESTS is not set to 'true'.")
            return@runBlocking
        }

        val httpClient = HttpClient(OkHttp)
        val client = IcelandSourceClient(httpClient)
        val adapter = IcelandRetailerAdapter(client)

        val result = adapter.searchProducts("broccoli")
        assertTrue(result is RetailProductSearchResult.Success || result is RetailProductSearchResult.PartialSuccess)
    }
}
