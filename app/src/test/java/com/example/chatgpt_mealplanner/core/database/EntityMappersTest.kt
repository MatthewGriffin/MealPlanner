package com.example.chatgpt_mealplanner.core.database

import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemEntity
import com.example.chatgpt_mealplanner.core.database.entity.InventoryTransactionEntity
import com.example.chatgpt_mealplanner.core.database.entity.toDomainModel
import com.example.chatgpt_mealplanner.core.database.entity.toEntity
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.InventoryTransaction
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Test

class EntityMappersTest {

    @Test
    fun inventoryItemEntity_mappingToDomainAndBack_preservesAllFields() {
        val domainItem = InventoryItem(
            id = "item-101",
            barcode = "5000159461122",
            canonicalIngredientId = "ing-cheddar",
            canonicalName = "Cheddar",
            displayName = "Cathedral City Cheddar 350g",
            brand = "Cathedral City",
            category = FoodCategory.FRIDGE,
            storageLocation = StorageLocation.FRIDGE,
            quantity = 350.0,
            quantityUnit = QuantityUnit.GRAM,
            packCount = 1,
            packSize = 350.0,
            packSizeUnit = QuantityUnit.GRAM,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE,
            opened = true,
            frozen = false,
            staple = false,
            purchaseDate = "2026-03-01",
            useByDate = "2026-03-15",
            createdAt = 100000L,
            updatedAt = 100005L
        )

        val entity = domainItem.toEntity()
        val mappedBack = entity.toDomainModel()

        assertEquals(domainItem, mappedBack)
    }

    @Test
    fun inventoryTransactionEntity_mappingToDomainAndBack_preservesAllFields() {
        val transaction = InventoryTransaction(
            id = "tx-202",
            inventoryItemId = "item-101",
            type = TransactionType.COOKED,
            quantityChange = -100.0,
            unit = QuantityUnit.GRAM,
            mealId = "meal-55",
            note = "Used for Mac and Cheese",
            timestamp = 200000L
        )

        val entity = transaction.toEntity()
        val mappedBack = entity.toDomainModel()

        assertEquals(transaction, mappedBack)
    }
}
