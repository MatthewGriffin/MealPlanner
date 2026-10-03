package com.example.chatgpt_mealplanner.core.barcode

import com.example.chatgpt_mealplanner.core.barcode.model.ProductDataSource
import com.example.chatgpt_mealplanner.core.barcode.provider.OpenFoodFactsProductDataProvider
import com.example.chatgpt_mealplanner.core.barcode.provider.ProductProviderResult
import com.example.chatgpt_mealplanner.core.barcode.provider.openfoodfacts.OpenFoodFactsApi
import com.example.chatgpt_mealplanner.core.barcode.provider.openfoodfacts.OpenFoodFactsProductDto
import com.example.chatgpt_mealplanner.core.barcode.provider.openfoodfacts.OpenFoodFactsResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class OpenFoodFactsDataProviderTest {

    private val mockApi: OpenFoodFactsApi = mock()
    private val provider = OpenFoodFactsProductDataProvider(mockApi)

    private val offUrl = "https://world.openfoodfacts.org/api/v2/product/8700216666367.json"
    private val obfUrl = "https://world.openbeautyfacts.org/api/v2/product/8700216666367.json"
    private val opfUrl = "https://world.openproductsfacts.org/api/v2/product/8700216666367.json"

    @Test
    fun realObfJsonDeserialization_deserializesCategoriesAndTags() {
        val jsonString = """{"code":"8700216666367","product":{"product_name":"Sensitivy Protection","brands":"oral b","categories":"toothpaste","categories_tags":["en:toothpaste"]},"status":1}"""
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
        val response = json.decodeFromString<OpenFoodFactsResponse>(jsonString)
        val product = response.product!!

        assertEquals("toothpaste", product.categories)
        assertEquals(listOf("en:toothpaste"), product.categoriesTags)

        val concept = com.example.chatgpt_mealplanner.core.barcode.util.HouseholdConceptDeriver.deriveConcept(
            categoriesTags = product.categoriesTags,
            categoriesString = product.categories,
            defaultName = product.productName
        )
        assertEquals("Toothpaste", concept)
    }

    @Test
    fun acceptanceTest_oralBToothpaste_resolvesViaOpenBeautyFacts() = runTest {
        val barcode = "8700216666367"
        val offResponse = OpenFoodFactsResponse(code = barcode, status = 0)
        val obfResponse = OpenFoodFactsResponse(
            code = barcode,
            status = 1,
            product = OpenFoodFactsProductDto(
                productName = "Sensitivy Protection",
                brands = "oral b",
                categories = "toothpaste"
            )
        )

        whenever(mockApi.getProductByUrl(offUrl)).thenReturn(offResponse)
        whenever(mockApi.getProductByUrl(obfUrl)).thenReturn(obfResponse)

        val result = provider.lookupBarcode(barcode)

        assertTrue(result is ProductProviderResult.Success)
        val success = result as ProductProviderResult.Success
        assertEquals("Sensitivy Protection", success.metadata.displayName)
        assertEquals("Toothpaste", success.metadata.canonicalName)
        assertEquals("oral b", success.metadata.brand)
        assertEquals(ProductDataSource.OPEN_BEAUTY_FACTS, success.metadata.source)

        verify(mockApi).getProductByUrl(offUrl)
        verify(mockApi).getProductByUrl(obfUrl)
        verify(mockApi, never()).getProductByUrl(opfUrl)
    }

    @Test
    fun foodProducts_retainProductSpecificNames_monsterAndMarsAndRice() = runTest {
        val monsterBarcode = "5000159461122"
        val monsterUrl = "https://world.openfoodfacts.org/api/v2/product/5000159461122.json"
        val monsterResponse = OpenFoodFactsResponse(
            code = monsterBarcode,
            status = 1,
            product = OpenFoodFactsProductDto(
                productName = "Monster Energy Ultra",
                brands = "Monster",
                categories = "energy-drinks"
            )
        )
        whenever(mockApi.getProductByUrl(monsterUrl)).thenReturn(monsterResponse)

        val result = provider.lookupBarcode(monsterBarcode)

        assertTrue(result is ProductProviderResult.Success)
        val success = result as ProductProviderResult.Success
        assertEquals("Monster Energy Ultra", success.metadata.displayName)
        assertEquals("Monster Energy Ultra", success.metadata.canonicalName)
    }

    @Test
    fun foodProduct_foundInOpenFoodFacts_stopsChainImmediately() = runTest {
        val barcode = "5000159461122"
        val foodUrl = "https://world.openfoodfacts.org/api/v2/product/5000159461122.json"
        val beautyUrl = "https://world.openbeautyfacts.org/api/v2/product/5000159461122.json"
        val mockResponse = OpenFoodFactsResponse(
            code = barcode,
            status = 1,
            product = OpenFoodFactsProductDto(
                productName = "Mars Bar",
                brands = "Mars",
                quantity = "51 g"
            )
        )
        whenever(mockApi.getProductByUrl(foodUrl)).thenReturn(mockResponse)

        val result = provider.lookupBarcode(barcode)

        assertTrue(result is ProductProviderResult.Success)
        val success = result as ProductProviderResult.Success
        assertEquals("Mars Bar", success.metadata.displayName)
        assertEquals(ProductDataSource.OPEN_FOOD_FACTS, success.metadata.source)

        verify(mockApi).getProductByUrl(foodUrl)
        verify(mockApi, never()).getProductByUrl(beautyUrl)
    }

    @Test
    fun nonFoodProduct_foundInOpenProductsFacts() = runTest {
        val barcode = "8700216666367"
        val offResponse = OpenFoodFactsResponse(code = barcode, status = 0)
        val obfResponse = OpenFoodFactsResponse(code = barcode, status = 0)
        val opfResponse = OpenFoodFactsResponse(
            code = barcode,
            status = 1,
            product = OpenFoodFactsProductDto(
                productName = "Laundry Detergent",
                brands = "Persil"
            )
        )

        whenever(mockApi.getProductByUrl(offUrl)).thenReturn(offResponse)
        whenever(mockApi.getProductByUrl(obfUrl)).thenReturn(obfResponse)
        whenever(mockApi.getProductByUrl(opfUrl)).thenReturn(opfResponse)

        val result = provider.lookupBarcode(barcode)

        assertTrue(result is ProductProviderResult.Success)
        val success = result as ProductProviderResult.Success
        assertEquals("Laundry Detergent", success.metadata.displayName)
        assertEquals(ProductDataSource.OPEN_PRODUCTS_FACTS, success.metadata.source)
    }

    @Test
    fun lookupBarcode_allCataloguesNotFound_returnsDefinitiveNotFound() = runTest {
        val barcode = "8700216666367"
        val notFound = OpenFoodFactsResponse(code = barcode, status = 0)

        whenever(mockApi.getProductByUrl(offUrl)).thenReturn(notFound)
        whenever(mockApi.getProductByUrl(obfUrl)).thenReturn(notFound)
        whenever(mockApi.getProductByUrl(opfUrl)).thenReturn(notFound)

        val result = provider.lookupBarcode(barcode)

        assertTrue(result is ProductProviderResult.NotFound)
    }

    @Test
    fun partialErrorCaseA_offError_obfFound_returnsObfFound() = runTest {
        val barcode = "8700216666367"
        val obfResponse = OpenFoodFactsResponse(
            code = barcode,
            status = 1,
            product = OpenFoodFactsProductDto(productName = "Toothpaste", brands = "Oral-B")
        )

        whenever(mockApi.getProductByUrl(offUrl)).thenThrow(RuntimeException("HTTP 500"))
        whenever(mockApi.getProductByUrl(obfUrl)).thenReturn(obfResponse)

        val result = provider.lookupBarcode(barcode)

        assertTrue(result is ProductProviderResult.Success)
        val success = result as ProductProviderResult.Success
        assertEquals(ProductDataSource.OPEN_BEAUTY_FACTS, success.metadata.source)
    }

    @Test
    fun partialErrorCaseB_offError_othersNotFound_returnsFailureNotIncompleteNotFound() = runTest {
        val barcode = "8700216666367"
        val notFound = OpenFoodFactsResponse(code = barcode, status = 0)

        whenever(mockApi.getProductByUrl(offUrl)).thenThrow(RuntimeException("HTTP 500"))
        whenever(mockApi.getProductByUrl(obfUrl)).thenReturn(notFound)
        whenever(mockApi.getProductByUrl(opfUrl)).thenReturn(notFound)

        val result = provider.lookupBarcode(barcode)

        assertTrue(result is ProductProviderResult.Failure)
    }

    @Test
    fun partialErrorCaseC_offNotFound_obfError_opfFound_returnsOpfFound() = runTest {
        val barcode = "8700216666367"
        val notFound = OpenFoodFactsResponse(code = barcode, status = 0)
        val opfResponse = OpenFoodFactsResponse(
            code = barcode,
            status = 1,
            product = OpenFoodFactsProductDto(productName = "General Item")
        )

        whenever(mockApi.getProductByUrl(offUrl)).thenReturn(notFound)
        whenever(mockApi.getProductByUrl(obfUrl)).thenThrow(RuntimeException("Timeout"))
        whenever(mockApi.getProductByUrl(opfUrl)).thenReturn(opfResponse)

        val result = provider.lookupBarcode(barcode)

        assertTrue(result is ProductProviderResult.Success)
        val success = result as ProductProviderResult.Success
        assertEquals(ProductDataSource.OPEN_PRODUCTS_FACTS, success.metadata.source)
    }

    @Test
    fun partialErrorCaseD_allError_returnsFailure() = runTest {
        val barcode = "8700216666367"

        whenever(mockApi.getProductByUrl(any())).thenThrow(RuntimeException("Offline"))

        val result = provider.lookupBarcode(barcode)

        assertTrue(result is ProductProviderResult.Failure)
    }
}
