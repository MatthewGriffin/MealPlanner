package com.example.chatgpt_mealplanner.core.barcode

import com.example.chatgpt_mealplanner.core.barcode.provider.openfoodfacts.OpenFoodFactsResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** A real Open Food Facts answer (Cornitos Nacho Crisps Sweet Chili, 8908001017473) reads as a found product. */
class RealOffResponseTest {
    @Test fun cornitosResponseDecodes() {
        val text = javaClass.classLoader!!.getResource("cornitos_off.json")!!.readText()
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
        val response = json.decodeFromString<OpenFoodFactsResponse>(text)
        assertEquals(1, response.status)
        assertEquals("Cornitos Nacho Crisps Sweet Chili 55 gm", response.product?.productName)
        assertEquals("55 gm", response.product?.quantity)
    }
}
