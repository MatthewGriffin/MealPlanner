package com.example.chatgpt_mealplanner.core.util

import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.QuantityUnit

/**
 * A recipe amount once the user has picked the pantry item it comes from: always in that item's unit.
 * [quantity] is null when there is no reliable conversion (1 onion against grams), so the user enters it.
 */
data class CookAmount(val quantity: Double?, val unit: QuantityUnit)

object CookAmounts {
    fun inPantryUnit(quantity: Double, unit: QuantityUnit, item: InventoryItem): CookAmount {
        val pantryUnit = item.quantityUnit ?: return CookAmount(quantity, unit)
        val converted = UnitConverter.convert(quantity, unit, pantryUnit, item.packSize, item.packSizeUnit)
        // Three places is plenty for part packs: a third of a pack reads as 0.333.
        val value = (converted as? DomainResult.Success)?.value?.let { Math.round(it * 1000.0) / 1000.0 }
        return CookAmount(value?.takeIf { it > 0.0 || quantity == 0.0 }, pantryUnit)
    }
}
