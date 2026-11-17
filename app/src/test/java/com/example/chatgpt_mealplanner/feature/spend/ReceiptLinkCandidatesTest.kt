package com.example.chatgpt_mealplanner.feature.spend

import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.receipt.FakeReceiptSpendDao
import com.example.chatgpt_mealplanner.core.receipt.ReceiptOcrResult
import com.example.chatgpt_mealplanner.core.receipt.ReceiptOcrService
import com.example.chatgpt_mealplanner.core.repository.InventoryRepository
import com.example.chatgpt_mealplanner.core.service.FakeInventoryDao
import com.example.chatgpt_mealplanner.core.service.FakeShoppingDao
import com.example.chatgpt_mealplanner.core.service.FakeTransactionRunner
import com.example.chatgpt_mealplanner.core.service.ReceiptSpendDomainServiceImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The receipt "Link item" list must show the pantry the phone actually uses; a follower's pantry is not in the main database. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ReceiptLinkCandidatesTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun item(id: String, name: String, status: ItemStatus = ItemStatus.AVAILABLE) = InventoryItem(
        id = id, barcode = null, canonicalName = name.lowercase(), displayName = name, category = FoodCategory.DRY_GOODS,
        storageLocation = StorageLocation.CUPBOARD, status = status
    )

    @Test
    fun pantryItemsFromTheRoleAwareRepositoryAreOffered_discardedOnesAreNot() {
        val repository: InventoryRepository = mock()
        whenever(repository.getAllItems()).thenReturn(flowOf(listOf(item("a", "White bread"), item("b", "Old rice", ItemStatus.DISCARDED))))
        val spendDao = FakeReceiptSpendDao()
        val vm = ReceiptSpendViewModel(
            ocrService = object : ReceiptOcrService { override suspend fun recognizeTextFromUri(imageUri: android.net.Uri): ReceiptOcrResult = ReceiptOcrResult(emptyList(), "") },
            spendDomainService = ReceiptSpendDomainServiceImpl(spendDao, FakeTransactionRunner()),
            receiptSpendDao = spendDao,
            inventoryDao = FakeInventoryDao(),
            shoppingDao = FakeShoppingDao(),
            inventoryRepository = repository
        )

        val candidates = vm.uiState.value.linkCandidates
        assertEquals(listOf("White bread"), candidates.filter { it.type == ReceiptLinkType.INVENTORY }.map { it.displayName })
    }
}
