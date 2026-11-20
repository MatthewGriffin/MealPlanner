package com.example.chatgpt_mealplanner.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Where an item usually comes in the order a household ticks things off in one shop (0 = first, 1 = last).
 * The list is sorted by this the next time they shop there. [itemKey] is the item's name reduced to its core
 * words, not a shopping-list id, because the list is rebuilt (with new ids) whenever it is regenerated.
 */
@Entity(
    tableName = "store_item_order",
    indices = [Index(value = ["storeId", "itemKey"], unique = true)]
)
data class StoreItemOrderEntity(
    @PrimaryKey val id: String,
    val storeId: String,
    val itemKey: String,
    val displayName: String,
    /** Average position over [samples] shops, as a share of the way round the shop. */
    val avgRank: Double,
    val samples: Int,
    val updatedAt: Long
)

/** One trip to a shop. It is open (endedAt null) from "Start shopping" until "Finish shopping". */
@Entity(tableName = "shopping_sessions", indices = [Index("endedAt")])
data class ShoppingSessionEntity(
    @PrimaryKey val id: String,
    val storeId: String,
    val startedAt: Long,
    val endedAt: Long?
)

/** An item ticked off during a trip, kept as it happens because the list itself is rebuilt and loses its ticks. */
@Entity(tableName = "shopping_tick_events", indices = [Index("sessionId")])
data class ShoppingTickEventEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val itemKey: String,
    val displayName: String,
    val tickedAt: Long
)
