package com.example.chatgpt_mealplanner.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.chatgpt_mealplanner.core.database.entity.PurchasePriceObservationEntity
import com.example.chatgpt_mealplanner.core.database.entity.PurchasePriceWithStore
import com.example.chatgpt_mealplanner.core.database.entity.UserStoreEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PurchasePriceDao {
    @Query("SELECT * FROM user_stores WHERE isArchived = 0 ORDER BY name COLLATE NOCASE")
    fun observeActiveStores(): Flow<List<UserStoreEntity>>

    @Query("SELECT * FROM user_stores ORDER BY name COLLATE NOCASE")
    suspend fun getAllStores(): List<UserStoreEntity>

    @Query("SELECT * FROM user_stores WHERE id = :id LIMIT 1")
    suspend fun getStore(id: String): UserStoreEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStore(store: UserStoreEntity)

    @Update
    suspend fun updateStore(store: UserStoreEntity)

    @Query("UPDATE user_stores SET isArchived = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun archiveStore(id: String, updatedAt: Long = System.currentTimeMillis())

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPriceObservation(observation: PurchasePriceObservationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPriceObservations(observations: List<PurchasePriceObservationEntity>)

    @Query("SELECT * FROM purchase_price_observations WHERE id = :id LIMIT 1")
    suspend fun getObservation(id: String): PurchasePriceObservationEntity?

    @Query("SELECT * FROM purchase_price_observations WHERE storeId = :storeId ORDER BY purchasedAt DESC")
    fun observeStoreHistory(storeId: String): Flow<List<PurchasePriceObservationEntity>>

    @Query("SELECT * FROM purchase_price_observations WHERE barcode = :barcode ORDER BY purchasedAt DESC")
    suspend fun getHistoryForBarcode(barcode: String): List<PurchasePriceObservationEntity>

    @Query("SELECT * FROM purchase_price_observations WHERE storeId = :storeId AND barcode = :barcode ORDER BY purchasedAt DESC LIMIT 1")
    suspend fun getLatestPriceForBarcodeAtStore(storeId: String, barcode: String): PurchasePriceObservationEntity?

    @Query("SELECT purchase_price_observations.*, user_stores.name AS storeName FROM purchase_price_observations INNER JOIN user_stores ON user_stores.id = purchase_price_observations.storeId WHERE user_stores.isArchived = 0 AND purchase_price_observations.productName = :productName COLLATE NOCASE ORDER BY purchase_price_observations.purchasedAt DESC")
    suspend fun getPriceHistoryByExactProductName(productName: String): List<PurchasePriceWithStore>

    @Query("SELECT purchase_price_observations.*, user_stores.name AS storeName FROM purchase_price_observations INNER JOIN user_stores ON user_stores.id = purchase_price_observations.storeId WHERE user_stores.isArchived = 0 ORDER BY purchasedAt DESC")
    suspend fun getActiveStorePriceHistory(): List<PurchasePriceWithStore>

    @Query("SELECT * FROM purchase_price_observations WHERE storeId = :storeId AND productName = :productName COLLATE NOCASE ORDER BY purchasedAt DESC LIMIT 1")
    suspend fun getLatestPriceByNameAtStore(storeId: String, productName: String): PurchasePriceObservationEntity?

    @Query("SELECT purchase_price_observations.*, user_stores.name AS storeName FROM purchase_price_observations INNER JOIN user_stores ON user_stores.id = purchase_price_observations.storeId ORDER BY purchasedAt DESC LIMIT 100")
    fun observeRecentPriceHistory(): Flow<List<PurchasePriceWithStore>>

    @Query("SELECT * FROM purchase_price_observations ORDER BY purchasedAt DESC")
    suspend fun getAllPriceObservations(): List<PurchasePriceObservationEntity>
}
