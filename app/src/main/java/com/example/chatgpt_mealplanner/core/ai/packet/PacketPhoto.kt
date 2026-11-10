package com.example.chatgpt_mealplanner.core.ai.packet

import com.example.chatgpt_mealplanner.contract.AiChatMessage

/**
 * Photos the user took for the assistant to read: a kit's packet, or the pages of a recipe. Claude reads the
 * pictures themselves; the phone doesn't turn them into text first.
 */
data class PacketPhoto(
    /** Each photo as a JPEG in base64, in the order they were taken. */
    val imagesJpegBase64: List<String>
) {
    /** The user's message with the photos attached. */
    fun asUserTurn(userText: String): AiChatMessage {
        val note = buildString {
            append(userText)
            val count = imagesJpegBase64.size
            append("\n\n[")
            append(if (count == 1) "A photo is" else "$count photos are")
            append(" attached for you to read")
            if (count > 1) append(", in the order the user took them")
            append(". They are attached to this message only and are not kept: in later messages you can't see them.]")
        }
        return AiChatMessage(role = "user", content = note, imagesJpegBase64 = imagesJpegBase64)
    }
}
