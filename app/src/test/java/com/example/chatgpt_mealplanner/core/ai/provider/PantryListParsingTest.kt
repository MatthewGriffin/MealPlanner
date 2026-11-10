package com.example.chatgpt_mealplanner.core.ai.provider

import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PantryListParsingTest {

    @Test
    fun readsEveryField() {
        val items = PantryListParsing.parse(
            """{"items":[{"displayName":"Basmati rice","canonicalName":"Basmati Rice","brand":"Tilda","category":"DRY_GOODS",
            "storageLocation":"CUPBOARD","quantity":2,"unit":"KILOGRAM","quantityGuessed":false}]}"""
        )
        val item = items.single()
        assertEquals("Basmati rice", item.displayName)
        assertEquals("basmati rice", item.canonicalName)
        assertEquals("Tilda", item.brand)
        assertEquals(FoodCategory.DRY_GOODS, item.category)
        assertEquals(StorageLocation.CUPBOARD, item.storageLocation)
        assertEquals(2.0, item.quantity, 0.0)
        assertEquals(QuantityUnit.KILOGRAM, item.unit)
        assertEquals(false, item.quantityGuessed)
    }

    @Test
    fun unknownChoicesFallBackToSafeDefaults() {
        val item = PantryListParsing.parse(
            """{"items":[{"displayName":"Mystery","category":"WEIRD","storageLocation":"ATTIC","quantity":3,"unit":"BUCKETS"}]}"""
        ).single()
        assertEquals(FoodCategory.OTHER, item.category)
        assertEquals(StorageLocation.CUPBOARD, item.storageLocation)
        assertEquals(QuantityUnit.UNIT, item.unit)
        assertEquals("mystery", item.canonicalName)
    }

    @Test
    fun missingOrBadQuantityBecomesAGuessOfOne() {
        val items = PantryListParsing.parse(
            """{"items":[{"displayName":"Salt","unit":"GRAM"},{"displayName":"Flour","quantity":-4,"unit":"GRAM"},{"displayName":"Oil","quantity":"lots","unit":"LITRE"}]}"""
        )
        assertEquals(3, items.size)
        items.forEach {
            assertEquals(1.0, it.quantity, 0.0)
            assertEquals(QuantityUnit.UNIT, it.unit)
            assertTrue(it.quantityGuessed)
        }
    }

    @Test
    fun linesWithoutANameAndBrokenJsonAreDropped() {
        assertEquals(1, PantryListParsing.parse("""{"items":[{"quantity":2},{"displayName":" "},{"displayName":"Eggs","quantity":12,"unit":"UNIT"}]}""").size)
        assertTrue(PantryListParsing.parse("not json").isEmpty())
        assertTrue(PantryListParsing.parse("""{"other":[]}""").isEmpty())
    }
}
