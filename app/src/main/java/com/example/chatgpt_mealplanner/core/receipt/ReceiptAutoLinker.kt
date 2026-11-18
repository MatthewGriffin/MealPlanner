package com.example.chatgpt_mealplanner.core.receipt

import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.util.IngredientNameMatcher

/** A pantry or shopping-list entry a receipt line might be, by name. */
data class LinkTarget(val id: String, val name: String)

/**
 * Links receipt lines to what the household already has or planned to buy, and works out which food and drink lines are not
 * in the pantry yet. The links are only suggestions the user can change on the review screen.
 */
object ReceiptAutoLinker {
    /** Everything a household keeps at home. Lines the reader could not classify are left out, since they may not be products at all. */
    private val pantryCategories = ReceiptSpendCategory.entries.filter { it != ReceiptSpendCategory.UNCATEGORISED }.map { it.name }.toSet()

    /** What the line is as the pantry would call it: "White bread", never the brand. */
    fun genericNameOf(item: ReceiptSpendItemDraft): String =
        item.genericName?.trim()?.takeIf { it.isNotEmpty() } ?: item.normalizedDescription.trim()

    private fun matches(item: ReceiptSpendItemDraft, name: String) =
        IngredientNameMatcher.matches(genericNameOf(item), name) || IngredientNameMatcher.matches(item.normalizedDescription, name)

    /**
     * A line already linked (by the user or earlier) is left alone. Otherwise it links to the same product on the shopping list
     * (so it is ticked off as bought), else to the same product in the pantry.
     */
    fun suggestLinks(items: List<ReceiptSpendItemDraft>, pantry: List<LinkTarget>, shopping: List<LinkTarget>): List<ReceiptSpendItemDraft> =
        items.map { item ->
            if (item.isDiscount || item.linkLocked || item.linkedInventoryItemId != null || item.linkedShoppingListItemId != null) return@map item
            val onList = shopping.firstOrNull { matches(item, it.name) }
            val inPantry = pantry.firstOrNull { matches(item, it.name) }
            when {
                onList != null -> item.copy(linkedShoppingListItemId = onList.id, autoLinked = true)
                inPantry != null -> item.copy(linkedInventoryItemId = inPantry.id, autoLinked = true)
                else -> item
            }
        }

    /** True for product lines (food, drink and household goods) whose generic product is not in the pantry yet. */
    fun needsPantryItem(item: ReceiptSpendItemDraft, pantry: List<LinkTarget>): Boolean =
        !item.isDiscount && item.category in pantryCategories && genericNameOf(item).isNotBlank() &&
            pantry.none { matches(item, it.name) }

    private fun titleCase(name: String) = name.trim().lowercase().replaceFirstChar { it.uppercase() }

    private fun foodCategoryFor(item: ReceiptSpendItemDraft): FoodCategory =
        FoodCategory.entries.firstOrNull { it.name == item.foodCategory }
            ?: when (item.category) {
                ReceiptSpendCategory.DRINKS.name -> FoodCategory.DRINKS
                ReceiptSpendCategory.FOOD.name -> FoodCategory.OTHER
                else -> FoodCategory.HOUSEHOLD   // cleaning, toiletries, pet, baby and other goods
            }

    private fun storageFor(item: ReceiptSpendItemDraft, category: FoodCategory): StorageLocation =
        StorageLocation.entries.firstOrNull { it.name == item.storageLocation }
            ?: when (category) {
                FoodCategory.MEAT_SEAFOOD, FoodCategory.DAIRY_EGGS, FoodCategory.READY_MEALS -> StorageLocation.FRIDGE
                FoodCategory.FROZEN_FOOD -> StorageLocation.FREEZER
                else -> StorageLocation.CUPBOARD
            }

    /**
     * One new pantry item per distinct generic product on the receipt (two lines of "Wedges" are one item with both quantities).
     * The result pairs each new item with the receipt lines it covers.
     */
    fun newPantryItems(items: List<ReceiptSpendItemDraft>, pantry: List<LinkTarget>, purchasedAt: String?): List<Pair<InventoryItem, List<String>>> =
        items.filter { needsPantryItem(it, pantry) }
            .groupBy { IngredientNameMatcher.core(genericNameOf(it)) }
            .filterKeys { it.isNotEmpty() }
            .map { (_, lines) ->
                val first = lines.first()
                val category = foodCategoryFor(first)
                val generic = genericNameOf(first)
                InventoryItem(
                    canonicalName = generic.lowercase(),
                    displayName = titleCase(generic),
                    brand = lines.firstNotNullOfOrNull { it.brand?.trim()?.takeIf { b -> b.isNotEmpty() } },
                    category = category,
                    storageLocation = storageFor(first, category),
                    quantity = lines.sumOf { it.quantity.coerceAtLeast(1) }.toDouble(),
                    quantityUnit = QuantityUnit.UNIT,
                    quantityConfidence = QuantityConfidence.APPROXIMATE,
                    purchaseDate = purchasedAt
                ) to lines.map { it.id }
            }
}
