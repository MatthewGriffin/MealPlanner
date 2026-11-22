package com.example.chatgpt_mealplanner.core.shopping

import com.example.chatgpt_mealplanner.core.database.dao.StoreOrderDao
import com.example.chatgpt_mealplanner.core.database.entity.ShoppingSessionEntity
import com.example.chatgpt_mealplanner.core.database.entity.ShoppingTickEventEntity
import com.example.chatgpt_mealplanner.core.database.entity.StoreItemOrderEntity
import com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** An item ticked off, with where the phone was if the motion sensors were on. */
data class TickedItem(val displayName: String, val x: Double? = null, val y: Double? = null)

/**
 * A shopping trip: "Start shopping" picks the shop, every item ticked is noted as it happens, and finishing learns
 * the order those items were found in. On the main phone the order is learned straight away; a follower keeps the
 * trip until it can send it to Main, which learns from it. Nothing here uses sensors.
 */
@Singleton
class ShoppingSessionManager @Inject constructor(
    private val dao: StoreOrderDao,
    private val appRoleResolver: AppRoleResolver? = null,
    val departmentOverrides: DepartmentOverrides? = null
) {
    private fun isFollower(): Boolean = appRoleResolver?.isFollower() == true

    fun observeOpenSession(): Flow<ShoppingSessionEntity?> = dao.observeOpenSession()

    fun observeOrder(storeId: String): Flow<List<StoreItemOrderEntity>> = dao.observeOrderForStore(storeId)

    /** Starts a trip at [storeId]. A trip left open for hours is finished (and learned from) first. */
    suspend fun start(storeId: String, now: Long = System.currentTimeMillis()) {
        dao.getOpenSession()?.let { finish(it, now) }
        dao.upsertSession(ShoppingSessionEntity(UUID.randomUUID().toString(), storeId, now, null))
    }

    /** Notes an item being ticked off, if a trip is open. Un-ticking is not recorded. */
    suspend fun recordTick(displayName: String, position: ShopPoint? = null, now: Long = System.currentTimeMillis()) {
        val session = dao.getOpenSession() ?: return
        val key = StoreOrder.keyOf(displayName)
        if (key.isBlank()) return
        dao.insertTick(ShoppingTickEventEntity(UUID.randomUUID().toString(), session.id, key, displayName, now, position?.x, position?.y))
    }

    suspend fun finishOpenTrip(now: Long = System.currentTimeMillis()) {
        dao.getOpenSession()?.let { finish(it, now) }
    }

    private suspend fun finish(session: ShoppingSessionEntity, now: Long) {
        if (isFollower()) {
            // Main does the learning; this trip waits (uploadedAt null) until it has been sent.
            dao.upsertSession(session.copy(endedAt = now))
        } else {
            learn(session.storeId, dao.getTicks(session.id).map { TickedItem(it.displayName, it.x, it.y) }, now)
            dao.upsertSession(session.copy(endedAt = now, uploadedAt = now))
        }
        val cutoff = now - KEEP_TRIPS_MS
        dao.deleteOldTicks(cutoff)
        dao.deleteOldSessions(cutoff)
    }

    /** Main: learns from a trip a follower made. Sending it twice (a lost reply) counts once. */
    suspend fun learnFromFollowerTrip(tripId: String, storeId: String, startedAt: Long, endedAt: Long, ticksInOrder: List<TickedItem>, now: Long = System.currentTimeMillis()) {
        if (dao.getSession(tripId) != null) return
        dao.upsertSession(ShoppingSessionEntity(tripId, storeId, startedAt, endedAt, uploadedAt = now))
        learn(storeId, ticksInOrder, now)
    }

    /** A follower's finished trips, ready to send. */
    suspend fun tripsToUpload(): List<PendingTrip> = dao.getTripsToUpload().map { session ->
        PendingTrip(session, dao.getTicks(session.id))
    }

    suspend fun markUploaded(tripId: String, now: Long = System.currentTimeMillis()) {
        dao.getSession(tripId)?.let { dao.upsertSession(it.copy(uploadedAt = now)) }
    }

    /** A follower replaces what it holds of each shop's order with Main's. */
    suspend fun installOrders(orders: List<StoreItemOrderEntity>) {
        dao.deleteAllOrders()
        if (orders.isNotEmpty()) dao.upsertOrders(orders)
    }

    suspend fun allOrders(): List<StoreItemOrderEntity> = dao.getAllOrders()

    private suspend fun learn(storeId: String, ticksInOrder: List<TickedItem>, now: Long) {
        val keys = ticksInOrder.map { StoreOrder.keyOf(it.displayName) }
        // An item ticked twice (ticked, un-ticked, ticked again) counts where it was first ticked.
        val ranks = StoreOrder.ranksOf(keys)
        val firstTick = ticksInOrder.groupBy { StoreOrder.keyOf(it.displayName) }.mapValues { it.value.first() }
        ranks.forEach { (key, rank) ->
            val old = dao.getOrder(storeId, key)
            val tick = firstTick[key]
            val sensed = if (tick?.x != null && tick.y != null) ShopPoint(tick.x, tick.y) else null
            val oldPoint = if (old?.avgX != null && old.avgY != null) ShopPoint(old.avgX, old.avgY) else null
            val merged = when {
                sensed == null -> oldPoint
                else -> StoreRoute.mergePoint(oldPoint, old?.posSamples ?: 0, sensed)
            }
            dao.upsertOrder(
                StoreItemOrderEntity(
                    id = old?.id ?: UUID.randomUUID().toString(),
                    storeId = storeId,
                    itemKey = key,
                    displayName = tick?.displayName ?: key,
                    avgRank = if (old == null) rank else StoreOrder.mergeRank(old.avgRank, old.samples, rank),
                    samples = (old?.samples ?: 0) + 1,
                    updatedAt = now,
                    avgX = merged?.x,
                    avgY = merged?.y,
                    posSamples = (old?.posSamples ?: 0) + if (sensed != null) 1 else 0
                )
            )
        }
    }

    data class PendingTrip(val session: ShoppingSessionEntity, val ticks: List<ShoppingTickEventEntity>)

    private companion object {
        const val KEEP_TRIPS_MS = 30L * 24 * 60 * 60 * 1000
    }
}
