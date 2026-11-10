package com.example.chatgpt_mealplanner.core.ai.orchestrator

/**
 * Whether the user has said they can't supply a kit's packet, or asked for it to be looked up. The
 * assistant asks for the packet first, since the user usually has it and a search costs more and may
 * find the wrong product, so web search is only switched on when the user says so.
 */
object KitLookupIntent {

    fun asksToLookUp(userMessageText: String): Boolean {
        val lower = userMessageText.lowercase()
        return lookUp.containsMatchIn(lower) || noPacket.containsMatchIn(lower)
    }

    private val lookUp = Regex("""\b(?:look (?:it|that|this|them)? ?up|search(?: for)?(?: it| that)?|google|find (?:it|that|them|the instructions) online|check online|on the (?:web|internet)|from the (?:web|internet)|online)\b""")
    private val noPacket = Regex("""\b(?:(?:don't|do not|dont|haven't|have not|havent|no longer) have (?:the |a |that )?(?:packet|packaging|box|sachet|instructions)|no (?:packet|packaging|box|instructions)|(?:lost|threw|thrown|binned|chucked)(?: away| out)? (?:the |that )?(?:packet|packaging|box|instructions)|can't find (?:the |that )?(?:packet|packaging|box|instructions)|cant find (?:the |that )?(?:packet|packaging|box|instructions)|already (?:thrown|binned|recycled))\b""")
}
