package com.example.chatgpt_mealplanner.core.ai.packet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketPhotoTest {

    private val goodPacket = """
        Old El Paso Fajita Kit. Contains 8 soft tortillas, 1 sachet of fajita seasoning and 1 jar of salsa.
        You will need 400 g chicken strips, 2 peppers, 1 onion and 2 tbsp oil.
        Heat the oil in a large pan, add the chicken and fry for 5 minutes. Add the vegetables and cook for 3 minutes.
        Stir in the seasoning with 3 tbsp water and simmer for 2 minutes. Serve with the warm tortillas and salsa.
    """.trimIndent()

    @Test
    fun aFullSetOfInstructionsHasNoProblems() {
        assertTrue(PacketTextQuality.problems(goodPacket, 0.87f, 0.72f).isEmpty())
        assertTrue(PacketTextQuality.isUsable(goodPacket))
    }

    @Test
    fun eachWayTheTextCanFallShortIsExplainedInPlainWords() {
        assertEquals(listOf("The phone couldn't find any text in the photo."), PacketTextQuality.problems(""))
        val brand = PacketTextQuality.problems("OLD EL PASO FAJITA KIT")
        assertTrue(brand.any { it.startsWith("Only 5 words were found") })
        assertTrue(brand.any { it.contains("doesn't look like cooking instructions") })
        // Plenty of words but nothing about cooking: the front of a packet.
        val front = PacketTextQuality.problems("Delicious family favourite with real spices and a rich smoky flavour made with natural ingredients and no artificial colours or preservatives for the whole family to enjoy together tonight with friends, neighbours and relatives at any time of the year")
        assertEquals(1, front.size)
        assertTrue(front.single().contains("doesn't look like cooking instructions"))
    }

    @Test
    fun garbledTextFromAnUnconfidentReaderIsNotTrustedEvenIfItHasEnoughWords() {
        val garbled = PacketTextQuality.problems(goodPacket, averageConfidence = 0.61f, lowestConfidence = 0.47f)
        assertEquals(listOf("The phone wasn't confident about some of the text, so words or amounts may be wrong."), garbled)
        // One bad line is enough to doubt an amount.
        assertFalse(PacketTextQuality.isUsable(goodPacket, averageConfidence = 0.86f, lowestConfidence = 0.5f))
        // A reader that gave no scores is judged on the words alone.
        assertTrue(PacketTextQuality.isUsable(goodPacket))
    }

    @Test
    fun aScanOffersTheTextTheUserCheckedOrThePictureForClaude() {
        val scan = PacketScan(goodPacket, 0.87f, 0.72f, jpegBase64 = "QUJD")
        assertTrue(scan.looksGood)

        val text = scan.textForClaude("  Fry the chicken 6 minutes, add the seasoning.  ")
        assertEquals("Fry the chicken 6 minutes, add the seasoning.", text.ocrText)
        assertNull(text.imageJpegBase64)

        val picture = scan.pictureForClaude()!!
        assertEquals("", picture.ocrText)
        assertEquals("QUJD", picture.imageJpegBase64)
    }

    @Test
    fun withoutAPictureThereIsNothingForClaudeToRead() {
        assertNull(PacketScan("Add water", null, null, jpegBase64 = null).pictureForClaude())
        assertFalse(PacketScan("Add water", null, null, jpegBase64 = null).looksGood)
    }

    @Test
    fun checkedTextGoesAlongsideTheMessage() {
        val turn = PacketPhoto("Fry for 5 minutes, add 3 tbsp water.", null).asUserTurn("here are the instructions")

        assertEquals("user", turn.role)
        assertNull(turn.imageJpegBase64)
        assertTrue(turn.content.startsWith("here are the instructions"))
        assertTrue(turn.content.contains("the user has checked it"))
        assertTrue(turn.content.contains("3 tbsp water"))
        assertFalse(turn.content.contains("attached for you to read"))
    }

    @Test
    fun aPictureIsAttachedForClaudeToRead() {
        val turn = PacketPhoto("", "QUJD").asUserTurn("the packet")

        assertEquals("QUJD", turn.imageJpegBase64)
        assertNotNull(turn.content)
        assertTrue(turn.content.contains("attached for you to read"))
        assertFalse(turn.content.contains("Text from the kit's packet"))
    }
}
