package com.example.chatgpt_mealplanner.backend.retail

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI

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
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile
    private var cachedAlgoliaConfig: IcelandAlgoliaConfigDiscovery.Config? = null

    /**
     * Discover Iceland's public browser-side Algolia settings from the retailer's own
     * homepage and same-site JavaScript assets. No credentials or index names are guessed.
     * Returns null when the live site no longer exposes a recognizable configuration.
     */
    suspend fun fetchAlgoliaSearchJson(query: String): String? {
        require(query.isNotBlank()) { "Iceland search query must not be blank" }
        return fetchAlgoliaPageJson(query.trim(), page = 0, hitsPerPage = 30)
    }

    /**
     * Fetch one bounded page of Iceland's public browser-side product index.
     * An empty query requests the index's default catalogue ordering. Callers must
     * inspect nbPages and request pages incrementally; this method never crawls
     * the whole catalogue in one request.
     */
    suspend fun fetchAlgoliaCataloguePageJson(page: Int, hitsPerPage: Int = 100): String? {
        require(page >= 0) { "Catalogue page must be zero or greater" }
        require(hitsPerPage in 1..100) { "Catalogue page size must be between 1 and 100" }
        return fetchAlgoliaPageJson(query = "", page = page, hitsPerPage = hitsPerPage)
    }

    private suspend fun fetchAlgoliaPageJson(query: String, page: Int, hitsPerPage: Int): String? {
        val discovered = cachedAlgoliaConfig ?: discoverAlgoliaConfig().also { found ->
            if (found != null) cachedAlgoliaConfig = found
        } ?: return null
        val url = "https://${discovered.applicationId}-dsn.algolia.net/1/indexes/${encodePathSegment(discovered.indexName)}/query"
        val requestBody = json.encodeToString(
            buildJsonObject {
                put("query", query)
                put("page", page)
                put("hitsPerPage", hitsPerPage)
            }
        )
        awaitRequestSlot()
        val response = httpClient.post(url) {
            header("x-algolia-application-id", discovered.applicationId)
            header("x-algolia-api-key", discovered.searchApiKey)
            header(HttpHeaders.UserAgent, config.userAgent)
            // Iceland exposes a browser-restricted Algolia key; send the retailer page
            // as the referer, as the storefront browser client does.
            header("Referer", "https://www.iceland.co.uk/")
            header("Origin", "https://www.iceland.co.uk")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        if (!response.status.isSuccess()) {
            val responseDetails = response.bodyAsText()
                .replace(Regex("\\s+"), " ")
                .take(500)
            throw IllegalStateException(
                "Iceland Algolia search returned HTTP ${response.status.value}: ${responseDetails.ifBlank { "<empty response body>" }}"
            )
        }
        return response.bodyAsText()
    }

    /**
     * Fetch the retailer's real search route as the HTML/JSON-LD fallback.
     * Never map arbitrary terms to hand-picked categories and imply they were searched.
     */
    suspend fun fetchSearchHtml(query: String): String {
        require(query.isNotBlank()) { "Iceland search query must not be blank" }
        val url = URLBuilder("https://www.iceland.co.uk/search").apply {
            parameters.append("q", query.trim())
        }.buildString()
        return getText(url)
    }

    private suspend fun discoverAlgoliaConfig(): IcelandAlgoliaConfigDiscovery.Config? {
        val homepage = getText("https://www.iceland.co.uk/")
        val sources = mutableListOf(homepage)
        val scriptUrls = Regex("""(?i)<script[^>]+src=["']([^"']+\.js(?:\?[^"']*)?)["']""")
            .findAll(homepage)
            .mapNotNull { match ->
                runCatching { URI("https://www.iceland.co.uk/").resolve(match.groupValues[1]).toString() }
                    .getOrNull()
            }
            .filter { url ->
                runCatching {
                    val host = URI(url).host.orEmpty().lowercase()
                    (host == "iceland.co.uk" || host.endsWith(".iceland.co.uk")) &&
                        url.startsWith("https://")
                }.getOrDefault(false)
            }
            .distinct()
            // Keep first-run discovery bounded; do not fan out across every site asset.
            .take(12)
            .toList()

        for (scriptUrl in scriptUrls) {
            val source = runCatching { getText(scriptUrl) }.getOrNull() ?: continue
            sources += source
            IcelandAlgoliaConfigDiscovery.find(sources)?.let { return it }
        }
        return IcelandAlgoliaConfigDiscovery.find(sources)
    }

    private suspend fun awaitRequestSlot() {
        rateLimiterMutex.withLock {
            val now = System.currentTimeMillis()
            val timeSinceLast = now - lastRequestTimestamp
            if (timeSinceLast < config.minRequestIntervalMs) {
                kotlinx.coroutines.delay(config.minRequestIntervalMs - timeSinceLast)
            }
            lastRequestTimestamp = System.currentTimeMillis()
        }
    }

    private suspend fun getText(url: String): String {
        awaitRequestSlot()
        val response = httpClient.get(url) {
            header(HttpHeaders.UserAgent, config.userAgent)
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/javascript,application/json;q=0.9,*/*;q=0.8")
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Iceland source returned HTTP " + response.status.value)
        }
        return response.bodyAsText()
    }

    private fun encodePathSegment(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
