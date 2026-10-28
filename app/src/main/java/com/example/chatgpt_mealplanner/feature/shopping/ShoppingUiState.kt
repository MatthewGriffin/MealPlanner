package com.example.chatgpt_mealplanner.feature.shopping

import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.ShoppingItemStatus
import com.example.chatgpt_mealplanner.core.model.ShoppingListItemWithSources
import com.example.chatgpt_mealplanner.core.model.ShoppingListWithItems
import com.example.chatgpt_mealplanner.core.model.StapleRule
import com.example.chatgpt_mealplanner.retail.contract.BasketComparisonResult
import com.example.chatgpt_mealplanner.retail.contract.BasketLine
import com.example.chatgpt_mealplanner.retail.contract.Money
import com.example.chatgpt_mealplanner.retail.contract.PackAlternative
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import com.example.chatgpt_mealplanner.core.database.entity.PurchasePriceWithStore

enum class ShoppingTab(val displayName: String) {
    SHOPPING_LIST("Shopping List"),
    STAPLE_RULES("Staple Rules")
}

data class RetailerGroup(
    val retailerId: RetailerId?,
    val retailerName: String,
    val subtotal: Money?,
    val items: List<ShoppingListItemWithSources>,
    val lineByItemId: Map<String, BasketLine> = emptyMap()
)

data class ShoppingUiState(
    val activeListWithItems: ShoppingListWithItems? = null,
    val isFollower: Boolean = false,
    val stapleRules: List<StapleRule> = emptyList(),
    val activeTab: ShoppingTab = ShoppingTab.SHOPPING_LIST,
    val isLoading: Boolean = false,
    val userMessage: String? = null,
    val selectedItemForDetail: ShoppingListItemWithSources? = null,
    val selectedItemOptions: List<PackAlternative> = emptyList(),
    val isLoadingItemOptions: Boolean = false,
    val showAddManualDialog: Boolean = false,
    val basketComparisonResult: BasketComparisonResult? = null,
    val retailLookupMessage: String? = null,
    val localPriceMatches: Map<String, List<PurchasePriceWithStore>> = emptyMap(),
    val retailerGroups: List<RetailerGroup> = emptyList(),
    val manualItemName: String = "",
    val manualItemNotes: String = ""
)

sealed interface ShoppingUiEvent {
    data class SelectTab(val tab: ShoppingTab) : ShoppingUiEvent
    object RegenerateShoppingList : ShoppingUiEvent
    object ClearCompletedItems : ShoppingUiEvent
    data class ToggleItemChecked(val itemId: String, val currentStatus: ShoppingItemStatus) : ShoppingUiEvent
    data class RemoveItem(val itemId: String) : ShoppingUiEvent
    data class SelectItemDetail(val itemWithSources: ShoppingListItemWithSources?) : ShoppingUiEvent
    data class ReassignItemRetailer(val itemId: String, val retailerId: RetailerId) : ShoppingUiEvent
    object RequestAddManualItem : ShoppingUiEvent
    data class RequestLowStockShortcut(val item: InventoryItem) : ShoppingUiEvent
    object DismissAddManualDialog : ShoppingUiEvent
    data class AddManualItem(val name: String, val quantity: Double?, val unit: QuantityUnit?, val notes: String?) : ShoppingUiEvent
    data class ResolveUncertainty(val inventoryItemId: String, val confirmedQuantity: Double, val unit: QuantityUnit, val confidence: QuantityConfidence = QuantityConfidence.EXACT) : ShoppingUiEvent
    data class SaveStapleRule(val rule: StapleRule) : ShoppingUiEvent
    data class DeleteStapleRule(val ruleId: String) : ShoppingUiEvent
    object ClearUserMessage : ShoppingUiEvent
}
