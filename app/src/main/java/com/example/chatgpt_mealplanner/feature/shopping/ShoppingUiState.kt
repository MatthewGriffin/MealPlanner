package com.example.chatgpt_mealplanner.feature.shopping

import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.ShoppingItemStatus
import com.example.chatgpt_mealplanner.core.model.ShoppingListItemWithSources
import com.example.chatgpt_mealplanner.core.model.ShoppingListWithItems
import com.example.chatgpt_mealplanner.core.model.StapleRule
import com.example.chatgpt_mealplanner.core.service.ShopPriceOption

enum class ShoppingTab(val displayName: String) {
    SHOPPING_LIST("Shopping List"),
    STAPLE_RULES("Staple Rules")
}

data class ShoppingUiState(
    val activeListWithItems: ShoppingListWithItems? = null,
    val isFollower: Boolean = false,
    val stapleRules: List<StapleRule> = emptyList(),
    val activeTab: ShoppingTab = ShoppingTab.SHOPPING_LIST,
    val isLoading: Boolean = false,
    val userMessage: String? = null,
    val selectedItemForDetail: ShoppingListItemWithSources? = null,
    val showAddManualDialog: Boolean = false,
    val shopPriceOptions: Map<String, List<ShopPriceOption>> = emptyMap(),
    val manualItemName: String = "",
    val manualItemNotes: String = "",
    /** Set while a shopping trip is open: the list is sorted by this shop's learned order. */
    val activeShop: ActiveShop? = null,
    val shops: List<ShopChoice> = emptyList()
)

data class ActiveShop(val storeId: String, val storeName: String, val orderByKey: Map<String, Double>)

data class ShopChoice(val id: String, val name: String)

sealed interface ShoppingUiEvent {
    data class SelectTab(val tab: ShoppingTab) : ShoppingUiEvent
    object RegenerateShoppingList : ShoppingUiEvent
    object ClearCompletedItems : ShoppingUiEvent
    data class ToggleItemChecked(val itemId: String, val currentStatus: ShoppingItemStatus) : ShoppingUiEvent
    data class RemoveItem(val itemId: String) : ShoppingUiEvent
    data class SelectItemDetail(val itemWithSources: ShoppingListItemWithSources?) : ShoppingUiEvent
    object RequestAddManualItem : ShoppingUiEvent
    data class RequestLowStockShortcut(val item: InventoryItem) : ShoppingUiEvent
    object DismissAddManualDialog : ShoppingUiEvent
    data class AddManualItem(val name: String, val quantity: Double?, val unit: QuantityUnit?, val notes: String?) : ShoppingUiEvent
    data class ResolveUncertainty(val inventoryItemId: String, val confirmedQuantity: Double, val unit: QuantityUnit, val confidence: QuantityConfidence = QuantityConfidence.EXACT) : ShoppingUiEvent
    data class SaveStapleRule(val rule: StapleRule) : ShoppingUiEvent
    data class DeleteStapleRule(val ruleId: String) : ShoppingUiEvent
    data class StartShopping(val storeId: String) : ShoppingUiEvent
    object FinishShopping : ShoppingUiEvent
    object ClearUserMessage : ShoppingUiEvent
}
