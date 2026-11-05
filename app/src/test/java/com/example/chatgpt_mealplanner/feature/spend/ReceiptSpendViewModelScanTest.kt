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
}
