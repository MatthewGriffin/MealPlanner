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

class FollowerMealPlanSyncEngine @Inject constructor(
    private val database: FollowerDatabase,
    private val client: PantrySyncTestClient
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun refresh(host: String, port: Int, fingerprint: String?, token: String,
                        refreshToken: suspend (Boolean) -> String, expectedHouseholdId: String? = null): Boolean {
        var result = client.getBootstrapSnapshot(host, port, fingerprint, token)
        if (result.status == NetworkResultStatus.UNAUTHORIZED) {
            result = client.getBootstrapSnapshot(host, port, fingerprint, refreshToken(true))
        }
        if (!result.success) return false
        val snapshot = result.bootstrapResponse ?: return false
        if (expectedHouseholdId != null && snapshot.householdId != expectedHouseholdId) return false
        return try {
            install(snapshot)
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false // Preserve the previous projection and keep the sync retryable.
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
