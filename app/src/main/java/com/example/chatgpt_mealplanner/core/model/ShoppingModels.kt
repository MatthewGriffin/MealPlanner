package com.example.chatgpt_mealplanner.core.model

enum class ShoppingListStatus {
    ACTIVE,
    COMPLETED,
    ARCHIVED
}

enum class ShoppingItemStatus {
    NEEDED,
    CHECKED,
    PURCHASED_CONFIRMED
}

enum class ShoppingSourceType {
    MEAL_REQUIREMENT,
    STAPLE_RESTOCK,
    MEAL_AND_STAPLE,
    MANUAL,
    OPTIONAL_UPGRADE
}

enum class ShoppingPriority {
    ESSENTIAL,
    STAPLE,
    OPTIONAL
}

enum class RequirementConfidence {
    EXACT,
    APPROXIMATE,
    NEEDS_CONFIRMATION
}

enum class WasteRisk {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
    UNKNOWN
}

enum class StapleRuleMode {
    QUANTITY,
    PACK_COUNT
}

data class ShoppingList(
    val id: String,
    val name: String,
    val startDate: String? = null,
    val endDate: String? = null,
    val status: ShoppingListStatus = ShoppingListStatus.ACTIVE,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class ShoppingListItem(
    val id: String,
    val shoppingListId: String,
    val canonicalIngredientId: String? = null,
    val displayName: String,
    val requiredQuantity: Double? = null,
    val requiredUnit: QuantityUnit? = null,
    val suggestedPurchaseQuantity: Double? = null,
    val suggestedPurchaseUnit: QuantityUnit? = null,
    val packCount: Int? = null,
    val reason: String,
    val priority: ShoppingPriority = ShoppingPriority.ESSENTIAL,
    val status: ShoppingItemStatus = ShoppingItemStatus.NEEDED,
    val optional: Boolean = false,
    val sourceType: ShoppingSourceType = ShoppingSourceType.MEAL_REQUIREMENT,
    val hasMealRequirement: Boolean = true,
    val hasStapleRestock: Boolean = false,
    val mealShortfallQuantity: Double? = null,
    val stapleReserveTopUpQuantity: Double? = null,
    val expectedFinalInventory: Double? = null,
    val expectedFinalInventoryUnit: QuantityUnit? = null,
    val quantityUncertain: Boolean = false,
    val uncertaintyReason: String? = null,
    val requirementConfidence: RequirementConfidence = RequirementConfidence.EXACT,
    val wasteRisk: WasteRisk = WasteRisk.LOW,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class StapleRule(
    val id: String,
    val canonicalIngredientId: String,
    val displayName: String,
    val enabled: Boolean = true,
    val archivedByMerge: Boolean = false,
    val mode: StapleRuleMode = StapleRuleMode.QUANTITY,
    val thresholdQuantity: Double? = null,
    val thresholdUnit: QuantityUnit? = null,
    val targetQuantity: Double? = null,
    val targetUnit: QuantityUnit? = null,
    val thresholdPackCount: Int? = null,
    val targetPackCount: Int? = null,
    val preferredStorageLocation: StorageLocation? = null,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class ShoppingRequirementSource(
    val id: String,
    val shoppingListItemId: String,
    val mealId: String,
    val mealIngredientId: String? = null,
    val mealTitle: String,
    val mealDate: String,
    val requiredQuantity: Double,
    val requiredUnit: QuantityUnit
)

data class ShoppingListItemWithSources(
    val item: ShoppingListItem,
    val sources: List<ShoppingRequirementSource> = emptyList(),
    val ownedUsableQuantity: Double? = null,
    val ownedUsableUnit: QuantityUnit? = null
)

data class ShoppingListWithItems(
    val shoppingList: ShoppingList,
    val items: List<ShoppingListItemWithSources> = emptyList()
)
