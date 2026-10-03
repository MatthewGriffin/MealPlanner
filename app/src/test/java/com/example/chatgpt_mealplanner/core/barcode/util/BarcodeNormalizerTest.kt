package com.example.chatgpt_mealplanner.core.barcode.util

import org.junit.Assert.assertEquals
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
}
