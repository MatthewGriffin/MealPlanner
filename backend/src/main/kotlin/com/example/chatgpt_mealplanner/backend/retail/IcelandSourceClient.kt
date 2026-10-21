package com.example.chatgpt_mealplanner.backend.retail

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class IcelandClientConfig(
    val minRequestIntervalMs: Long = 1000L,
    val maxRequestsPerMinute: Int = 60,
    val userAgent: String = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36",
    val timeoutMs: Long = 10000L
)

class IcelandSourceClient(
    private val httpClient: HttpClient,
    private val config: IcelandClientConfig = IcelandClientConfig()
) {
    private val rateLimiterMutex = Mutex()
    private var lastRequestTimestamp = 0L

    /**
     * Fetch the retailer's real search route for every free-text query.
     *
     * Do not map arbitrary search terms to hand-picked category pages: that can return
     * unrelated products while appearing to have searched successfully.
     */
    suspend fun fetchSearchHtml(query: String): String {
        require(query.isNotBlank()) { "Iceland search query must not be blank" }
        rateLimiterMutex.withLock {
            val now = System.currentTimeMillis()
            val timeSinceLast = now - lastRequestTimestamp
            if (timeSinceLast < config.minRequestIntervalMs) {
                kotlinx.coroutines.delay(config.minRequestIntervalMs - timeSinceLast)
            }
            lastRequestTimestamp = System.currentTimeMillis()
        }

        val url = URLBuilder("https://www.iceland.co.uk/search").apply {
            parameters.append("q", query.trim())
        }.buildString()

        val response = httpClient.get(url) {
            header(HttpHeaders.UserAgent, config.userAgent)
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Iceland search returned HTTP " + response.status.value)
        }
        return response.bodyAsText()
    }
}
