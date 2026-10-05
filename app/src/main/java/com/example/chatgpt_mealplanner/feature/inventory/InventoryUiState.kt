package com.example.chatgpt_mealplanner.feature.inventory

import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence

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
    val selectedItemForEdit: InventoryItem? = null,
    val isFollowerPaired: Boolean = false,
    val followerSyncStatus: com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusModel? = null
)

sealed interface InventoryUiEvent {
    object TriggerManualSync : InventoryUiEvent
    data class SelectFilter(val filter: InventoryFilter) : InventoryUiEvent
    data class SearchQueryChanged(val query: String) : InventoryUiEvent
    data class AddItemRequested(val item: InventoryItem) : InventoryUiEvent
    data class UpdateItemRequested(val item: InventoryItem) : InventoryUiEvent
    data class ToggleStaple(val itemId: String) : InventoryUiEvent
    data class SetStaple(val itemId: String, val isStaple: Boolean) : InventoryUiEvent
    data class RecordCorrection(
        val itemId: String,
        val newQuantity: Double?,
        val newUnit: com.example.chatgpt_mealplanner.core.model.QuantityUnit? = null,
        val confidence: QuantityConfidence,
        val status: ItemStatus? = null,
        val note: String? = null
    ) : InventoryUiEvent
    data class MarkAsOut(val itemId: String) : InventoryUiEvent
    data class DiscardItem(val itemId: String, val reason: String? = null) : InventoryUiEvent
    data class SelectItemForEdit(val item: InventoryItem?) : InventoryUiEvent
    data class MergeRequested(
        val survivorItemId: String,
        val mergedItemId: String,
        val preferredDisplayName: String? = null
    ) : InventoryUiEvent
    data class UnmergeRequested(
        val mergeLogId: String,
        val survivorStockAllocation: Double? = null,
        val restoredStockAllocation: Double? = null
    ) : InventoryUiEvent
    data class AssociateBarcodeRequested(val itemId: String, val rawBarcode: String) : InventoryUiEvent
    data class RemoveBarcodeRequested(val itemId: String, val rawBarcode: String) : InventoryUiEvent
    object ClearMessage : InventoryUiEvent
}
