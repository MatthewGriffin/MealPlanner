package com.example.chatgpt_mealplanner.core.ai.context

import com.example.chatgpt_mealplanner.contract.AiContextPayload
import com.example.chatgpt_mealplanner.contract.InventorySummaryContextDto
import com.example.chatgpt_mealplanner.contract.LeftoverContextDto
import com.example.chatgpt_mealplanner.contract.RecentMealContextDto
import com.example.chatgpt_mealplanner.contract.ShoppingSummaryContextDto
import com.example.chatgpt_mealplanner.contract.UserPreferencesSummaryDto
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
    private val pendingMutationStore: com.example.chatgpt_mealplanner.core.ai.pending.PendingMutationStore
) {
    private val json = Json { ignoreUnknownKeys = true }

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
                status = it.status.name
            )
        }

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

        return AiContextPayload(
            currentDate = todayStr,
            dayOfWeek = dayOfWeek,
            householdSize = 2,
            preferences = UserPreferencesSummaryDto(),
            recentCookedMeals = recentCookedDtos,
            upcomingPlannedMeals = upcomingPlannedDtos,
            availableLeftovers = leftoverDtos,
            useSoonItems = useSoonDtos,
            usableInventory = usableInventoryDtos,
            lowStockStaples = lowDtos,
            activeShoppingList = activeShoppingDtos,
            activeProposalTitle = activeProposal?.title,
            pendingMutationTitle = pendingTitle
        )
    }

    suspend fun buildContextPayloadJson(conversationId: String = ""): String {
        return json.encodeToString(buildContextPayload(conversationId))
    }
}
