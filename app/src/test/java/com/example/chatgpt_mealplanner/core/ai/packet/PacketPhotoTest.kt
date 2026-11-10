package com.example.chatgpt_mealplanner.core.ai.packet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketPhotoTest {
    @Test
    fun aSinglePhotoIsAttachedAndExplained() {
        val turn = PacketPhoto(listOf("QUJD")).asUserTurn("the packet")

        assertEquals("user", turn.role)
        assertEquals(listOf("QUJD"), turn.imagesJpegBase64)
        assertTrue(turn.content.startsWith("the packet"))
        assertTrue(turn.content.contains("A photo is attached for you to read"))
        assertTrue(turn.content.contains("not kept"))
    }

    @Test
    fun severalPagesAreAttachedInOrder() {
        val turn = PacketPhoto(listOf("A", "B", "C")).asUserTurn("my recipe")

        assertEquals(listOf("A", "B", "C"), turn.imagesJpegBase64)
        assertTrue(turn.content.contains("3 photos are attached"))
        assertTrue(turn.content.contains("in the order the user took them"))
    }
}
