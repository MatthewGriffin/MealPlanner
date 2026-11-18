package com.example.chatgpt_mealplanner.feature.spend

import android.net.Uri
import com.example.chatgpt_mealplanner.core.receipt.FakeReceiptSpendDao
import com.example.chatgpt_mealplanner.core.receipt.ReceiptOcrException
import com.example.chatgpt_mealplanner.core.receipt.ReceiptOcrLine
import com.example.chatgpt_mealplanner.core.receipt.ReceiptOcrResult
import com.example.chatgpt_mealplanner.core.receipt.ReceiptOcrService
import com.example.chatgpt_mealplanner.core.service.FakeInventoryDao
import com.example.chatgpt_mealplanner.core.service.FakeShoppingDao
import com.example.chatgpt_mealplanner.core.service.FakeTransactionRunner
import com.example.chatgpt_mealplanner.core.service.ReceiptSpendDomainServiceImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ReceiptSpendViewModelScanTest {

    private val imageUri = Uri.parse("content://test/receipt.jpg")
    private val spendDao = FakeReceiptSpendDao()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(ocr: suspend () -> ReceiptOcrResult) = ReceiptSpendViewModel(
        ocrService = object : ReceiptOcrService {
            override suspend fun recognizeTextFromUri(imageUri: Uri) = ocr()
        },
        spendDomainService = ReceiptSpendDomainServiceImpl(spendDao, FakeTransactionRunner()),
        receiptSpendDao = spendDao,
        inventoryDao = FakeInventoryDao(),
        shoppingDao = FakeShoppingDao()
    )

    private fun receipt(vararg rows: String): ReceiptOcrResult {
        val lines = rows.mapIndexed { index, text ->
            val y = 100 + index * 40
            ReceiptOcrLine(text, 0.9f, index, left = 100, top = y, right = 900, bottom = y + 20, centerY = y + 10)
        }
        return ReceiptOcrResult(lines, rows.joinToString("\n"))
    }

    @Test
    fun ocrFailure_showsItsRealMessage() {
        val vm = viewModel { throw ReceiptOcrException("This image couldn't be opened.", RuntimeException("cause")) }

        vm.processReceiptImage(imageUri)

        assertEquals("This image couldn't be opened.", vm.uiState.value.errorMessage)
        assertNull(vm.uiState.value.draft)
        assertFalse(vm.uiState.value.isScanning)
    }

    @Test
    fun noTextFound_asksForABetterPhoto() {
        val vm = viewModel { ReceiptOcrResult(emptyList(), "") }

        vm.processReceiptImage(imageUri)

        assertTrue(vm.uiState.value.errorMessage!!.contains("No text was found"))
        assertNull(vm.uiState.value.draft)
    }

    @Test
    fun noItemsRecognised_keepsTheTotalAndDateForTheUserToFinish() {
        val vm = viewModel { receipt("ASDA", "TOTAL 5.00", "27/09/2026") }

        vm.processReceiptImage(imageUri)

        val draft = vm.uiState.value.draft
        assertNotNull(draft)
        assertEquals(0, draft!!.items.size)
        assertEquals(500L, draft.totalMinorPence)
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun userCanFixAScanThroughTheViewModelThenSaveIt() {
        val vm = viewModel { receipt("ASDA", "MILK 8.00") }
        vm.processReceiptImage(imageUri)
        assertFalse(vm.uiState.value.draft!!.canSave)

        vm.updatePurchaseDate("2026-09-27")
        vm.updateSummaryPaidTotal(500L)
        val milkId = vm.uiState.value.draft!!.items.single().id
        vm.updateItemDetails(milkId, "semi skimmed milk", 200L)
        vm.addItemDraft("bread", 300L)

        val draft = vm.uiState.value.draft!!
        assertTrue(draft.canSave)
        assertTrue(draft.isReconciled)
        assertEquals("SEMI SKIMMED MILK", draft.items[0].normalizedDescription)
        assertEquals(200L, draft.items[0].unitPriceMinorPence)
        assertEquals("BREAD", draft.items[1].normalizedDescription)

        vm.saveReceiptSpend()

        assertTrue(vm.uiState.value.isSaveSuccess)
        assertEquals(500L, spendDao.savedSpends().single().totalMinorPence)
        assertEquals("2026-09-27", spendDao.savedSpends().single().purchasedAt)
        assertEquals(listOf(200L, 300L), spendDao.savedItems().map { it.lineAmountMinorPence })
    }

    @Test
    fun saveFailure_staysOnTheReviewScreenWithItsMessage() {
        val vm = viewModel { receipt("ASDA", "MILK 2.00", "TOTAL 2.00", "27/09/2026") }
        vm.processReceiptImage(imageUri)
        vm.saveReceiptSpend()
        vm.resetSaveSuccess()

        vm.processReceiptImage(imageUri)
        vm.saveReceiptSpend()

        assertNotNull(vm.uiState.value.draft)
        assertTrue(vm.uiState.value.errorMessage!!.contains("already been saved"))
    }

    private fun claudeViewModel(result: com.example.chatgpt_mealplanner.core.ai.provider.ReceiptReadResult, ocr: suspend () -> ReceiptOcrResult) = ReceiptSpendViewModel(
        ocrService = object : ReceiptOcrService { override suspend fun recognizeTextFromUri(imageUri: Uri) = ocr() },
        spendDomainService = ReceiptSpendDomainServiceImpl(spendDao, FakeTransactionRunner()),
        receiptSpendDao = spendDao,
        inventoryDao = FakeInventoryDao(),
        shoppingDao = FakeShoppingDao(),
        aiProvider = object : com.example.chatgpt_mealplanner.core.ai.provider.MealPlannerAiProvider {
            override suspend fun send(request: com.example.chatgpt_mealplanner.contract.AiProviderRequest) = throw UnsupportedOperationException()
            override suspend fun continueWithToolResults(request: com.example.chatgpt_mealplanner.contract.AiContinueWithToolsRequest) = throw UnsupportedOperationException()
            override suspend fun readReceipt(imagesJpegBase64: List<String>) = result
        },
        photoSource = object : com.example.chatgpt_mealplanner.core.ai.packet.ReceiptPhotoSource {
            override suspend fun prepare(uri: Uri) = listOf("AAAA")
        }
    )

    @Test
    fun claudeReading_isUsedWhenItWorks_andOcrIsNotCalled() {
        val read = com.example.chatgpt_mealplanner.core.ai.provider.ReadReceipt(
            "Lidl", "EUR", "2026-11-17",
            listOf(com.example.chatgpt_mealplanner.core.ai.provider.ReadReceiptLine("BROT", "Brot", "FOOD", 1, "1.29")), emptyList(), null, "1.29"
        )
        val vm = claudeViewModel(com.example.chatgpt_mealplanner.core.ai.provider.ReceiptReadResult.Read(read)) { throw AssertionError("OCR must not run") }
        vm.processReceiptImage(imageUri)
        assertEquals("EUR", vm.uiState.value.draft!!.currencyCode)
        assertEquals("Brot", vm.uiState.value.draft!!.items.single().normalizedDescription)
        assertNull(vm.uiState.value.scanNote)
    }

    @Test
    fun whenClaudeFails_thePhoneReadsItAndTheUserIsTold() {
        val vm = claudeViewModel(com.example.chatgpt_mealplanner.core.ai.provider.ReceiptReadResult.Failed("You have used your AI allowance.")) {
            receipt("TESCO", "17/11/2026", "BREAD £1.10", "TOTAL £1.10")
        }
        vm.processReceiptImage(imageUri)
        assertEquals("GBP", vm.uiState.value.draft!!.currencyCode)
        assertTrue(vm.uiState.value.scanNote!!.contains("phone read it"))
    }

    @Test
    fun saving_addsGenericProductsToThePantry_foodAndNonFood() {
        val added = mutableListOf<com.example.chatgpt_mealplanner.core.model.InventoryItem>()
        val inventoryService = org.mockito.kotlin.mock<com.example.chatgpt_mealplanner.core.service.InventoryDomainService>()
        kotlinx.coroutines.runBlocking {
            org.mockito.kotlin.whenever(inventoryService.addItem(org.mockito.kotlin.any())).thenAnswer {
                added += it.getArgument<com.example.chatgpt_mealplanner.core.model.InventoryItem>(0)
                com.example.chatgpt_mealplanner.core.model.DomainResult.Success(Unit)
            }
        }
        val read = com.example.chatgpt_mealplanner.core.ai.provider.ReadReceipt(
            "Tesco", "GBP", "2026-11-18",
            listOf(
                com.example.chatgpt_mealplanner.core.ai.provider.ReadReceiptLine("WARB WHT", "Warburtons Soft White Bread", "FOOD", 1, "1.50", genericName = "White bread", brand = "Warburtons", foodCategory = "BAKERY", storageLocation = "CUPBOARD"),
                com.example.chatgpt_mealplanner.core.ai.provider.ReadReceiptLine("ORALB", "Oral-B Toothpaste", "PERSONAL_CARE", 1, "2.00", genericName = "Toothpaste")
            ),
            emptyList(), null, "3.50"
        )
        val vm = ReceiptSpendViewModel(
            ocrService = object : ReceiptOcrService { override suspend fun recognizeTextFromUri(imageUri: Uri) = throw AssertionError("no OCR") },
            spendDomainService = ReceiptSpendDomainServiceImpl(spendDao, FakeTransactionRunner()),
            receiptSpendDao = spendDao,
            inventoryDao = FakeInventoryDao(),
            shoppingDao = FakeShoppingDao(),
            aiProvider = object : com.example.chatgpt_mealplanner.core.ai.provider.MealPlannerAiProvider {
                override suspend fun send(request: com.example.chatgpt_mealplanner.contract.AiProviderRequest) = throw UnsupportedOperationException()
                override suspend fun continueWithToolResults(request: com.example.chatgpt_mealplanner.contract.AiContinueWithToolsRequest) = throw UnsupportedOperationException()
                override suspend fun readReceipt(imagesJpegBase64: List<String>) = com.example.chatgpt_mealplanner.core.ai.provider.ReceiptReadResult.Read(read)
            },
            photoSource = object : com.example.chatgpt_mealplanner.core.ai.packet.ReceiptPhotoSource { override suspend fun prepare(uri: Uri) = listOf("AAAA") },
            inventoryDomainService = inventoryService
        )
        vm.processReceiptImage(imageUri)
        assertTrue(added.isEmpty())                       // nothing is added until the receipt is saved

        vm.saveReceiptSpend()

        assertEquals(listOf("White bread", "Toothpaste"), added.map { it.displayName })
        assertEquals("Warburtons", added[0].brand)
        assertEquals(com.example.chatgpt_mealplanner.core.model.FoodCategory.BAKERY, added[0].category)
        assertEquals(com.example.chatgpt_mealplanner.core.model.FoodCategory.HOUSEHOLD, added[1].category)
        assertTrue(vm.uiState.value.isSaveSuccess)
    }
}
