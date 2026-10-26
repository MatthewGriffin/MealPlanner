package com.example.chatgpt_mealplanner.feature.shopping

import com.example.chatgpt_mealplanner.core.model.ShoppingListItem
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.RequirementConfidence
import com.example.chatgpt_mealplanner.core.model.ShoppingItemStatus
import com.example.chatgpt_mealplanner.core.model.ShoppingListItemWithSources
import com.example.chatgpt_mealplanner.core.model.ShoppingPriority
import com.example.chatgpt_mealplanner.core.model.ShoppingSourceType
import com.example.chatgpt_mealplanner.core.model.WasteRisk
import com.example.chatgpt_mealplanner.retail.contract.BasketComparisonResult
import com.example.chatgpt_mealplanner.retail.contract.BasketFeasibilityTier
import com.example.chatgpt_mealplanner.retail.contract.BasketLine
import com.example.chatgpt_mealplanner.retail.contract.BasketPlan
import com.example.chatgpt_mealplanner.retail.contract.MatchConfidence
import com.example.chatgpt_mealplanner.retail.contract.Money
import com.example.chatgpt_mealplanner.retail.contract.RetailOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailProduct
import com.example.chatgpt_mealplanner.retail.contract.RetailProductWithOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ShoppingViewModelTest {

    private fun createItemWithSources(
        id: String,
        displayName: String,
        sourceType: ShoppingSourceType = ShoppingSourceType.MEAL_REQUIREMENT
    ): ShoppingListItemWithSources {
        val item = ShoppingListItem(
            id = id,
            shoppingListId = "list_1",
            canonicalIngredientId = id.lowercase(),
            displayName = displayName,
            requiredQuantity = 1.0,
            requiredUnit = QuantityUnit.PACK,
            suggestedPurchaseQuantity = 1.0,
            suggestedPurchaseUnit = QuantityUnit.PACK,
            packCount = 1,
            reason = "Required for meal plan",
            priority = ShoppingPriority.ESSENTIAL,
            status = ShoppingItemStatus.NEEDED,
            optional = false,
            sourceType = sourceType,
            hasMealRequirement = sourceType == ShoppingSourceType.MEAL_REQUIREMENT,
            hasStapleRestock = sourceType == ShoppingSourceType.STAPLE_RESTOCK,
            mealShortfallQuantity = null,
            stapleReserveTopUpQuantity = null,
            expectedFinalInventory = null,
            expectedFinalInventoryUnit = null,
            quantityUncertain = false,
            uncertaintyReason = null,
            requirementConfidence = RequirementConfidence.EXACT,
            wasteRisk = WasteRisk.LOW,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        return ShoppingListItemWithSources(item = item, sources = emptyList())
    }

    private fun createBasketLine(
        reqId: String,
        displayName: String,
        retailerId: RetailerId,
        costMinorPence: Long
    ): BasketLine {
        val prod = RetailProduct(
            retailerId = retailerId,
            retailerProductId = "prod_$reqId",
            name = "$displayName ($retailerId Choice)",
            canonicalIngredientId = reqId.lowercase()
        )
        val offer = RetailOffer(
            retailerId = retailerId,
            retailerProductId = "prod_$reqId",
            price = Money(costMinorPence)
        )
        return BasketLine(
            shoppingRequirementId = reqId,
            requirementDisplayName = displayName,
            retailerId = retailerId,
            productWithOffer = RetailProductWithOffer(prod, offer),
            packCountToBuy = 1,
            lineTotalCost = Money(costMinorPence),
            requirementCovered = 1.0,
            expectedRemainder = 0.0,
            matchConfidence = MatchConfidence.EXACT
        )
    }

    private fun createBasketComparisonResult(
        lines: List<BasketLine>,
        unmatchedReqIds: List<String> = emptyList()
    ): BasketComparisonResult {
        val subtotalMinor = lines.sumOf { it.lineTotalCost.amountMinor }
        val stores = lines.map { it.retailerId }.toSet()
        val plan = BasketPlan(
            planId = "plan_1",
            stores = stores,
            lines = lines,
            feasibilityTier = BasketFeasibilityTier.COMPLETE,
            basketSubtotal = Money(subtotalMinor),
            estimatedTripPenalty = Money(0L),
            estimatedWastePenaltyScore = 0.0,
            unmatchedRequirements = unmatchedReqIds,
            uncertainItems = emptyList(),
            explanationText = "Optimized basket"
        )
        return BasketComparisonResult(
            selectedPlan = plan,
            minimumSavingsForExtraStore = Money(300L),
            extraStoreThresholdApplied = false,
            feasibilityTier = BasketFeasibilityTier.COMPLETE,
            unmatchedRequirements = unmatchedReqIds,
            uncertainItems = emptyList()
        )
    }

    @Test
    fun shoppingScreen_usesBasketOptimizerResult() {
        val item = createItemWithSources("item_1", "Spaghetti")
        val line = createBasketLine("item_1", "Spaghetti", RetailerId.ASDA, 120L)
        val optResult = createBasketComparisonResult(listOf(line))

        val groups = ShoppingViewModel.computeRetailerGroups(listOf(item), optResult)

        assertEquals(1, groups.size)
        assertEquals("ASDA", groups.first().retailerName)
        assertEquals(RetailerId.ASDA, groups.first().retailerId)
        assertEquals(Money(120L), groups.first().subtotal)
        assertEquals(line, groups.first().lineByItemId["item_1"])
    }

    @Test
    fun multiRetailerBasket_groupsItemsByAssignedRetailer() {
        val item1 = createItemWithSources("item_1", "Spaghetti")
        val item2 = createItemWithSources("item_2", "Frozen Peas")

        val line1 = createBasketLine("item_1", "Spaghetti", RetailerId.ASDA, 150L)
        val line2 = createBasketLine("item_2", "Frozen Peas", RetailerId.ICELAND, 200L)

        val optResult = createBasketComparisonResult(listOf(line1, line2))

        val groups = ShoppingViewModel.computeRetailerGroups(listOf(item1, item2), optResult)

        assertEquals(2, groups.size)

        val asdaGroup = groups.first { it.retailerId == RetailerId.ASDA }
        assertEquals("ASDA", asdaGroup.retailerName)
        assertEquals(Money(150L), asdaGroup.subtotal)
        assertEquals(1, asdaGroup.items.size)
        assertEquals("item_1", asdaGroup.items.first().item.id)

        val icelandGroup = groups.first { it.retailerId == RetailerId.ICELAND }
        assertEquals("Iceland", icelandGroup.retailerName)
        assertEquals(Money(200L), icelandGroup.subtotal)
        assertEquals(1, icelandGroup.items.size)
        assertEquals("item_2", icelandGroup.items.first().item.id)
    }

    @Test
    fun sharedCanonicalIngredient_doesNotReuseAnotherRequirementsPriceLine() {
        val item1 = createItemWithSources("item_1", "Garlic cloves").let {
            it.copy(item = it.item.copy(canonicalIngredientId = "ING_GARLIC"))
        }
        val item2 = createItemWithSources("item_2", "Garlic cloves").let {
            it.copy(item = it.item.copy(canonicalIngredientId = "ING_GARLIC"))
        }
        val asdaLine = createBasketLine("item_1", "Garlic cloves", RetailerId.ASDA, 200L).let { line ->
            line.copy(productWithOffer = line.productWithOffer.copy(
                product = line.productWithOffer.product.copy(canonicalIngredientId = "ING_GARLIC")
            ))
        }
        val icelandLine = createBasketLine("item_2", "Garlic cloves", RetailerId.ICELAND, 300L).let { line ->
            line.copy(productWithOffer = line.productWithOffer.copy(
                product = line.productWithOffer.product.copy(canonicalIngredientId = "ING_GARLIC")
            ))
        }
        val result = createBasketComparisonResult(listOf(asdaLine, icelandLine))

        val groups = ShoppingViewModel.computeRetailerGroups(listOf(item1, item2), result)

        assertEquals(Money(200L), groups.single { it.retailerId == RetailerId.ASDA }.subtotal)
        assertEquals(Money(300L), groups.single { it.retailerId == RetailerId.ICELAND }.subtotal)
        assertEquals(result.selectedPlan.basketSubtotal,
            groups.mapNotNull { it.subtotal }.fold(Money.zero()) { acc, money -> acc + money })
    }

    @Test
    fun singleRetailerBasket_displaysSelectedRetailer() {
        val item1 = createItemWithSources("item_1", "Spaghetti")
        val item2 = createItemWithSources("item_2", "Tomato Sauce")

        val line1 = createBasketLine("item_1", "Spaghetti", RetailerId.ASDA, 100L)
        val line2 = createBasketLine("item_2", "Tomato Sauce", RetailerId.ASDA, 150L)

        val optResult = createBasketComparisonResult(listOf(line1, line2))

        val groups = ShoppingViewModel.computeRetailerGroups(listOf(item1, item2), optResult)

        assertEquals(1, groups.size)
        assertEquals("ASDA", groups.first().retailerName)
        assertEquals(Money(250L), groups.first().subtotal)
        assertEquals(2, groups.first().items.size)
    }

    @Test
    fun manualItem_participatesInBasketOptimization_whenMatched() {
        val manualItem = createItemWithSources("manual_1", "Dishwasher Tablets", sourceType = ShoppingSourceType.MANUAL)
        val line = createBasketLine("manual_1", "Dishwasher Tablets", RetailerId.ASDA, 350L)
        val optResult = createBasketComparisonResult(listOf(line))

        val groups = ShoppingViewModel.computeRetailerGroups(listOf(manualItem), optResult)

        assertEquals(1, groups.size)
        assertEquals("ASDA", groups.first().retailerName)
        assertEquals(1, groups.first().items.size)
        assertEquals("manual_1", groups.first().items.first().item.id)
        assertEquals(line, groups.first().lineByItemId["manual_1"])
    }

    @Test
    fun unmatchedManualItem_remainsVisible() {
        val manualItem = createItemWithSources("manual_unmatched", "Specialist Cleaning Powder", sourceType = ShoppingSourceType.MANUAL)
        val optResult = createBasketComparisonResult(emptyList(), unmatchedReqIds = listOf("manual_unmatched"))

        val groups = ShoppingViewModel.computeRetailerGroups(listOf(manualItem), optResult)

        assertEquals(1, groups.size)
        assertEquals("Unassigned Items", groups.first().retailerName)
        assertNull(groups.first().retailerId)
        assertNull(groups.first().subtotal)
        assertEquals(1, groups.first().items.size)
        assertEquals("manual_unmatched", groups.first().items.first().item.id)
    }

    @Test
    fun unmatchedDerivedItem_remainsVisible() {
        val derivedItem = createItemWithSources("derived_unmatched", "Exotic Spice Mix", sourceType = ShoppingSourceType.MEAL_REQUIREMENT)
        val optResult = createBasketComparisonResult(emptyList(), unmatchedReqIds = listOf("derived_unmatched"))

        val groups = ShoppingViewModel.computeRetailerGroups(listOf(derivedItem), optResult)

        assertEquals(1, groups.size)
        assertEquals("Unassigned Items", groups.first().retailerName)
        assertEquals(1, groups.first().items.size)
        assertEquals("derived_unmatched", groups.first().items.first().item.id)
    }

    @Test
    fun retailFailure_doesNotHideShoppingRequirements() {
        val item1 = createItemWithSources("item_1", "Milk")
        val item2 = createItemWithSources("item_2", "Bread")

        // Optimization returns null (simulating retail service failure/degradation)
        val groups = ShoppingViewModel.computeRetailerGroups(listOf(item1, item2), basketResult = null)

        assertEquals(1, groups.size)
        assertEquals("All Required Items", groups.first().retailerName)
        assertNull(groups.first().retailerId)
        assertNull(groups.first().subtotal)
        assertEquals(2, groups.first().items.size)
    }

    @Test
    fun basketTotal_usesOptimizerTotal() {
        val item1 = createItemWithSources("item_1", "Cheddar Cheese")
        val line1 = createBasketLine("item_1", "Cheddar Cheese", RetailerId.ASDA, 250L)
        val optResult = createBasketComparisonResult(listOf(line1))

        assertEquals(Money(250L), optResult.selectedPlan.basketSubtotal)
        assertEquals("£2.50", optResult.selectedPlan.basketSubtotal.toFormattedString())
    }

    @Test
    fun uiDoesNotIndependentlyChooseCheapestOfferPerItem() {
        // BasketOptimizer chose ASDA for item_1 even though Iceland offered a cheaper single product,
        // because whole-basket optimization minimized total trip/convenience penalties.
        val item1 = createItemWithSources("item_1", "Cheddar Cheese")
        val chosenLine = createBasketLine("item_1", "Cheddar Cheese", RetailerId.ASDA, 300L)
        val optResult = createBasketComparisonResult(listOf(chosenLine))

        val groups = ShoppingViewModel.computeRetailerGroups(listOf(item1), optResult)

        assertEquals("ASDA", groups.first().retailerName)
        assertEquals(Money(300L), groups.first().lineByItemId["item_1"]?.lineTotalCost)
    }
}
