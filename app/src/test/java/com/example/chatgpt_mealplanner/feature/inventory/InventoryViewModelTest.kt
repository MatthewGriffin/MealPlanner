package com.example.chatgpt_mealplanner.feature.inventory

import com.example.chatgpt_mealplanner.core.database.entity.toEntity
import com.example.chatgpt_mealplanner.core.service.FakeInventoryBarcodeDao
import com.example.chatgpt_mealplanner.core.service.FakeInventoryDao
import com.example.chatgpt_mealplanner.core.service.FakeStapleRuleDao
import com.example.chatgpt_mealplanner.core.service.FakeTransactionDao
import com.example.chatgpt_mealplanner.core.service.FakeTransactionRunner
import com.example.chatgpt_mealplanner.core.service.InventoryDomainServiceImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.Before

@OptIn(ExperimentalCoroutinesApi::class)
class InventoryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val fakeInventoryDao = FakeInventoryDao()
    private val fakeInventoryBarcodeDao = FakeInventoryBarcodeDao()
    private val fakeTransactionDao = FakeTransactionDao()
    private val fakeStapleRuleDao = FakeStapleRuleDao()
    private val fakeRunner = FakeTransactionRunner()

    private val fakeRepository = com.example.chatgpt_mealplanner.core.repository.InventoryRepositoryImpl(fakeInventoryDao)
    private val domainService = InventoryDomainServiceImpl(fakeInventoryDao, fakeInventoryBarcodeDao, fakeTransactionDao, fakeStapleRuleDao, fakeRunner)

    private lateinit var viewModel: InventoryViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = InventoryViewModel(fakeRepository, domainService)
    }

    @org.junit.Test
    fun addItemRequested_savesManualItemWithSelectedUnit_noHardcodedPackFallback() = kotlinx.coroutines.test.runTest {
        val toothpaste = com.example.chatgpt_mealplanner.core.model.InventoryItem(
            canonicalName = "Toothpaste",
            displayName = "Oral-B Toothpaste",
            category = com.example.chatgpt_mealplanner.core.model.FoodCategory.CUPBOARD,
            storageLocation = com.example.chatgpt_mealplanner.core.model.StorageLocation.CUPBOARD,
            quantity = 75.0,
            quantityUnit = com.example.chatgpt_mealplanner.core.model.QuantityUnit.MILLILITRE,
            quantityConfidence = com.example.chatgpt_mealplanner.core.model.QuantityConfidence.EXACT
        )

        viewModel.onEvent(InventoryUiEvent.AddItemRequested(toothpaste))
        testDispatcher.scheduler.advanceUntilIdle()

        val saved = fakeInventoryDao.getItemById(toothpaste.id)
        org.junit.Assert.assertEquals("Oral-B Toothpaste", saved?.displayName)
        org.junit.Assert.assertEquals(75.0, saved?.quantity ?: 0.0, 0.001)
        org.junit.Assert.assertEquals(com.example.chatgpt_mealplanner.core.model.QuantityUnit.MILLILITRE, saved?.quantityUnit)
    }

    @org.junit.Test
    fun recordCorrection_updatesQuantityAndUnitCoherently() = kotlinx.coroutines.test.runTest {
        val existingItem = com.example.chatgpt_mealplanner.core.model.InventoryItem(
            id = "rice-1",
            canonicalName = "Rice",
            displayName = "Basmati Rice",
            category = com.example.chatgpt_mealplanner.core.model.FoodCategory.CUPBOARD,
            storageLocation = com.example.chatgpt_mealplanner.core.model.StorageLocation.CUPBOARD,
            quantity = 1000.0,
            quantityUnit = com.example.chatgpt_mealplanner.core.model.QuantityUnit.GRAM
        )
        fakeInventoryDao.insertItem(existingItem.toEntity())

        viewModel.onEvent(InventoryUiEvent.RecordCorrection(
            itemId = "rice-1",
            newQuantity = 1.0,
            newUnit = com.example.chatgpt_mealplanner.core.model.QuantityUnit.KILOGRAM,
            confidence = com.example.chatgpt_mealplanner.core.model.QuantityConfidence.EXACT
        ))
        testDispatcher.scheduler.advanceUntilIdle()

        val updated = fakeInventoryDao.getItemById("rice-1")
        org.junit.Assert.assertEquals(1.0, updated?.quantity ?: 0.0, 0.001)
        org.junit.Assert.assertEquals(com.example.chatgpt_mealplanner.core.model.QuantityUnit.KILOGRAM, updated?.quantityUnit)
    }

    @org.junit.Test
    fun searchProductsByName_queriesBarcodeLookupCoordinator_whenAvailable() = kotlinx.coroutines.test.runTest {
        val fakeBarcodeMappingDao = com.example.chatgpt_mealplanner.core.service.FakeBarcodeMappingDao()
        val coordinator = com.example.chatgpt_mealplanner.core.barcode.BarcodeLookupCoordinator(fakeBarcodeMappingDao)

        val vm = InventoryViewModel(
            inventoryRepository = fakeRepository,
            inventoryDomainService = domainService,
            barcodeLookupCoordinator = coordinator
        )

        coordinator.confirmAndSaveBarcodeMapping(
            com.example.chatgpt_mealplanner.core.barcode.model.ConfirmedProductInput(
                barcode = "50123456789",
                displayName = "Monster Energy 500ml",
                brand = "Monster",
                category = com.example.chatgpt_mealplanner.core.model.FoodCategory.SNACKS,
                storageLocation = com.example.chatgpt_mealplanner.core.model.StorageLocation.FRIDGE,
                netQuantity = 500.0,
                netQuantityUnit = com.example.chatgpt_mealplanner.core.model.QuantityUnit.MILLILITRE
            )
        )

        val results = vm.searchProductsByName("Monster")
        org.junit.Assert.assertEquals(1, results.size)
        org.junit.Assert.assertEquals("Monster Energy 500ml", results[0].displayName)
        org.junit.Assert.assertEquals("50123456789", results[0].barcode)
    }
}
