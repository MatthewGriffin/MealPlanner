package com.example.chatgpt_mealplanner.core.sync.follower.engine

import androidx.room.withTransaction
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
    private val client: PantrySyncTestClient
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
        database.withTransaction {
            database.mealDao().clear()
            database.mealDao().insertAll(rows)
        }
    }
}
