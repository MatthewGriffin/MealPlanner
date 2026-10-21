package com.example.chatgpt_mealplanner.backend.retail

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IcelandSourceClientTest {

    @Test
    fun freeTextSearchUsesSearchRouteAndPreservesQueryParameters() = runBlocking {
        var requestedUrl: Url? = null
        val httpClient = HttpClient(MockEngine { request ->
            requestedUrl = request.url
            respond(
                content = "<html>search result</html>",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html")
            )
        })
        try {
            val client = IcelandSourceClient(
                httpClient = httpClient,
                config = IcelandClientConfig(minRequestIntervalMs = 0)
            )

            val body = client.fetchSearchHtml("ice cream & eggs")

            assertEquals("<html>search result</html>", body)
            assertEquals("www.iceland.co.uk", requestedUrl?.host)
            assertEquals("/search", requestedUrl?.encodedPath)
            assertEquals("ice cream & eggs", requestedUrl?.parameters?.get("q"))
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun discoveredAlgoliaConfigurationDrivesDirectProductSearchRequest() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val homepage = """
            <html>
              <script src="/assets/search-config.js"></script>
            </html>
        """.trimIndent()
        val script = """
            window.searchConfig = {
              applicationId: "ABCDEF1234",
              searchApiKey: "0123456789abcdef0123456789abcdef",
              indexName: "iceland_products"
            };
        """.trimIndent()
        val searchResponse = """{"hits":[{"objectID":"milk-1","name":"Semi Skimmed Milk","price":"1.45"}]}"""
        val httpClient = HttpClient(MockEngine { request ->
            requests += request
            when {
                request.url.host == "www.iceland.co.uk" && request.url.encodedPath == "/" ->
                    respond(homepage, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                request.url.host == "www.iceland.co.uk" && request.url.encodedPath == "/assets/search-config.js" ->
                    respond(script, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/javascript"))
                request.url.host == "ABCDEF1234-dsn.algolia.net" &&
                    request.url.encodedPath == "/1/indexes/iceland_products/query" -> {
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals("ABCDEF1234", request.headers["x-algolia-application-id"])
                    assertEquals("0123456789abcdef0123456789abcdef", request.headers["x-algolia-api-key"])
                    respond(searchResponse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                else -> error("Unexpected request: ${request.method} ${request.url}")
            }
        })
        try {
            val client = IcelandSourceClient(
                httpClient = httpClient,
                config = IcelandClientConfig(minRequestIntervalMs = 0)
            )

            assertEquals(searchResponse, client.fetchAlgoliaSearchJson("semi skimmed milk"))
            assertEquals(3, requests.size)
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun nonSuccessHttpResponseIsNotTreatedAsSearchHtml() = runBlocking {
        val httpClient = HttpClient(MockEngine {
            respond(
                content = "temporarily unavailable",
                status = HttpStatusCode.ServiceUnavailable,
                headers = headersOf(HttpHeaders.ContentType, "text/plain")
            )
        })
        try {
            val client = IcelandSourceClient(
                httpClient = httpClient,
                config = IcelandClientConfig(minRequestIntervalMs = 0)
            )

            val error = assertFailsWith<IllegalStateException> {
                client.fetchSearchHtml("milk")
            }
            assertTrue(error.message.orEmpty().contains("503"))
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun blankSearchIsRejectedBeforeMakingRequest() = runBlocking {
        val httpClient = HttpClient(MockEngine {
            error("HTTP request should not be made for a blank query")
        })
        try {
            val client = IcelandSourceClient(
                httpClient = httpClient,
                config = IcelandClientConfig(minRequestIntervalMs = 0)
            )
            assertFailsWith<IllegalArgumentException> {
                client.fetchSearchHtml("  ")
            }
        } finally {
            httpClient.close()
        }
    }
}
