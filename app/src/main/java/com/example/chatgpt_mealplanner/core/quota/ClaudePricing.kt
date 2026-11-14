package com.example.chatgpt_mealplanner.core.quota

import com.example.chatgpt_mealplanner.core.ai.provider.TokenUsage

/** What a Claude reply costs, from its token counts. Update the table when prices or models change. */
object ClaudePricing {
    // Dollars per million tokens: input, output, cache read, cache write. $1 per million tokens = 1 micro-dollar per token.
    private val PRICES = mapOf(
        "claude-haiku-5-5" to listOf(0.10, 0.50, 0.01, 0.125),
        "claude-sonnet-5-5" to listOf(2.0, 10.0, 0.20, 2.50)
    )
    private const val WEB_SEARCH_MICRO_DOLLARS = 10_000L // $10 per 1,000 searches

    /** An unknown model is charged at the dearest known price, so the limit errs on the safe side. */
    fun microDollars(model: String, usage: TokenUsage): Long {
        val price = PRICES[model] ?: PRICES.getValue("claude-sonnet-5-5")
        val tokens = usage.input * price[0] + usage.output * price[1] + usage.cacheRead * price[2] + usage.cacheWrite * price[3]
        return Math.round(tokens) + usage.webSearches * WEB_SEARCH_MICRO_DOLLARS
    }
}
