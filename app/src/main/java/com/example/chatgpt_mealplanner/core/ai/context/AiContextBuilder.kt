package com.example.chatgpt_mealplanner.core.ai.context

import com.example.chatgpt_mealplanner.contract.AiContextPayload
import com.example.chatgpt_mealplanner.contract.InventorySummaryContextDto
import com.example.chatgpt_mealplanner.contract.LeftoverContextDto
import com.example.chatgpt_mealplanner.contract.RecentMealContextDto
import com.example.chatgpt_mealplanner.contract.ShoppingSummaryContextDto
import com.example.chatgpt_mealplanner.core.database.dao.InventoryDao
import com.example.chatgpt_mealplanner.core.database.dao.LeftoverDao
import com.example.chatgpt_mealplanner.core.database.dao.MealDao
import com.example.chatgpt_mealplanner.core.database.dao.ShoppingDao
import com.example.chatgpt_mealplanner.core.database.entity.toDomainModel
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiContextBuilder @Inject constructor(
    private val inventoryDao: InventoryDao,
    private val mealDao: MealDao,
    private val leftoverDao: LeftoverDao,
    private val shoppingDao: ShoppingDao,
    private val activeProposalStore: com.example.chatgpt_mealplanner.core.ai.proposal.ActiveProposalStore,
    private val pendingMutationStore: com.example.chatgpt_mealplanner.core.ai.pending.PendingMutationStore,
    private val cookingPreferences: com.example.chatgpt_mealplanner.core.repository.CookingPreferencesSource =
        com.example.chatgpt_mealplanner.core.repository.NoCookingPreferences,
    private val kitInstructionDao: com.example.chatgpt_mealplanner.core.database.dao.KitInstructionDao? = null,
    private val savedRecipeDao: com.example.chatgpt_mealplanner.core.database.dao.SavedRecipeDao? = null
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The saved packet instructions for the kits the household owns. A kit matches by name, ignoring case,
     * and also when the saved name is contained in the inventory name or the other way round, because the
     * user may call it "Fajita kit" while the packet says "Old El Paso Fajita Kit".
     */
    private suspend fun kitInstructionsFor(
        inventory: List<com.example.chatgpt_mealplanner.core.model.InventoryItem>
    ): List<com.example.chatgpt_mealplanner.contract.KitInstructionContextDto> {
        val dao = kitInstructionDao ?: return emptyList()
        val kits = inventory.filter { it.category == com.example.chatgpt_mealplanner.core.model.FoodCategory.KITS_MIXES }
        if (kits.isEmpty()) return emptyList()
        val kitNames = kits.flatMap { listOf(com.example.chatgpt_mealplanner.core.database.entity.normalizeKitName(it.displayName), com.example.chatgpt_mealplanner.core.database.entity.normalizeKitName(it.canonicalName)) }
            .filter { it.isNotEmpty() }
        return dao.getAll().filter { saved ->
            com.example.chatgpt_mealplanner.core.database.entity.savedKitMatches(kitNames, saved.normalizedName)
        }.map {
            com.example.chatgpt_mealplanner.contract.KitInstructionContextDto(kitName = it.displayName, instructions = it.instructions, source = it.source)
        }
    }

    suspend fun buildContextPayload(conversationId: String = ""): AiContextPayload {
        val today = LocalDate.now()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val dayOfWeek = today.dayOfWeek.name

        val fourteenDaysAgo = System.currentTimeMillis() - (14 * 24 * 60 * 60 * 1000L)
        val recentCookedEntities = mealDao.getRecentCookedMeals(fourteenDaysAgo)
        val recentCookedDtos = recentCookedEntities.map {
            RecentMealContextDto(
                id = it.meal.id,
                title = it.meal.title,
                date = it.meal.date,
                status = it.meal.status.name,
                mealType = it.meal.mealType.name
            )
        }

        val upcomingPlannedEntities = mealDao.getUpcomingPlannedMeals(todayStr, "2099-12-31")
        val upcomingPlannedDtos = upcomingPlannedEntities.map {
            RecentMealContextDto(
                id = it.meal.id,
                title = it.meal.title,
                date = it.meal.date,
                status = it.meal.status.name,
                mealType = it.meal.mealType.name
            )
        }

        val leftoversEntities = leftoverDao.observeAvailableLeftovers().first()
        val leftoverDtos = leftoversEntities.map {
            LeftoverContextDto(
                name = it.name,
                portions = it.portions,
                storageLocation = it.storageLocation.name,
                useByDate = it.consumeByDate
            )
        }

        val usableInventoryEntities = inventoryDao.getAllItems().first()
            .map { it.toDomainModel() }
            .filter { it.status in listOf(
                com.example.chatgpt_mealplanner.core.model.ItemStatus.AVAILABLE,
                com.example.chatgpt_mealplanner.core.model.ItemStatus.LOW,
                com.example.chatgpt_mealplanner.core.model.ItemStatus.USE_SOON
            ) }
        val usableInventoryDtos = usableInventoryEntities.map {
            InventorySummaryContextDto(
                canonicalIngredientId = it.canonicalIngredientId,
                displayName = it.displayName,
                quantity = it.quantity,
                unit = it.quantityUnit?.name,
                storageLocation = it.storageLocation.name,
                isStaple = it.staple,
                status = it.status.name,
                category = it.category.name
            )
        }

        val kitInstructionDtos = kitInstructionsFor(usableInventoryEntities)

        val useSoonEntities = inventoryDao.getUseSoonItems().first()
        val useSoonDtos = useSoonEntities.map {
            InventorySummaryContextDto(
                canonicalIngredientId = it.canonicalIngredientId,
                displayName = it.displayName,
                quantity = it.quantity,
                unit = it.quantityUnit?.name,
                storageLocation = it.storageLocation.name,
                isStaple = it.staple,
                status = it.status.name
            )
        }

        val lowEntities = inventoryDao.getLowStockItems().first()
        val lowDtos = lowEntities.map {
            InventorySummaryContextDto(
                canonicalIngredientId = it.canonicalIngredientId,
                displayName = it.displayName,
                quantity = it.quantity,
                unit = it.quantityUnit?.name,
                storageLocation = it.storageLocation.name,
                isStaple = it.staple,
                status = it.status.name
            )
        }

        val activeList = shoppingDao.getActiveShoppingList()
        val activeShoppingDtos = if (activeList != null) {
            val listWithItems = shoppingDao.getShoppingListWithItems(activeList.id)
            listWithItems?.items?.map {
                val item = it.item.toDomainModel()
                ShoppingSummaryContextDto(
                    displayName = item.displayName,
                    quantity = item.suggestedPurchaseQuantity ?: item.requiredQuantity,
                    unit = item.suggestedPurchaseUnit?.name ?: item.requiredUnit?.name,
                    priority = item.priority.name
                )
            } ?: emptyList()
        } else {
            emptyList()
        }

        val activeProposal = if (conversationId.isNotBlank()) activeProposalStore.getActiveProposal(conversationId) else null
        val pendingMutation = if (conversationId.isNotBlank()) pendingMutationStore.getPendingMutation(conversationId) else null
        val pendingTitle = (pendingMutation as? com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.AddPlannedMeal)?.title

        val preferences = cookingPreferences.current()
        // The newest rating of a title counts, because a meal can be saved and rated more than once.
        val ratings = mealDao.getRatedMealTitles(MAX_RATINGS_READ).distinctBy { it.title.trim().lowercase() }
        fun ratedTitles(rating: com.example.chatgpt_mealplanner.core.model.MealRating) =
            ratings.filter { it.rating == rating }.map { it.title }.take(MAX_RATED_TITLES_SENT)

        return AiContextPayload(
            currentDate = todayStr,
            dayOfWeek = dayOfWeek,
            householdSize = preferences.householdSize,
            preferences = preferences,
            recentCookedMeals = recentCookedDtos,
            upcomingPlannedMeals = upcomingPlannedDtos,
            availableLeftovers = leftoverDtos,
            useSoonItems = useSoonDtos,
            usableInventory = usableInventoryDtos,
            kitInstructions = kitInstructionDtos,
            savedRecipes = savedRecipeDao?.getAll().orEmpty().map { com.example.chatgpt_mealplanner.contract.SavedRecipeContextDto(it.title, it.servings, it.source) },
            lowStockStaples = lowDtos,
            activeShoppingList = activeShoppingDtos,
            activeProposalTitle = activeProposal?.title,
            pendingMutationTitle = pendingTitle,
            likedMeals = ratedTitles(com.example.chatgpt_mealplanner.core.model.MealRating.UP),
            dislikedMeals = ratedTitles(com.example.chatgpt_mealplanner.core.model.MealRating.DOWN)
        )
    }

    private companion object {
        const val MAX_RATINGS_READ = 200
        const val MAX_RATED_TITLES_SENT = 15
    }

    suspend fun buildContextPayloadJson(conversationId: String = ""): String {
        return json.encodeToString(buildContextPayload(conversationId))
    }
}
