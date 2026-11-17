package com.example.chatgpt_mealplanner.feature.scanner

import com.example.chatgpt_mealplanner.core.util.rethrowIfCancelled
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatgpt_mealplanner.core.barcode.model.ConfirmedProductInput
import com.example.chatgpt_mealplanner.core.barcode.model.ProductDataSource
import com.example.chatgpt_mealplanner.core.barcode.model.ProductLookupResult
import com.example.chatgpt_mealplanner.core.barcode.model.ProductMetadata
import com.example.chatgpt_mealplanner.core.barcode.repository.ProductLookupRepository
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.database.dao.PurchasePriceDao
import com.example.chatgpt_mealplanner.core.database.entity.PurchasePriceObservationEntity
import com.example.chatgpt_mealplanner.core.database.entity.UserStoreEntity
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.service.InventoryDomainService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.UUID
import kotlin.math.roundToLong

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val productLookupRepository: ProductLookupRepository,
    private val inventoryDomainService: InventoryDomainService,
    private val purchasePriceDao: PurchasePriceDao? = null
) : ViewModel() {

    private val _state = MutableStateFlow<ScannerState>(ScannerState.RequestingPermission)
    val state: StateFlow<ScannerState> = _state.asStateFlow()
    private val _userStores = MutableStateFlow<List<UserStoreEntity>>(emptyList())
    val userStores: StateFlow<List<UserStoreEntity>> = _userStores.asStateFlow()

    init {
        purchasePriceDao?.let { dao ->
            viewModelScope.launch {
                dao.observeActiveStores().collect { stores -> _userStores.value = stores }
            }
        }
    }

    private var lastScannedBarcode: String? = null
    private var lastScannedTimestamp: Long = 0L

    companion object {
        const val DEBOUNCE_INTERVAL_MS = 2000L
    }

    fun onEvent(event: ScannerUiEvent) {
        when (event) {
            ScannerUiEvent.PermissionGranted -> {
                _state.value = ScannerState.Ready
            }
            ScannerUiEvent.PermissionDenied -> {
                _state.value = ScannerState.PermissionDenied
            }
            is ScannerUiEvent.BarcodeScanned -> {
                onBarcodeScanned(event.barcode)
            }
            is ScannerUiEvent.ConfirmProduct -> {
                saveConfirmedProduct(event.input)
            }
            ScannerUiEvent.ScanAnother -> {
                resetScanner()
            }
            ScannerUiEvent.ManualAddRequested -> {
                openManualAdd()
            }
            ScannerUiEvent.ClearError -> {
                _state.value = ScannerState.Ready
            }
        }
    }

    private fun onBarcodeScanned(barcode: String) {
        val now = System.currentTimeMillis()
        if (barcode == lastScannedBarcode && (now - lastScannedTimestamp) < DEBOUNCE_INTERVAL_MS) {
            return // Ignore duplicate scan within debounce window
        }

        lastScannedBarcode = barcode
        lastScannedTimestamp = now

        _state.value = ScannerState.LookingUp(barcode)

        viewModelScope.launch {
            when (val result = productLookupRepository.lookupBarcode(barcode)) {
                is ProductLookupResult.Found -> {
                    _state.value = ScannerState.Confirming(
                        barcode = barcode,
                        editableMetadata = result.metadata
                    )
                }
                is ProductLookupResult.NotFound -> {
                    _state.value = ScannerState.NotFound(barcode)
                }
                is ProductLookupResult.Error -> {
                    _state.value = ScannerState.Error(
                        barcode = barcode,
                        message = "Lookup failed. You can add this item manually."
                    )
                }
            }
        }
    }

    private fun saveConfirmedProduct(input: ConfirmedProductInput) {
        // Idempotency check: Guard against rapid double taps
        if (_state.value is ScannerState.Saving || _state.value is ScannerState.Saved) {
            return
        }

        _state.value = ScannerState.Saving(input)

        viewModelScope.launch {
            // 1. Add physical lot in Room database via Domain Service
            when (val lotResult = inventoryDomainService.addLotFromProductInput(input)) {
                is DomainResult.Success -> {
                    // 2. Cache confirmed mapping locally for future instant offline lookups
                    if (!input.barcode.isNullOrBlank()) {
                        val currentLookup = (_state.value as? ScannerState.Confirming)?.editableMetadata
                        val mappingToCache = ProductMetadata(
                            barcode = input.barcode,
                            displayName = currentLookup?.displayName ?: input.displayName,
                            canonicalName = input.displayName,
                            brand = input.brand,
                            canonicalIngredientId = input.canonicalIngredientId,
                            category = input.category,
                            suggestedStorageLocation = input.storageLocation,
                            netQuantity = input.netQuantity,
                            netQuantityUnit = input.netQuantityUnit,
                            packCount = input.packCount,
                            packSize = input.packSize,
                            packSizeUnit = input.packSizeUnit,
                            source = currentLookup?.source ?: ProductDataSource.MANUAL
                        )
                        productLookupRepository.saveBarcodeMapping(mappingToCache)
                    }

                    recordPurchasePriceIfProvided(input)
                    _state.value = ScannerState.Saved(lotResult.value)
                }
                is DomainResult.Failure -> {
                    _state.value = ScannerState.Error(
                        barcode = input.barcode,
                        message = "Failed to save item to inventory"
                    )
                }
            }
        }
    }

    private suspend fun recordPurchasePriceIfProvided(input: ConfirmedProductInput) {
        val storeId = input.purchaseStoreId?.takeIf { it.isNotBlank() } ?: return
        val priceText = input.purchasePriceText?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val pricePounds = priceText.replace(",", ".").toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 } ?: return
        val dao = purchasePriceDao ?: return
        try {
            val store = dao.getStore(storeId) ?: return
            if (store.isArchived) return
            val currency = com.example.chatgpt_mealplanner.core.util.MoneyFormat.defaultCurrencyCode()
            val priceMinorUnits = com.example.chatgpt_mealplanner.core.util.MoneyFormat.toMinorUnits(pricePounds, currency)
            dao.insertPriceObservation(
                PurchasePriceObservationEntity(
                    id = UUID.randomUUID().toString(),
                    storeId = storeId,
                    barcode = input.barcode?.trim()?.takeIf { it.isNotEmpty() },
                    productName = input.displayName.trim(),
                    brand = input.brand?.trim()?.takeIf { it.isNotEmpty() },
                    packQuantity = if ((input.packCount ?: 1) > 1) input.packSize ?: input.netQuantity else input.netQuantity ?: input.packSize,
                    packUnit = (if ((input.packCount ?: 1) > 1) input.packSizeUnit ?: input.netQuantityUnit else input.netQuantityUnit ?: input.packSizeUnit)?.name,
                    packCount = input.packCount,
                    priceMinorUnits = priceMinorUnits,
                    currencyCode = currency,
                    purchasedAt = System.currentTimeMillis(),
                    createdAt = System.currentTimeMillis(),
                    source = "SCANNER",
                    canonicalName = (input.canonicalIngredientId ?: input.canonicalName)?.trim()?.takeIf { it.isNotEmpty() }
                )
            )
        } catch (_: Exception) {
            rethrowIfCancelled()
            // Inventory save must not fail because an optional price-history write failed.
        }
    }

    private fun openManualAdd() {
        val defaultManualMetadata = ProductMetadata(
            barcode = lastScannedBarcode ?: "",
            displayName = null,
            suggestedStorageLocation = StorageLocation.CUPBOARD,
            source = ProductDataSource.MANUAL,
            confidence = QuantityConfidence.EXACT.let { com.example.chatgpt_mealplanner.core.barcode.model.ProductMatchConfidence.EXACT }
        )
        _state.value = ScannerState.Confirming(
            barcode = lastScannedBarcode,
            editableMetadata = defaultManualMetadata
        )
    }

    private fun resetScanner() {
        lastScannedBarcode = null
        lastScannedTimestamp = 0L
        _state.value = ScannerState.Ready
    }
}
