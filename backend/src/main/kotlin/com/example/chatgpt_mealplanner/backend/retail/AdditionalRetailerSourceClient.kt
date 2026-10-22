package com.example.chatgpt_mealplanner.backend.retail

import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder

/**
 * Public retailer search pages for retailers without a verified branch-level endpoint.
 * This client intentionally does not call undocumented JSON APIs or bypass access controls.
 */
open class AdditionalRetailerSourceClient(
    private val httpClient: HttpClient = HttpClient(OkHttp),
    private val minRequestIntervalMs: Long = 1000L
) {
    private val mutex = Mutex()
    private var lastRequestAt = 0L

    open suspend fun searchHtml(retailerId: RetailerId, query: String): String {
        require(query.isNotBlank()) { "Retailer search query must not be blank" }
        mutex.withLock {
            val waitMs = minRequestIntervalMs - (System.currentTimeMillis() - lastRequestAt)
            if (waitMs > 0) delay(waitMs)
            lastRequestAt = System.currentTimeMillis()
        }
        val url = when (retailerId) {
            RetailerId.MORRISONS -> URLBuilder("https://groceries.morrisons.com/search").apply {
                parameters.append("q", query.trim())
            }.buildString()
            RetailerId.SAINSBURYS -> "https://www.sainsburys.co.uk/gol-ui/SearchResults/" +
                URLEncoder.encode(query.trim(), Charsets.UTF_8.name()).replace("+", "%20")
            RetailerId.LIDL -> URLBuilder("https://www.lidl.co.uk/q/search").apply {
                parameters.append("q", query.trim())
            }.buildString()
            else -> throw IllegalArgumentException("No public search route configured for $retailerId")
        }
        val response = httpClient.get(url) {
            header(HttpHeaders.UserAgent, "PantryAssistantRetailSearch/1.0 (https://github.com/MatthewGriffin/MealPlanner)")
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/json;q=0.9")
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("${retailerId.name} public product search returned HTTP ${response.status.value}")
        }
        return response.bodyAsText()
    }
}
