package com.example.chatgpt_mealplanner.feature.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.repository.InventoryRepository
import com.example.chatgpt_mealplanner.core.service.InventoryDomainService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class InventoryViewModel @Inject constructor(
    private val inventoryRepository: InventoryRepository,
    private val inventoryDomainService: InventoryDomainService
) : ViewModel() {

    private val _uiState = MutableStateFlow(InventoryUiState(isLoading = true))
    val uiState: StateFlow<InventoryUiState> = _uiState.asStateFlow()

    private val _filter = MutableStateFlow(InventoryFilter.ALL)
    private val _searchQuery = MutableStateFlow("")

    init {
        combine(
            inventoryRepository.getAllItems(),
            _filter,
            _searchQuery
        ) { rawItems, filter, query ->
            val filtered = filterItems(rawItems, filter, query)
            _uiState.update {
                it.copy(
                    rawItems = rawItems,
                    filteredItems = filtered,
                    activeFilter = filter,
                    searchQuery = query,
                    isLoading = false
                )
            }
        }.launchIn(viewModelScope)
    }

    fun onEvent(event: InventoryUiEvent) {
        when (event) {
            is InventoryUiEvent.SelectFilter -> {
                _filter.value = event.filter
            }
            is InventoryUiEvent.SearchQueryChanged -> {
                _searchQuery.value = event.query
            }
            is InventoryUiEvent.AddItemRequested -> {
                addItem(event.item)
            }
            is InventoryUiEvent.UpdateItemRequested -> {
                updateItem(event.item)
            }
            is InventoryUiEvent.RecordCorrection -> {
                recordCorrection(event)
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
            InventoryUiEvent.ClearMessage -> {
                _uiState.update { it.copy(userMessage = null) }
            }
        }
    }

    private fun addItem(item: InventoryItem) {
        viewModelScope.launch {
            runCatching {
                inventoryDomainService.addItem(item)
            }.onSuccess {
                _uiState.update { it.copy(userMessage = "Added ${item.displayName}") }
            }.onFailure { ex ->
                _uiState.update { it.copy(userMessage = "Failed to add item: ${ex.message}") }
            }
        }
    }

    private fun updateItem(item: InventoryItem) {
        viewModelScope.launch {
            runCatching {
                inventoryDomainService.updateItem(item)
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        userMessage = "Updated ${item.displayName}",
                        selectedItemForEdit = null
                    )
                }
            }.onFailure { ex ->
                _uiState.update { it.copy(userMessage = "Failed to update item: ${ex.message}") }
            }
        }
    }

    private fun recordCorrection(event: InventoryUiEvent.RecordCorrection) {
        viewModelScope.launch {
            runCatching {
                inventoryDomainService.recordUserCorrection(
                    itemId = event.itemId,
                    newQuantity = event.newQuantity,
                    newConfidence = event.confidence,
                    newStatus = event.status,
                    note = event.note
                )
            }.onSuccess {
                _uiState.update { it.copy(userMessage = "Quantity updated") }
            }.onFailure { ex ->
                _uiState.update { it.copy(userMessage = "Failed to record correction: ${ex.message}") }
            }
        }
    }

    private fun markAsOut(itemId: String) {
        viewModelScope.launch {
            runCatching {
                inventoryDomainService.markAsOut(itemId)
            }.onSuccess {
                _uiState.update { it.copy(userMessage = "Item marked as Out") }
            }.onFailure { ex ->
                _uiState.update { it.copy(userMessage = "Failed to mark as out: ${ex.message}") }
            }
        }
    }

    private fun discardItem(itemId: String, reason: String?) {
        viewModelScope.launch {
            runCatching {
                inventoryDomainService.discardItem(itemId, reason)
            }.onSuccess {
                _uiState.update { it.copy(userMessage = "Item marked as Discarded") }
            }.onFailure { ex ->
                _uiState.update { it.copy(userMessage = "Failed to discard item: ${ex.message}") }
            }
        }
    }

    private fun filterItems(
        items: List<InventoryItem>,
        filter: InventoryFilter,
        query: String
    ): List<InventoryItem> {
        return items.filter { item ->
            val matchesFilter = when (filter) {
                InventoryFilter.ALL -> true
                InventoryFilter.CUPBOARD -> item.storageLocation == StorageLocation.CUPBOARD
                InventoryFilter.FRIDGE -> item.storageLocation == StorageLocation.FRIDGE
                InventoryFilter.FREEZER -> item.storageLocation == StorageLocation.FREEZER
                InventoryFilter.USE_SOON -> item.status == ItemStatus.USE_SOON
                InventoryFilter.LOW -> item.status == ItemStatus.LOW
                InventoryFilter.OUT -> item.status == ItemStatus.OUT
            }
            val matchesQuery = query.isBlank() ||
                    item.displayName.contains(query, ignoreCase = true) ||
                    item.canonicalName.contains(query, ignoreCase = true) ||
                    (item.brand?.contains(query, ignoreCase = true) == true)

            matchesFilter && matchesQuery
        }
    }
}
