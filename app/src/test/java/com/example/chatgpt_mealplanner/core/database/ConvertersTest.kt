package com.example.chatgpt_mealplanner.core.database

import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConvertersTest {

    private val converters = Converters()

    @Test
    fun foodCategoryConverters_roundTripLegacyValuesAndFallback() {
        assertEquals("DAIRY_EGGS", converters.fromFoodCategory(FoodCategory.DAIRY_EGGS))
        assertEquals(FoodCategory.DAIRY_EGGS, converters.toFoodCategory("DAIRY_EGGS"))
        assertEquals(FoodCategory.BAKERY, converters.toFoodCategory("BREAD_BAKERY"))
        assertEquals(FoodCategory.OTHER, converters.toFoodCategory("CUPBOARD"))
        assertEquals(FoodCategory.OTHER, converters.toFoodCategory("INVALID_CATEGORY"))
        assertNull(converters.fromFoodCategory(null))
    }

    @Test
    fun storageLocationConverters_roundTripAndFallback() {
        assertEquals("FRIDGE", converters.fromStorageLocation(StorageLocation.FRIDGE))
        assertEquals(StorageLocation.FRIDGE, converters.toStorageLocation("FRIDGE"))
        assertEquals(StorageLocation.PANTRY, converters.toStorageLocation("FRESH_PANTRY"))
        assertEquals(StorageLocation.CUPBOARD, converters.toStorageLocation("SNACKS"))
        assertEquals(StorageLocation.OTHER, converters.toStorageLocation("UNKNOWN_LOC"))
    }

    @Test
    fun categoryAndStorageLocationEnumsHaveDistinctResponsibilities() {
        val categoryNames = FoodCategory.entries.map { it.name }.toSet()
        val locationNames = StorageLocation.entries.map { it.name }.toSet()

        assertEquals(setOf("OTHER"), categoryNames.intersect(locationNames))
        assertEquals(
            setOf("CUPBOARD", "FRIDGE", "FREEZER", "PANTRY", "OTHER"),
            locationNames
        )
    }

    @Test
    fun quantityConfidenceConverters_roundTripAndFallback() {
        assertEquals("EXACT", converters.fromQuantityConfidence(QuantityConfidence.EXACT))
        assertEquals(QuantityConfidence.EXACT, converters.toQuantityConfidence("EXACT"))
        assertEquals(QuantityConfidence.UNKNOWN, converters.toQuantityConfidence("BAD_VAL"))
    }

    @Test
    fun itemStatusConverters_roundTripAndFallback() {
        assertEquals("USE_SOON", converters.fromItemStatus(ItemStatus.USE_SOON))
        assertEquals(ItemStatus.USE_SOON, converters.toItemStatus("USE_SOON"))
        assertEquals(ItemStatus.AVAILABLE, converters.toItemStatus("NOT_REAL"))
    }

    @Test
    fun transactionTypeConverters_roundTripAndNull() {
        assertEquals("COOKED", converters.fromTransactionType(TransactionType.COOKED))
        assertEquals(TransactionType.COOKED, converters.toTransactionType("COOKED"))
        assertNull(converters.toTransactionType("INVALID"))
    }

    @org.junit.Test
    fun kitsAndMixesIsACategoryAgain_andOldMealKitDataMapsToIt() {
        org.junit.Assert.assertEquals(com.example.chatgpt_mealplanner.core.model.FoodCategory.KITS_MIXES, com.example.chatgpt_mealplanner.core.model.parseFoodCategory("KITS_MIXES"))
        org.junit.Assert.assertEquals(com.example.chatgpt_mealplanner.core.model.FoodCategory.KITS_MIXES, com.example.chatgpt_mealplanner.core.model.parseFoodCategory("meal_kits"))
        org.junit.Assert.assertEquals(com.example.chatgpt_mealplanner.core.model.FoodCategory.READY_MEALS, com.example.chatgpt_mealplanner.core.model.parseFoodCategory("READY_MEALS"))
        org.junit.Assert.assertEquals("Kits & Mixes", com.example.chatgpt_mealplanner.core.model.FoodCategory.KITS_MIXES.displayName)
    }
}
