package com.example.chatgpt_mealplanner.backend.retail

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

/**
 * Public Tesco grocery search. Returned prices are online guide prices, not
 * selected-store prices; this client deliberately does not infer branch stock.
 */
open class TescoSourceClient(
    private val httpClient: HttpClient = HttpClient(OkHttp),
    private val minRequestIntervalMs: Long = 1000L
) {
    private val mutex = Mutex()
    private var lastRequestAt = 0L

    open suspend fun searchHtml(query: String): String {
        require(query.isNotBlank()) { "Tesco search query must not be blank" }
        mutex.withLock {
            val waitMs = minRequestIntervalMs - (System.currentTimeMillis() - lastRequestAt)
            if (waitMs > 0) delay(waitMs)
            lastRequestAt = System.currentTimeMillis()
        }
        val url = URLBuilder("https://www.tesco.com/groceries/en-GB/search").apply {
            parameters.append("query", query.trim())
        }.buildString()
        val response = httpClient.get(url) {
            header(HttpHeaders.UserAgent, "PantryAssistantRetailSearch/1.0 (https://github.com/MatthewGriffin/MealPlanner)")
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml")
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Tesco product search returned HTTP ${response.status.value}")
        }
        return response.bodyAsText()
    }
}
