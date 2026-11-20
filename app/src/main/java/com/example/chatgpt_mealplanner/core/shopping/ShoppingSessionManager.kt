package com.example.chatgpt_mealplanner.core.shopping

import com.example.chatgpt_mealplanner.core.database.dao.StoreOrderDao
import com.example.chatgpt_mealplanner.core.database.entity.ShoppingSessionEntity
import com.example.chatgpt_mealplanner.core.database.entity.ShoppingTickEventEntity
import com.example.chatgpt_mealplanner.core.database.entity.StoreItemOrderEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A shopping trip: "Start shopping" picks the shop, every item ticked is noted as it happens, and finishing learns
 * the order those items were found in. Nothing here uses sensors, and it all stays on the phone.
 */
@Singleton
class ShoppingSessionManager @Inject constructor(private val dao: StoreOrderDao) {

    fun observeOpenSession(): Flow<ShoppingSessionEntity?> = dao.observeOpenSession()

    fun observeOrder(storeId: String): Flow<List<StoreItemOrderEntity>> = dao.observeOrderForStore(storeId)

    /** Starts a trip at [storeId]. A trip left open for hours is finished (and learned from) first. */
    suspend fun start(storeId: String, now: Long = System.currentTimeMillis()) {
        dao.getOpenSession()?.let { finish(it, now) }
        dao.upsertSession(ShoppingSessionEntity(UUID.randomUUID().toString(), storeId, now, null))
    }

    /** Notes an item being ticked off, if a trip is open. Un-ticking is not recorded. */
    suspend fun recordTick(displayName: String, now: Long = System.currentTimeMillis()) {
        val session = dao.getOpenSession() ?: return
        val key = StoreOrder.keyOf(displayName)
        if (key.isBlank()) return
        dao.insertTick(ShoppingTickEventEntity(UUID.randomUUID().toString(), session.id, key, displayName, now))
    }

    suspend fun finishOpenTrip(now: Long = System.currentTimeMillis()) {
        dao.getOpenSession()?.let { finish(it, now) }
    }

    private suspend fun finish(session: ShoppingSessionEntity, now: Long) {
        val ticks = dao.getTicks(session.id)
        // An item ticked twice (ticked, un-ticked, ticked again) counts where it was first ticked.
        val ranks = StoreOrder.ranksOf(ticks.map { it.itemKey })
        val names = ticks.associate { it.itemKey to it.displayName }
        ranks.forEach { (key, rank) ->
            val old = dao.getOrder(session.storeId, key)
            dao.upsertOrder(
                StoreItemOrderEntity(
                    id = old?.id ?: UUID.randomUUID().toString(),
                    storeId = session.storeId,
                    itemKey = key,
                    displayName = names[key] ?: key,
                    avgRank = if (old == null) rank else StoreOrder.mergeRank(old.avgRank, old.samples, rank),
                    samples = (old?.samples ?: 0) + 1,
                    updatedAt = now
                )
            )
        }
        dao.upsertSession(session.copy(endedAt = now))
        val cutoff = now - KEEP_TRIPS_MS
        dao.deleteOldTicks(cutoff)
        dao.deleteOldSessions(cutoff)
    }

    private companion object {
        const val KEEP_TRIPS_MS = 30L * 24 * 60 * 60 * 1000
    }
}
