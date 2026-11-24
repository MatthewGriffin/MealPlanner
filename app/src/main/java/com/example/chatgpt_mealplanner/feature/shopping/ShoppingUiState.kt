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
    val shops: List<ShopChoice> = emptyList(),
    /** Departments fixed by hand, by item key. */
    val departmentOverrides: Map<String, com.example.chatgpt_mealplanner.core.shopping.Department> = emptyMap(),
    /** The pantry, for pointing a list line at the item the household already has under another name. */
    val pantryItems: List<InventoryItem> = emptyList(),
    val motion: MotionOptions = MotionOptions()
)

/** Whether this phone can learn a shop's layout from its motion sensors, and whether the household has switched that on. */
data class MotionOptions(val available: Boolean = false, val enabled: Boolean = false, val hasPermission: Boolean = false)

data class ActiveShop(val storeId: String, val storeName: String, val orderByKey: Map<String, Double>)

data class ShopChoice(val id: String, val name: String)

sealed interface ShoppingUiEvent {
    data class SelectTab(val tab: ShoppingTab) : ShoppingUiEvent
    object RegenerateShoppingList : ShoppingUiEvent
    object ClearCompletedItems : ShoppingUiEvent
    data class ToggleItemChecked(val itemId: String, val currentStatus: ShoppingItemStatus) : ShoppingUiEvent
    data class RemoveItem(val itemId: String) : ShoppingUiEvent
    /** Fixes (or, with null, clears the fix for) the department an item is found in. */
    /** Renames a hand-added item and changes its "why". */
    /** The list line is the pantry item the household already has under another name. */
    data class MatchToPantry(val itemName: String, val pantryItemName: String) : ShoppingUiEvent
    data class EditManualItem(val itemId: String, val name: String, val why: String) : ShoppingUiEvent
    data class SetItemDepartment(val itemName: String, val department: com.example.chatgpt_mealplanner.core.shopping.Department?) : ShoppingUiEvent
    data class SelectItemDetail(val itemWithSources: ShoppingListItemWithSources?) : ShoppingUiEvent
    object RequestAddManualItem : ShoppingUiEvent
    data class RequestLowStockShortcut(val item: InventoryItem) : ShoppingUiEvent
    object DismissAddManualDialog : ShoppingUiEvent
    data class AddManualItem(val name: String, val quantity: Double?, val unit: QuantityUnit?, val notes: String?) : ShoppingUiEvent
    data class ResolveUncertainty(val inventoryItemId: String, val confirmedQuantity: Double, val unit: QuantityUnit, val confidence: QuantityConfidence = QuantityConfidence.EXACT) : ShoppingUiEvent
    data class SaveStapleRule(val rule: StapleRule) : ShoppingUiEvent
    data class DeleteStapleRule(val ruleId: String) : ShoppingUiEvent
    data class StartShopping(val storeId: String, val learnLayout: Boolean? = null) : ShoppingUiEvent
    object FinishShopping : ShoppingUiEvent
    object ClearUserMessage : ShoppingUiEvent
}
