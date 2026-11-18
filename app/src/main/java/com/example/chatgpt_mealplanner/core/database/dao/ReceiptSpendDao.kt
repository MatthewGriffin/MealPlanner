package com.example.chatgpt_mealplanner.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptCategoryMappingEntity
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptQuantityMemoryEntity
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptSpendEntity
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptSpendItemEntity
import kotlinx.coroutines.flow.Flow

data class ReceiptSpendWithItems(
    val spend: ReceiptSpendEntity,
    val items: List<ReceiptSpendItemEntity>
)

@Dao
interface ReceiptSpendDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReceiptSpend(spend: ReceiptSpendEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReceiptSpendItems(items: List<ReceiptSpendItemEntity>)

    @Query("SELECT * FROM receipt_spend WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun getSpendByFingerprint(fingerprint: String): ReceiptSpendEntity?

    @Query("SELECT * FROM receipt_spend ORDER BY purchasedAt DESC, createdAt DESC")
    fun observeAllReceiptSpends(): Flow<List<ReceiptSpendEntity>>

    @Query("SELECT * FROM receipt_spend_items WHERE receiptSpendId = :spendId")
    suspend fun getItemsForSpend(spendId: String): List<ReceiptSpendItemEntity>

    @Query("SELECT * FROM receipt_category_mappings WHERE retailerId = :retailerId AND normalizedDescription = :normalizedDescription LIMIT 1")
    suspend fun getCategoryMapping(retailerId: String, normalizedDescription: String): ReceiptCategoryMappingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateCategoryMapping(mapping: ReceiptCategoryMappingEntity)

    @Query("SELECT * FROM receipt_quantity_memory WHERE retailerId = :retailerId AND productKey = :productKey LIMIT 1")
    suspend fun getQuantityMemory(retailerId: String, productKey: String): ReceiptQuantityMemoryEntity?

    @Query("SELECT * FROM receipt_quantity_memory WHERE productKey = :productKey ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getAnyQuantityMemory(productKey: String): ReceiptQuantityMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateQuantityMemory(memory: ReceiptQuantityMemoryEntity)

    @Transaction
    suspend fun saveReceiptSpendWithItems(spend: ReceiptSpendEntity, items: List<ReceiptSpendItemEntity>) {
        insertReceiptSpend(spend)
        insertReceiptSpendItems(items)
    }
}
