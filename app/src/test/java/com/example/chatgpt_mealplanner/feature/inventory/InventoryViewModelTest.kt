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

    private class FakePantryProvider(var result: com.example.chatgpt_mealplanner.core.ai.provider.PantryListResult) :
        com.example.chatgpt_mealplanner.core.ai.provider.MealPlannerAiProvider {
        var lastText: String? = null
        override suspend fun send(request: com.example.chatgpt_mealplanner.contract.AiProviderRequest) = error("unused")
        override suspend fun continueWithToolResults(request: com.example.chatgpt_mealplanner.contract.AiContinueWithToolsRequest) = error("unused")
        override suspend fun parsePantryList(text: String): com.example.chatgpt_mealplanner.core.ai.provider.PantryListResult {
            lastText = text
            return result
        }
    }

    private fun parsed(name: String, quantity: Double, unit: com.example.chatgpt_mealplanner.core.model.QuantityUnit, guessed: Boolean = false) =
        com.example.chatgpt_mealplanner.core.ai.provider.ParsedPantryItem(
            displayName = name, canonicalName = name.lowercase(), brand = null,
            category = com.example.chatgpt_mealplanner.core.model.FoodCategory.DRY_GOODS,
            storageLocation = com.example.chatgpt_mealplanner.core.model.StorageLocation.CUPBOARD,
            quantity = quantity, unit = unit, quantityGuessed = guessed
        )

    private fun bulkViewModel(provider: FakePantryProvider) = InventoryViewModel(fakeRepository, domainService, aiProvider = provider)

    @org.junit.Test
    fun bulkAdd_readsTheListIntoEditableRows_thenSavesThemAll() = kotlinx.coroutines.test.runTest {
        val provider = FakePantryProvider(
            com.example.chatgpt_mealplanner.core.ai.provider.PantryListResult.Items(
                listOf(parsed("Basmati rice", 2.0, com.example.chatgpt_mealplanner.core.model.QuantityUnit.KILOGRAM), parsed("Cumin", 40.0, com.example.chatgpt_mealplanner.core.model.QuantityUnit.GRAM, guessed = true), parsed("Eggs", 12.0, com.example.chatgpt_mealplanner.core.model.QuantityUnit.UNIT))
            )
        )
        val vm = bulkViewModel(provider)
        vm.onEvent(InventoryUiEvent.OpenBulkAdd)
        vm.onEvent(InventoryUiEvent.BulkAddTextChanged("2 kg rice, cumin, a dozen eggs"))
        vm.onEvent(InventoryUiEvent.ReadBulkAddList)
        testDispatcher.scheduler.advanceUntilIdle()

        org.junit.Assert.assertEquals("2 kg rice, cumin, a dozen eggs", provider.lastText)
        val rows = vm.uiState.value.bulkAdd!!.rows
        org.junit.Assert.assertEquals(listOf("2", "40", "12"), rows.map { it.quantityText })
        org.junit.Assert.assertTrue(rows[1].quantityGuessed)

        // The user corrects one amount and removes one line.
        vm.onEvent(InventoryUiEvent.BulkAddRowChanged(rows[0].copy(quantityText = "1,5")))
        vm.onEvent(InventoryUiEvent.BulkAddRowRemoved(rows[2].id))
        vm.onEvent(InventoryUiEvent.SaveBulkAddRows)
        testDispatcher.scheduler.advanceUntilIdle()

        org.junit.Assert.assertNull(vm.uiState.value.bulkAdd)
        org.junit.Assert.assertEquals("Added 2 items", vm.uiState.value.userMessage)
        val saved = fakeInventoryDao.getAllItemsSync().associateBy { it.displayName }
        org.junit.Assert.assertEquals(setOf("Basmati rice", "Cumin"), saved.keys)
        org.junit.Assert.assertEquals(1.5, saved.getValue("Basmati rice").quantity ?: 0.0, 0.001)
        org.junit.Assert.assertEquals(com.example.chatgpt_mealplanner.core.model.QuantityUnit.KILOGRAM.name, saved.getValue("Basmati rice").quantityUnit?.name)
    }

    @org.junit.Test
    fun bulkAdd_keepsRowsThatCouldNotBeSaved() = kotlinx.coroutines.test.runTest {
        val provider = FakePantryProvider(
            com.example.chatgpt_mealplanner.core.ai.provider.PantryListResult.Items(listOf(parsed("Rice", 1.0, com.example.chatgpt_mealplanner.core.model.QuantityUnit.KILOGRAM), parsed("Pasta", 500.0, com.example.chatgpt_mealplanner.core.model.QuantityUnit.GRAM)))
        )
        val vm = bulkViewModel(provider)
        vm.onEvent(InventoryUiEvent.OpenBulkAdd)
        vm.onEvent(InventoryUiEvent.BulkAddTextChanged("rice, pasta"))
        vm.onEvent(InventoryUiEvent.ReadBulkAddList)
        testDispatcher.scheduler.advanceUntilIdle()
        val rows = vm.uiState.value.bulkAdd!!.rows
        vm.onEvent(InventoryUiEvent.BulkAddRowChanged(rows[1].copy(quantityText = "lots")))
        vm.onEvent(InventoryUiEvent.SaveBulkAddRows)
        testDispatcher.scheduler.advanceUntilIdle()

        val bulk = vm.uiState.value.bulkAdd!!
        org.junit.Assert.assertEquals(listOf("Pasta"), bulk.rows.map { it.displayName })
        org.junit.Assert.assertTrue(bulk.error!!.startsWith("1 item couldn't be saved"))
        org.junit.Assert.assertEquals(setOf("Rice"), fakeInventoryDao.getAllItemsSync().map { it.displayName }.toSet())
    }

    @org.junit.Test
    fun bulkAdd_showsTheReadErrorAndKeepsTheTypedList() = kotlinx.coroutines.test.runTest {
        val provider = FakePantryProvider(com.example.chatgpt_mealplanner.core.ai.provider.PantryListResult.Failed("No Claude API key saved. Add one in Settings."))
        val vm = bulkViewModel(provider)
        vm.onEvent(InventoryUiEvent.OpenBulkAdd)
        vm.onEvent(InventoryUiEvent.BulkAddTextChanged("rice"))
        vm.onEvent(InventoryUiEvent.ReadBulkAddList)
        testDispatcher.scheduler.advanceUntilIdle()
        val bulk = vm.uiState.value.bulkAdd!!
        org.junit.Assert.assertEquals("rice", bulk.text)
        org.junit.Assert.assertTrue(bulk.rows.isEmpty())
        org.junit.Assert.assertEquals("No Claude API key saved. Add one in Settings.", bulk.error)
        org.junit.Assert.assertFalse(bulk.isReading)
    }
}
