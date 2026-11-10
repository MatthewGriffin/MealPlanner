package com.example.chatgpt_mealplanner.core.barcode

import com.example.chatgpt_mealplanner.core.barcode.provider.OpenFoodFactsProductDataProvider
import com.example.chatgpt_mealplanner.core.barcode.provider.ProductProviderSearchResult
import com.example.chatgpt_mealplanner.core.barcode.provider.openfoodfacts.OpenFoodFactsApi
import com.example.chatgpt_mealplanner.core.barcode.provider.openfoodfacts.OpenFoodFactsProductDto
import com.example.chatgpt_mealplanner.core.barcode.provider.openfoodfacts.OpenFoodFactsSearchResponse
import com.example.chatgpt_mealplanner.core.barcode.provider.openfoodfacts.SearchALiciousApi
import com.example.chatgpt_mealplanner.core.barcode.provider.openfoodfacts.SearchALiciousResponse
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class FastProductSearchTest {

    private val legacy: OpenFoodFactsApi = mock()
    private val fast: SearchALiciousApi = mock()
    private val provider = OpenFoodFactsProductDataProvider(legacy, fast).apply { countryTag = { "en:united-kingdom" } }
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    /** A response in the shape the live service returned on 2026-11-10: brands is a list, quantity may be missing or a number. */
    private val liveShape = """{"hits":[
        {"code":"5000157071644","brands":["Heinz"],"quantity":"415 g","product_name":"Heinz Baked Beans","categories_tags":["en:baked-beans"]},
        {"code":"0786173802442","product_name":"Heinz Baked Beans"},
        {"code":"1","brands":["Kellogg's"],"quantity":3,"product_name":"Kelloggs Cereal Cornflakes"},
        {"code":"2","brands":[],"quantity":null}
    ],"aggregations":null,"page":1,"page_size":4}"""

    @Test
    fun theServicesResponseIsReadAndMapped() = runTest {
        whenever(fast.search(any(), any(), any(), any())).thenReturn(json.decodeFromString<SearchALiciousResponse>(liveShape))

        val result = provider.searchProductsByName("heinz baked beans") as ProductProviderSearchResult.Success

        // The hit with no name is dropped.
        assertEquals(3, result.products.size)
        val heinz = result.products[0]
        assertEquals("5000157071644", heinz.barcode)
        assertEquals("Heinz", heinz.brand)
        assertEquals(415.0, heinz.netQuantity!!, 0.0)
        assertEquals(QuantityUnit.GRAM, heinz.netQuantityUnit)
        assertEquals(null, result.products[1].brand)
        verify(legacy, never()).searchProductsByName(any(), any(), any())
    }

    @Test
    fun aFailureOfTheFastServiceFallsBackToTheLegacySearch() = runTest {
        whenever(fast.search(any(), any(), any(), any())).thenThrow(RuntimeException("503"))
        whenever(legacy.searchProductsByName(any(), any(), any())).thenReturn(
            OpenFoodFactsSearchResponse(products = listOf(OpenFoodFactsProductDto(code = "9", productName = "Monster Energy", brands = "Monster", quantity = "500ml")))
        )

        val result = provider.searchProductsByName("monster") as ProductProviderSearchResult.Success

        assertEquals("Monster Energy", result.products.single().displayName)
        assertEquals(QuantityUnit.MILLILITRE, result.products.single().netQuantityUnit)
    }

    @Test
    fun whenBothFailTheResultIsAFailureSoTheCallerCanRetry() = runTest {
        whenever(fast.search(any(), any(), any(), any())).thenThrow(RuntimeException("down"))
        whenever(legacy.searchProductsByName(any(), any(), any())).thenThrow(RuntimeException("also down"))

        assertTrue(provider.searchProductsByName("monster") is ProductProviderSearchResult.Failure)
    }

    @Test
    fun nothingFoundByTheFastServiceIsFinalAndSkipsTheSlowSearch() = runTest {
        whenever(fast.search(any(), any(), any(), any())).thenReturn(SearchALiciousResponse(hits = emptyList()))

        assertTrue(provider.searchProductsByName("zzzz") is ProductProviderSearchResult.Empty)
        verify(legacy, never()).searchProductsByName(any(), any(), any())
    }

    @Test
    fun withoutTheFastServiceTheLegacySearchStillWorks() = runTest {
        val legacyOnly = OpenFoodFactsProductDataProvider(legacy)
        whenever(legacy.searchProductsByName(any(), any(), any())).thenReturn(
            OpenFoodFactsSearchResponse(products = listOf(OpenFoodFactsProductDto(code = "9", productName = "Monster Energy")))
        )
        assertTrue(legacyOnly.searchProductsByName("monster") is ProductProviderSearchResult.Success)
    }

    @Test
    fun productsSoldInTheUsersCountryAreSearchedFirst() = runTest {
        whenever(fast.search(any(), any(), any(), any())).thenReturn(json.decodeFromString<SearchALiciousResponse>(liveShape))

        provider.searchProductsByName("Asda white bread")

        verify(fast).search(org.mockito.kotlin.eq("Asda white bread countries_tags:\"en:united-kingdom\""), any(), any(), any())
        verify(fast, never()).search(org.mockito.kotlin.eq("Asda white bread"), any(), any(), any())
    }

    @Test
    fun ifNothingIsSoldThereTheWholeDatabaseIsSearched() = runTest {
        whenever(fast.search(org.mockito.kotlin.eq("kimchi countries_tags:\"en:united-kingdom\""), any(), any(), any())).thenReturn(SearchALiciousResponse(hits = emptyList()))
        whenever(fast.search(org.mockito.kotlin.eq("kimchi"), any(), any(), any())).thenReturn(json.decodeFromString<SearchALiciousResponse>(liveShape))

        assertTrue(provider.searchProductsByName("kimchi") is ProductProviderSearchResult.Success)
    }

    @Test
    fun noCountryMeansNoFilter() = runTest {
        val worldwide = OpenFoodFactsProductDataProvider(legacy, fast).apply { countryTag = { null } }
        whenever(fast.search(any(), any(), any(), any())).thenReturn(json.decodeFromString<SearchALiciousResponse>(liveShape))

        worldwide.searchProductsByName("rice")

        verify(fast).search(org.mockito.kotlin.eq("rice"), any(), any(), any())
    }

    @Test
    fun theCountryTagIsBuiltFromTheDevicesLocale() {
        assertEquals("en:united-kingdom", com.example.chatgpt_mealplanner.core.barcode.provider.defaultCountryTag(java.util.Locale.UK))
        assertEquals("en:united-states", com.example.chatgpt_mealplanner.core.barcode.provider.defaultCountryTag(java.util.Locale.US))
        assertEquals(null, com.example.chatgpt_mealplanner.core.barcode.provider.defaultCountryTag(java.util.Locale.ENGLISH))
    }
}
