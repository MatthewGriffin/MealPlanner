package com.example.chatgpt_mealplanner.backend.eval

data class ModelPriceRate(
    val inputCostPerMillionTokens: Double,
    val outputCostPerMillionTokens: Double
)

object ModelPricingConfig {
    private val prices = mapOf(
        "gpt-4o-mini" to ModelPriceRate(0.15, 0.60),
        "gpt-4o" to ModelPriceRate(2.50, 10.00),
        "o3-mini" to ModelPriceRate(1.10, 4.40)
    )

    fun calculateEstimatedCost(modelId: String, inputTokens: Int, outputTokens: Int): Double {
        val rate = prices[modelId.lowercase()] ?: ModelPriceRate(0.15, 0.60)
        val inputCost = (inputTokens / 1_000_000.0) * rate.inputCostPerMillionTokens
        val outputCost = (outputTokens / 1_000_000.0) * rate.outputCostPerMillionTokens
        return inputCost + outputCost
    }
}
