package com.example.chatgpt_mealplanner.core.util

import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class QuantityFormatterTest {

    @Test
    fun formatQuantityAndUnit_gram_displaysAsG() {
        assertEquals("400 g", formatQuantityAndUnit(400.0, QuantityUnit.GRAM))
    }

    @Test
    fun formatQuantityAndUnit_millilitre_displaysAsMl() {
        assertEquals("200 ml", formatQuantityAndUnit(200.0, QuantityUnit.MILLILITRE))
    }

    @Test
    fun formatQuantityAndUnit_kilogram_displaysAsKg() {
        assertEquals("1.5 kg", formatQuantityAndUnit(1.5, QuantityUnit.KILOGRAM))
    }

    @Test
    fun formatQuantityAndUnit_unit_displaysAsWholeNumber() {
        assertEquals("2", formatQuantityAndUnit(2.0, QuantityUnit.UNIT))
    }
}
