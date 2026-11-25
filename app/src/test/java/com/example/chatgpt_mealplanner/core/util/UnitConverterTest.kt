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

    private fun value(v: Double, from: QuantityUnit, to: QuantityUnit, size: Double? = null, sizeUnit: QuantityUnit? = null) =
        (UnitConverter.convert(v, from, to, size, sizeUnit) as DomainResult.Success).value

    @Test
    fun aPackAndAUnitAreTheSameThing() {
        assertEquals(1.0, value(1.0, QuantityUnit.PACK, QuantityUnit.UNIT), 0.001)
        assertEquals(2.0, value(2.0, QuantityUnit.UNIT, QuantityUnit.PACK), 0.001)
        assertEquals(1.0, value(1.0, QuantityUnit.UNIT, QuantityUnit.TIN), 0.001)
        // Still one for one when the pack's weight is known and the recipe counts it.
        assertEquals(1.0, value(1.0, QuantityUnit.PACK, QuantityUnit.UNIT, 500.0, QuantityUnit.GRAM), 0.001)
        assertEquals(1.0, value(1.0, QuantityUnit.UNIT, QuantityUnit.PACK, 500.0, QuantityUnit.GRAM), 0.001)
    }

    @Test
    fun aRecordedPackSizeConvertsBothWays() {
        assertEquals(800.0, value(2.0, QuantityUnit.TIN, QuantityUnit.GRAM, 400.0, QuantityUnit.GRAM), 0.001)
        // One onion is not the whole bag.
        assertTrue(UnitConverter.convert(1.0, QuantityUnit.UNIT, QuantityUnit.GRAM, 1000.0, QuantityUnit.GRAM) is DomainResult.Failure)
        assertTrue(UnitConverter.convert(1000.0, QuantityUnit.GRAM, QuantityUnit.UNIT, 1000.0, QuantityUnit.GRAM) is DomainResult.Failure)
        assertEquals(0.4, value(200.0, QuantityUnit.GRAM, QuantityUnit.PACK, 500.0, QuantityUnit.GRAM), 0.001)
        assertEquals(6.0, value(1.0, QuantityUnit.PACK, QuantityUnit.UNIT, 6.0, QuantityUnit.UNIT), 0.001)
        assertEquals(0.5, value(3.0, QuantityUnit.UNIT, QuantityUnit.PACK, 6.0, QuantityUnit.UNIT), 0.001)
        // Loose eggs already counted one by one are not multiplied by the box size.
        assertEquals(6.0, value(6.0, QuantityUnit.UNIT, QuantityUnit.UNIT, 6.0, QuantityUnit.UNIT), 0.001)
    }

    @Test
    fun weightsStillDoNotBecomeCountsWithoutAPackSize() {
        assertTrue(UnitConverter.convert(200.0, QuantityUnit.GRAM, QuantityUnit.UNIT) is DomainResult.Failure)
        assertTrue(UnitConverter.convert(1.0, QuantityUnit.PACK, QuantityUnit.GRAM) is DomainResult.Failure)
        assertTrue(UnitConverter.convert(1.0, QuantityUnit.SLICE, QuantityUnit.PACK) is DomainResult.Failure)
    }
}
