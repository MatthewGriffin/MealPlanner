package com.example.chatgpt_mealplanner.core.database

import androidx.room.TypeConverter
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.LeftoverStatus
import com.example.chatgpt_mealplanner.core.model.MealStatus
import com.example.chatgpt_mealplanner.core.model.MealTag
import com.example.chatgpt_mealplanner.core.model.MealType
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.RequirementConfidence
import com.example.chatgpt_mealplanner.core.model.ShoppingItemStatus
import com.example.chatgpt_mealplanner.core.model.ShoppingListStatus
import com.example.chatgpt_mealplanner.core.model.ShoppingPriority
import com.example.chatgpt_mealplanner.core.model.ShoppingSourceType
import com.example.chatgpt_mealplanner.core.model.StapleRuleMode
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.model.TransactionType
import com.example.chatgpt_mealplanner.core.model.WasteRisk
import com.example.chatgpt_mealplanner.retail.contract.BasketFeasibilityTier
import com.example.chatgpt_mealplanner.retail.contract.MatchConfidence
import com.example.chatgpt_mealplanner.retail.contract.RetailAvailability
import com.example.chatgpt_mealplanner.retail.contract.RetailProductMappingDecision
import com.example.chatgpt_mealplanner.retail.contract.RetailStorageClass
import com.example.chatgpt_mealplanner.retail.contract.RetailerId

class Converters {

    @TypeConverter
    fun fromFoodCategory(category: FoodCategory?): String? = category?.name

    @TypeConverter
    fun toFoodCategory(value: String?): FoodCategory? = value?.let {
        when (it.trim().uppercase()) {
            "BREAD_BAKERY", "BAKERY" -> FoodCategory.BAKERY
            "SAUCES_SEASONINGS", "SAUCES_CONDIMENTS" -> FoodCategory.SAUCES_CONDIMENTS
            "MEAL_KITS", "READY_MEALS" -> FoodCategory.READY_MEALS
            "FRESH_PANTRY", "PRODUCE" -> FoodCategory.PRODUCE
            "SNACKS" -> FoodCategory.SNACKS
            "CUPBOARD", "FRIDGE", "FREEZER" -> FoodCategory.OTHER
            else -> runCatching { enumValueOf<FoodCategory>(it.trim().uppercase()) }.getOrDefault(FoodCategory.OTHER)
        }
    }

    @TypeConverter
    fun fromStorageLocation(location: StorageLocation?): String? = location?.name

    @TypeConverter
    fun toStorageLocation(value: String?): StorageLocation? = value?.let {
        runCatching { enumValueOf<StorageLocation>(it) }.getOrDefault(StorageLocation.OTHER)
    }

    @TypeConverter
    fun fromQuantityUnit(unit: QuantityUnit?): String? = unit?.name

    @TypeConverter
    fun toQuantityUnit(value: String?): QuantityUnit? = value?.let {
        runCatching { enumValueOf<QuantityUnit>(it) }.getOrNull()
    }

    @TypeConverter
    fun fromQuantityConfidence(confidence: QuantityConfidence?): String? = confidence?.name

    @TypeConverter
    fun toQuantityConfidence(value: String?): QuantityConfidence? = value?.let {
        runCatching { enumValueOf<QuantityConfidence>(it) }.getOrDefault(QuantityConfidence.UNKNOWN)
    }

    @TypeConverter
    fun fromItemStatus(status: ItemStatus?): String? = status?.name

    @TypeConverter
    fun toItemStatus(value: String?): ItemStatus? = value?.let {
        runCatching { enumValueOf<ItemStatus>(it) }.getOrDefault(ItemStatus.AVAILABLE)
    }

    @TypeConverter
    fun fromTransactionType(type: TransactionType?): String? = type?.name

    @TypeConverter
    fun toTransactionType(value: String?): TransactionType? = value?.let {
        runCatching { enumValueOf<TransactionType>(it) }.getOrNull()
    }

    @TypeConverter
    fun fromMealType(type: MealType?): String? = type?.name

    @TypeConverter
    fun toMealType(value: String?): MealType? = value?.let {
        runCatching { enumValueOf<MealType>(it) }.getOrDefault(MealType.DINNER)
    }

    @TypeConverter
    fun fromMealStatus(status: MealStatus?): String? = status?.name

    @TypeConverter
    fun toMealStatus(value: String?): MealStatus? = value?.let {
        runCatching { enumValueOf<MealStatus>(it) }.getOrDefault(MealStatus.PLANNED)
    }

    @TypeConverter
    fun fromLeftoverStatus(status: LeftoverStatus?): String? = status?.name

    @TypeConverter
    fun toLeftoverStatus(value: String?): LeftoverStatus? = value?.let {
        runCatching { enumValueOf<LeftoverStatus>(it) }.getOrDefault(LeftoverStatus.AVAILABLE)
    }

    @TypeConverter
    fun fromMealTagSet(tags: Set<MealTag>?): String? = tags?.joinToString(",") { it.name }

    @TypeConverter
    fun toMealTagSet(value: String?): Set<MealTag> = value.orEmpty()
        .split(",")
        .filter { it.isNotBlank() }
        .mapNotNull { runCatching { enumValueOf<MealTag>(it) }.getOrNull() }
        .toSet()

    @TypeConverter
    fun fromShoppingListStatus(status: ShoppingListStatus?): String? = status?.name

    @TypeConverter
    fun toShoppingListStatus(value: String?): ShoppingListStatus? = value?.let {
        runCatching { enumValueOf<ShoppingListStatus>(it) }.getOrDefault(ShoppingListStatus.ACTIVE)
    }

    @TypeConverter
    fun fromShoppingItemStatus(status: ShoppingItemStatus?): String? = status?.name

    @TypeConverter
    fun toShoppingItemStatus(value: String?): ShoppingItemStatus? = value?.let {
        runCatching { enumValueOf<ShoppingItemStatus>(it) }.getOrDefault(ShoppingItemStatus.NEEDED)
    }

    @TypeConverter
    fun fromShoppingSourceType(type: ShoppingSourceType?): String? = type?.name

    @TypeConverter
    fun toShoppingSourceType(value: String?): ShoppingSourceType? = value?.let {
        runCatching { enumValueOf<ShoppingSourceType>(it) }.getOrDefault(ShoppingSourceType.MEAL_REQUIREMENT)
    }

    @TypeConverter
    fun fromShoppingPriority(priority: ShoppingPriority?): String? = priority?.name

    @TypeConverter
    fun toShoppingPriority(value: String?): ShoppingPriority? = value?.let {
        runCatching { enumValueOf<ShoppingPriority>(it) }.getOrDefault(ShoppingPriority.ESSENTIAL)
    }

    @TypeConverter
    fun fromRequirementConfidence(confidence: RequirementConfidence?): String? = confidence?.name

    @TypeConverter
    fun toRequirementConfidence(value: String?): RequirementConfidence? = value?.let {
        runCatching { enumValueOf<RequirementConfidence>(it) }.getOrDefault(RequirementConfidence.EXACT)
    }

    @TypeConverter
    fun fromWasteRisk(risk: WasteRisk?): String? = risk?.name

    @TypeConverter
    fun toWasteRisk(value: String?): WasteRisk? = value?.let {
        runCatching { enumValueOf<WasteRisk>(it) }.getOrDefault(WasteRisk.LOW)
    }

    @TypeConverter
    fun fromStapleRuleMode(mode: StapleRuleMode?): String? = mode?.name

    @TypeConverter
    fun toStapleRuleMode(value: String?): StapleRuleMode? = value?.let {
        runCatching { enumValueOf<StapleRuleMode>(it) }.getOrDefault(StapleRuleMode.QUANTITY)
    }

    @TypeConverter
    fun fromRetailerId(id: RetailerId?): String? = id?.name

    @TypeConverter
    fun toRetailerId(value: String?): RetailerId? = value?.let {
        runCatching { enumValueOf<RetailerId>(it) }.getOrDefault(RetailerId.ASDA)
    }

    @TypeConverter
    fun fromRetailStorageClass(storageClass: RetailStorageClass?): String? = storageClass?.name

    @TypeConverter
    fun toRetailStorageClass(value: String?): RetailStorageClass? = value?.let {
        runCatching { enumValueOf<RetailStorageClass>(it) }.getOrDefault(RetailStorageClass.UNKNOWN)
    }

    @TypeConverter
    fun fromRetailAvailability(availability: RetailAvailability?): String? = availability?.name

    @TypeConverter
    fun toRetailAvailability(value: String?): RetailAvailability? = value?.let {
        runCatching { enumValueOf<RetailAvailability>(it) }.getOrDefault(RetailAvailability.UNKNOWN)
    }

    @TypeConverter
    fun fromBasketFeasibilityTier(tier: BasketFeasibilityTier?): String? = tier?.name

    @TypeConverter
    fun toBasketFeasibilityTier(value: String?): BasketFeasibilityTier? = value?.let {
        runCatching { enumValueOf<BasketFeasibilityTier>(it) }.getOrDefault(BasketFeasibilityTier.COMPLETE)
    }

    @TypeConverter
    fun fromMatchConfidence(confidence: MatchConfidence?): String? = confidence?.name

    @TypeConverter
    fun toMatchConfidence(value: String?): MatchConfidence? = value?.let {
        runCatching { enumValueOf<MatchConfidence>(it) }.getOrDefault(MatchConfidence.HIGH)
    }

    @TypeConverter
    fun fromRetailProductMappingDecision(decision: RetailProductMappingDecision?): String? = decision?.name

    @TypeConverter
    fun toRetailProductMappingDecision(value: String?): RetailProductMappingDecision? = value?.let {
        runCatching { enumValueOf<RetailProductMappingDecision>(it) }.getOrDefault(RetailProductMappingDecision.CONFIRMED_MATCH)
    }

    @TypeConverter
    fun fromProductDataSource(source: com.example.chatgpt_mealplanner.core.barcode.model.ProductDataSource?): String? = source?.name

    @TypeConverter
    fun toProductDataSource(value: String?): com.example.chatgpt_mealplanner.core.barcode.model.ProductDataSource? = value?.let {
        runCatching { enumValueOf<com.example.chatgpt_mealplanner.core.barcode.model.ProductDataSource>(it) }.getOrDefault(com.example.chatgpt_mealplanner.core.barcode.model.ProductDataSource.OPEN_FOOD_FACTS)
    }

    @TypeConverter
    fun fromProductMatchConfidence(confidence: com.example.chatgpt_mealplanner.core.barcode.model.ProductMatchConfidence?): String? = confidence?.name

    @TypeConverter
    fun toProductMatchConfidence(value: String?): com.example.chatgpt_mealplanner.core.barcode.model.ProductMatchConfidence? = value?.let {
        runCatching { enumValueOf<com.example.chatgpt_mealplanner.core.barcode.model.ProductMatchConfidence>(it) }.getOrDefault(com.example.chatgpt_mealplanner.core.barcode.model.ProductMatchConfidence.HIGH)
    }
}
