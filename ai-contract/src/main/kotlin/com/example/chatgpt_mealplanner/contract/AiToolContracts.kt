package com.example.chatgpt_mealplanner.contract

import com.example.chatgpt_mealplanner.retail.contract.BasketComparisonResult
import com.example.chatgpt_mealplanner.retail.contract.RetailProductWithOffer
import kotlinx.serialization.Serializable

const val AI_CONTRACT_VERSION = "ai-contract-v2"

enum class AiToolClass {
    READ,
    WRITE_EXPLICIT,
    WRITE_CONFIRMATION_REQUIRED
}

enum class AiToolId(
    val toolName: String,
    val toolClass: AiToolClass,
    val description: String
) {
    // READ Tools (14)
    GET_INVENTORY_SUMMARY("get_inventory_summary", AiToolClass.READ, "Returns high-level summary of total inventory lots and storage locations."),
    GET_INVENTORY_FOR_INGREDIENT("get_inventory_for_ingredient", AiToolClass.READ, "Queries usable inventory lots for a specific canonical ingredient ID or search query."),
    GET_USABLE_INVENTORY("get_usable_inventory", AiToolClass.READ, "Returns all usable inventory items filtered by location or category."),
    GET_LOW_STOCK_ITEMS("get_low_stock_items", AiToolClass.READ, "Returns items marked LOW or below staple thresholds."),
    GET_USE_SOON_ITEMS("get_use_soon_items", AiToolClass.READ, "Returns items marked USE_SOON or nearing use-by date."),
    GET_RECENT_COOKED_MEALS("get_recent_cooked_meals", AiToolClass.READ, "Returns cooked meals in 14-day history for repetition penalty evaluation."),
    GET_UPCOMING_PLANNED_MEALS("get_upcoming_planned_meals", AiToolClass.READ, "Returns upcoming planned meals for date range."),
    GET_MEAL_DETAILS("get_meal_details", AiToolClass.READ, "Returns full meal details and ingredients for a specific mealId."),
    GET_AVAILABLE_LEFTOVERS("get_available_leftovers", AiToolClass.READ, "Returns active leftover items with remaining portions."),
    GET_ACTIVE_SHOPPING_LIST("get_active_shopping_list", AiToolClass.READ, "Returns active shopping list items."),
    CALCULATE_SHOPPING_REQUIREMENTS("calculate_shopping_requirements", AiToolClass.READ, "Calculates deterministic shopping requirements for planned meals."),
    GET_RETAIL_BASKET_COMPARISON("get_retail_basket_comparison", AiToolClass.READ, "Returns deterministic store basket optimization comparison across enabled retailers."),
    GET_RETAIL_ITEM_OPTIONS("get_retail_item_options", AiToolClass.READ, "Returns normalized retailer product options for a specific shopping requirement ID."),
    SEARCH_RETAIL_PRODUCTS("search_retail_products", AiToolClass.READ, "Searches accepted UK retailer catalogues for real product-name evidence. Use selectively for a few ambiguous, niche, or retailer-sensitive ingredient terms; do not search every ingredient or repeat searches across meals. For a weekly plan, limit to 3-4 distinct terms total and continue with standard UK grocery wording when lookup is unnecessary or unhelpful. Results inform retailer terminology, not proof of stock or canonical equivalence."),
    OFFER_ADD_PLANNED_MEAL("offer_add_planned_meal", AiToolClass.READ, "Prepares a structured offer to add a meal to the planner without modifying the database, placing the offer in AWAITING_CONFIRMATION state. Args: title, date (optional YYYY-MM-DD), mealType (optional BREAKFAST, LUNCH, DINNER, SNACK), servings (number), ingredients (JSON array of {displayName, quantity, unit, canonicalIngredientId?}), instructions (string - complete step-by-step cooking method). An offer is invalid without both complete ingredients and complete instructions."),

    // WRITE Tools (14)
    ADD_PLANNED_MEAL("add_planned_meal", AiToolClass.WRITE_EXPLICIT, "Creates a new planned meal with ingredients on the meal planner for a specified date and meal type. Args: title, date (YYYY-MM-DD), mealType (BREAKFAST, LUNCH, DINNER, SNACK), servings (number), ingredients (JSON array of {displayName, quantity, unit, canonicalIngredientId?}), instructions (string - complete step-by-step cooking method)."),
    MOVE_PLANNED_MEAL("move_planned_meal", AiToolClass.WRITE_EXPLICIT, "Moves a planned meal to a new date."),
    CANCEL_PLANNED_MEAL("cancel_planned_meal", AiToolClass.WRITE_EXPLICIT, "Marks a planned meal as CANCELLED."),
    RESTORE_MEAL_TO_PLANNED("restore_meal_to_planned", AiToolClass.WRITE_EXPLICIT, "Restores a SKIPPED meal back to PLANNED."),
    PREPARE_MARK_MEAL_COOKED("prepare_mark_meal_cooked", AiToolClass.WRITE_CONFIRMATION_REQUIRED, "Prepares meal cooking confirmation data for UI approval before deducting inventory."),
    MARK_INVENTORY_LOT_OUT("mark_inventory_lot_out", AiToolClass.WRITE_EXPLICIT, "Sets a specific physical lot status to OUT and quantity to 0."),
    MARK_ALL_INVENTORY_FOR_CANONICAL_INGREDIENT_OUT("mark_all_inventory_for_canonical_ingredient_out", AiToolClass.WRITE_EXPLICIT, "Sets all physical lots for a canonical ingredient to OUT."),
    ADJUST_INVENTORY_QUANTITY("adjust_inventory_quantity", AiToolClass.WRITE_EXPLICIT, "Adjusts physical lot quantity."),
    RESOLVE_INVENTORY_LOT_UNCERTAINTY("resolve_inventory_lot_uncertainty", AiToolClass.WRITE_EXPLICIT, "Resolves uncertain lot quantity and updates confidence to EXACT."),
    REGENERATE_SHOPPING_LIST("regenerate_shopping_list", AiToolClass.WRITE_EXPLICIT, "Triggers atomic shopping list regeneration."),
    ADD_MANUAL_SHOPPING_ITEM("add_manual_shopping_item", AiToolClass.WRITE_EXPLICIT, "Adds a manual shopping item to list."),
    SET_STAPLE_STATUS("set_staple_status", AiToolClass.WRITE_EXPLICIT, "Sets an inventory item as a household staple or unsets it. Args: itemId or searchQuery/displayName, isStaple (boolean)."),
    DELETE_INVENTORY_ITEM("delete_inventory_item", AiToolClass.WRITE_CONFIRMATION_REQUIRED, "Soft-deletes a specific inventory item. Args: itemId or searchQuery."),
    DELETE_ALL_INVENTORY("delete_all_inventory", AiToolClass.WRITE_CONFIRMATION_REQUIRED, "Soft-deletes all active inventory items. REQUIRES EXPLICIT CONFIRMATION. Args: confirmBulkDelete.");

    companion object {
        fun fromToolName(name: String): AiToolId? = entries.find { it.toolName == name }
    }
}

sealed interface AuthorizedMutationScope {
    object None : AuthorizedMutationScope

    data class AddPlannedMealScope(
        val sourceUserMessageId: String
    ) : AuthorizedMutationScope

    data class InventoryOutScope(
        val sourceUserMessageId: String,
        val permittedCanonicalIngredientIds: Set<String> = emptySet(),
        val permittedInventoryItemIds: Set<String> = emptySet()
    ) : AuthorizedMutationScope

    data class MoveMealScope(
        val sourceUserMessageId: String,
        val permittedMealId: String,
        val permittedDestinationDate: String
    ) : AuthorizedMutationScope

    data class CancelMealScope(
        val sourceUserMessageId: String,
        val permittedMealId: String
    ) : AuthorizedMutationScope

    data class RestoreMealScope(
        val sourceUserMessageId: String,
        val permittedMealId: String
    ) : AuthorizedMutationScope

    data class AdjustInventoryScope(
        val sourceUserMessageId: String,
        val permittedInventoryItemId: String,
        val confirmedQuantity: Double?,
        val confirmedUnit: String?
    ) : AuthorizedMutationScope

    data class ResolveUncertaintyScope(
        val sourceUserMessageId: String,
        val permittedInventoryItemId: String,
        val confirmedQuantity: Double,
        val confirmedUnit: String
    ) : AuthorizedMutationScope

    data class RegenerateShoppingScope(
        val sourceUserMessageId: String
    ) : AuthorizedMutationScope

    data class AddManualShoppingScope(
        val sourceUserMessageId: String
    ) : AuthorizedMutationScope

    data class SetStapleScope(
        val sourceUserMessageId: String,
        val permittedItemId: String? = null,
        val searchScope: String? = null,
        val isStaple: Boolean = true
    ) : AuthorizedMutationScope

    data class DeleteInventoryScope(
        val sourceUserMessageId: String,
        val targetQuery: String? = null,
        val permittedItemId: String? = null
    ) : AuthorizedMutationScope

    data class DeleteAllInventoryScope(
        val sourceUserMessageId: String
    ) : AuthorizedMutationScope
}

data class ActionAuthorizationContext(
    val sourceUserMessageId: String = "",
    val scopes: List<AuthorizedMutationScope> = emptyList(),
    val userMessageText: String = ""
)

@Serializable
sealed interface AiToolPayload {
    @Serializable
    data class InventorySummaryPayload(val items: List<InventoryItemAiDto>) : AiToolPayload
    @Serializable
    data class MealListPayload(val meals: List<MealAiDto>) : AiToolPayload
    @Serializable
    data class MealDetailsPayload(val meal: MealAiDto) : AiToolPayload
    @Serializable
    data class LeftoverListPayload(val leftovers: List<LeftoverAiDto>) : AiToolPayload
    @Serializable
    data class ShoppingListPayload(val items: List<ShoppingItemAiDto>) : AiToolPayload
    @Serializable
    data class TextPayload(val message: String) : AiToolPayload
}

@Serializable
data class InventorySummaryContextDto(
    val canonicalIngredientId: String?,
    val displayName: String,
    val quantity: Double?,
    val unit: String?,
    val storageLocation: String,
    val isStaple: Boolean = false
)

@Serializable
data class RecentMealContextDto(
    val id: String,
    val title: String,
    val date: String,
    val status: String,
    val mealType: String
)

@Serializable
data class LeftoverContextDto(
    val name: String,
    val portions: Int,
    val storageLocation: String,
    val useByDate: String?
)

@Serializable
data class ShoppingSummaryContextDto(
    val displayName: String,
    val quantity: Double?,
    val unit: String?,
    val priority: String
)

@Serializable
data class UserPreferencesSummaryDto(
    val householdSize: Int = 2,
    val preferFrozenVegetables: Boolean = true,
    val preferGarlicPowderOverFresh: Boolean = true
)

@Serializable
data class AiContextPayload(
    val currentDate: String = "2026-09-28",
    val dayOfWeek: String = "MONDAY",
    val householdSize: Int = 2,
    val preferences: UserPreferencesSummaryDto = UserPreferencesSummaryDto(),
    val recentCookedMeals: List<RecentMealContextDto> = emptyList(),
    val upcomingPlannedMeals: List<RecentMealContextDto> = emptyList(),
    val availableLeftovers: List<LeftoverContextDto> = emptyList(),
    val useSoonItems: List<InventorySummaryContextDto> = emptyList(),
    val lowStockStaples: List<InventorySummaryContextDto> = emptyList(),
    val activeShoppingList: List<ShoppingSummaryContextDto> = emptyList(),
    val activeProposalTitle: String? = null,
    val pendingMutationTitle: String? = null
)

@Serializable
data class InventoryItemAiDto(
    val id: String,
    val canonicalIngredientId: String?,
    val displayName: String,
    val brand: String? = null,
    val quantity: Double? = null,
    val unit: String? = null,
    val confidence: String,
    val status: String,
    val storageLocation: String
)

@Serializable
data class MealAiDto(
    val id: String,
    val date: String,
    val mealType: String,
    val title: String,
    val servings: Int,
    val status: String,
    val ingredients: List<PlannedIngredientAiDto>
)

@Serializable
data class PlannedIngredientAiDto(
    val id: String,
    val canonicalIngredientId: String?,
    val displayName: String,
    val quantity: Double,
    val unit: String
)

@Serializable
data class LeftoverAiDto(
    val id: String,
    val name: String,
    val remainingPortions: Int,
    val storageLocation: String,
    val useByDate: String?
)

@Serializable
data class ShoppingItemAiDto(
    val id: String,
    val canonicalIngredientId: String?,
    val displayName: String,
    val quantity: Double?,
    val unit: String?,
    val priority: String,
    val status: String
)
