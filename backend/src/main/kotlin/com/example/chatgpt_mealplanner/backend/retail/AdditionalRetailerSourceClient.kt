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

/**
 * Public retailer search sources for retailers without a verified branch-level endpoint.
 * Sainsbury's public unauthenticated product-search response is JSON; no authenticated
 * basket, checkout, or private store-stock endpoint is called.
 */
open class AdditionalRetailerSourceClient(
    private val httpClient: HttpClient = HttpClient(OkHttp),
    private val minRequestIntervalMs: Long = 1000L
) {
    private val mutex = Mutex()
    private var lastRequestAt = 0L

    open suspend fun searchHtml(retailerId: RetailerId, query: String): String {
        require(query.isNotBlank()) { "Retailer search query must not be blank" }
        return fetch(retailerId, query.trim(), pageNumber = 1, pageSize = 24)
    }

    /**
     * Fetch one page from Sainsbury's public product endpoint without a keyword filter.
     * This is only a candidate catalogue source: callers must validate the response's
     * pagination metadata and complete every page before treating the result as a catalogue.
     */
    open suspend fun fetchSainsburysCataloguePageJson(pageNumber: Int, pageSize: Int = 60): String {
        require(pageNumber >= 1) { "Sainsbury's page number must be one or greater" }
        require(pageSize in 1..60) { "Sainsbury's page size must be between 1 and 60" }
        return fetch(RetailerId.SAINSBURYS, query = "", pageNumber = pageNumber, pageSize = pageSize)
    }

    private suspend fun fetch(
        retailerId: RetailerId,
        query: String,
        pageNumber: Int,
        pageSize: Int
    ): String {
        mutex.withLock {
            val waitMs = minRequestIntervalMs - (System.currentTimeMillis() - lastRequestAt)
            if (waitMs > 0) delay(waitMs)
            lastRequestAt = System.currentTimeMillis()
        }
        val url = when (retailerId) {
            RetailerId.MORRISONS -> URLBuilder("https://groceries.morrisons.com/search").apply {
                parameters.append("q", query)
            }.buildString()
            RetailerId.SAINSBURYS -> URLBuilder("https://www.sainsburys.co.uk/groceries-api/gol-services/product/v1/product").apply {
                if (query.isNotBlank()) parameters.append("filter[keyword]", query)
                parameters.append("page_number", pageNumber.toString())
                parameters.append("page_size", pageSize.toString())
            }.buildString()
            RetailerId.LIDL -> URLBuilder("https://www.lidl.co.uk/q/search").apply {
                parameters.append("q", query)
            }.buildString()
            else -> throw IllegalArgumentException("No public search route configured for $retailerId")
        }
        val response = httpClient.get(url) {
            header(HttpHeaders.UserAgent, "PantryAssistantRetailSearch/1.0 (https://github.com/MatthewGriffin/MealPlanner)")
            header(HttpHeaders.Accept, if (retailerId == RetailerId.SAINSBURYS) "application/json" else "text/html,application/xhtml+xml,application/json;q=0.9")
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("${retailerId.name} public product search returned HTTP ${response.status.value}")
        }
        return response.bodyAsText()
    }
}
