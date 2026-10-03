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
}
