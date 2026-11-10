package com.example.chatgpt_mealplanner.core.web

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/** Fetches a web page for the assistant to read. */
interface WebPageSource {
    /** The page's readable content, or a failure with a reason in words the assistant can pass on. */
    suspend fun fetch(url: String): Result<String>
}

/** Cuts a web page down to a recipe where it has one, otherwise to its readable text. */
@Singleton
class WebPageFetcher @Inject constructor(private val client: OkHttpClient) : WebPageSource {

    override suspend fun fetch(url: String): Result<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val parsed = url.trim().toHttpUrlOrNull()
                ?: return@withContext Result.failure(IllegalArgumentException("That isn't a web address."))
            if (!isSafeToFetch(parsed)) {
                return@withContext Result.failure(IllegalArgumentException("That address can't be fetched."))
            }
            val request = Request.Builder().url(parsed).header("User-Agent", "Mozilla/5.0 (Android) MealPlanner").build()
            client.newBuilder().followRedirects(true).build().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("The page returned HTTP ${response.code}."))
                }
                val body = response.body ?: return@withContext Result.failure(IllegalStateException("The page was empty."))
                val html = body.source().let { source ->
                    source.request(MAX_BYTES)
                    source.buffer.snapshot().utf8().take(MAX_BYTES.toInt())
                }
                Result.success(RecipePageExtractor.extract(html))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(IllegalStateException("The page couldn't be fetched (${e.message ?: e.javaClass.simpleName})."))
        }
    }

    internal companion object {
        const val MAX_BYTES = 2_000_000L

        /** Web pages only, and not the phone's own network or a private one. */
        fun isSafeToFetch(url: okhttp3.HttpUrl): Boolean {
            if (url.scheme != "https" && url.scheme != "http") return false
            return try {
                InetAddress.getAllByName(url.host).none { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress }
            } catch (_: Exception) {
                false
            }
        }
    }
}
