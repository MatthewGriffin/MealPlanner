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
            when (VideoPageExtractor.kindOf(parsed.host)) {
                VideoPageExtractor.Kind.YOUTUBE -> return@withContext fetchYouTube(parsed)
                VideoPageExtractor.Kind.TIKTOK -> return@withContext fetchTikTok(parsed)
                null -> Unit
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

    private fun getText(url: okhttp3.HttpUrl, vararg headers: Pair<String, String>): String? {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        return client.newBuilder().followRedirects(true).build().newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.source()?.let { source ->
                source.request(MAX_BYTES)
                source.buffer.snapshot().utf8().take(MAX_BYTES.toInt())
            }
        }
    }

    /** A YouTube video or Short: its description and the creator's own subtitles, read from the public page. */
    private fun fetchYouTube(url: okhttp3.HttpUrl): Result<String> {
        val html = getText(
            url,
            "User-Agent" to BROWSER_UA, "Accept-Language" to "en-GB,en;q=0.9", "Cookie" to "CONSENT=YES+1; SOCS=CAI"
        ) ?: return Result.failure(IllegalStateException("YouTube didn't return the video page."))
        val video = VideoPageExtractor.parseYouTube(html)
            ?: return Result.failure(IllegalStateException("That video's page couldn't be read."))
        val subtitles = VideoPageExtractor.pickTrack(video.tracks)?.let { track ->
            val withFormat = track.url.replace("\\u0026", "&") + if (track.url.contains("fmt=")) "" else "&fmt=json3"
            withFormat.toHttpUrlOrNull()?.takeIf { isSafeToFetch(it) }?.let { runCatching { getText(it, "User-Agent" to BROWSER_UA) }.getOrNull() }
                ?.let { VideoPageExtractor.captionText(it) }
        }
        return Result.success(withNoRecipeNote(VideoPageExtractor.render(video, subtitles, url.toString()), VideoPageExtractor.hasRecipeText(video, subtitles)))
    }

    /** A TikTok: its caption and creator, from TikTok's public oEmbed answer. */
    private fun fetchTikTok(url: okhttp3.HttpUrl): Result<String> {
        val oembed = "https://www.tiktok.com/oembed".toHttpUrlOrNull()!!.newBuilder().addQueryParameter("url", url.toString()).build()
        val body = getText(oembed, "User-Agent" to BROWSER_UA)
            ?: return Result.failure(IllegalStateException("TikTok didn't return that video's details."))
        val video = VideoPageExtractor.parseTikTokOEmbed(body)
            ?: return Result.failure(IllegalStateException("That TikTok has no caption to read."))
        return Result.success(withNoRecipeNote(VideoPageExtractor.render(video, null, url.toString()), VideoPageExtractor.hasRecipeText(video, null)))
    }

    private fun withNoRecipeNote(text: String, hasRecipe: Boolean): String =
        if (hasRecipe) text else text + "\n\nNOTE: the page has little or no recipe text. Do not invent a recipe: tell the user the video doesn't describe the recipe and ask them to paste the ingredients or send screenshots of it."

    internal companion object {
        const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
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
