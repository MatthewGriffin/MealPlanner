package com.example.chatgpt_mealplanner.core.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPageExtractorTest {
    private val page = """
        <html><script>var x = {"a":1};</script>
        <script>var ytInitialPlayerResponse = {"videoDetails":{"title":"Easy Garlic Chicken","author":"Chef Sam","shortDescription":"Ingredients: 4 chicken thighs, 3 cloves garlic {crushed}, 2 tbsp butter.\nMethod: sear 5 min each side, add garlic and butter, bake 25 min at 200C."},
        "captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[
          {"baseUrl":"https://example.test/auto","languageCode":"en","kind":"asr"},
          {"baseUrl":"https://example.test/de","languageCode":"de"},
          {"baseUrl":"https://example.test/en","languageCode":"en-GB"}]}}};var meta = 1;</script></html>
    """.trimIndent()

    @Test
    fun readsTitleCreatorAndDescriptionEvenWithBracesInsideStrings() {
        val video = VideoPageExtractor.parseYouTube(page)!!
        assertEquals("Easy Garlic Chicken", video.title)
        assertEquals("Chef Sam", video.creator)
        assertTrue(video.description!!.contains("{crushed}"))
        assertEquals(3, video.tracks.size)
    }

    @Test
    fun neverPicksAutomaticCaptionsAndPrefersEnglish() {
        val video = VideoPageExtractor.parseYouTube(page)!!
        assertEquals("https://example.test/en", VideoPageExtractor.pickTrack(video.tracks)!!.url)
        assertNull(VideoPageExtractor.pickTrack(listOf(VideoPageExtractor.CaptionTrack("u", "en", automatic = true))))
    }

    @Test
    fun turnsJson3SubtitlesIntoText() {
        val json3 = """{"events":[{"segs":[{"utf8":"Add the garlic"}]},{"tStartMs":1},{"segs":[{"utf8":"and "},{"utf8":"butter\n"}]}]}"""
        assertEquals("Add the garlic and butter", VideoPageExtractor.captionText(json3))
        assertNull(VideoPageExtractor.captionText("not json"))
    }

    @Test
    fun readsATikTokCaption() {
        val video = VideoPageExtractor.parseTikTokOEmbed("""{"title":"2 ingredient pancakes: 1 banana 2 eggs, fry","author_name":"foodie"}""")!!
        assertEquals("foodie", video.creator)
        assertTrue(video.description!!.contains("banana"))
        assertNull(VideoPageExtractor.parseTikTokOEmbed("""{"title":""}"""))
    }

    @Test
    fun recognisesVideoLinksOnly() {
        assertEquals(VideoPageExtractor.Kind.YOUTUBE, VideoPageExtractor.kindOf("www.youtube.com"))
        assertEquals(VideoPageExtractor.Kind.YOUTUBE, VideoPageExtractor.kindOf("youtu.be"))
        assertEquals(VideoPageExtractor.Kind.TIKTOK, VideoPageExtractor.kindOf("vm.tiktok.com"))
        assertNull(VideoPageExtractor.kindOf("bbcgoodfood.com"))
    }

    @Test
    fun saysWhenThePageHasNoRecipeText() {
        val thin = VideoPageExtractor.Video("Yum", "x", "so good #food")
        assertFalse(VideoPageExtractor.hasRecipeText(thin, null))
        assertTrue(VideoPageExtractor.render(thin, null, "https://youtu.be/x").contains("none written by the creator"))
        assertNotNull(VideoPageExtractor.parseYouTube(page))
        assertTrue(VideoPageExtractor.hasRecipeText(VideoPageExtractor.parseYouTube(page)!!, null))
    }
}
