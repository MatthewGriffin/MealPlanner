package com.example.chatgpt_mealplanner.feature.inventory

import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.StorageLocation

enum class InventoryFilter(val displayName: String) {
    ALL("All Items"),
    CUPBOARD("Cupboard"),
    FRIDGE("Fridge"),
    FREEZER("Freezer"),
    USE_SOON("Use Soon"),
    LOW("Low Stock"),
    OUT("Out of Stock")
}

data class InventoryUiState(
    val rawItems: List<InventoryItem> = emptyList(),
    val filteredItems: List<InventoryItem> = emptyList(),
    val activeFilter: InventoryFilter = InventoryFilter.ALL,
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val userMessage: String? = null,
    val selectedItemForEdit: InventoryItem? = null
)

sealed interface InventoryUiEvent {
    data class SelectFilter(val filter: InventoryFilter) : InventoryUiEvent
    data class SearchQueryChanged(val query: String) : InventoryUiEvent
    data class AddItemRequested(val item: InventoryItem) : InventoryUiEvent
    data class UpdateItemRequested(val item: InventoryItem) : InventoryUiEvent
    data class RecordCorrection(
        val itemId: String,
        val newQuantity: Double?,
        val confidence: QuantityConfidence,
        val status: ItemStatus? = null,
        val note: String? = null
    ) : InventoryUiEvent
    data class MarkAsOut(val itemId: String) : InventoryUiEvent
    data class DiscardItem(val itemId: String, val reason: String? = null) : InventoryUiEvent
    data class SelectItemForEdit(val item: InventoryItem?) : InventoryUiEvent
    object ClearMessage : InventoryUiEvent
}
