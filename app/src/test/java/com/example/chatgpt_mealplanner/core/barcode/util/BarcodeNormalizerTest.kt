package com.example.chatgpt_mealplanner.core.barcode.util

import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class BarcodeNormalizerTest {

    @Test
    fun normalize_handlesLeadingZeros_andWhitespace() {
        assertEquals("5012345678900", BarcodeNormalizer.normalize("05012345678900")) // Leading zero stripped
        assertEquals("5012345678900", BarcodeNormalizer.normalize("  5012345678900  "))
        assertEquals("5012345678900", BarcodeNormalizer.normalize("5012345678900"))
    }

    @Test
    fun normalize_handlesNullAndBlank() {
        assertNull(BarcodeNormalizer.normalize(null))
        assertNull(BarcodeNormalizer.normalize(""))
        assertNull(BarcodeNormalizer.normalize("   "))
    }

    @Test
    fun formatForExternalLookup_retainsLeadingZerosForEan13() {
        assertEquals("05012345678900", BarcodeNormalizer.formatForExternalLookup("05012345678900"))
        assertEquals("012345678905", BarcodeNormalizer.formatForExternalLookup("012345678905"))
    }

    @Test
    fun formatForExternalLookup_handlesOrdinaryEan13AndUpc() {
        assertEquals("5012345678900", BarcodeNormalizer.formatForExternalLookup("5012345678900"))
        assertEquals("5012345678900", BarcodeNormalizer.formatForExternalLookup("  5012345678900  "))
    }

    @Test
    fun isValidGroceryBarcode_validatesEanAndGtin() {
        assertTrue(BarcodeNormalizer.isValidGroceryBarcode("5012345678900"))
        assertTrue(BarcodeNormalizer.isValidGroceryBarcode("05012345678900"))
    }

    @Test
    fun isVariableWeightOrInternalBarcode_detectsStoreInternalPrefixes() {
        assertTrue(BarcodeNormalizer.isVariableWeightOrInternalBarcode("0201234567890")) // Prefix 02
        assertTrue(BarcodeNormalizer.isVariableWeightOrInternalBarcode("0401234567890")) // Prefix 04
        assertTrue(BarcodeNormalizer.isVariableWeightOrInternalBarcode("2012345678901")) // Prefix 20
        assertTrue(BarcodeNormalizer.isVariableWeightOrInternalBarcode("2812345678901")) // Prefix 28
        assertFalse(BarcodeNormalizer.isVariableWeightOrInternalBarcode("5012345678900")) // Standard EAN-13
    }

    @Test
    fun parseProductTitle_extractsEggCountPrefix() {
        val parsed = BarcodeNormalizer.parseProductTitle("10 free range eggs")
        assertEquals("Free Range Eggs", parsed.cleanName)
        assertEquals(10.0, parsed.extractedPackSize)
        assertEquals(QuantityUnit.UNIT, parsed.extractedPackSizeUnit)
    }

    @Test
    fun parseProductTitle_extractsMultipackPrefix() {
        val parsed = BarcodeNormalizer.parseProductTitle("6x330ml Coca-Cola")
        assertEquals("Coca-Cola", parsed.cleanName)
        assertEquals(6, parsed.extractedPackCount)
        assertEquals(330.0, parsed.extractedPackSize)
        assertEquals(QuantityUnit.MILLILITRE, parsed.extractedPackSizeUnit)
    }

    @Test
    fun parseProductTitle_preservesLegitimateBrandNamesWithLeadingNumbers() {
        assertEquals("7UP Zero", BarcodeNormalizer.parseProductTitle("7UP Zero").cleanName)
        assertEquals("3M Command Hooks", BarcodeNormalizer.parseProductTitle("3M Command Hooks").cleanName)
        assertEquals("2in1 Shampoo", BarcodeNormalizer.parseProductTitle("2in1 Shampoo").cleanName)
    }
}
