package com.example.chatgpt_mealplanner.core.util

import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class CookAmountsTest {
    private fun item(unit: QuantityUnit?, size: Double? = null, sizeUnit: QuantityUnit? = null) = InventoryItem(
        canonicalName = "meatballs", displayName = "Meatballs", category = com.example.chatgpt_mealplanner.core.model.FoodCategory.FRIDGE,
        storageLocation = com.example.chatgpt_mealplanner.core.model.StorageLocation.FRIDGE, quantity = 1.0, quantityUnit = unit, packSize = size, packSizeUnit = sizeUnit
    )

    @Test fun aCleanConversionKeepsAnAmountInThePantrysUnit() {
        assertEquals(CookAmount(0.5, QuantityUnit.KILOGRAM), CookAmounts.inPantryUnit(500.0, QuantityUnit.GRAM, item(QuantityUnit.KILOGRAM)))
        assertEquals(CookAmount(1.0, QuantityUnit.PACK), CookAmounts.inPantryUnit(1.0, QuantityUnit.UNIT, item(QuantityUnit.PACK)))
        assertEquals(CookAmount(0.333, QuantityUnit.PACK), CookAmounts.inPantryUnit(200.0, QuantityUnit.GRAM, item(QuantityUnit.PACK, 600.0, QuantityUnit.GRAM)))
    }

    @Test fun noCleanConversionClearsTheAmount() {
        assertEquals(CookAmount(null, QuantityUnit.GRAM), CookAmounts.inPantryUnit(1.0, QuantityUnit.UNIT, item(QuantityUnit.GRAM)))
        assertEquals(CookAmount(null, QuantityUnit.PACK), CookAmounts.inPantryUnit(400.0, QuantityUnit.GRAM, item(QuantityUnit.PACK)))
    }

    @Test fun anItemWithoutAUnitLeavesTheRecipeAmount() {
        assertEquals(CookAmount(2.0, QuantityUnit.UNIT), CookAmounts.inPantryUnit(2.0, QuantityUnit.UNIT, item(null)))
    }
}
