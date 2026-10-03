package com.example.chatgpt_mealplanner.core.barcode.util

import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuantityParserTest {

    @Test
    fun parseSingleGrams_parsesCorrectly() {
        val result = QuantityParser.parse("400 g")
        assertEquals(400.0, result.netQuantity!!, 0.001)
        assertEquals(QuantityUnit.GRAM, result.netQuantityUnit)
        assertEquals(QuantityConfidence.EXACT, result.confidence)
    }

    @Test
    fun parseSingleKilograms_convertsToMetricGrams() {
        val result = QuantityParser.parse("1.5 kg")
        assertEquals(1500.0, result.netQuantity!!, 0.001)
        assertEquals(QuantityUnit.GRAM, result.netQuantityUnit)
        assertEquals(QuantityConfidence.EXACT, result.confidence)
    }

    @Test
    fun parseSingleMillilitres_parsesCorrectly() {
        val result = QuantityParser.parse("500 ml")
        assertEquals(500.0, result.netQuantity!!, 0.001)
        assertEquals(QuantityUnit.MILLILITRE, result.netQuantityUnit)
        assertEquals(QuantityConfidence.EXACT, result.confidence)
    }

    @Test
    fun parseSingleLitres_convertsToMetricMillilitres() {
        val result = QuantityParser.parse("2 l")
        assertEquals(2000.0, result.netQuantity!!, 0.001)
        assertEquals(QuantityUnit.MILLILITRE, result.netQuantityUnit)
        assertEquals(QuantityConfidence.EXACT, result.confidence)
    }

    @Test
    fun parseMultiPackGrams_parsesPackDetailsAndTotal() {
        val result = QuantityParser.parse("4 x 405 g")
        assertEquals(4, result.packCount)
        assertEquals(405.0, result.packSize!!, 0.001)
        assertEquals(QuantityUnit.GRAM, result.packSizeUnit)
        assertEquals(1620.0, result.netQuantity!!, 0.001)
        assertEquals(QuantityUnit.GRAM, result.netQuantityUnit)
        assertEquals(QuantityConfidence.EXACT, result.confidence)
    }

    @Test
    fun parseMultiPackMillilitres_parsesPackDetailsAndTotal() {
        val result = QuantityParser.parse("6x330ml")
        assertEquals(6, result.packCount)
        assertEquals(330.0, result.packSize!!, 0.001)
        assertEquals(QuantityUnit.MILLILITRE, result.packSizeUnit)
        assertEquals(1980.0, result.netQuantity!!, 0.001)
        assertEquals(QuantityUnit.MILLILITRE, result.netQuantityUnit)
        assertEquals(QuantityConfidence.EXACT, result.confidence)
    }

    @Test
    fun parseToothpaste75ml_withAndWithoutSpace_parsesMillilitres() {
        val withSpace = QuantityParser.parse("75 ml")
        assertEquals(75.0, withSpace.netQuantity!!, 0.001)
        assertEquals(QuantityUnit.MILLILITRE, withSpace.netQuantityUnit)

        val noSpace = QuantityParser.parse("75ml")
        assertEquals(75.0, noSpace.netQuantity!!, 0.001)
        assertEquals(QuantityUnit.MILLILITRE, noSpace.netQuantityUnit)
    }

    @Test
    fun parse6Pack_parsesPackUnit() {
        val result = QuantityParser.parse("6 pack")
        assertEquals(6.0, result.netQuantity!!, 0.001)
        assertEquals(QuantityUnit.PACK, result.netQuantityUnit)
    }

    @Test
    fun parseAmbiguousQuantity_returnsUnknownConfidence() {
        val result = QuantityParser.parse("Family Pack")
        assertNull(result.netQuantity)
        assertNull(result.netQuantityUnit)
        assertEquals(QuantityConfidence.UNKNOWN, result.confidence)
    }

    @Test
    fun parseUnsupportedUnit_returnsUnknownConfidence_andDoesNotFabricateGram() {
        val result = QuantityParser.parse("12 oz")
        assertNull(result.netQuantity)
        assertNull(result.netQuantityUnit)
        assertEquals(QuantityConfidence.UNKNOWN, result.confidence)
    }
}
