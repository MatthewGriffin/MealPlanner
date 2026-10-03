package com.example.chatgpt_mealplanner.retail.engine

import com.example.chatgpt_mealplanner.retail.contract.BasketDiffEventType
import com.example.chatgpt_mealplanner.retail.contract.BasketFeasibilityTier
import com.example.chatgpt_mealplanner.retail.contract.BasketLine
import com.example.chatgpt_mealplanner.retail.contract.BasketPlan
import com.example.chatgpt_mealplanner.retail.contract.MatchConfidence
import com.example.chatgpt_mealplanner.retail.contract.Money
import com.example.chatgpt_mealplanner.retail.contract.RetailAvailability
import com.example.chatgpt_mealplanner.retail.contract.RetailOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailProduct
import com.example.chatgpt_mealplanner.retail.contract.RetailProductWithOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailStorageClass
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BasketDiffCalculatorTest {

    @Test
    fun computeDiff_detectsPriceChangeAndStorePlanChange_fromImmutablePlans() {
        val prod1 = RetailProduct(RetailerId.ASDA, "p1", "Asda Chicken", storageClass = RetailStorageClass.FRESH)
        val offer1Old = RetailOffer(RetailerId.ASDA, "p1", Money(400L), availability = RetailAvailability.IN_STOCK)
        val offer1New = RetailOffer(RetailerId.ASDA, "p1", Money(350L), availability = RetailAvailability.IN_STOCK)

        val lineOld = BasketLine("req1", "Chicken", RetailerId.ASDA, RetailProductWithOffer(prod1, offer1Old), 1, Money(400L), 400.0, 0.0, MatchConfidence.EXACT)
        val lineNew = BasketLine("req1", "Chicken", RetailerId.ASDA, RetailProductWithOffer(prod1, offer1New), 1, Money(350L), 400.0, 0.0, MatchConfidence.EXACT)

        val planOld = BasketPlan("plan1", setOf(RetailerId.ASDA), listOf(lineOld), BasketFeasibilityTier.COMPLETE, Money(400L), Money.zero(), 0.0, emptyList(), emptyList(), "Asda plan")
        val planNew = BasketPlan("plan2", setOf(RetailerId.ASDA), listOf(lineNew), BasketFeasibilityTier.COMPLETE, Money(350L), Money.zero(), 0.0, emptyList(), emptyList(), "Asda plan new")

        val diff = BasketDiffCalculator.computeDiff(planOld, planNew)

        assertEquals("plan1", diff.oldPlanId)
        assertEquals("plan2", diff.newPlanId)
        assertEquals(Money(-50L), diff.subtotalDifference) // -£0.50
        assertTrue(diff.events.any { it.type == BasketDiffEventType.PRICE_CHANGED })
    }
}
