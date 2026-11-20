package com.example.chatgpt_mealplanner.feature.scanner

import com.example.chatgpt_mealplanner.core.barcode.model.ConfirmedProductInput
import com.example.chatgpt_mealplanner.core.barcode.model.ProductLookupResult
import com.example.chatgpt_mealplanner.core.barcode.model.ProductMetadata
import com.example.chatgpt_mealplanner.core.barcode.repository.ProductLookupRepository
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.service.InventoryDomainService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class ScannerViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val mockRepository: ProductLookupRepository = mock()
    private val mockDomainService: InventoryDomainService = mock()

    private lateinit var viewModel: ScannerViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = ScannerViewModel(mockRepository, mockDomainService)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun permissionGranted_setsReadyState() = runTest {
        viewModel.onEvent(ScannerUiEvent.PermissionGranted)
        assertEquals(ScannerState.Ready, viewModel.state.value)
    }

    @Test
    fun barcodeScanned_triggersRepositoryLookupAndConfirmingState() = runTest {
        val barcode = "5000159461122"
        val metadata = ProductMetadata(
            barcode = barcode,
            displayName = "Mars Bar",
            category = FoodCategory.SNACKS,
            suggestedStorageLocation = StorageLocation.CUPBOARD
        )
        whenever(mockRepository.lookupBarcode(barcode))
            .thenReturn(ProductLookupResult.Found(metadata, isFromCache = false))

        viewModel.onEvent(ScannerUiEvent.BarcodeScanned(barcode))
        testDispatcher.scheduler.advanceUntilIdle()

        val currentState = viewModel.state.value
        assertTrue(currentState is ScannerState.Confirming)
        val confirming = currentState as ScannerState.Confirming
        assertEquals(barcode, confirming.barcode)
        assertEquals("Mars Bar", confirming.editableMetadata.displayName)
    }

    @Test
    fun duplicateBarcodeScannedWithinDebounce_triggersOnlyOneLookup() = runTest {
        val barcode = "5000159461122"
        val metadata = ProductMetadata(
            barcode = barcode,
            displayName = "Mars Bar",
            category = FoodCategory.SNACKS,
            suggestedStorageLocation = StorageLocation.CUPBOARD
        )
        whenever(mockRepository.lookupBarcode(barcode))
            .thenReturn(ProductLookupResult.Found(metadata, isFromCache = false))

        // Trigger 3 consecutive frame scans immediately
        viewModel.onEvent(ScannerUiEvent.BarcodeScanned(barcode))
        viewModel.onEvent(ScannerUiEvent.BarcodeScanned(barcode))
        viewModel.onEvent(ScannerUiEvent.BarcodeScanned(barcode))
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify repository lookup was called EXACTLY ONCE
        verify(mockRepository, times(1)).lookupBarcode(barcode)
    }

    @Test
    fun confirmProduct_savesLotViaDomainServiceAndCachesMapping() = runTest {
        val input = ConfirmedProductInput(
            barcode = "5012345678901",
            displayName = "Basmati Rice",
            brand = "Tilda",
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD,
            netQuantity = 1000.0,
            netQuantityUnit = com.example.chatgpt_mealplanner.core.model.QuantityUnit.GRAM,
            quantityConfidence = QuantityConfidence.EXACT
        )
        val createdItem = InventoryItem(
            id = "item_101",
            canonicalName = "Basmati Rice",
            displayName = "Basmati Rice",
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD
        )

        whenever(mockDomainService.addLotFromProductInput(input))
            .thenReturn(DomainResult.Success(createdItem))

        viewModel.onEvent(ScannerUiEvent.ConfirmProduct(input))
        testDispatcher.scheduler.advanceUntilIdle()

        val currentState = viewModel.state.value
        assertTrue(currentState is ScannerState.Saved)
        assertEquals("item_101", (currentState as ScannerState.Saved).item.id)

        // Verify domain service and repository caching called
        verify(mockDomainService).addLotFromProductInput(input)
        verify(mockRepository).saveBarcodeMapping(any())
    }

    @Test
    fun doubleTapSave_isIdempotent_callsDomainServiceOnlyOnce() = runTest {
        val input = ConfirmedProductInput(
            barcode = "5012345678901",
            displayName = "Basmati Rice",
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD
        )
        val createdItem = InventoryItem(
            id = "item_101",
            canonicalName = "Basmati Rice",
            displayName = "Basmati Rice",
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD
        )

        whenever(mockDomainService.addLotFromProductInput(input))
            .thenReturn(DomainResult.Success(createdItem))

        // Simulate rapid double tap
        viewModel.onEvent(ScannerUiEvent.ConfirmProduct(input))
        viewModel.onEvent(ScannerUiEvent.ConfirmProduct(input))
        testDispatcher.scheduler.advanceUntilIdle()

        verify(mockDomainService, times(1)).addLotFromProductInput(input)
    }

    @Test
    fun confirmProduct_failure_transitionsToErrorState_doesNotSaveOrReturn() = runTest {
        val input = ConfirmedProductInput(
            barcode = "5012345678901",
            displayName = "Basmati Rice",
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD
        )

        whenever(mockDomainService.addLotFromProductInput(input))
            .thenReturn(DomainResult.Failure(com.example.chatgpt_mealplanner.core.model.DomainError.TransactionFailed("DB write failed")))

        viewModel.onEvent(ScannerUiEvent.ConfirmProduct(input))
        testDispatcher.scheduler.advanceUntilIdle()

        val currentState = viewModel.state.value
        assertTrue("Failure MUST transition to Error state, NOT Saved state", currentState is ScannerState.Error)
        assertEquals("Failed to save item to inventory", (currentState as ScannerState.Error).message)
    }

    @Test
    fun scanAnother_resetsScannerStateToReady() = runTest {
        val input = ConfirmedProductInput(
            barcode = "5012345678901",
            displayName = "Basmati Rice",
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD
        )
        val createdItem = InventoryItem(
            id = "item_101",
            canonicalName = "Basmati Rice",
            displayName = "Basmati Rice",
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD
        )

        whenever(mockDomainService.addLotFromProductInput(input))
            .thenReturn(DomainResult.Success(createdItem))

        viewModel.onEvent(ScannerUiEvent.ConfirmProduct(input))
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.state.value is ScannerState.Saved)

        viewModel.onEvent(ScannerUiEvent.ScanAnother)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("ScanAnother MUST reset state to Ready for subsequent scans", ScannerState.Ready, viewModel.state.value)
    }

    @Test
    fun scansWhileAResultIsShowing_areIgnoredUntilTheScannerIsReadyAgain() = runTest {
        val barcode = "8908001017046"
        whenever(mockRepository.lookupBarcode(barcode)).thenReturn(ProductLookupResult.NotFound(barcode))

        viewModel.onEvent(ScannerUiEvent.BarcodeScanned(barcode))
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value is ScannerState.NotFound)

        // The camera keeps seeing the same pack: the result must stay put.
        viewModel.onEvent(ScannerUiEvent.BarcodeScanned(barcode))
        viewModel.onEvent(ScannerUiEvent.BarcodeScanned("5000159461122"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value is ScannerState.NotFound)
        verify(mockRepository, times(1)).lookupBarcode(barcode)

        viewModel.onEvent(ScannerUiEvent.ScanAnother)
        assertEquals(ScannerState.Ready, viewModel.state.value)
    }
}
