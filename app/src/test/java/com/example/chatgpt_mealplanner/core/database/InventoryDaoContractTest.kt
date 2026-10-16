package com.example.chatgpt_mealplanner.core.database

import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemEntity
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import org.junit.Assert.assertEquals
import org.junit.Test

class InventoryDaoContractTest {

    @Test
    fun entityFields_matchDatabaseSchemaRequirements() {
        val entity = InventoryItemEntity(
            id = "test-1",
            canonicalName = "Eggs",
            displayName = "6 Free Range Eggs",
            category = FoodCategory.FRIDGE,
            storageLocation = StorageLocation.FRIDGE,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE,
            frozen = false,
            staple = true,
            createdAt = 100L,
            updatedAt = 100L
        )

        assertEquals("test-1", entity.id)
        assertEquals("Eggs", entity.canonicalName)
    }

    @Test
    fun entityFields_twoPackScenario_preservesQuantityAndPackaging() {
        val entity = InventoryItemEntity(
            id = "test-pizza-2pack",
            canonicalName = "Margherita Pizza",
            displayName = "Margherita Pizza",
            brand = "Goodfella's",
            barcode = "5011111111111",
            category = FoodCategory.FREEZER,
            storageLocation = StorageLocation.FREEZER,
            quantity = 2.0,
            quantityUnit = com.example.chatgpt_mealplanner.core.model.QuantityUnit.PACK,
            packCount = 2,
            packSize = 1.0,
            packSizeUnit = com.example.chatgpt_mealplanner.core.model.QuantityUnit.UNIT,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE,
            frozen = true,
            staple = false,
            createdAt = 100L,
            updatedAt = 100L
        )

        assertEquals("test-pizza-2pack", entity.id)
        assertEquals(2.0, entity.quantity!!, 0.001)
        assertEquals(com.example.chatgpt_mealplanner.core.model.QuantityUnit.PACK, entity.quantityUnit)
        assertEquals(2, entity.packCount)
        assertEquals(1.0, entity.packSize!!, 0.001)
        assertEquals(com.example.chatgpt_mealplanner.core.model.QuantityUnit.UNIT, entity.packSizeUnit)
    }
}
