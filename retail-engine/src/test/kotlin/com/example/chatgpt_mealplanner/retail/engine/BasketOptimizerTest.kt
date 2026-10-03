package com.example.chatgpt_mealplanner.retail.engine

import com.example.chatgpt_mealplanner.retail.contract.BasketFeasibilityTier
import com.example.chatgpt_mealplanner.retail.contract.Money
import com.example.chatgpt_mealplanner.retail.contract.RetailAvailability
import com.example.chatgpt_mealplanner.retail.contract.RetailOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailProduct
import com.example.chatgpt_mealplanner.retail.contract.RetailProductWithOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailStorageClass
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import com.example.chatgpt_mealplanner.retail.contract.UserShoppingPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BasketOptimizerTest {

    @Test
    fun optimizer_selectsSingleStorePlan_whenSplitSavingIsBelowMinimumThreshold() {
        val matcher = RetailProductMatcher()
        val optimizer = BasketOptimizer(matcher)

        val requirements = listOf(
            ShoppingRequirementSpec("r1", "CHICKEN", "Chicken Breast", 400.0, "GRAM", "ESSENTIAL"),
            ShoppingRequirementSpec("r2", "BROCCOLI", "Broccoli", 500.0, "GRAM", "ESSENTIAL")
        )

        // Asda products: Chicken £3.80 + Broccoli £1.20 = £5.00
        // Iceland products: Chicken £3.50 + Broccoli £1.80 = £5.30
        // Split (Iceland Chicken + Asda Broccoli) = £3.50 + £1.20 = £4.70
        // Raw savings over Asda single-store = £5.00 - £4.70 = £0.30
        // Minimum threshold = £3.00 (300L pence) -> Single store MUST WIN!

        val testFixtures = listOf(
            // Asda
            RetailProductWithOffer(
                product = RetailProduct(RetailerId.ASDA, "asda_chick", "Chicken Breast", canonicalIngredientId = "CHICKEN", packSize = 400.0, packSizeUnit = "GRAM", storageClass = RetailStorageClass.FRESH),
                offer = RetailOffer(RetailerId.ASDA, "asda_chick", Money(380L), availability = RetailAvailability.IN_STOCK)
            ),
            RetailProductWithOffer(
                product = RetailProduct(RetailerId.ASDA, "asda_broc", "Broccoli", canonicalIngredientId = "BROCCOLI", packSize = 500.0, packSizeUnit = "GRAM", storageClass = RetailStorageClass.FROZEN),
                offer = RetailOffer(RetailerId.ASDA, "asda_broc", Money(120L), availability = RetailAvailability.IN_STOCK)
            ),
            // Iceland
            RetailProductWithOffer(
                product = RetailProduct(RetailerId.ICELAND, "ice_chick", "Chicken Breast", canonicalIngredientId = "CHICKEN", packSize = 400.0, packSizeUnit = "GRAM", storageClass = RetailStorageClass.FRESH),
                offer = RetailOffer(RetailerId.ICELAND, "ice_chick", Money(350L), availability = RetailAvailability.IN_STOCK)
            ),
            RetailProductWithOffer(
                product = RetailProduct(RetailerId.ICELAND, "ice_broc", "Broccoli", canonicalIngredientId = "BROCCOLI", packSize = 500.0, packSizeUnit = "GRAM", storageClass = RetailStorageClass.FROZEN),
                offer = RetailOffer(RetailerId.ICELAND, "ice_broc", Money(180L), availability = RetailAvailability.IN_STOCK)
            )
        )

        val prefs = UserShoppingPreferences(
            avoidExtraStoreTrips = true,
            minimumSavingsForExtraStore = Money(300L) // £3.00
        )

        val comparison = optimizer.optimizeBasket(requirements, testFixtures, prefs)

        assertEquals(BasketFeasibilityTier.COMPLETE, comparison.feasibilityTier)
        assertTrue(comparison.extraStoreThresholdApplied)
        assertEquals(Money(30L), comparison.rawMultiStoreSaving) // £0.30 raw split saving
        assertEquals(1, comparison.selectedPlan.stores.size) // Single store won!
        assertEquals(RetailerId.ASDA, comparison.selectedPlan.stores.first())
        assertTrue(comparison.selectedPlan.explanationText.contains("below your £3.00 extra-store minimum"))
    }

    @Test
    fun optimizer_doesNotSelectCheaperFlavouredVariant_overPlainRequirement() {
        val matcher = RetailProductMatcher()
        val optimizer = BasketOptimizer(matcher)

        val requirements = listOf(
            ShoppingRequirementSpec("r_tom", null, "Tinned Tomatoes", 400.0, "GRAM", "ESSENTIAL")
        )

        // Flavoured variant is cheaper (£0.80 = 80L) but unrequested -> NOT auto-eligible
        // Plain variant is pricier (£1.00 = 100L) but compatible -> AUTO-ELIGIBLE and wins!

        val testFixtures = listOf(
            RetailProductWithOffer(
                product = RetailProduct(RetailerId.ASDA, "flav_tom", "Chopped Tomatoes with Chilli and Peppers 400g", packSize = 400.0, packSizeUnit = "GRAM", storageClass = RetailStorageClass.AMBIENT),
                offer = RetailOffer(RetailerId.ASDA, "flav_tom", Money(80L), availability = RetailAvailability.IN_STOCK)
            ),
            RetailProductWithOffer(
                product = RetailProduct(RetailerId.ASDA, "plain_tom", "Chopped Tomatoes 400g", packSize = 400.0, packSizeUnit = "GRAM", storageClass = RetailStorageClass.AMBIENT),
                offer = RetailOffer(RetailerId.ASDA, "plain_tom", Money(100L), availability = RetailAvailability.IN_STOCK)
            )
        )

        val comparison = optimizer.optimizeBasket(requirements, testFixtures, UserShoppingPreferences())

        assertEquals(BasketFeasibilityTier.COMPLETE, comparison.feasibilityTier)
        assertEquals(1, comparison.selectedPlan.lines.size)
        assertEquals("Chopped Tomatoes 400g", comparison.selectedPlan.lines.first().productWithOffer.product.name)
        assertEquals(Money(100L), comparison.selectedPlan.basketSubtotal)
    }
}
