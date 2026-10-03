package com.example.chatgpt_mealplanner.retail.engine

import com.example.chatgpt_mealplanner.retail.contract.IngredientPurchaseConstraint
import com.example.chatgpt_mealplanner.retail.contract.Money
import com.example.chatgpt_mealplanner.retail.contract.PackAlternative
import com.example.chatgpt_mealplanner.retail.contract.RemainderPenaltyConfig
import com.example.chatgpt_mealplanner.retail.contract.RetailProductWithOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailStorageClass

class PackSelectionEngine(
    private val penaltyConfig: RemainderPenaltyConfig = RemainderPenaltyConfig()
) {

    fun generatePackAlternatives(
        requiredQuantity: Double,
        requiredUnit: String,
        candidateMatches: List<RetailProductWithOffer>,
        constraint: IngredientPurchaseConstraint = IngredientPurchaseConstraint.ANY,
        enableLoyalty: Boolean = false
    ): List<PackAlternative> {
        if (requiredQuantity <= 0.0 || candidateMatches.isEmpty()) return emptyList()

        val validCandidates = candidateMatches.filter { candidate ->
            val prod = candidate.product
            when (constraint) {
                IngredientPurchaseConstraint.FRESH_REQUIRED -> prod.storageClass in listOf(RetailStorageClass.FRESH, RetailStorageClass.CHILLED)
                IngredientPurchaseConstraint.FROZEN_ALLOWED -> true
                IngredientPurchaseConstraint.CHILLED_REQUIRED -> prod.storageClass == RetailStorageClass.CHILLED
                IngredientPurchaseConstraint.ANY -> true
            }
        }.take(5) // Max 5 candidate products cap

        val alternatives = mutableListOf<PackAlternative>()

        validCandidates.forEach { candidate ->
            val product = candidate.product
            val packQty = product.packSize ?: product.netQuantity ?: 1.0

            val isCountUnit = requiredUnit.equals("UNIT", ignoreCase = true) ||
                    requiredUnit.equals("COUNT", ignoreCase = true) ||
                    requiredUnit.equals("ITEM", ignoreCase = true) ||
                    requiredUnit.equals("PACK", ignoreCase = true) ||
                    requiredUnit.equals("TIN", ignoreCase = true) ||
                    requiredUnit.equals("CAN", ignoreCase = true) ||
                    requiredUnit.equals("PIECE", ignoreCase = true) ||
                    requiredUnit.equals("EACH", ignoreCase = true)

            val countToBuy = if (isCountUnit) {
                val innerCount = product.packCount ?: (product.packSize?.toInt() ?: 1)
                Math.ceil(requiredQuantity / innerCount.toDouble()).toInt().coerceAtLeast(1)
            } else {
                Math.ceil(requiredQuantity / packQty).toInt().coerceAtLeast(1)
            }

            val totalQtyPurchased = if (isCountUnit) {
                val innerCount = product.packCount ?: (product.packSize?.toInt() ?: 1)
                (countToBuy * innerCount).toDouble()
            } else {
                countToBuy * packQty
            }

            val covered = minOf(requiredQuantity, totalQtyPurchased)
            val remainder = maxOf(0.0, totalQtyPurchased - requiredQuantity)

            val wastePenaltyRate = when (product.storageClass) {
                RetailStorageClass.FROZEN -> penaltyConfig.frozenPenaltyPerBaseUnit
                RetailStorageClass.AMBIENT -> penaltyConfig.ambientPenaltyPerBaseUnit
                RetailStorageClass.CHILLED -> penaltyConfig.chilledPenaltyPerBaseUnit
                RetailStorageClass.FRESH -> penaltyConfig.freshPenaltyPerBaseUnit
                RetailStorageClass.UNKNOWN -> penaltyConfig.freshPenaltyPerBaseUnit
            }

            val wasteScore = remainder * wastePenaltyRate
            val totalCost = candidate.offer.getEffectivePrice(countToBuy, enableLoyalty)

            alternatives.add(
                PackAlternative(
                    productWithOffer = candidate,
                    packCountToBuy = countToBuy,
                    totalCost = totalCost,
                    totalQuantityPurchased = totalQtyPurchased,
                    unit = requiredUnit,
                    requirementCovered = covered,
                    expectedRemainder = remainder,
                    wastePenaltyScore = wasteScore
                )
            )
        }

        return alternatives.sortedBy { it.totalCost }
    }
}
