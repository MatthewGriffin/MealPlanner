package com.example.chatgpt_mealplanner.core.ai.packet

import com.example.chatgpt_mealplanner.contract.AiChatMessage

/**
 * A photo of the back of a kit's packet. The phone reads the text itself first. The picture is only
 * sent to Claude when that text wasn't good enough, because it costs more and the text alone is usually enough.
 */
data class PacketPhoto(
    /** What the phone's text reader found, possibly empty or partial. */
    val ocrText: String,
    /** The picture as a JPEG in base64, present only when [ocrText] wasn't usable on its own. */
    val imageJpegBase64: String?
) {
    /** The user's message with the packet's text added, and the picture attached when the text fell short. */
    fun asUserTurn(userText: String): AiChatMessage {
        val note = buildString {
            append(userText)
            if (ocrText.isNotBlank()) {
                append("\n\n[Text read from the photo of the packet, on the phone. It may have small errors.]\n")
                append(ocrText.trim())
            }
            if (imageJpegBase64 != null) {
                append("\n\n[A photo of the packet is attached. The text the phone could read from it was incomplete, so read the image itself.]")
            }
        }
        return AiChatMessage(role = "user", content = note, imageJpegBase64 = imageJpegBase64)
    }
}

/** Whether text read from a packet is enough to work from, or the picture should go to Claude too. */
object PacketTextQuality {

    private val word = Regex("""\p{L}{2,}""")
    private val cue = Regex("""\b(?:add|cook|heat|stir|simmer|fry|mix|bake|boil|pour|serve|minutes?|mins?|water|sachet|tbsp|tsp|ml|g|grams?|oven|pan)\b|°""", RegexOption.IGNORE_CASE)

    /**
     * Enough words, several cooking words or amounts, and a reader that was confident about what it read.
     * A packet front with only a brand name, a blurred photo that gives a few scraps, or text that is
     * garbled ("toillas", "To40 g") all fail and go to Claude as a picture, because a wrong amount from
     * the phone would be worse than no text. Measured on the emulator: clear text scored 0.84 to 0.88 on
     * average with no line under 0.72; unreadably small text scored 0.61 with a worst line of 0.47.
     * [averageConfidence] and [lowestConfidence] are the text reader's own scores, null if it gave none.
     */
    fun isUsable(text: String, averageConfidence: Float? = null, lowestConfidence: Float? = null): Boolean {
        val words = word.findAll(text).count()
        if (words < MIN_WORDS) return false
        val cues = cue.findAll(text).map { it.value.lowercase() }.toSet().size
        if (cues < MIN_DIFFERENT_COOKING_WORDS) return false
        if (averageConfidence != null && averageConfidence < MIN_AVERAGE_CONFIDENCE) return false
        if (lowestConfidence != null && lowestConfidence < MIN_LOWEST_CONFIDENCE) return false
        return true
    }

    const val MIN_WORDS = 30
    const val MIN_DIFFERENT_COOKING_WORDS = 3
    const val MIN_AVERAGE_CONFIDENCE = 0.78f
    const val MIN_LOWEST_CONFIDENCE = 0.6f
}
