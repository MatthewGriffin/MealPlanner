package com.example.chatgpt_mealplanner.core.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pulls what a recipe video's public page says about itself, for the assistant to turn into a saved recipe: the title,
 * the creator, the description or caption, and the creator's own subtitles when the video has them. Automatic
 * (speech-recognised) captions are never used, they are too often wrong about amounts. Nothing here downloads the video.
 */
object VideoPageExtractor {
    enum class Kind { YOUTUBE, TIKTOK }

    data class CaptionTrack(val url: String, val language: String, val automatic: Boolean)

    data class Video(
        val title: String?,
        val creator: String?,
        val description: String?,
        val tracks: List<CaptionTrack> = emptyList()
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun kindOf(host: String?): Kind? {
        val h = host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
        return when {
            h == "youtube.com" || h == "youtu.be" || h == "music.youtube.com" -> Kind.YOUTUBE
            h == "tiktok.com" || h.endsWith(".tiktok.com") -> Kind.TIKTOK
            else -> null
        }
    }

    /** The JSON object that follows `ytInitialPlayerResponse = ` in a watch/shorts page, found by matching braces. */
    fun playerResponseJson(html: String): String? {
        val marker = html.indexOf("ytInitialPlayerResponse")
        if (marker < 0) return null
        val start = html.indexOf('{', marker)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (inString) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return html.substring(start, i + 1) }
            }
        }
        return null
    }

    fun parseYouTube(html: String): Video? {
        val raw = playerResponseJson(html) ?: return null
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val details = root["videoDetails"] as? JsonObject
        val tracks = ((root["captions"] as? JsonObject)?.get("playerCaptionsTracklistRenderer") as? JsonObject)
            ?.get("captionTracks") as? JsonArray
        val parsed = tracks?.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val url = o["baseUrl"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            CaptionTrack(url, o["languageCode"]?.jsonPrimitive?.contentOrNull ?: "", o["kind"]?.jsonPrimitive?.contentOrNull == "asr")
        }.orEmpty()
        val video = Video(
            title = details?.get("title")?.jsonPrimitive?.contentOrNull,
            creator = details?.get("author")?.jsonPrimitive?.contentOrNull,
            description = details?.get("shortDescription")?.jsonPrimitive?.contentOrNull,
            tracks = parsed
        )
        return if (video.title == null && video.description == null) null else video
    }

    /** The creator's own subtitles: English first, then any language; never the automatic ones. */
    fun pickTrack(tracks: List<CaptionTrack>): CaptionTrack? {
        val written = tracks.filter { !it.automatic }
        return written.firstOrNull { it.language.startsWith("en") } ?: written.firstOrNull()
    }

    /** Turns a subtitle file in YouTube's json3 form into plain text. */
    fun captionText(json3: String): String? {
        val events = runCatching { json.parseToJsonElement(json3).jsonObject["events"]?.jsonArray }.getOrNull() ?: return null
        val text = events.mapNotNull { event ->
            (event as? JsonObject)?.get("segs")?.jsonArray
                ?.joinToString("") { it.jsonObject["utf8"]?.jsonPrimitive?.contentOrNull.orEmpty() }
                ?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }
        }.joinToString(" ")
        return text.takeIf { it.isNotBlank() }
    }

    /** TikTok's public oEmbed answer: the caption is its `title`. */
    fun parseTikTokOEmbed(body: String): Video? {
        val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val caption = o["title"]?.jsonPrimitive?.contentOrNull
        val creator = o["author_name"]?.jsonPrimitive?.contentOrNull
        return if (caption.isNullOrBlank()) null else Video(title = null, creator = creator, description = caption)
    }

    /** What the assistant is given. It says plainly when the page held no recipe text so the assistant never invents one. */
    fun render(video: Video, subtitles: String?, url: String): String = buildString {
        appendLine("VIDEO PAGE ($url). This is what the video's public page says. The video itself was not watched.")
        video.title?.let { appendLine("Title: $it") }
        video.creator?.let { appendLine("Creator: $it") }
        appendLine("Description or caption:")
        appendLine(video.description?.trim()?.take(MAX_DESCRIPTION)?.ifBlank { null } ?: "(none)")
        appendLine()
        if (subtitles != null) {
            appendLine("Subtitles written by the creator:")
            appendLine(subtitles.take(MAX_SUBTITLES))
        } else {
            appendLine("Subtitles: none written by the creator (automatic captions are not used).")
        }
    }.trim()

    /** True when the page gives enough text that a recipe could be built from it. */
    fun hasRecipeText(video: Video, subtitles: String?): Boolean =
        (video.description?.length ?: 0) >= 80 || (subtitles?.length ?: 0) >= 120

    const val MAX_DESCRIPTION = 6_000
    const val MAX_SUBTITLES = 12_000
}
