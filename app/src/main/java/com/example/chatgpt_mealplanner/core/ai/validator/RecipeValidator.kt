package com.example.chatgpt_mealplanner.core.ai.validator

import com.example.chatgpt_mealplanner.contract.IngredientAvailability
import com.example.chatgpt_mealplanner.contract.MealProposal
import com.example.chatgpt_mealplanner.contract.RecipeValidationStatus
import com.example.chatgpt_mealplanner.contract.ValidatedMealProposal
import com.example.chatgpt_mealplanner.contract.ValidatedProposedIngredient
import com.example.chatgpt_mealplanner.core.database.dao.InventoryDao
import com.example.chatgpt_mealplanner.core.database.dao.MealDao
import com.example.chatgpt_mealplanner.core.database.entity.toDomainModel
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.parseQuantityUnit
import com.example.chatgpt_mealplanner.core.util.UnitConverter
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecipeValidator @Inject constructor(
    private val inventoryDao: InventoryDao,
    private val mealDao: MealDao
) {

    suspend fun validateProposal(proposal: MealProposal): ValidatedMealProposal {
        val issues = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (proposal.servings <= 0) {
            issues.add("Servings must be greater than 0.")
        }

        if (proposal.batchPortions != proposal.immediatePortions + proposal.leftoverPortions) {
            issues.add("Batch portions (${proposal.batchPortions}) must equal immediate portions (${proposal.immediatePortions}) plus leftover portions (${proposal.leftoverPortions}).")
        }

        if (proposal.ingredients.isEmpty()) {
            issues.add("Recipe must contain at least one ingredient.")
        }

        val allInventory = inventoryDao.getAllItems().first().map { it.toDomainModel() }
        val usableInventory = allInventory.filter { it.status in listOf(ItemStatus.AVAILABLE, ItemStatus.LOW, ItemStatus.USE_SOON) }

        val validatedIngredients = mutableListOf<ValidatedProposedIngredient>()
        var shoppingNeeded = false

        proposal.ingredients.forEach { ing ->
            val parsedUnit = parseQuantityUnit(ing.unit)
            val normalizedIngredientName = ing.displayName
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
                .replace(Regex("\\s+"), " ")
            val vagueIngredientNames = setOf(
                "herb", "herbs", "spice", "spices", "seasoning", "seasonings",
                "vegetable", "vegetables", "greens", "meat", "protein",
                "sauce", "oil", "garnish", "garnishes", "toppings",
                "herbs to taste", "spices to taste", "seasoning to taste"
            )
            if (normalizedIngredientName in vagueIngredientNames) {
                issues.add("Ingredient '${ing.displayName}' is too vague to shop for. Name the specific ingredient or blend.")
            }
            if (ing.quantity <= 0.0) {
                issues.add("Ingredient '${ing.displayName}' quantity must be greater than 0.")
            }

            val matchingLots = if (ing.canonicalIngredientId != null) {
                allInventory.filter { it.canonicalIngredientId == ing.canonicalIngredientId }
            } else {
                allInventory.filter { it.displayName.equals(ing.displayName, ignoreCase = true) }
            }
            val hasUncertain = matchingLots.any {
                it.status == ItemStatus.NEEDS_CONFIRMATION ||
                    (it.status in listOf(ItemStatus.AVAILABLE, ItemStatus.LOW, ItemStatus.USE_SOON) &&
                        it.quantityConfidence == QuantityConfidence.UNKNOWN)
            }
            val usableMatchingLots = matchingLots.filter {
                it.status in listOf(ItemStatus.AVAILABLE, ItemStatus.LOW, ItemStatus.USE_SOON)
            }

            var ownedTotal = 0.0
            usableMatchingLots.forEach { lot ->
                val qty = lot.quantity ?: 0.0
                val converted = UnitConverter.convert(qty, lot.quantityUnit ?: parsedUnit, parsedUnit).getOrNull() ?: qty
                ownedTotal += converted
            }

            val availability = when {
                hasUncertain -> IngredientAvailability.UNCERTAIN
                ownedTotal >= ing.quantity -> IngredientAvailability.AVAILABLE
                ownedTotal > 0.0 -> IngredientAvailability.PARTIAL
                else -> IngredientAvailability.MISSING
            }

            if (availability in listOf(IngredientAvailability.PARTIAL, IngredientAvailability.MISSING)) {
                shoppingNeeded = true
            }

            validatedIngredients.add(
                ValidatedProposedIngredient(
                    ingredient = ing,
                    availability = availability,
                    ownedQuantity = if (ownedTotal > 0.0) ownedTotal else null,
                    ownedUnit = if (ownedTotal > 0.0) parsedUnit.name else null
                )
            )

            // Rule check: Frozen vegetable preference
            val nameLower = ing.displayName.lowercase()
            if (nameLower.contains("fresh") && (nameLower.contains("broccoli") || nameLower.contains("peas") || nameLower.contains("spinach"))) {
                val hasFrozenEquivalent = usableInventory.any {
                    it.displayName.lowercase().contains("frozen") && it.displayName.lowercase().contains(nameLower.replace("fresh", "").trim())
                }
                if (hasFrozenEquivalent) {
                    warnings.add("You own frozen ${ing.displayName.replace("Fresh", "").trim()} in inventory. Consider using frozen vegetables before buying fresh.")
                }
            }

            // Rule check: Garlic powder preference
            if (nameLower.contains("fresh garlic") || nameLower.equals("garlic")) {
                val hasGarlicPowder = usableInventory.any { it.displayName.lowercase().contains("garlic powder") }
                if (hasGarlicPowder) {
                    warnings.add("Garlic powder is owned in inventory. Consider using garlic powder unless fresh garlic materially improves the dish.")
                }
            }
        }

        // Rule check: 14-day repetition penalty
        val fourteenDaysAgo = System.currentTimeMillis() - (14 * 24 * 60 * 60 * 1000L)
        val recentCooked = mealDao.getRecentCookedMeals(fourteenDaysAgo)
        val matchingTitleInPastWeek = recentCooked.any {
            it.meal.title.equals(proposal.title, ignoreCase = true)
        }
        if (matchingTitleInPastWeek) {
            warnings.add("You have cooked '${proposal.title}' within the last 14 days. Consider varying your meal plan.")
        }

        val status = when {
            issues.isNotEmpty() -> RecipeValidationStatus.INVALID
            warnings.isNotEmpty() -> RecipeValidationStatus.VALID_WITH_WARNINGS
            else -> RecipeValidationStatus.VALID
        }

        return ValidatedMealProposal(
            proposal = proposal,
            validatedIngredients = validatedIngredients,
            shoppingNeeded = shoppingNeeded,
            status = status,
            issues = issues,
            warnings = warnings
        )
    }
}
