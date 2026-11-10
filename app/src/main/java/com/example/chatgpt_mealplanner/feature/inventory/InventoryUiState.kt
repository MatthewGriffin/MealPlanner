package com.example.chatgpt_mealplanner.feature.inventory

import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
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
    val followerSyncStatus: com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusModel? = null,
    /** Non-null while the "add many items" screen is open. */
    val bulkAdd: BulkAddState? = null
)

/** One line on the review screen of the bulk add. Every field can be edited before saving. */
data class BulkAddRow(
    val id: String,
    val displayName: String,
    val canonicalName: String,
    val brand: String?,
    val category: FoodCategory,
    val storageLocation: StorageLocation,
    val quantityText: String,
    val unit: QuantityUnit,
    val quantityGuessed: Boolean,
    /** What the user wrote, which the food database was searched for. */
    val searchText: String = displayName,
    /** The name before any product was matched, so "keep as I wrote it" can restore it. */
    val genericName: String = displayName,
    /** Products the food database returned, best guess first. */
    val candidates: List<com.example.chatgpt_mealplanner.core.barcode.BarcodeLookupResult> = emptyList(),
    val matched: com.example.chatgpt_mealplanner.core.barcode.BarcodeLookupResult? = null,
    /** False while Claude wasn't sure which product the user means and the user hasn't chosen yet. */
    val decided: Boolean = true,
    /** The food database was busy or unreachable, so this line wasn't looked up. */
    val lookupFailed: Boolean = false
)

data class BulkAddState(
    val text: String = "",
    val isReading: Boolean = false,
    /** What the read is doing, such as "Looking up products 3 of 15". */
    val progress: String? = null,
    /** Said once after the read, such as how many items the database had nothing for. */
    val lookupNote: String? = null,
    val isSaving: Boolean = false,
    /** Empty until the list has been read. */
    val rows: List<BulkAddRow> = emptyList(),
    val error: String? = null
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
    object OpenBulkAdd : InventoryUiEvent
    object CloseBulkAdd : InventoryUiEvent
    data class BulkAddTextChanged(val text: String) : InventoryUiEvent
    object ReadBulkAddList : InventoryUiEvent
    /** Back from the review lines to the typed list, keeping what was typed. */
    object EditBulkAddList : InventoryUiEvent
    /** Looks up again the lines the food database couldn't answer. */
    object RetryBulkLookup : InventoryUiEvent
    /** Keeps the user's own wording for every line still waiting for a product choice. */
    object KeepWordingForUndecided : InventoryUiEvent
    data class BulkAddRowChanged(val row: BulkAddRow) : InventoryUiEvent
    data class BulkAddRowRemoved(val rowId: String) : InventoryUiEvent
    object SaveBulkAddRows : InventoryUiEvent
    object ClearMessage : InventoryUiEvent
}
