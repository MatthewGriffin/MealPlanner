package com.example.chatgpt_mealplanner.retail.engine

import com.example.chatgpt_mealplanner.retail.contract.Money
import com.example.chatgpt_mealplanner.retail.contract.RetailAvailability
import com.example.chatgpt_mealplanner.retail.contract.RetailOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailProduct
import com.example.chatgpt_mealplanner.retail.contract.RetailProductWithOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailStorageClass
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import kotlin.test.Test
import kotlin.test.assertEquals

class PackSelectionEngineTest {

    @Test
    fun generatesPackAlternatives_calculatesCorrectPacksAndRemainder() {
        val engine = PackSelectionEngine()

        val candidate = RetailProductWithOffer(
            product = RetailProduct(
                retailerId = RetailerId.ASDA,
                retailerProductId = "broc_500g",
                name = "Asda Frozen Broccoli 500g",
                canonicalIngredientId = "BROCCOLI",
                packSize = 500.0,
                packSizeUnit = "GRAM",
                storageClass = RetailStorageClass.FROZEN
            ),
            offer = RetailOffer(
                retailerId = RetailerId.ASDA,
                retailerProductId = "broc_500g",
                price = Money(120L), // £1.20
                availability = RetailAvailability.IN_STOCK
            )
        )

        val alts = engine.generatePackAlternatives(
            requiredQuantity = 550.0,
            requiredUnit = "GRAM",
            candidateMatches = listOf(candidate)
        )

        assertEquals(1, alts.size)
        val alt = alts.first()
        assertEquals(2, alt.packCountToBuy) // 2 x 500g = 1000g >= 550g
        assertEquals(Money(240L), alt.totalCost) // £2.40
        assertEquals(450.0, alt.expectedRemainder, 0.001) // 1000g - 550g = 450g
    }
}
