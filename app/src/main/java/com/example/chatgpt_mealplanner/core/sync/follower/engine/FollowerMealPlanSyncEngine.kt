package com.example.chatgpt_mealplanner.core.sync.follower.engine

import androidx.room.withTransaction
import com.example.chatgpt_mealplanner.core.database.AppDatabase
import com.example.chatgpt_mealplanner.core.database.entity.ShoppingListEntity
import com.example.chatgpt_mealplanner.core.database.entity.ShoppingListItemEntity
import com.example.chatgpt_mealplanner.core.database.entity.StapleRuleEntity
import com.example.chatgpt_mealplanner.core.model.*
import com.example.chatgpt_mealplanner.core.model.MealWithIngredients
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.core.sync.client.NetworkResultStatus
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerMealEntity
import com.example.chatgpt_mealplanner.sync.contract.BootstrapResponseDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import javax.inject.Inject

data class MealPlanRefreshResult(val success: Boolean, val message: String, val mealCount: Int = 0)

class FollowerMealPlanSyncEngine @Inject constructor(
    private val database: FollowerDatabase,
    private val client: PantrySyncTestClient,
    private val appDatabase: AppDatabase
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun refresh(host: String, port: Int, fingerprint: String?, token: String,
                        refreshToken: suspend (Boolean) -> String, expectedHouseholdId: String? = null): Boolean =
        refreshWithResult(host, port, fingerprint, token, refreshToken, expectedHouseholdId).success

    suspend fun refreshWithResult(host: String, port: Int, fingerprint: String?, token: String,
                        refreshToken: suspend (Boolean) -> String, expectedHouseholdId: String? = null): MealPlanRefreshResult {
        fun failure(message: String): MealPlanRefreshResult {
            println("[MEAL_SYNC] $message")
            return MealPlanRefreshResult(false, message)
        }
        var result = client.getBootstrapSnapshot(host, port, fingerprint, token)
        if (result.status == NetworkResultStatus.UNAUTHORIZED) {
            result = client.getBootstrapSnapshot(host, port, fingerprint, refreshToken(true))
        }
        if (!result.success) return failure("Could not fetch Main's meal plan (${result.status.name}).")
        val snapshot = result.bootstrapResponse ?: return failure("Main returned no meal snapshot.")
        if (expectedHouseholdId != null && snapshot.householdId != expectedHouseholdId) {
            return failure("Meal snapshot household does not match pairing.")
        }
        val meals = snapshot.mealPlan ?: return failure("Main did not publish a meal plan. Check that Main has the latest app build.")
        return try {
            install(snapshot)
            println("[MEAL_SYNC] installed meals=${meals.size}")
            MealPlanRefreshResult(true, "Synced ${meals.size} meals from Main.", meals.size)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            failure("Main's meal snapshot contains invalid meal data.")
        } catch (e: java.time.DateTimeException) {
            failure("Main's meal snapshot contains an invalid meal date.")
        } catch (e: Exception) {
            println("[MEAL_SYNC] install_failed type=${e.javaClass.simpleName}")
            failure("Could not save Main's meal plan on this device.")
        }
    }

    suspend fun install(snapshot: BootstrapResponseDto) {
        // Null means an older Main that does not publish meal plans. Never erase
        // local projections just because an older peer omitted the extension.
        val meals = snapshot.mealPlan ?: return
        require(meals.size <= 5000) { "Meal snapshot too large" }
        require(meals.map { it.meal.id }.distinct().size == meals.size) { "Duplicate meal IDs" }
        val rows = meals.map { entry ->
            val payload = json.encodeToString(entry)
            val model = json.decodeFromString<MealWithIngredients>(payload)
            require(model.meal.id.isNotBlank() && model.meal.title.isNotBlank())
            java.time.LocalDate.parse(model.meal.date)
            require(model.meal.servings > 0)
            require(model.ingredients.all { it.mealId == model.meal.id && it.plannedQuantity.isFinite() && it.plannedQuantity >= 0 })
            FollowerMealEntity(model.meal.id, payload)
        }
        val shoppingListDtos = snapshot.shoppingLists
        val shoppingLists = shoppingListDtos?.map { dto ->
            ShoppingListEntity(dto.id, dto.name, dto.startDate, dto.endDate,
                enumValueOf(dto.status), dto.createdAt, dto.updatedAt)
        }
        val shoppingItems = if (shoppingListDtos != null) {
            val knownListIds = shoppingLists.orEmpty().mapTo(mutableSetOf()) { it.id }
            val itemDtos = snapshot.shoppingItems ?: emptyList()
            require(itemDtos.all { it.shoppingListId in knownListIds }) { "Shopping item references an unknown list" }
            itemDtos.map { dto ->
                ShoppingListItemEntity(
                    id = dto.id, shoppingListId = dto.shoppingListId, canonicalIngredientId = dto.canonicalIngredientId,
                    displayName = dto.displayName, requiredQuantity = dto.requiredQuantity,
                    requiredUnit = dto.requiredUnit?.let { enumValueOf<QuantityUnit>(it) },
                    suggestedPurchaseQuantity = dto.suggestedPurchaseQuantity,
                    suggestedPurchaseUnit = dto.suggestedPurchaseUnit?.let { enumValueOf<QuantityUnit>(it) },
                    packCount = dto.packCount, reason = dto.reason, priority = enumValueOf(dto.priority),
                    status = enumValueOf(dto.status), optional = dto.optional, sourceType = enumValueOf(dto.sourceType),
                    hasMealRequirement = dto.hasMealRequirement, hasStapleRestock = dto.hasStapleRestock,
                    mealShortfallQuantity = dto.mealShortfallQuantity, stapleReserveTopUpQuantity = dto.stapleReserveTopUpQuantity,
                    expectedFinalInventory = dto.expectedFinalInventory,
                    expectedFinalInventoryUnit = dto.expectedFinalInventoryUnit?.let { enumValueOf<QuantityUnit>(it) },
                    quantityUncertain = dto.quantityUncertain, uncertaintyReason = dto.uncertaintyReason,
                    requirementConfidence = enumValueOf(dto.requirementConfidence), wasteRisk = enumValueOf(dto.wasteRisk),
                    createdAt = dto.createdAt, updatedAt = dto.updatedAt
                )
            }
        } else null
        val stapleRules = snapshot.stapleRules?.map { dto ->
            StapleRuleEntity(
                id = dto.id, canonicalIngredientId = dto.canonicalIngredientId, displayName = dto.displayName,
                enabled = dto.enabled, archivedByMerge = dto.archivedByMerge, mode = enumValueOf(dto.mode),
                thresholdQuantity = dto.thresholdQuantity, thresholdUnit = dto.thresholdUnit?.let { enumValueOf<QuantityUnit>(it) },
                targetQuantity = dto.targetQuantity, targetUnit = dto.targetUnit?.let { enumValueOf<QuantityUnit>(it) },
                thresholdPackCount = dto.thresholdPackCount, targetPackCount = dto.targetPackCount,
                preferredStorageLocation = dto.preferredStorageLocation?.let { enumValueOf<StorageLocation>(it) },
                notes = dto.notes, createdAt = dto.createdAt, updatedAt = dto.updatedAt
            )
        }
        database.withTransaction {
            database.mealDao().clear()
            database.mealDao().insertAll(rows)
        }
        appDatabase.withTransaction {
            if (shoppingLists != null) {
                require(shoppingLists.count { it.status == ShoppingListStatus.ACTIVE } <= 1) { "Multiple active shopping lists" }
                appDatabase.shoppingDao().deleteAllShoppingLists()
                if (shoppingLists.isNotEmpty()) appDatabase.shoppingDao().insertShoppingLists(shoppingLists)
                if (!shoppingItems.isNullOrEmpty()) appDatabase.shoppingDao().insertShoppingListItems(shoppingItems)
            }
            if (stapleRules != null) {
                appDatabase.stapleRuleDao().deleteAllStapleRules()
                if (stapleRules.isNotEmpty()) appDatabase.stapleRuleDao().insertStapleRules(stapleRules)
            }
        }
    }
}
