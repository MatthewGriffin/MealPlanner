package com.example.chatgpt_mealplanner.core.sync.follower

import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerBasketComparisonCachePayload
import com.example.chatgpt_mealplanner.retail.contract.BasketComparisonResult
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FollowerBasketComparisonCacheStore(
    private val database: FollowerDatabase? = null
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun getCachedComparison(): BasketComparisonResult? {
        val payload = try { database?.mealDao()?.getById(CACHE_ID)?.payloadJson } catch (_: Exception) { null }
        return decode(payload)
    }

    fun observeCachedComparison(): Flow<BasketComparisonResult?> =
        database?.mealDao()?.observeAll()?.map { rows -> decode(rows.firstOrNull { it.id == CACHE_ID }?.payloadJson) }
            ?: flowOf(null)

    private fun decode(payload: String?): BasketComparisonResult? {
        if (payload.isNullOrBlank()) return null
        return try {
            val envelope = json.decodeFromString<FollowerBasketComparisonCachePayload>(payload)
            json.decodeFromString<BasketComparisonResult>(envelope.payloadJson)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val CACHE_ID = "basket_comparison_cache"
    }
}
