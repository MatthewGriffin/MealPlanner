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
}
