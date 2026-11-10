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
        var matchResult: com.example.chatgpt_mealplanner.core.ai.provider.ProductMatchResult =
            com.example.chatgpt_mealplanner.core.ai.provider.ProductMatchResult.Choices(emptyList())
        override suspend fun send(request: com.example.chatgpt_mealplanner.contract.AiProviderRequest) = error("unused")
        override suspend fun continueWithToolResults(request: com.example.chatgpt_mealplanner.contract.AiContinueWithToolsRequest) = error("unused")
        override suspend fun chooseProducts(lines: List<com.example.chatgpt_mealplanner.core.ai.provider.ProductMatchLine>) = matchResult
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

    private suspend fun coordinatorWithCornflakes(): com.example.chatgpt_mealplanner.core.barcode.BarcodeLookupCoordinator {
        val coordinator = com.example.chatgpt_mealplanner.core.barcode.BarcodeLookupCoordinator(com.example.chatgpt_mealplanner.core.service.FakeBarcodeMappingDao())
        listOf("500" to "5000000000500", "375" to "5000000000375").forEach { (size, code) ->
            coordinator.confirmAndSaveBarcodeMapping(
                com.example.chatgpt_mealplanner.core.barcode.model.ConfirmedProductInput(
                    barcode = code, displayName = "Corn Flakes $size", brand = "Kelloggs",
                    netQuantity = size.toDouble(), netQuantityUnit = com.example.chatgpt_mealplanner.core.model.QuantityUnit.GRAM
                )
            )
        }
        return coordinator
    }

    private fun cornflakesList(provider: FakePantryProvider, coordinator: com.example.chatgpt_mealplanner.core.barcode.BarcodeLookupCoordinator): InventoryViewModel {
        provider.result = com.example.chatgpt_mealplanner.core.ai.provider.PantryListResult.Items(
            listOf(
                parsed("Cornflakes", 2.0, com.example.chatgpt_mealplanner.core.model.QuantityUnit.PACK).copy(searchText = "Corn Flakes"),
                parsed("Salt", 750.0, com.example.chatgpt_mealplanner.core.model.QuantityUnit.GRAM).copy(searchText = "no such product")
            )
        )
        return InventoryViewModel(fakeRepository, domainService, barcodeLookupCoordinator = coordinator, aiProvider = provider)
    }

    private suspend fun readCornflakes(vm: InventoryViewModel) {
        vm.onEvent(InventoryUiEvent.OpenBulkAdd)
        vm.onEvent(InventoryUiEvent.BulkAddTextChanged("2 packs corn flakes, salt"))
        vm.onEvent(InventoryUiEvent.ReadBulkAddList)
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @org.junit.Test
    fun bulkAdd_aSureMatchUsesTheProductAndWorksOutTheTotalFromItsPackSize() = kotlinx.coroutines.test.runTest {
        val provider = FakePantryProvider(com.example.chatgpt_mealplanner.core.ai.provider.PantryListResult.Items(emptyList()))
        provider.matchResult = com.example.chatgpt_mealplanner.core.ai.provider.ProductMatchResult.Choices(
            listOf(com.example.chatgpt_mealplanner.core.ai.provider.ProductMatchChoice(0, 0, sure = true))
        )
        val vm = cornflakesList(provider, coordinatorWithCornflakes())
        readCornflakes(vm)

        val rows = vm.uiState.value.bulkAdd!!.rows
        val cornflakes = rows.first { it.genericName == "Cornflakes" }
        org.junit.Assert.assertTrue(cornflakes.decided)
        org.junit.Assert.assertNotNull(cornflakes.matched)
        // The inventory name stays what the user called it, not the product's full name.
        org.junit.Assert.assertEquals("Cornflakes", cornflakes.displayName)
        // The line with no database match keeps the user's wording and needs no decision.
        val salt = rows.first { it.genericName == "Salt" }
        org.junit.Assert.assertTrue(salt.decided && salt.candidates.isEmpty())
        org.junit.Assert.assertTrue(vm.uiState.value.bulkAdd!!.lookupNote!!.contains("1 had no match"))

        vm.onEvent(InventoryUiEvent.SaveBulkAddRows)
        testDispatcher.scheduler.advanceUntilIdle()
        val saved = fakeInventoryDao.getAllItemsSync().first { it.displayName == "Cornflakes" }
        val size = cornflakes.matched!!.netQuantity!!
        org.junit.Assert.assertEquals(2 * size, saved.quantity ?: 0.0, 0.001)
        org.junit.Assert.assertEquals(com.example.chatgpt_mealplanner.core.model.QuantityUnit.GRAM, saved.quantityUnit)
    }

    @org.junit.Test
    fun bulkAdd_anUnsureMatchAsksTheUser_andSavingWaitsForTheAnswer() = kotlinx.coroutines.test.runTest {
        val provider = FakePantryProvider(com.example.chatgpt_mealplanner.core.ai.provider.PantryListResult.Items(emptyList()))
        provider.matchResult = com.example.chatgpt_mealplanner.core.ai.provider.ProductMatchResult.Choices(
            listOf(com.example.chatgpt_mealplanner.core.ai.provider.ProductMatchChoice(0, 1, sure = false))
        )
        val vm = cornflakesList(provider, coordinatorWithCornflakes())
        readCornflakes(vm)

        val rows = vm.uiState.value.bulkAdd!!.rows
        // Undecided lines come first, and Claude's best guess leads the choices.
        val cornflakes = rows.first()
        org.junit.Assert.assertEquals("Cornflakes", cornflakes.genericName)
        org.junit.Assert.assertFalse(cornflakes.decided)
        org.junit.Assert.assertEquals(2, cornflakes.candidates.size)

        vm.onEvent(InventoryUiEvent.SaveBulkAddRows)
        testDispatcher.scheduler.advanceUntilIdle()
        org.junit.Assert.assertTrue(fakeInventoryDao.getAllItemsSync().isEmpty())

        // Picking a product decides it; keeping their own wording also decides it.
        vm.onEvent(InventoryUiEvent.BulkAddRowChanged(cornflakes.copy(matched = cornflakes.candidates[0], decided = true, displayName = cornflakes.candidates[0].displayName)))
        vm.onEvent(InventoryUiEvent.SaveBulkAddRows)
        testDispatcher.scheduler.advanceUntilIdle()
        org.junit.Assert.assertEquals(2, fakeInventoryDao.getAllItemsSync().size)
        org.junit.Assert.assertNull(vm.uiState.value.bulkAdd)
    }

    @org.junit.Test
    fun bulkAdd_keepMyWordingDecidesEveryOpenLine() = kotlinx.coroutines.test.runTest {
        val provider = FakePantryProvider(com.example.chatgpt_mealplanner.core.ai.provider.PantryListResult.Items(emptyList()))
        provider.matchResult = com.example.chatgpt_mealplanner.core.ai.provider.ProductMatchResult.Failed("busy")
        val vm = cornflakesList(provider, coordinatorWithCornflakes())
        readCornflakes(vm)
        // Matching failed, so the line with candidates asks the user.
        org.junit.Assert.assertEquals(1, vm.uiState.value.bulkAdd!!.rows.count { !it.decided })

        vm.onEvent(InventoryUiEvent.KeepWordingForUndecided)
        val rows = vm.uiState.value.bulkAdd!!.rows
        org.junit.Assert.assertTrue(rows.all { it.decided && it.matched == null })
        org.junit.Assert.assertEquals("Cornflakes", rows.first { it.genericName == "Cornflakes" }.displayName)
    }

    private suspend fun breadCoordinator(): com.example.chatgpt_mealplanner.core.barcode.BarcodeLookupCoordinator {
        val coordinator = com.example.chatgpt_mealplanner.core.barcode.BarcodeLookupCoordinator(com.example.chatgpt_mealplanner.core.service.FakeBarcodeMappingDao())
        listOf("Asda" to "5000000000111", "Morrisons" to "5000000000222").forEach { (brand, code) ->
            coordinator.confirmAndSaveBarcodeMapping(
                com.example.chatgpt_mealplanner.core.barcode.model.ConfirmedProductInput(
                    barcode = code, displayName = "$brand White Bread", brand = brand,
                    netQuantity = 800.0, netQuantityUnit = com.example.chatgpt_mealplanner.core.model.QuantityUnit.GRAM
                )
            )
        }
        return coordinator
    }

    private suspend fun bulkAddWhiteBread(coordinator: com.example.chatgpt_mealplanner.core.barcode.BarcodeLookupCoordinator, brand: String) {
        val provider = FakePantryProvider(
            com.example.chatgpt_mealplanner.core.ai.provider.PantryListResult.Items(
                listOf(parsed("White bread", 1.0, com.example.chatgpt_mealplanner.core.model.QuantityUnit.UNIT).copy(searchText = "$brand White Bread"))
            )
        )
        provider.matchResult = com.example.chatgpt_mealplanner.core.ai.provider.ProductMatchResult.Choices(
            listOf(com.example.chatgpt_mealplanner.core.ai.provider.ProductMatchChoice(0, 0, sure = true))
        )
        val vm = InventoryViewModel(fakeRepository, domainService, barcodeLookupCoordinator = coordinator, aiProvider = provider)
        vm.onEvent(InventoryUiEvent.OpenBulkAdd)
        vm.onEvent(InventoryUiEvent.BulkAddTextChanged("$brand white bread"))
        vm.onEvent(InventoryUiEvent.ReadBulkAddList)
        testDispatcher.scheduler.advanceUntilIdle()
        org.junit.Assert.assertEquals("White bread", vm.uiState.value.bulkAdd!!.rows.single().displayName)
        vm.onEvent(InventoryUiEvent.SaveBulkAddRows)
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @org.junit.Test
    fun bulkAdd_sameFoodFromDifferentBrandsMergesIntoOneGenericItemWithBothBarcodes() = kotlinx.coroutines.test.runTest {
        val coordinator = breadCoordinator()
        bulkAddWhiteBread(coordinator, "Asda")
        bulkAddWhiteBread(coordinator, "Morrisons")

        val items = fakeInventoryDao.getAllItemsSync()
        org.junit.Assert.assertEquals(listOf("White bread"), items.map { it.displayName })
        org.junit.Assert.assertNull(items.single().brand)
        org.junit.Assert.assertEquals(1600.0, items.single().quantity ?: 0.0, 0.001)
        val barcodes = fakeInventoryBarcodeDao.getBarcodesForItem(items.single().id).map { it.normalizedBarcode }.toSet()
        org.junit.Assert.assertEquals(2, barcodes.size)
        // Each product's own name and brand are still remembered against its barcode.
        org.junit.Assert.assertEquals("Morrisons", coordinator.lookupBarcode("5000000000222").brand)
    }

    @org.junit.Test
    fun bulkAdd_mergingIntoAnItemCountedInAnotherUnitUsesThatUnit() = kotlinx.coroutines.test.runTest {
        domainService.addLotFromProductInput(
            com.example.chatgpt_mealplanner.core.barcode.model.ConfirmedProductInput(
                barcode = null, displayName = "White bread",
                netQuantity = 2.0, netQuantityUnit = com.example.chatgpt_mealplanner.core.model.QuantityUnit.UNIT
            )
        )
        bulkAddWhiteBread(breadCoordinator(), "Asda")

        val item = fakeInventoryDao.getAllItemsSync().single()
        org.junit.Assert.assertEquals(3.0, item.quantity ?: 0.0, 0.001)
        org.junit.Assert.assertEquals(com.example.chatgpt_mealplanner.core.model.QuantityUnit.UNIT, item.quantityUnit)
    }

    @org.junit.Test
    fun bulkAdd_whenClaudeSaysNoCandidateFitsThereIsNothingToAskTheUser() = kotlinx.coroutines.test.runTest {
        val provider = FakePantryProvider(com.example.chatgpt_mealplanner.core.ai.provider.PantryListResult.Items(emptyList()))
        provider.matchResult = com.example.chatgpt_mealplanner.core.ai.provider.ProductMatchResult.Choices(
            listOf(com.example.chatgpt_mealplanner.core.ai.provider.ProductMatchChoice(0, null, sure = false))
        )
        val vm = cornflakesList(provider, coordinatorWithCornflakes())
        readCornflakes(vm)

        val row = vm.uiState.value.bulkAdd!!.rows.first { it.genericName == "Cornflakes" }
        org.junit.Assert.assertTrue(row.decided)
        org.junit.Assert.assertNull(row.matched)
        // The candidates are still there if the user wants to pick one in the editor.
        org.junit.Assert.assertEquals(2, row.candidates.size)
    }
}
