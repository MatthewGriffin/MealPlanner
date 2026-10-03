package com.example.chatgpt_mealplanner.core.util

import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class UnitConverterTest {

    @Test
    fun convertKilogramToGram_convertsCorrectly() {
        val result = UnitConverter.convert(0.5, QuantityUnit.KILOGRAM, QuantityUnit.GRAM)
        assertTrue(result is DomainResult.Success)
        assertEquals(500.0, (result as DomainResult.Success).value, 0.001)
    }

    @Test
    fun convertGramToKilogram_convertsCorrectly() {
        val result = UnitConverter.convert(500.0, QuantityUnit.GRAM, QuantityUnit.KILOGRAM)
        assertTrue(result is DomainResult.Success)
        assertEquals(0.5, (result as DomainResult.Success).value, 0.001)
    }

    @Test
    fun convertLitreToMillilitre_convertsCorrectly() {
        val result = UnitConverter.convert(1.0, QuantityUnit.LITRE, QuantityUnit.MILLILITRE)
        assertTrue(result is DomainResult.Success)
        assertEquals(1000.0, (result as DomainResult.Success).value, 0.001)
    }

    @Test
    fun convertMillilitreToLitre_convertsCorrectly() {
        val result = UnitConverter.convert(1000.0, QuantityUnit.MILLILITRE, QuantityUnit.LITRE)
        assertTrue(result is DomainResult.Success)
        assertEquals(1.0, (result as DomainResult.Success).value, 0.001)
    }

    @Test
    fun sameUnitConversion_returnsOriginalValue() {
        val result = UnitConverter.convert(250.0, QuantityUnit.GRAM, QuantityUnit.GRAM)
        assertTrue(result is DomainResult.Success)
        assertEquals(250.0, (result as DomainResult.Success).value, 0.001)
    }
}
