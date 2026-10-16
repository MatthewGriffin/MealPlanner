package com.example.chatgpt_mealplanner.core.barcode

import com.example.chatgpt_mealplanner.core.barcode.model.ConfirmedProductInput
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.service.FakeBarcodeMappingDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class BarcodeLookupCoordinatorTest {

    private lateinit var mappingDao: FakeBarcodeMappingDao
    private lateinit var coordinator: BarcodeLookupCoordinator

    @Before
    fun setup() {
        mappingDao = FakeBarcodeMappingDao()
        coordinator = BarcodeLookupCoordinator(mappingDao)
    }

    @Test
    fun unconfirmedBarcodeScan_missingQuantityRemainsUnknown() = runTest {
        val res = coordinator.lookupBarcode("50123456789")

        assertEquals("50123456789", res.barcode)
        assertNull(res.netQuantity) // Missing quantity MUST remain null / UNKNOWN!
        assertEquals(QuantityConfidence.UNKNOWN, res.quantityConfidence)
    }

    @Test
    fun confirmedUserMapping_overridesExternalLookup() = runTest {
        val input = ConfirmedProductInput(
            barcode = "50123456789",
            displayName = "Monster Energy 500ml",
            brand = "Monster",
            canonicalIngredientId = "MONSTER_ENERGY",
            canonicalName = "Monster Energy",
            category = FoodCategory.SNACKS,
            storageLocation = StorageLocation.FRIDGE,
            netQuantity = 500.0,
            netQuantityUnit = QuantityUnit.MILLILITRE,
            quantityConfidence = QuantityConfidence.EXACT
        )

        coordinator.confirmAndSaveBarcodeMapping(input)

        val res = coordinator.lookupBarcode("50123456789")
        assertEquals("Monster Energy 500ml", res.displayName)
        assertEquals(500.0, res.netQuantity!!, 0.001)
        assertEquals(QuantityUnit.MILLILITRE, res.netQuantityUnit)
        assertEquals(QuantityConfidence.EXACT, res.quantityConfidence)
        assertEquals("USER_CONFIRMED_MAPPING", res.sourceName)
    }

    @Test
    fun variableWeightBarcodeScan_flagsQuantityConfidenceApproximate() = runTest {
        val res = coordinator.lookupBarcode("0201234567890")

        assertEquals("0201234567890", res.barcode)
        assertEquals(QuantityConfidence.APPROXIMATE, res.quantityConfidence)
        assertEquals("VARIABLE_WEIGHT_STORE_INTERNAL", res.sourceName)
    }

    @Test
    fun searchProductsByName_returnsMatchingProducts() = runTest {
        val input = ConfirmedProductInput(
            barcode = "50123456789",
            displayName = "Monster Energy 500ml",
            brand = "Monster",
            category = FoodCategory.SNACKS,
            storageLocation = StorageLocation.FRIDGE,
            netQuantity = 500.0,
            netQuantityUnit = QuantityUnit.MILLILITRE
        )
        coordinator.confirmAndSaveBarcodeMapping(input)

        val searchResults = coordinator.searchProductsByName("Monster")
        assertEquals(1, searchResults.size)
        assertEquals("Monster Energy 500ml", searchResults[0].displayName)
        assertEquals("50123456789", searchResults[0].barcode)
        assertEquals("USER_CONFIRMED_MAPPING", searchResults[0].sourceName)
    }

    @Test
    fun searchProductsByName_hybridSearch_placesLocalMatchesFirstAndDeduplicatesRemote() = runTest {
        val mockRepo = org.mockito.kotlin.mock<com.example.chatgpt_mealplanner.core.barcode.repository.ProductLookupRepository>()
        val hybridCoordinator = BarcodeLookupCoordinator(mappingDao, mockRepo)

        val localInput = ConfirmedProductInput(
            barcode = "50123456789",
            displayName = "Monster Energy 500ml",
            brand = "Monster",
            category = FoodCategory.SNACKS,
            storageLocation = StorageLocation.FRIDGE
        )
        hybridCoordinator.confirmAndSaveBarcodeMapping(localInput)

        val remoteProduct1 = com.example.chatgpt_mealplanner.core.barcode.model.ProductMetadata(
            barcode = "50123456789", // DUPLICATE BARCODE -> Should be suppressed
            displayName = "Monster Energy 500ml",
            brand = "Monster"
        )
        val remoteProduct2 = com.example.chatgpt_mealplanner.core.barcode.model.ProductMetadata(
            barcode = "50999999999", // NEW REMOTE PRODUCT -> Should be included
            displayName = "Monster Ultra Zero",
            brand = "Monster"
        )

        org.mockito.kotlin.whenever(mockRepo.searchProductsByName(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(
            com.example.chatgpt_mealplanner.core.barcode.repository.ProductLookupSearchResult.Success(
                listOf(remoteProduct1, remoteProduct2)
            )
        )

        val results = hybridCoordinator.searchProductsByName("Monster")
        assertEquals(2, results.size)
        assertEquals("USER_CONFIRMED_MAPPING", results[0].sourceName)
        assertEquals("50123456789", results[0].barcode)

        assertEquals("OPEN_FOOD_FACTS", results[1].sourceName)
        assertEquals("Monster Ultra Zero", results[1].displayName)
        assertEquals("50999999999", results[1].barcode)
    }
}
