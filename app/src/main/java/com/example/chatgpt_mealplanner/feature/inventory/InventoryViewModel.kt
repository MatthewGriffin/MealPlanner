package com.example.chatgpt_mealplanner.feature.inventory

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.chatgpt_mealplanner.core.ai.provider.MealPlannerAiProvider
import com.example.chatgpt_mealplanner.core.ai.provider.PantryListResult
import com.example.chatgpt_mealplanner.core.barcode.BarcodeLookupCoordinator
import com.example.chatgpt_mealplanner.core.barcode.model.ConfirmedProductInput
import com.example.chatgpt_mealplanner.core.barcode.BarcodeLookupResult
import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.repository.InventoryRepository
import com.example.chatgpt_mealplanner.core.service.InventoryDomainService
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusRepository
import com.example.chatgpt_mealplanner.core.sync.follower.worker.FollowerSyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class InventoryViewModel @Inject constructor(
    private val inventoryRepository: InventoryRepository,
    private val inventoryDomainService: InventoryDomainService,
    @param:ApplicationContext private val context: Context? = null,
    private val followerPairingStore: FollowerPairingStore? = null,
    private val followerSyncStatusRepository: FollowerSyncStatusRepository? = null,
    private val followerSyncScheduler: FollowerSyncScheduler? = null,
    private val followerDatabase: FollowerDatabase? = null,
    private val barcodeLookupCoordinator: BarcodeLookupCoordinator? = null,
    private val aiProvider: MealPlannerAiProvider? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(InventoryUiState())
    val uiState: StateFlow<InventoryUiState> = _uiState.asStateFlow()

    private var workObserverJob: kotlinx.coroutines.Job? = null

    init {
        loadInventory()
        observeWorkManagerAndSyncStatus()
    }

    suspend fun searchProductsByName(query: String): List<BarcodeLookupResult> {
        val coordinator = barcodeLookupCoordinator ?: return emptyList()
        return coordinator.searchProductsByName(query)
    }

    fun triggerManualSync() {
        val scheduler = followerSyncScheduler ?: return
        val shouldEnqueue = scheduler.recordManualSyncIntent()
        if (!shouldEnqueue) {
            return
        }
        viewModelScope.launch {
            scheduler.scheduleSyncWorkAsync(context, "USER_MANUAL_TRIGGER", intentAlreadyRecorded = true)
        }
    }

    private fun observeWorkManagerAndSyncStatus() {
        workObserverJob?.cancel()
        val store = followerPairingStore ?: return
        if (!store.isPaired()) {
            _uiState.update { it.copy(isFollowerPaired = false, followerSyncStatus = null) }
            return
        }

        workObserverJob = viewModelScope.launch {
            val dao = followerDatabase?.followerDao()
            val ctx = context
            val workManager = try { if (ctx != null) WorkManager.getInstance(ctx) else null } catch (_: Exception) { null }

            if (workManager != null) {
                workManager.getWorkInfosForUniqueWorkFlow(FollowerSyncScheduler.WORK_NAME)
                    .collect { workInfos ->
                        val isWorkerRunning = workInfos.any { it.state == WorkInfo.State.RUNNING }
                        val status = followerSyncStatusRepository?.calculateStatus(dao, isWorkerRunning)
                        _uiState.update { it.copy(isFollowerPaired = true, followerSyncStatus = status) }
                    }
            } else {
                val status = followerSyncStatusRepository?.calculateStatus(dao, false)
                _uiState.update { it.copy(isFollowerPaired = true, followerSyncStatus = status) }
            }
        }
    }

    private fun loadInventory() {
        viewModelScope.launch {
            try {
                inventoryDomainService.repairOrphanStapleRulesFromMergeLogs()
            } catch (_: Exception) {}

            inventoryRepository.getAllItems().collect { items ->
                _uiState.update { currentState ->
                    val filtered = filterItems(items, currentState.activeFilter, currentState.searchQuery)
                    currentState.copy(
                        rawItems = items,
                        filteredItems = filtered,
                        isLoading = false
                    )
                }
            }
        }
    }

    fun onEvent(event: InventoryUiEvent) {
        when (event) {
            InventoryUiEvent.TriggerManualSync -> {
                triggerManualSync()
            }
            is InventoryUiEvent.SearchQueryChanged -> {
                _uiState.update { currentState ->
                    val filtered = filterItems(currentState.rawItems, currentState.activeFilter, event.query)
                    currentState.copy(searchQuery = event.query, filteredItems = filtered)
                }
            }
            is InventoryUiEvent.SelectFilter -> {
                _uiState.update { currentState ->
                    val filtered = filterItems(currentState.rawItems, event.filter, currentState.searchQuery)
                    currentState.copy(activeFilter = event.filter, filteredItems = filtered)
                }
            }
            is InventoryUiEvent.AddItemRequested -> {
                addItem(event.item)
            }
            is InventoryUiEvent.UpdateItemRequested -> {
                updateItem(event.item)
            }
            is InventoryUiEvent.ToggleStaple -> {
                toggleStaple(event.itemId)
            }
            is InventoryUiEvent.SetStaple -> {
                setStaple(event.itemId, event.isStaple)
            }
            is InventoryUiEvent.RecordCorrection -> {
                recordCorrection(event.itemId, event.newQuantity, event.newUnit, event.confidence, event.note)
            }
            is InventoryUiEvent.MarkAsOut -> {
                markAsOut(event.itemId)
            }
            is InventoryUiEvent.DiscardItem -> {
                discardItem(event.itemId, event.reason)
            }
            is InventoryUiEvent.SelectItemForEdit -> {
                _uiState.update { it.copy(selectedItemForEdit = event.item) }
            }
            is InventoryUiEvent.MergeRequested -> {
                mergeItems(event.survivorItemId, event.mergedItemId, event.preferredDisplayName)
            }
            is InventoryUiEvent.UnmergeRequested -> {
                unmergeItems(event.mergeLogId, event.survivorStockAllocation, event.restoredStockAllocation)
            }
            is InventoryUiEvent.AssociateBarcodeRequested -> {
                associateBarcode(event.itemId, event.rawBarcode)
            }
            is InventoryUiEvent.RemoveBarcodeRequested -> {
                removeBarcode(event.itemId, event.rawBarcode)
            }
            InventoryUiEvent.OpenBulkAdd -> _uiState.update { it.copy(bulkAdd = BulkAddState()) }
            InventoryUiEvent.CloseBulkAdd -> _uiState.update { it.copy(bulkAdd = null) }
            is InventoryUiEvent.BulkAddTextChanged -> updateBulk { it.copy(text = event.text, error = null) }
            InventoryUiEvent.EditBulkAddList -> updateBulk { it.copy(rows = emptyList(), error = null) }
            InventoryUiEvent.ReadBulkAddList -> readBulkAddList()
            is InventoryUiEvent.BulkAddRowChanged -> updateBulk { bulk ->
                bulk.copy(rows = bulk.rows.map { if (it.id == event.row.id) event.row else it })
            }
            is InventoryUiEvent.BulkAddRowRemoved -> updateBulk { bulk -> bulk.copy(rows = bulk.rows.filterNot { it.id == event.rowId }) }
            InventoryUiEvent.SaveBulkAddRows -> saveBulkAddRows()
            InventoryUiEvent.ClearMessage -> {
                _uiState.update { it.copy(userMessage = null) }
            }
        }
    }

    private fun updateBulk(change: (BulkAddState) -> BulkAddState) {
        _uiState.update { state -> state.bulkAdd?.let { state.copy(bulkAdd = change(it)) } ?: state }
    }

    private fun readBulkAddList() {
        val bulk = _uiState.value.bulkAdd ?: return
        if (bulk.isReading || bulk.text.isBlank()) return
        val provider = aiProvider
        if (provider == null) {
            updateBulk { it.copy(error = "Reading a list needs the AI, which isn't available here.") }
            return
        }
        updateBulk { it.copy(isReading = true, error = null) }
        viewModelScope.launch {
            val result = provider.parsePantryList(bulk.text)
            updateBulk { current ->
                when (result) {
                    is PantryListResult.Failed -> current.copy(isReading = false, error = result.message)
                    is PantryListResult.Items -> current.copy(
                        isReading = false,
                        rows = result.items.map { item ->
                            BulkAddRow(
                                id = java.util.UUID.randomUUID().toString(),
                                displayName = item.displayName,
                                canonicalName = item.canonicalName,
                                brand = item.brand,
                                category = item.category,
                                storageLocation = item.storageLocation,
                                quantityText = formatBulkQuantity(item.quantity),
                                unit = item.unit,
                                quantityGuessed = item.quantityGuessed
                            )
                        }
                    )
                }
            }
        }
    }

    private fun saveBulkAddRows() {
        val bulk = _uiState.value.bulkAdd ?: return
        if (bulk.isSaving || bulk.rows.isEmpty()) return
        updateBulk { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            val failed = mutableListOf<BulkAddRow>()
            var saved = 0
            bulk.rows.forEach { row ->
                val quantity = row.quantityText.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }
                val name = row.displayName.trim()
                if (quantity == null || name.isEmpty()) {
                    failed += row
                    return@forEach
                }
                val input = ConfirmedProductInput(
                    barcode = null,
                    displayName = name,
                    brand = row.brand,
                    canonicalName = row.canonicalName.ifBlank { name.lowercase() },
                    category = row.category,
                    storageLocation = row.storageLocation,
                    netQuantity = quantity,
                    netQuantityUnit = row.unit,
                    quantityConfidence = if (row.quantityGuessed) QuantityConfidence.APPROXIMATE else QuantityConfidence.EXACT
                )
                when (inventoryDomainService.addLotFromProductInput(input)) {
                    is DomainResult.Success -> saved++
                    is DomainResult.Failure -> failed += row
                }
            }
            _uiState.update { state ->
                state.copy(
                    bulkAdd = if (failed.isEmpty()) null else state.bulkAdd?.copy(
                        isSaving = false,
                        rows = failed,
                        error = "${failed.size} item${if (failed.size == 1) "" else "s"} couldn't be saved. Check the amount and name, then try again."
                    ),
                    userMessage = if (saved > 0) "Added $saved item${if (saved == 1) "" else "s"}" else state.userMessage
                )
            }
        }
    }

    private fun formatBulkQuantity(quantity: Double): String =
        if (quantity == Math.floor(quantity) && quantity < 1_000_000) quantity.toLong().toString() else quantity.toString()

    private fun addItem(item: InventoryItem) {
        viewModelScope.launch {
            when (val result = inventoryDomainService.addItem(item)) {
                is DomainResult.Success -> {
                    _uiState.update { it.copy(userMessage = "Item added successfully") }
                }
                is DomainResult.Failure -> {
                    _uiState.update { it.copy(userMessage = formatErrorMessage(result.error)) }
                }
            }
        }
    }

    private fun updateItem(item: InventoryItem) {
        viewModelScope.launch {
            when (val result = inventoryDomainService.updateItem(item)) {
                is DomainResult.Success -> {
                    _uiState.update { it.copy(userMessage = "Item updated successfully", selectedItemForEdit = null) }
                }
                is DomainResult.Failure -> {
                    _uiState.update { it.copy(userMessage = formatErrorMessage(result.error)) }
                }
            }
        }
    }

    private fun toggleStaple(itemId: String) {
        viewModelScope.launch {
            when (val result = inventoryDomainService.toggleStapleStatus(itemId)) {
                is DomainResult.Success -> {
                    val statusText = if (result.value) "Marked as staple" else "Removed from staples"
                    _uiState.update { it.copy(userMessage = statusText) }
                }
                is DomainResult.Failure -> {
                    _uiState.update { it.copy(userMessage = formatErrorMessage(result.error)) }
                }
            }
        }
    }

    private fun setStaple(itemId: String, isStaple: Boolean) {
        viewModelScope.launch {
            when (val result = inventoryDomainService.setStapleStatus(itemId, isStaple)) {
                is DomainResult.Success -> {
                    val statusText = if (result.value) "Marked as staple" else "Removed from staples"
                    _uiState.update { it.copy(userMessage = statusText) }
                }
                is DomainResult.Failure -> {
                    _uiState.update { it.copy(userMessage = formatErrorMessage(result.error)) }
                }
            }
        }
    }

    private fun recordCorrection(
        itemId: String,
        newQuantity: Double?,
        newUnit: QuantityUnit? = null,
        confidence: QuantityConfidence,
        note: String?
    ) {
        viewModelScope.launch {
            when (val result = inventoryDomainService.recordUserCorrection(
                itemId = itemId,
                newQuantity = newQuantity,
                newUnit = newUnit,
                newConfidence = confidence,
                note = note
            )) {
                is DomainResult.Success -> {
                    _uiState.update { it.copy(userMessage = "Correction applied successfully") }
                }
                is DomainResult.Failure -> {
                    _uiState.update { it.copy(userMessage = formatErrorMessage(result.error)) }
                }
            }
        }
    }

    private fun markAsOut(itemId: String) {
        viewModelScope.launch {
            when (val result = inventoryDomainService.markAsOut(itemId)) {
                is DomainResult.Success -> {
                    _uiState.update { it.copy(userMessage = "Item marked as Out of Stock") }
                }
                is DomainResult.Failure -> {
                    _uiState.update { it.copy(userMessage = formatErrorMessage(result.error)) }
                }
            }
        }
    }

    private fun discardItem(itemId: String, reason: String?) {
        viewModelScope.launch {
            when (val result = inventoryDomainService.discardItem(itemId, reason)) {
                is DomainResult.Success -> {
                    _uiState.update { it.copy(userMessage = "Item discarded") }
                }
                is DomainResult.Failure -> {
                    _uiState.update { it.copy(userMessage = formatErrorMessage(result.error)) }
                }
            }
        }
    }

    private fun mergeItems(survivorItemId: String, mergedItemId: String, preferredDisplayName: String?) {
        viewModelScope.launch {
            when (val result = inventoryDomainService.mergeInventoryItems(survivorItemId, mergedItemId, preferredDisplayName)) {
                is DomainResult.Success -> {
                    _uiState.update { it.copy(userMessage = "Items successfully merged into '${result.value.displayName}'") }
                }
                is DomainResult.Failure -> {
                    _uiState.update { it.copy(userMessage = formatErrorMessage(result.error)) }
                }
            }
        }
    }

    private fun unmergeItems(mergeLogId: String, survivorStockAllocation: Double?, restoredStockAllocation: Double?) {
        viewModelScope.launch {
            when (val result = inventoryDomainService.unmergeInventoryItems(mergeLogId, survivorStockAllocation, restoredStockAllocation)) {
                is DomainResult.Success -> {
                    _uiState.update { it.copy(userMessage = "Items successfully unmerged") }
                }
                is DomainResult.Failure -> {
                    _uiState.update { it.copy(userMessage = formatErrorMessage(result.error)) }
                }
            }
        }
    }

    private fun associateBarcode(itemId: String, rawBarcode: String) {
        viewModelScope.launch {
            when (val result = inventoryDomainService.associateBarcode(itemId, rawBarcode)) {
                is DomainResult.Success -> {
                    _uiState.update { it.copy(userMessage = "Barcode associated successfully") }
                }
                is DomainResult.Failure -> {
                    _uiState.update { it.copy(userMessage = formatErrorMessage(result.error)) }
                }
            }
        }
    }

    private fun removeBarcode(itemId: String, rawBarcode: String) {
        viewModelScope.launch {
            when (val result = inventoryDomainService.removeBarcodeAssociation(itemId, rawBarcode)) {
                is DomainResult.Success -> {
                    _uiState.update { it.copy(userMessage = "Barcode association removed") }
                }
                is DomainResult.Failure -> {
                    _uiState.update { it.copy(userMessage = formatErrorMessage(result.error)) }
                }
            }
        }
    }

    private fun filterItems(items: List<InventoryItem>, filter: InventoryFilter, query: String): List<InventoryItem> {
        var result = items
        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            result = result.filter { it.displayName.lowercase().contains(q) || it.canonicalName.lowercase().contains(q) || (it.barcode?.contains(q) == true) }
        }
        if (filter != InventoryFilter.OUT) {
            result = result.filter { it.status != com.example.chatgpt_mealplanner.core.model.ItemStatus.OUT }
        }
        result = when (filter) {
            InventoryFilter.ALL -> result.filter { it.status != com.example.chatgpt_mealplanner.core.model.ItemStatus.DISCARDED }
            InventoryFilter.CUPBOARD -> result.filter { it.storageLocation == com.example.chatgpt_mealplanner.core.model.StorageLocation.CUPBOARD && it.status != com.example.chatgpt_mealplanner.core.model.ItemStatus.DISCARDED }
            InventoryFilter.FRIDGE -> result.filter { it.storageLocation == com.example.chatgpt_mealplanner.core.model.StorageLocation.FRIDGE && it.status != com.example.chatgpt_mealplanner.core.model.ItemStatus.DISCARDED }
            InventoryFilter.FREEZER -> result.filter { it.storageLocation == com.example.chatgpt_mealplanner.core.model.StorageLocation.FREEZER && it.status != com.example.chatgpt_mealplanner.core.model.ItemStatus.DISCARDED }
            InventoryFilter.USE_SOON -> result.filter { it.status == com.example.chatgpt_mealplanner.core.model.ItemStatus.USE_SOON }
            InventoryFilter.LOW -> result.filter { it.status == com.example.chatgpt_mealplanner.core.model.ItemStatus.LOW }
            InventoryFilter.OUT -> result.filter { it.status == com.example.chatgpt_mealplanner.core.model.ItemStatus.OUT }
        }
        return result.sortedWith(
            compareBy<InventoryItem> { it.useByDate ?: it.bestBeforeDate ?: "9999-12-31" }
                .thenBy { it.displayName.lowercase() }
        )
    }

    private fun formatErrorMessage(error: DomainError): String {
        return when (error) {
            is DomainError.ItemNotFound -> "Item not found"
            is DomainError.DuplicateOperation -> error.message
            is DomainError.InsufficientQuantity -> "Insufficient quantity available"
            is DomainError.InvalidQuantity -> "Invalid quantity"
            is DomainError.ItemNotUsable -> "Item is not usable (${error.status})"
            else -> "Operation failed"
        }
    }
}
