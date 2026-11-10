package com.example.chatgpt_mealplanner.core.ai.packet

import com.example.chatgpt_mealplanner.contract.AiChatMessage

/**
 * A kit's packet as the user chose to send it to the assistant: the text their phone read, which they
 * have checked and may have corrected, or the picture itself for Claude to read.
 */
data class PacketPhoto(
    /** The text sent, possibly empty when only the picture is sent. */
    val ocrText: String,
    /** The picture as a JPEG in base64, sent only when the user asked Claude to read it. */
    val imageJpegBase64: String?
) {
    /** The user's message with the packet added. */
    fun asUserTurn(userText: String): AiChatMessage {
        val note = buildString {
            append(userText)
            if (ocrText.isNotBlank()) {
                append("\n\n[Text from the kit's packet. The phone read it from a photo and the user has checked it, so small errors are possible.]\n")
                append(ocrText.trim())
            }
            if (imageJpegBase64 != null) {
                append("\n\n[A photo of the kit's packet is attached for you to read. It is attached to this message only and is not kept: in later messages you can't see it.]")
            }
        }
        return AiChatMessage(role = "user", content = note, imageJpegBase64 = imageJpegBase64)
    }
}

/** What the phone made of a packet photo, for the user to check before anything is sent. */
data class PacketScan(
    val text: String,
    val averageConfidence: Float?,
    val lowestConfidence: Float?,
    /** The photo scaled for Claude, kept so the user can choose to send it instead of the text. Null if it couldn't be prepared. */
    val jpegBase64: String?
) {
    /** Why the text may not be good enough, in words for the user. Empty when it looks complete. */
    val problems: List<String> = PacketTextQuality.problems(text, averageConfidence, lowestConfidence)
    val looksGood: Boolean get() = problems.isEmpty()

    fun textForClaude(checkedText: String) = PacketPhoto(ocrText = checkedText.trim(), imageJpegBase64 = null)
    fun pictureForClaude(): PacketPhoto? = jpegBase64?.let { PacketPhoto(ocrText = "", imageJpegBase64 = it) }
}

/** Whether text read from a packet is enough to work from. */
object PacketTextQuality {

    private val word = Regex("""\p{L}{2,}""")
    private val cue = Regex("""\b(?:add|cook|heat|stir|simmer|fry|mix|bake|boil|pour|serve|minutes?|mins?|water|sachet|tbsp|tsp|ml|g|grams?|oven|pan)\b|°""", RegexOption.IGNORE_CASE)

    /**
     * Reasons the text isn't enough: too few words, no sign of cooking steps or amounts, or a reader that wasn't
     * confident about what it read. A wrong amount from the phone would be worse than none, and garbled text
     * still has plenty of words ("toillas", "To40 g"), so the reader's own confidence counts. Measured on the
     * emulator: clear print averaged 0.84 to 0.88 with no line under 0.72; unreadably small print averaged 0.61
     * with a worst line of 0.47. [averageConfidence] and [lowestConfidence] are null if the reader gave none.
     */
    fun problems(text: String, averageConfidence: Float? = null, lowestConfidence: Float? = null): List<String> {
        if (text.isBlank()) return listOf("The phone couldn't find any text in the photo.")
        val found = mutableListOf<String>()
        val words = word.findAll(text).count()
        if (words < MIN_WORDS) found += "Only $words words were found. A full set of instructions usually has $MIN_WORDS or more."
        if (cue.findAll(text).map { it.value.lowercase() }.toSet().size < MIN_DIFFERENT_COOKING_WORDS) {
            found += "It doesn't look like cooking instructions: few steps, times or amounts were found."
        }
        val unsure = (averageConfidence != null && averageConfidence < MIN_AVERAGE_CONFIDENCE) ||
            (lowestConfidence != null && lowestConfidence < MIN_LOWEST_CONFIDENCE)
        if (unsure) found += "The phone wasn't confident about some of the text, so words or amounts may be wrong."
        return found
    }

    fun isUsable(text: String, averageConfidence: Float? = null, lowestConfidence: Float? = null): Boolean =
        problems(text, averageConfidence, lowestConfidence).isEmpty()

    const val MIN_WORDS = 30
    const val MIN_DIFFERENT_COOKING_WORDS = 3
    const val MIN_AVERAGE_CONFIDENCE = 0.78f
    const val MIN_LOWEST_CONFIDENCE = 0.6f
}
