package com.example.chatgpt_mealplanner.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user-defined shop. The stable ID is deliberately separate from the display name so
 * renaming a shop never detaches historical purchase prices.
 */
@Entity(
    tableName = "user_stores",
    indices = [Index("name")]
)
data class UserStoreEntity(
    @PrimaryKey val id: String,
    val name: String,
    val isArchived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * A price the user actually paid. This is a historical observation, not a live quote;
 * observations are appended rather than overwritten when prices change.
 */
@Entity(
    tableName = "purchase_price_observations",
    foreignKeys = [
        ForeignKey(
            entity = UserStoreEntity::class,
            parentColumns = ["id"],
            childColumns = ["storeId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index("storeId"),
        Index("barcode"),
        Index("productName"),
        Index("purchasedAt"),
        Index(value = ["storeId", "barcode", "purchasedAt"])
    ]
)
data class PurchasePriceObservationEntity(
    @PrimaryKey val id: String,
    val storeId: String,
    /** Barcode when available; null is supported for loose or unbarcoded goods. */
    val barcode: String?,
    val productName: String,
    val brand: String?,
    /** Net contents for one retail pack, e.g. 400.0 g. */
    val packQuantity: Double?,
    /** Unit is intentionally stored as text to support future units without DB migrations. */
    val packUnit: String?,
    val packCount: Int?,
    /** Actual total price paid for the recorded pack, in minor currency units (pence in GBP). */
    val priceMinorUnits: Long,
    val currencyCode: String = "GBP",
    val purchasedAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    /** Optional provenance label such as MANUAL or RECEIPT_IMPORT. */
    val source: String = "MANUAL"
)
