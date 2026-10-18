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

    private fun outItem() = InventoryItem(
        id = "out-item", canonicalName = "Milk", displayName = "Milk",
        category = FoodCategory.FRIDGE, storageLocation = StorageLocation.FRIDGE,
        quantity = 0.0, quantityUnit = QuantityUnit.LITRE, status = ItemStatus.OUT
    )

    @Test
    fun enteringQuantityForUnknownOutItem_setsExactQuantityAndClearsOut() {
        val original = outItem().copy(quantity = null, quantityUnit = null,
            quantityConfidence = QuantityConfidence.UNKNOWN)
        val edited = AddEditItemFormState.fromInventoryItem(original)
            .copy(quantityText = "1", selectedUnit = QuantityUnit.LITRE).toInventoryItem()
        assertEquals(1.0, edited.quantity!!, 0.0)
        assertEquals(QuantityUnit.LITRE, edited.quantityUnit)
        assertEquals(QuantityConfidence.EXACT, edited.quantityConfidence)
        assertEquals(ItemStatus.AVAILABLE, edited.status)
    }

    @Test
    fun editingMetadata_preservesIdentityHistoryAndQuantityConfidence() {
        val original = outItem().copy(canonicalIngredientId = "ingredient-milk", canonicalName = "milk",
            createdAt = 123L, updatedAt = 456L, revision = 9L,
            quantity = 2.0, quantityConfidence = QuantityConfidence.APPROXIMATE,
            status = ItemStatus.LOW)
        val edited = AddEditItemFormState.fromInventoryItem(original)
            .copy(displayName = "My milk", notes = "New note").toInventoryItem()
        assertEquals(original.canonicalIngredientId, edited.canonicalIngredientId)
        assertEquals(original.canonicalName, edited.canonicalName)
        assertEquals(original.createdAt, edited.createdAt)
        assertEquals(original.updatedAt, edited.updatedAt)
        assertEquals(original.revision, edited.revision)
        assertEquals(original.quantityConfidence, edited.quantityConfidence)
        assertEquals("My milk", edited.displayName)
        assertEquals("New note", edited.notes)
    }

    @Test
    fun editingMetadata_preservesUnknownQuantityAndNullIngredientIdentity() {
        val original = outItem().copy(quantity = null, quantityUnit = null,
            quantityConfidence = QuantityConfidence.UNKNOWN, canonicalIngredientId = null)
        val edited = AddEditItemFormState.fromInventoryItem(original).copy(notes = "Check later").toInventoryItem()
        assertNull(edited.quantity)
        assertNull(edited.quantityUnit)
        assertNull(edited.canonicalIngredientId)
        assertEquals(QuantityConfidence.UNKNOWN, edited.quantityConfidence)
        assertEquals(ItemStatus.OUT, edited.status)
    }

    @Test
    fun editingOutItemMetadata_preservesOutMarkAndZeroQuantity() {
        val edited = AddEditItemFormState.fromInventoryItem(outItem())
            .copy(displayName = "Whole milk", notes = "Buy again").toInventoryItem()
        assertEquals(ItemStatus.OUT, edited.status)
        assertEquals(0.0, edited.quantity!!, 0.0)
        assertEquals("Whole milk", edited.displayName)
        assertEquals("Buy again", edited.notes)
    }

    @Test
    fun editingOutItem_onlyPositiveQuantityClearsOutMark() {
        val form = AddEditItemFormState.fromInventoryItem(outItem())
        for (quantity in listOf("0", "-1", "", "invalid", "NaN", "Infinity")) {
            assertEquals("quantity=$quantity", ItemStatus.OUT, form.copy(quantityText = quantity).toInventoryItem().status)
        }
        for (quantity in listOf("0.5", "1", "1.01", "2")) {
            assertEquals("quantity=$quantity", ItemStatus.AVAILABLE, form.copy(quantityText = quantity).toInventoryItem().status)
        }
    }

    @Test
    fun editingMetadata_preservesOtherItemStatuses() {
        for (status in listOf(ItemStatus.LOW, ItemStatus.USE_SOON, ItemStatus.DISCARDED)) {
            val item = outItem().copy(status = status, quantity = 2.0)
            assertEquals(status, AddEditItemFormState.fromInventoryItem(item)
                .copy(notes = "Updated note").toInventoryItem().status)
        }
    }

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

    @Test
    fun applyProductTemplate_prepopulatesFields_andPreservesUserQuantity() {
        val initialForm = AddEditItemFormState(
            displayName = "Monst",
            quantityText = "4.0",
            selectedUnit = QuantityUnit.PACK
        )

        val updatedForm = initialForm.applyProductTemplate(
            displayName = "Monster Energy 500ml",
            brand = "Monster",
            barcode = "5012345678900",
            category = FoodCategory.SNACKS,
            storageLocation = StorageLocation.FRIDGE,
            netQuantityUnit = QuantityUnit.MILLILITRE
        )

        assertEquals("Monster Energy 500ml", updatedForm.displayName)
        assertEquals("Monster", updatedForm.brand)
        assertEquals("5012345678900", updatedForm.barcode)
        assertEquals(FoodCategory.SNACKS, updatedForm.category)
        assertEquals(StorageLocation.FRIDGE, updatedForm.storageLocation)
        // VERIFY: User-entered quantity 4.0 is PRESERVED!
        assertEquals("4.0", updatedForm.quantityText)
        assertTrue("Packaging section MUST be expanded", updatedForm.isPackagingExpanded)
    }
}
