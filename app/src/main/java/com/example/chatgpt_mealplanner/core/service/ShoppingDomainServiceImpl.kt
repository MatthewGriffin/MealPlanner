package com.example.chatgpt_mealplanner.core.service

import com.example.chatgpt_mealplanner.core.database.DatabaseTransactionRunner
import com.example.chatgpt_mealplanner.core.database.dao.InventoryDao
import com.example.chatgpt_mealplanner.core.database.dao.MealDao
import com.example.chatgpt_mealplanner.core.database.dao.ShoppingDao
import com.example.chatgpt_mealplanner.core.database.dao.StapleRuleDao
import com.example.chatgpt_mealplanner.core.database.entity.ShoppingListEntity
import com.example.chatgpt_mealplanner.core.database.entity.ShoppingListItemEntity
import com.example.chatgpt_mealplanner.core.database.entity.ShoppingRequirementSourceEntity
import com.example.chatgpt_mealplanner.core.database.entity.toDomainModel
import com.example.chatgpt_mealplanner.core.database.entity.toEntity
import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.RequirementConfidence
import com.example.chatgpt_mealplanner.core.model.ShoppingItemStatus
import com.example.chatgpt_mealplanner.core.model.ShoppingListItem
import com.example.chatgpt_mealplanner.core.model.ShoppingListItemWithSources
import com.example.chatgpt_mealplanner.core.model.ShoppingListWithItems
import com.example.chatgpt_mealplanner.core.model.ShoppingListStatus
import com.example.chatgpt_mealplanner.core.model.ShoppingPriority
import com.example.chatgpt_mealplanner.core.model.ShoppingRequirementSource
import com.example.chatgpt_mealplanner.core.model.ShoppingSourceType
import com.example.chatgpt_mealplanner.core.model.StapleRuleMode
import com.example.chatgpt_mealplanner.core.model.WasteRisk
import com.example.chatgpt_mealplanner.core.util.UnitConverter
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShoppingDomainServiceImpl @Inject constructor(
    private val shoppingDao: ShoppingDao,
    private val mealDao: MealDao,
    private val inventoryDao: InventoryDao,
    private val stapleRuleDao: StapleRuleDao,
    private val transactionRunner: DatabaseTransactionRunner
) : ShoppingDomainService {

    override suspend fun calculateShoppingRequirements(
        startDate: String?,
        endDate: String?
    ): DomainResult<List<ShoppingListItemWithSources>> {
        val todayStr = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        val effectiveStartDate = startDate ?: todayStr
        val effectiveEndDate = endDate ?: "2099-12-31"

        val plannedMeals = mealDao.getUpcomingPlannedMeals(effectiveStartDate, effectiveEndDate)

        val allIngredientsWithMeals = plannedMeals.flatMap { mealWithIngs ->
            val mealDomain = mealWithIngs.meal.toDomainModel()
            mealWithIngs.ingredients.map { ingEntity ->
                Pair(mealDomain, ingEntity.toDomainModel())
            }
        }

        // Ingredient rows without a canonical ID still need to aggregate across meals.
        // Using the meal-ingredient row ID here made every unresolved ingredient its own
        // shopping-list line, even when several meals all required "garlic cloves".
        val groupedIngredients = mutableMapOf<String, MutableList<Pair<com.example.chatgpt_mealplanner.core.model.Meal, com.example.chatgpt_mealplanner.core.model.MealIngredient>>>()
        allIngredientsWithMeals.forEach { (meal, ing) ->
            val canonicalId = ing.canonicalIngredientId?.trim()?.takeIf { it.isNotEmpty() }
            val key = canonicalId?.let { "canonical:${it.lowercase()}" }
                ?: "name:${normalizeIngredientGroupingName(ing.displayName)}"
            groupedIngredients.getOrPut(key) { mutableListOf() }.add(Pair(meal, ing))
        }

        val enabledStapleRules = stapleRuleDao.getEnabledStapleRules().map { it.toDomainModel() }
            .filter { !it.archivedByMerge }
            .associateBy { it.canonicalIngredientId }

        val allInventoryEntities = inventoryDao.getAllItems().first()
        val allInventoryItems = allInventoryEntities.map { it.toDomainModel() }

        val calculatedItems = mutableListOf<ShoppingListItemWithSources>()

        groupedIngredients.forEach { (_, mealIngPairs) ->
            val firstIng = mealIngPairs.first().second
            val canonicalId = firstIng.canonicalIngredientId
            val displayName = firstIng.displayName
            val primaryUnit = firstIng.plannedUnit

            var totalPlannedQuantity = 0.0
            val sources = mutableListOf<ShoppingRequirementSource>()

            mealIngPairs.forEach { (meal, ing) ->
                val convertedQty = UnitConverter.convert(ing.plannedQuantity, ing.plannedUnit, primaryUnit).getOrNull()
                    ?: ing.plannedQuantity
                totalPlannedQuantity += convertedQty

                sources.add(
                    ShoppingRequirementSource(
                        id = UUID.randomUUID().toString(),
                        shoppingListItemId = "",
                        mealId = meal.id,
                        mealIngredientId = ing.id,
                        mealTitle = meal.title,
                        mealDate = meal.date,
                        requiredQuantity = convertedQty,
                        requiredUnit = primaryUnit
                    )
                )
            }

            val matchingLots = allInventoryItems.filter { lot ->
                isLotMatchingRuleOrIngredient(lot, canonicalId)
            }
            val availableLotQuantity = matchingLots.sumOf { lot ->
                val lotQty = lot.quantity ?: 0.0
                UnitConverter.convert(lotQty, lot.quantityUnit ?: primaryUnit, primaryUnit).getOrNull() ?: lotQty
            }

            val shortfall = totalPlannedQuantity - availableLotQuantity
            val optional = shortfall <= 0.0
            val netRequirement = if (shortfall > 0.0) shortfall else 0.0

            val requirementConfidence = if (matchingLots.any { it.quantityConfidence == QuantityConfidence.EXACT }) {
                RequirementConfidence.EXACT
            } else {
                RequirementConfidence.APPROXIMATE
            }

            val wasteRisk = calculateWasteRisk(displayName)

            val item = ShoppingListItem(
                id = UUID.randomUUID().toString(),
                shoppingListId = "",
                displayName = displayName,
                requiredQuantity = totalPlannedQuantity,
                requiredUnit = primaryUnit,
                suggestedPurchaseQuantity = netRequirement,
                suggestedPurchaseUnit = primaryUnit,
                reason = "Required for ${sources.size} planned meal(s)",
                priority = ShoppingPriority.ESSENTIAL,
                status = ShoppingItemStatus.NEEDED,
                optional = optional,
                sourceType = ShoppingSourceType.MEAL_REQUIREMENT,
                hasMealRequirement = true,
                hasStapleRestock = false,
                mealShortfallQuantity = netRequirement,
                stapleReserveTopUpQuantity = 0.0,
                expectedFinalInventory = availableLotQuantity,
                expectedFinalInventoryUnit = primaryUnit,
                quantityUncertain = matchingLots.any { it.quantityConfidence == QuantityConfidence.UNKNOWN || it.quantityConfidence == QuantityConfidence.APPROXIMATE },
                uncertaintyReason = if (matchingLots.any { it.quantityConfidence == QuantityConfidence.UNKNOWN }) "Inventory lot quantity is unknown" else null,
                requirementConfidence = requirementConfidence,
                wasteRisk = wasteRisk
            )

            calculatedItems.add(ShoppingListItemWithSources(item, sources))
        }

        // Process enabled staple rules not covered by meal requirements
        val coveredCanonicalIds = calculatedItems.mapNotNull { it.item.canonicalIngredientId }.toSet()
        enabledStapleRules.forEach { (stapleCanonicalId, stapleRule) ->
            if (stapleCanonicalId !in coveredCanonicalIds) {
                val matchingLots = allInventoryItems.filter { lot ->
                    isLotMatchingRuleOrIngredient(lot, stapleCanonicalId)
                }

                val primaryUnit = stapleRule.targetUnit ?: stapleRule.thresholdUnit ?: QuantityUnit.PACK
                val availableLotQuantity = matchingLots.sumOf { lot ->
                    val lotQty = lot.quantity ?: 0.0
                    UnitConverter.convert(lotQty, lot.quantityUnit ?: primaryUnit, primaryUnit).getOrNull() ?: lotQty
                }

                var shouldRestock = false
                var deficit = 0.0

                if (stapleRule.mode == StapleRuleMode.PACK_COUNT) {
                    val totalPacks = matchingLots.sumOf { lot ->
                        lot.packCount ?: (if (lot.quantity != null && lot.packSize != null && lot.packSize > 0) (lot.quantity / lot.packSize).toInt() else 1)
                    }
                    val threshPacks = stapleRule.thresholdPackCount ?: 1
                    val targetPacks = stapleRule.targetPackCount ?: threshPacks

                    if (totalPacks <= threshPacks) {
                        val packDeficitPacks = (targetPacks - totalPacks).coerceAtLeast(1)
                        shouldRestock = true
                        deficit = packDeficitPacks.toDouble()
                    }
                } else {
                    val threshQty = stapleRule.thresholdQuantity ?: 0.0
                    val targetQty = stapleRule.targetQuantity ?: threshQty
                    if (availableLotQuantity <= threshQty) {
                        shouldRestock = true
                        deficit = (targetQty - availableLotQuantity).coerceAtLeast(0.0)
                    }
                }

                if (shouldRestock && deficit > 0.0) {
                    val wasteRisk = calculateWasteRisk(stapleRule.displayName)
                    val item = ShoppingListItem(
                        id = UUID.randomUUID().toString(),
                        shoppingListId = "",
                        displayName = stapleRule.displayName,
                        requiredQuantity = deficit,
                        requiredUnit = primaryUnit,
                        suggestedPurchaseQuantity = deficit,
                        suggestedPurchaseUnit = primaryUnit,
                        reason = "Staple reserve restock rule",
                        priority = ShoppingPriority.ESSENTIAL,
                        status = ShoppingItemStatus.NEEDED,
                        optional = false,
                        sourceType = ShoppingSourceType.STAPLE_RESTOCK,
                        hasMealRequirement = false,
                        hasStapleRestock = true,
                        mealShortfallQuantity = 0.0,
                        stapleReserveTopUpQuantity = deficit,
                        expectedFinalInventory = availableLotQuantity,
                        expectedFinalInventoryUnit = primaryUnit,
                        quantityUncertain = false,
                        uncertaintyReason = null,
                        requirementConfidence = RequirementConfidence.EXACT,
                        wasteRisk = wasteRisk
                    )
                    calculatedItems.add(ShoppingListItemWithSources(item, emptyList()))
                }
            }
        }

        return DomainResult.Success(calculatedItems)
    }

    override suspend fun generateOrRegenerateShoppingList(
        listName: String,
        startDate: String?,
        endDate: String?
    ): DomainResult<ShoppingListWithItems> = transactionRunner.runInDomainTransaction {
        val activeList = shoppingDao.getActiveShoppingList()
        val listId = activeList?.id ?: UUID.randomUUID().toString()

        if (activeList == null) {
            val newList = ShoppingListEntity(
                id = listId,
                name = listName,
                startDate = startDate,
                endDate = endDate,
                status = ShoppingListStatus.ACTIVE,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            shoppingDao.insertShoppingList(newList)
        }

        val requirementsResult = calculateShoppingRequirements(startDate, endDate)
        if (requirementsResult is DomainResult.Failure) {
            return@runInDomainTransaction requirementsResult
        }

        val calculated = (requirementsResult as DomainResult.Success).value

        shoppingDao.deleteGeneratedItemsForList(listId)

        val newItemsToInsert = mutableListOf<ShoppingListItemEntity>()
        val newSourcesToInsert = mutableListOf<ShoppingRequirementSourceEntity>()

        calculated.forEach { calculatedWithSources ->
            val newItem = calculatedWithSources.item.copy(shoppingListId = listId)
            newItemsToInsert.add(newItem.toEntity())

            calculatedWithSources.sources.forEach { source ->
                newSourcesToInsert.add(source.copy(shoppingListItemId = newItem.id).toEntity())
            }
        }

        shoppingDao.insertShoppingListItems(newItemsToInsert)
        shoppingDao.insertRequirementSources(newSourcesToInsert)

        val updatedListWithItems = shoppingDao.getShoppingListWithItems(listId)
            ?: return@runInDomainTransaction DomainResult.Failure(DomainError.TransactionFailed("Failed to fetch generated list"))

        DomainResult.Success(updatedListWithItems.toDomainModel())
    }

    override suspend fun addManualShoppingItem(
        shoppingListId: String,
        displayName: String,
        quantity: Double?,
        unit: QuantityUnit?,
        notes: String?
    ): DomainResult<ShoppingListItem> {
        val item = ShoppingListItem(
            id = UUID.randomUUID().toString(),
            shoppingListId = shoppingListId,
            displayName = displayName,
            requiredQuantity = quantity,
            requiredUnit = unit,
            suggestedPurchaseQuantity = quantity,
            suggestedPurchaseUnit = unit,
            reason = notes ?: "Manually added item",
            priority = ShoppingPriority.ESSENTIAL,
            status = ShoppingItemStatus.NEEDED,
            optional = false,
            sourceType = ShoppingSourceType.MANUAL,
            hasMealRequirement = false,
            hasStapleRestock = false,
            mealShortfallQuantity = null,
            stapleReserveTopUpQuantity = quantity,
            expectedFinalInventory = null,
            expectedFinalInventoryUnit = unit,
            quantityUncertain = false,
            uncertaintyReason = null,
            requirementConfidence = RequirementConfidence.EXACT,
            wasteRisk = WasteRisk.LOW
        )

        shoppingDao.insertShoppingListItem(item.toEntity())
        return DomainResult.Success(item)
    }

    override suspend fun updateItemStatus(
        itemId: String,
        status: ShoppingItemStatus
    ): DomainResult<Unit> {
        shoppingDao.updateItemStatus(itemId, status.name)
        return DomainResult.Success(Unit)
    }

    override suspend fun removeShoppingItem(itemId: String): DomainResult<Unit> {
        shoppingDao.deleteShoppingListItem(itemId)
        return DomainResult.Success(Unit)
    }

    override suspend fun getActiveShoppingList(): DomainResult<ShoppingListWithItems?> {
        val activeList = shoppingDao.getActiveShoppingList() ?: return DomainResult.Success(null)
        val listWithItems = shoppingDao.getShoppingListWithItems(activeList.id) ?: return DomainResult.Success(null)
        return DomainResult.Success(listWithItems.toDomainModel())
    }

    override suspend fun resolveInventoryLotUncertainty(
        inventoryItemId: String,
        confirmedQuantity: Double,
        unit: QuantityUnit,
        resultingConfidence: QuantityConfidence
    ): DomainResult<Unit> {
        val itemEntity = inventoryDao.getItemById(inventoryItemId)
            ?: return DomainResult.Failure(DomainError.ItemNotFound(inventoryItemId))

        val updated = itemEntity.copy(
            quantity = confirmedQuantity,
            quantityUnit = unit,
            quantityConfidence = resultingConfidence,
            status = ItemStatus.AVAILABLE,
            updatedAt = System.currentTimeMillis()
        )

        inventoryDao.updateItem(updated)
        return DomainResult.Success(Unit)
    }

    /**
     * Stable fallback identity for ingredients that have not been assigned a canonical ID.
     * This is deliberately generic: normalise harmless formatting and singular/plural
     * differences, but keep meaningful words so distinct ingredients are not combined.
     */
    private fun normalizeIngredientGroupingName(name: String): String =
        name.lowercase()
            .trim()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun isLotMatchingRuleOrIngredient(lot: com.example.chatgpt_mealplanner.core.model.InventoryItem, canonicalId: String?): Boolean {
        if (lot.status == ItemStatus.DISCARDED || canonicalId == null) return false
        val lotCanonicalId = lot.canonicalIngredientId.takeIf { !it.isNullOrBlank() } ?: "item_${lot.id}"
        if (lotCanonicalId == canonicalId) return true
        if (lot.id == canonicalId || "item_${lot.id}" == canonicalId) return true
        if ("item_${lot.id}" == "item_$canonicalId") return true
        return false
    }

    private fun calculateWasteRisk(ingredientName: String): WasteRisk {
        val lower = ingredientName.lowercase()
        return when {
            lower.contains("milk") || lower.contains("cream") || lower.contains("fish") || lower.contains("berry") -> WasteRisk.HIGH
            lower.contains("bread") || lower.contains("cheese") || lower.contains("salad") || lower.contains("chicken") -> WasteRisk.MEDIUM
            else -> WasteRisk.LOW
        }
    }
}
