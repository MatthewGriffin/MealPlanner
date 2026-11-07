package com.example.chatgpt_mealplanner.feature

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatgpt_mealplanner.core.barcode.model.ConfirmedProductInput
import com.example.chatgpt_mealplanner.core.barcode.model.ProductMetadata
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.feature.inventory.ui.CorrectionDialog
import com.example.chatgpt_mealplanner.feature.scanner.ui.ProductConfirmationScreen
import com.example.chatgpt_mealplanner.feature.spend.ReceiptItemCategoryDropdown
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SelectionDropdownsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun open(label: String) {
        composeRule.onNodeWithText(label).performClick()
        composeRule.waitForIdle()
    }

    /** An option in the open menu, not the field behind it, which can show the same text. */
    private fun choose(text: String) {
        composeRule.onNode(hasText(text) and hasAnyAncestor(isPopup())).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun correctionDialog_choosesTheUnitFromADropdown() {
        var confirmedUnit: QuantityUnit? = null
        composeRule.setContent {
            CorrectionDialog(
                item = InventoryItem(
                    canonicalName = "Rice",
                    displayName = "Rice",
                    category = FoodCategory.DRY_GOODS,
                    storageLocation = StorageLocation.CUPBOARD,
                    quantity = 500.0,
                    quantityUnit = QuantityUnit.GRAM
                ),
                onDismiss = {},
                onConfirm = { _, unit, _, _ -> confirmedUnit = unit }
            )
        }
        composeRule.onNodeWithText(QuantityUnit.UNIT.name).assertDoesNotExist()

        open("Unit")
        choose(QuantityUnit.UNIT.name)
        composeRule.onNodeWithText("Confirm").performClick()

        composeRule.waitForIdle()
        assertEquals(QuantityUnit.UNIT, confirmedUnit)
    }

    @Test
    fun productConfirmation_choosesLocationAndCategoryFromDropdowns() {
        var confirmed: ConfirmedProductInput? = null
        composeRule.setContent {
            ProductConfirmationScreen(
                barcode = "123",
                metadata = ProductMetadata(
                    barcode = "123",
                    displayName = "Milk",
                    category = FoodCategory.DRY_GOODS,
                    suggestedStorageLocation = StorageLocation.CUPBOARD,
                    netQuantity = 1000.0,
                    netQuantityUnit = QuantityUnit.GRAM
                ),
                onBack = {},
                onConfirm = { confirmed = it }
            )
        }
        composeRule.onNodeWithText(StorageLocation.FREEZER.displayName).assertDoesNotExist()
        composeRule.onNodeWithText(FoodCategory.DAIRY_EGGS.displayName).assertDoesNotExist()

        composeRule.onNodeWithText("Storage Location").performScrollTo()
        open("Storage Location")
        choose(StorageLocation.FREEZER.displayName)
        composeRule.onNodeWithText("Category").performScrollTo()
        open("Category")
        choose(FoodCategory.DAIRY_EGGS.displayName)
        composeRule.onNodeWithText("Add to Inventory").performScrollTo().performClick()

        composeRule.waitForIdle()
        assertEquals(StorageLocation.FREEZER, confirmed?.storageLocation)
        assertEquals(FoodCategory.DAIRY_EGGS, confirmed?.category)
    }

    @Test
    fun receiptItem_savesTheCategoryNameAndUnderstandsOlderValues() {
        var category by mutableStateOf("Household & Cleaning")
        composeRule.setContent {
            Column {
                ReceiptItemCategoryDropdown(category = category, onCategorySelected = { category = it })
            }
        }
        composeRule.onNodeWithText("Household & Cleaning").assertExists()
        composeRule.onNodeWithText("Pet").assertDoesNotExist()

        open("Category")
        choose("Pet")

        assertEquals("PET", category)
        composeRule.onNodeWithText("Pet").assertExists()
    }
}
