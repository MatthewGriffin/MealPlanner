package com.example.chatgpt_mealplanner.core.backup

import com.example.chatgpt_mealplanner.retail.contract.Money
import com.example.chatgpt_mealplanner.retail.contract.RetailProductMappingDecision
import com.example.chatgpt_mealplanner.retail.contract.RetailStorageClass
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import kotlinx.serialization.Serializable

@Serializable
data class InventoryItemExportDto(
    val id: String,
    val barcode: String?,
    val canonicalIngredientId: String?,
    val canonicalName: String,
    val displayName: String,
    val brand: String?,
    val category: String,
    val storageLocation: String,
    val quantity: Double,
    val quantityUnit: String?,
    val packCount: Int?,
    val packSize: Double?,
    val packSizeUnit: String?,
    val quantityConfidence: String,
    val status: String,
    val opened: Boolean,
    val frozen: Boolean,
    val staple: Boolean,
    val purchaseDate: String?,
    val openedDate: String?,
    val useByDate: String?,
    val bestBeforeDate: String?,
    val notes: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1L
)

@Serializable
data class TransactionExportDto(
    val id: String,
    val inventoryItemId: String,
    val transactionType: String,
    val deltaQuantity: Double,
    val unit: String?,
    val timestamp: Long,
    val source: String,
    val referenceId: String?,
    val note: String?
)

@Serializable
data class MealIngredientExportDto(
    val id: String,
    val mealId: String,
    val canonicalIngredientId: String?,
    val displayName: String,
    val plannedQuantity: Double,
    val plannedUnit: String,
    val actualQuantity: Double?,
    val actualUnit: String?,
    val optional: Boolean,
    val substitutionAllowed: Boolean,
    val substitutedCanonicalIngredientId: String?,
    val substitutedDisplayName: String?
)

@Serializable
data class MealExportDto(
    val id: String,
    val date: String,
    val mealType: String,
    val title: String,
    val servings: Int,
    val status: String,
    val batchPortions: Int,
    val immediatePortions: Int,
    val leftoverPortions: Int,
    val cookInTwoBatches: Boolean,
    val tags: Set<String>,
    val instructions: String?,
    val notes: String?,
    val cookedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val ingredients: List<MealIngredientExportDto>
)

@Serializable
data class LeftoverExportDto(
    val id: String,
    val sourceMealId: String?,
    val name: String,
    val initialPortions: Int,
    val portions: Int,
    val storageLocation: String,
    val status: String,
    val createdDate: String,
    val consumeByDate: String?,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class ShoppingRequirementSourceExportDto(
    val id: String,
    val shoppingListItemId: String,
    val mealId: String,
    val mealIngredientId: String?,
    val mealTitle: String,
    val mealDate: String,
    val requiredQuantity: Double,
    val requiredUnit: String
)

@Serializable
data class ShoppingListItemExportDto(
    val id: String,
    val shoppingListId: String,
    val canonicalIngredientId: String?,
    val displayName: String,
    val requiredQuantity: Double?,
    val requiredUnit: String?,
    val suggestedPurchaseQuantity: Double?,
    val suggestedPurchaseUnit: String?,
    val packCount: Int?,
    val reason: String,
    val priority: String,
    val status: String,
    val optional: Boolean,
    val sourceType: String,
    val hasMealRequirement: Boolean,
    val hasStapleRestock: Boolean,
    val mealShortfallQuantity: Double?,
    val stapleReserveTopUpQuantity: Double?,
    val expectedFinalInventory: Double?,
    val expectedFinalInventoryUnit: String?,
    val quantityUncertain: Boolean,
    val uncertaintyReason: String?,
    val requirementConfidence: String,
    val wasteRisk: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sources: List<ShoppingRequirementSourceExportDto>
)

@Serializable
data class ShoppingListExportDto(
    val id: String,
    val name: String,
    val startDate: String?,
    val endDate: String?,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val items: List<ShoppingListItemExportDto>
)

@Serializable
data class StapleRuleExportDto(
    val id: String,
    val canonicalIngredientId: String,
    val displayName: String,
    val enabled: Boolean,
    val mode: String,
    val thresholdQuantity: Double?,
    val thresholdUnit: String?,
    val targetQuantity: Double?,
    val targetUnit: String?,
    val thresholdPackCount: Int?,
    val targetPackCount: Int?,
    val preferredStorageLocation: String?,
    val notes: String?,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class BarcodeMappingExportDto(
    val barcode: String,
    val canonicalIngredientId: String?,
    val productName: String,
    val brand: String?,
    val defaultCategory: String,
    val defaultStorageLocation: String,
    val defaultQuantity: Double?,
    val defaultUnit: String?,
    val packCount: Int?,
    val packSize: Double?,
    val packSizeUnit: String?,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class RetailProductMappingExportDto(
    val id: String,
    val retailerId: RetailerId,
    val retailerProductId: String,
    val canonicalIngredientId: String,
    val decision: RetailProductMappingDecision,
    val userConfirmed: Boolean,
    val updatedAt: Long
)

@Serializable
data class ManualRetailOfferExportDto(
    val id: String,
    val retailerId: RetailerId,
    val retailerProductId: String,
    val productName: String,
    val brand: String?,
    val packSize: Double?,
    val packSizeUnit: String?,
    val storageClass: RetailStorageClass,
    val price: Money,
    val observedAt: Long
)

@Serializable
data class UserSettingsExportDto(
    val householdSize: Int = 2,
    val preferFrozenVegetables: Boolean = true,
    val preferGarlicPowderOverFresh: Boolean = true,
    val maxRepetitionDays: Int = 14,
    val enabledRetailers: Set<RetailerId> = setOf(RetailerId.ASDA, RetailerId.ICELAND, RetailerId.FOOD_WAREHOUSE),
    val avoidExtraStoreTrips: Boolean = true,
    val minimumSavingsForExtraStore: Money = Money(300L),
    val additionalStorePenalty: Money = Money(200L),
    val enableAsdaRewards: Boolean = false,
    val enableIcelandBonusCard: Boolean = false
)

@Serializable
data class ChatMessageExportDto(
    val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val timestamp: Long
)

@Serializable
data class ChatConversationExportDto(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ChatMessageExportDto>
)

@Serializable
data class PantryPlannerExportDto(
    val exportFormatVersion: String = "pantry-planner-export-v1",
    val exportedAt: Long,
    val appVersionName: String = "1.0",
    val inventory: List<InventoryItemExportDto> = emptyList(),
    val transactions: List<TransactionExportDto> = emptyList(),
    val meals: List<MealExportDto> = emptyList(),
    val leftovers: List<LeftoverExportDto> = emptyList(),
    val shoppingLists: List<ShoppingListExportDto> = emptyList(),
    val stapleRules: List<StapleRuleExportDto> = emptyList(),
    val productBarcodeMappings: List<BarcodeMappingExportDto> = emptyList(),
    val retailMappings: List<RetailProductMappingExportDto> = emptyList(),
    val manualRetailOffers: List<ManualRetailOfferExportDto> = emptyList(),
    val userSettings: UserSettingsExportDto = UserSettingsExportDto(),
    val chatHistory: List<ChatConversationExportDto>? = null
)
