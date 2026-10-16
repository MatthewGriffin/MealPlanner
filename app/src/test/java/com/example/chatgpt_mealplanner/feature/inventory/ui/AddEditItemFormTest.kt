package com.example.chatgpt_mealplanner.feature.inventory.ui

import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class AddEditItemFormTest {

    @Test
    fun fromInventoryItem_populatesFormState_andSetsExpansionFlags() {
        val item = InventoryItem(
            id = "item-123",
            canonicalName = "Extra Virgin Olive Oil",
            displayName = "Extra Virgin Olive Oil",
            brand = "Asda",
            barcode = "5012345678900",
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = 2.0,
            quantityUnit = QuantityUnit.LITRE,
            packCount = 2,
            packSize = 1.0,
            packSizeUnit = QuantityUnit.LITRE,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE,
            useByDate = "2027-10-01",
            notes = "Cold pressed"
        )

        val formState = AddEditItemFormState.fromInventoryItem(item)

        assertEquals("item-123", formState.id)
        assertEquals("Extra Virgin Olive Oil", formState.displayName)
        assertEquals("Asda", formState.brand)
        assertEquals("5012345678900", formState.barcode)
        assertEquals("2.0", formState.quantityText)
        assertEquals(QuantityUnit.LITRE, formState.selectedUnit)
        assertEquals("2", formState.packCountText)
        assertEquals("1.0", formState.packSizeText)
        assertEquals(QuantityUnit.LITRE, formState.packSizeUnit)
        assertEquals("2027-10-01", formState.useByDate)
        assertEquals("Cold pressed", formState.notes)

        assertTrue("Packaging section MUST expand if brand/barcode/packCount exist", formState.isPackagingExpanded)
        assertTrue("Dates section MUST expand if useByDate exists", formState.isDatesAndStorageExpanded)
        assertTrue("Notes section MUST expand if notes exist", formState.isNotesExpanded)
    }

    @Test
    fun toInventoryItem_preservesPackageCountAndSize_twoPackScenario() {
        val formState = AddEditItemFormState(
            displayName = "Margherita Pizza",
            brand = "Goodfella's",
            barcode = "5011111111111",
            quantityText = "2.0",
            selectedUnit = QuantityUnit.UNIT,
            category = FoodCategory.FREEZER,
            storageLocation = StorageLocation.FREEZER,
            packCountText = "2",
            packSizeText = "1.0",
            packSizeUnit = QuantityUnit.UNIT,
            isPackagingExpanded = true
        )

        val item = formState.toInventoryItem()

        assertEquals("Margherita Pizza", item.displayName)
        assertEquals("Goodfella's", item.brand)
        assertEquals("5011111111111", item.barcode)
        assertEquals(2.0, item.quantity!!, 0.001)
        assertEquals(QuantityUnit.UNIT, item.quantityUnit)
        assertEquals(2, item.packCount)
        assertEquals(1.0, item.packSize!!, 0.001)
        assertEquals(QuantityUnit.UNIT, item.packSizeUnit)
    }

    @Test
    fun toInventoryItem_blankOptionalFields_mapsToNull() {
        val formState = AddEditItemFormState(
            displayName = "Canned Tomatoes",
            brand = "  ",
            barcode = "",
            quantityText = "3.0",
            selectedUnit = QuantityUnit.PACK,
            purchaseDate = "  ",
            notes = ""
        )

        val item = formState.toInventoryItem()

        assertEquals("Canned Tomatoes", item.displayName)
        assertNull(item.brand)
        assertNull(item.barcode)
        assertEquals(3.0, item.quantity!!, 0.001)
        assertEquals(QuantityUnit.PACK, item.quantityUnit)
        assertNull(item.purchaseDate)
        assertNull(item.notes)
    }
}
