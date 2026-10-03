package com.example.chatgpt_mealplanner.backend.retail

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
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

    suspend fun fetchSearchHtml(query: String): String {
        rateLimiterMutex.withLock {
            val now = System.currentTimeMillis()
            val timeSinceLast = now - lastRequestTimestamp
            if (timeSinceLast < config.minRequestIntervalMs) {
                kotlinx.coroutines.delay(config.minRequestIntervalMs - timeSinceLast)
            }
            lastRequestTimestamp = System.currentTimeMillis()
        }

        val url = when {
            query.startsWith("http://") || query.startsWith("https://") -> query
            query.contains("egg", ignoreCase = true) -> "https://www.iceland.co.uk/fresh/milk-butter-and-eggs/eggs"
            query.contains("cheese", ignoreCase = true) || query.contains("cheddar", ignoreCase = true) -> "https://www.iceland.co.uk/fresh/cheese/cheddar-cheese"
            query.contains("bread", ignoreCase = true) -> "https://www.iceland.co.uk/fresh/bakery/bread"
            query.contains("spinach", ignoreCase = true) || query.contains("mushroom", ignoreCase = true) || query.contains("veg", ignoreCase = true) || query.contains("salad", ignoreCase = true) -> "https://www.iceland.co.uk/frozen/frozen-vegetables-and-rice"
            else -> "https://www.iceland.co.uk/search?q=${query.replace(" ", "+")}"
        }

        val response = httpClient.get(url) {
            header(HttpHeaders.UserAgent, config.userAgent)
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        }

        return response.bodyAsText()
    }
}
