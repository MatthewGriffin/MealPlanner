package com.example.chatgpt_mealplanner.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InventoryModelTest {

    @Test
    fun unknownQuantityConfidence_preservesNullQuantity() {
        val item = InventoryItem(
            canonicalName = "Garlic Powder",
            displayName = "Garlic Powder",
            category = FoodCategory.SAUCES_SEASONINGS,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = null,
            quantityConfidence = QuantityConfidence.UNKNOWN,
            status = ItemStatus.AVAILABLE
        )

        assertNull("Quantity must remain null when confidence is UNKNOWN", item.quantity)
        assertEquals(QuantityConfidence.UNKNOWN, item.quantityConfidence)
        assertEquals(ItemStatus.AVAILABLE, item.status)
    }

    @Test
    fun transactionCreation_linksToInventoryItemIdAndType() {
        val itemId = "test-item-123"
        val transaction = InventoryTransaction(
            inventoryItemId = itemId,
            type = TransactionType.PURCHASE,
            quantityChange = 400.0,
            unit = QuantityUnit.GRAM,
            note = "Bought at ASDA"
        )

        assertEquals(itemId, transaction.inventoryItemId)
        assertEquals(TransactionType.PURCHASE, transaction.type)
        assertEquals(400.0, transaction.quantityChange!!, 0.001)
    }
}
