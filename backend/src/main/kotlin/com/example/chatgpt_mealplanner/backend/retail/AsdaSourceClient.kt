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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put

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
    private val json = Json

    suspend fun search(query: String): String {
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) {
            throw IllegalStateException("ASDA Algolia API key not configured")
        }

        // Serialize user input as JSON rather than interpolating it into request JSON.
        // Quotes, backslashes and control characters in a query must not corrupt the payload.
        val requestBody = json.encodeToString(
            buildJsonObject {
                put("requests", buildJsonArray {
                    add(buildJsonObject {
                        put("query", query)
                        put("indexName", "ASDA_PRODUCTS")
                        put("hitsPerPage", 20)
                    })
                })
            }
        )

        val response = httpClient.post(url) {
            header("x-algolia-application-id", appId)
            header("x-algolia-api-key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        val responseText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw RuntimeException("Algolia returned HTTP " + response.status.value)
        }
        return responseText
    }
}
