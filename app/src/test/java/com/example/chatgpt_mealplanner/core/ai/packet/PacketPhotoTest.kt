package com.example.chatgpt_mealplanner.core.ai.packet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun aFullSetOfInstructionsIsEnoughToWorkFromAsText() {
        assertTrue(PacketTextQuality.isUsable(goodPacket))
    }

    @Test
    fun scrapsFromABlurredPhotoOrJustABrandAreNot() {
        assertFalse(PacketTextQuality.isUsable(""))
        assertFalse(PacketTextQuality.isUsable("OLD EL PASO FAJITA KIT"))
        assertFalse(PacketTextQuality.isUsable("Add 3 tbsp water and cook"))
        // Plenty of words, but nothing about cooking: the front of a packet.
        assertFalse(PacketTextQuality.isUsable("Delicious family favourite with real spices and a rich smoky flavour made with natural ingredients and no artificial colours or preservatives for the whole family to enjoy together tonight"))
    }

    @Test
    fun usableTextGoesAlongsideTheMessageWithoutThePicture() {
        val turn = PacketPhoto(goodPacket, imageJpegBase64 = null).asUserTurn("here is the fajita kit packet")

        assertEquals("user", turn.role)
        assertNull(turn.imageJpegBase64)
        assertTrue(turn.content.startsWith("here is the fajita kit packet"))
        assertTrue(turn.content.contains("Text read from the photo of the packet"))
        assertTrue(turn.content.contains("3 tbsp water"))
        assertFalse(turn.content.contains("photo of the packet is attached"))
    }

    @Test
    fun whenTheTextFellShortThePictureIsAttachedAndClaudeIsToldToReadIt() {
        val turn = PacketPhoto("FAJITA KIT 8 tortillas", imageJpegBase64 = "QUJD").asUserTurn("the packet")

        assertEquals("QUJD", turn.imageJpegBase64)
        assertTrue(turn.content.contains("FAJITA KIT 8 tortillas"))
        assertTrue(turn.content.contains("read the image itself"))
    }

    @Test
    fun aPictureWithNoTextAtAllIsStillSent() {
        val turn = PacketPhoto("", imageJpegBase64 = "QUJD").asUserTurn("the packet")

        assertEquals("QUJD", turn.imageJpegBase64)
        assertFalse(turn.content.contains("Text read from"))
    }

    @Test
    fun garbledTextFromAnUnconfidentReaderIsNotTrustedEvenIfItHasEnoughWords() {
        // The same words, as the phone read them from clear and from unreadably small print.
        assertTrue(PacketTextQuality.isUsable(goodPacket, averageConfidence = 0.87f, lowestConfidence = 0.72f))
        assertFalse(PacketTextQuality.isUsable(goodPacket, averageConfidence = 0.61f, lowestConfidence = 0.47f))
        // One bad line is enough to doubt an amount.
        assertFalse(PacketTextQuality.isUsable(goodPacket, averageConfidence = 0.86f, lowestConfidence = 0.5f))
        // A reader that gave no scores is judged on the words alone.
        assertTrue(PacketTextQuality.isUsable(goodPacket))
    }
}
