package com.example.chatgpt_mealplanner.core.ai.provider

import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProductIdentifyParsingTest {
    @Test fun aPackIsReadIntoProductDetails() {
        val p = ProductIdentifyParsing.parse(
            """{"name":"Nacho crisps sweet chilli","brand":"Cornitos","category":"SNACKS","storage":"CUPBOARD","netQuantity":55,"netQuantityUnit":"GRAM"}"""
        )!!
        assertEquals("Nacho crisps sweet chilli", p.name)
        assertEquals("Cornitos", p.brand)
        assertEquals(FoodCategory.SNACKS, p.category)
        assertEquals(StorageLocation.CUPBOARD, p.storage)
        assertEquals(55.0, p.netQuantity!!, 0.0)
        assertEquals(QuantityUnit.GRAM, p.netQuantityUnit)
    }

    @Test fun unknownValuesFallBackSafely_andAQuantityNeedsAUnit() {
        val p = ProductIdentifyParsing.parse("""{"name":"Mystery","category":"WHATEVER","storage":"SHED","netQuantity":3}""")!!
        assertEquals(FoodCategory.OTHER, p.category)
        assertEquals(StorageLocation.CUPBOARD, p.storage)
        assertNull(p.netQuantity)
    }

    @Test fun noNameMeansNothingWasRead() {
        assertNull(ProductIdentifyParsing.parse("""{"category":"SNACKS"}"""))
        assertNull(ProductIdentifyParsing.parse("not json"))
    }
}
