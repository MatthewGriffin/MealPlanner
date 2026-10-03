package com.example.chatgpt_mealplanner.retail.engine

import com.example.chatgpt_mealplanner.retail.contract.BasketDiffEvent
import com.example.chatgpt_mealplanner.retail.contract.BasketDiffEventType
import com.example.chatgpt_mealplanner.retail.contract.BasketPlan
import com.example.chatgpt_mealplanner.retail.contract.BasketPlanDiff

object BasketDiffCalculator {

    fun computeDiff(oldPlan: BasketPlan, newPlan: BasketPlan): BasketPlanDiff {
        val events = mutableListOf<BasketDiffEvent>()

        if (oldPlan.stores != newPlan.stores) {
            events.add(
                BasketDiffEvent(
                    type = BasketDiffEventType.STORE_PLAN_CHANGED,
                    requirementDisplayName = "All Stores",
                    description = "Stores changed from ${oldPlan.stores.joinToString { it.name }} to ${newPlan.stores.joinToString { it.name }}."
                )
            )
        }

        if (oldPlan.feasibilityTier != newPlan.feasibilityTier) {
            events.add(
                BasketDiffEvent(
                    type = BasketDiffEventType.COVERAGE_CHANGED,
                    requirementDisplayName = "Coverage Tier",
                    description = "Basket coverage changed from ${oldPlan.feasibilityTier.name} to ${newPlan.feasibilityTier.name}."
                )
            )
        }

        val oldLineMap = oldPlan.lines.associateBy { it.shoppingRequirementId }
        val newLineMap = newPlan.lines.associateBy { it.shoppingRequirementId }

        oldLineMap.forEach { (reqId, oldLine) ->
            val newLine = newLineMap[reqId]
            if (newLine == null) {
                events.add(
                    BasketDiffEvent(
                        type = BasketDiffEventType.PRODUCT_UNAVAILABLE,
                        requirementDisplayName = oldLine.requirementDisplayName,
                        description = "'${oldLine.productWithOffer.product.name}' is no longer available."
                    )
                )
            } else if (oldLine.productWithOffer.product.retailerProductId != newLine.productWithOffer.product.retailerProductId) {
                events.add(
                    BasketDiffEvent(
                        type = BasketDiffEventType.PRODUCT_REPLACED,
                        requirementDisplayName = oldLine.requirementDisplayName,
                        description = "Replaced '${oldLine.productWithOffer.product.name}' with '${newLine.productWithOffer.product.name}'."
                    )
                )
            } else if (oldLine.lineTotalCost != newLine.lineTotalCost) {
                events.add(
                    BasketDiffEvent(
                        type = BasketDiffEventType.PRICE_CHANGED,
                        requirementDisplayName = oldLine.requirementDisplayName,
                        description = "'${oldLine.productWithOffer.product.name}' changed price from ${oldLine.lineTotalCost.toFormattedString()} to ${newLine.lineTotalCost.toFormattedString()}."
                    )
                )
            }
        }

        val subtotalDiff = newPlan.basketSubtotal - oldPlan.basketSubtotal

        return BasketPlanDiff(
            oldPlanId = oldPlan.planId,
            newPlanId = newPlan.planId,
            events = events,
            subtotalDifference = subtotalDiff
        )
    }
}
