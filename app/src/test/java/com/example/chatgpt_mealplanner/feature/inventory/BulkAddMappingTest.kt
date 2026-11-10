package com.example.chatgpt_mealplanner.feature.inventory

import com.example.chatgpt_mealplanner.core.barcode.BarcodeLookupResult
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BulkAddMappingTest {

    private fun row(quantity: String, unit: QuantityUnit, matched: BarcodeLookupResult? = null, guessed: Boolean = false) = BulkAddRow(
        id = "1", displayName = "Baked beans", canonicalName = "baked beans", brand = null,
        category = FoodCategory.TINS_CANS, storageLocation = StorageLocation.CUPBOARD,
        quantityText = quantity, unit = unit, quantityGuessed = guessed, matched = matched
    )

    private val heinz = BarcodeLookupResult(
        barcode = "5000157024671", displayName = "Baked Beans in Tomato Sauce", brand = "Heinz",
        netQuantity = 415.0, netQuantityUnit = QuantityUnit.GRAM
    )

    @Test
    fun countOfPacksWithAMatchedProductUsesItsPackSize() {
        val input = row("4", QuantityUnit.TIN, heinz).toProductInput()!!
        assertEquals(4, input.packCount)
        assertEquals(415.0, input.packSize!!, 0.0)
        assertEquals(QuantityUnit.GRAM, input.packSizeUnit)
        assertNull(input.netQuantity)
        assertEquals("5000157024671", input.barcode)
        assertEquals("Heinz", input.brand)
        assertEquals(FoodCategory.TINS_CANS, input.category)
    }

    @Test
    fun aWeightTheUserGaveIsKeptEvenWithAMatchedProduct() {
        val input = row("2", QuantityUnit.KILOGRAM, heinz).toProductInput()!!
        assertEquals(2.0, input.netQuantity!!, 0.0)
        assertEquals(QuantityUnit.KILOGRAM, input.netQuantityUnit)
        assertNull(input.packCount)
    }

    @Test
    fun noMatchKeepsTheUsersAmountAndMarksGuesses() {
        val input = row("500", QuantityUnit.GRAM, guessed = true).toProductInput()!!
        assertNull(input.barcode)
        assertEquals(500.0, input.netQuantity!!, 0.0)
        assertEquals(QuantityConfidence.APPROXIMATE, input.quantityConfidence)
    }

    @Test
    fun anUnreadableAmountOrEmptyNameGivesNothing() {
        assertNull(row("lots", QuantityUnit.GRAM).toProductInput())
        assertNull(row("1", QuantityUnit.GRAM).copy(displayName = " ").toProductInput())
    }

    @Test
    fun sizeAndChoiceLabelsReadNaturally() {
        assertEquals("415 gram", heinz.sizeText())
        assertEquals("Baked Beans in Tomato Sauce (Heinz) · 415 gram", heinz.choiceLabel())
        assertNull(BarcodeLookupResult(barcode = "1", displayName = "X").sizeText())
    }
}
