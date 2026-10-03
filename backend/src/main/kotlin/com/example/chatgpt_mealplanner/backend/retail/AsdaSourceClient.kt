package com.example.chatgpt_mealplanner.backend.retail

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

class AsdaSourceClient(
    private val httpClient: HttpClient = HttpClient(OkHttp),
    private val apiKeyProvider: () -> String = {
        val buildConfigKey = runCatching {
            val clazz = Class.forName("com.example.chatgpt_mealplanner.BuildConfig")
            val field = clazz.getField("ASDA_ALGOLIA_API_KEY")
            field.get(null) as? String ?: ""
        }.getOrDefault("")
        if (buildConfigKey.isNotBlank()) buildConfigKey
        else System.getenv("ASDA_ALGOLIA_API_KEY") ?: System.getProperty("asda.algolia.api.key") ?: ""
    }
) {
    private val appId = "8I6WSKCCNV"
    private val url = "https://8i6wskccnv-dsn.algolia.net/1/indexes/*/queries"

    suspend fun search(query: String): String {
        val apiKey = apiKeyProvider()
        val credentialPresent = apiKey.isNotBlank()
        safeLog("[ASDA_HTTP_CALL] query=$query credentialPresent=$credentialPresent")

        if (!credentialPresent) {
            val ex = IllegalStateException("ASDA Algolia API key not configured")
            safeLog("[ASDA_HTTP_EXCEPTION] query=$query exceptionType=${ex.javaClass.simpleName} message=${ex.message}")
            throw ex
        }

        val requestBody = """
            {
              "requests": [
                {
                  "query": "$query",
                  "indexName": "ASDA_PRODUCTS",
                  "hitsPerPage": 20
                }
              ]
            }
        """.trimIndent()

        val startTime = System.currentTimeMillis()
        val response = try {
            httpClient.post(url) {
                header("x-algolia-application-id", appId)
                header("x-algolia-api-key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
        } catch (e: Exception) {
            safeLog("[ASDA_HTTP_EXCEPTION] query=$query exceptionType=${e.javaClass.simpleName} message=${e.message}")
            throw e
        }

        val duration = System.currentTimeMillis() - startTime
        safeLog("[ASDA_LIVE_SEARCH_HTTP] status=${response.status.value} durationMs=$duration")

        val responseText = response.bodyAsText()
        safeLog("[ASDA_HTTP_RESULT] query=$query httpStatus=${response.status.value} hitCount=...")

        if (!response.status.isSuccess()) {
            val ex = RuntimeException("Algolia returned HTTP ${response.status.value}")
            safeLog("[ASDA_HTTP_EXCEPTION] query=$query exceptionType=${ex.javaClass.simpleName} message=${ex.message}")
            throw ex
        }

        return responseText
    }

    private fun safeLog(msg: String) {
        try {
            println("[AI_MEALPLANNER] $msg")
        } catch (_: Throwable) {}
    }
}
