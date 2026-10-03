package com.example.chatgpt_mealplanner.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptImportEntity
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptImportLineEntity
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptProductMappingEntity
import kotlinx.coroutines.flow.Flow

data class ReceiptImportWithLines(
    val receiptImport: ReceiptImportEntity,
    val lines: List<ReceiptImportLineEntity>
)

@Dao
interface ReceiptDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReceiptImport(importEntity: ReceiptImportEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReceiptLines(lines: List<ReceiptImportLineEntity>)

    @Query("SELECT * FROM receipt_imports WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun getImportByFingerprint(fingerprint: String): ReceiptImportEntity?

    @Query("SELECT * FROM receipt_imports ORDER BY createdAt DESC")
    fun observeAllReceiptImports(): Flow<List<ReceiptImportEntity>>

    @Query("SELECT * FROM receipt_import_lines WHERE receiptImportId = :importId")
    suspend fun getLinesForImport(importId: String): List<ReceiptImportLineEntity>

    @Query("SELECT * FROM receipt_product_mappings WHERE retailerId = :retailerId AND normalizedDescription = :normalizedDescription LIMIT 1")
    suspend fun getMapping(retailerId: String, normalizedDescription: String): ReceiptProductMappingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateMapping(mapping: ReceiptProductMappingEntity)

    @Transaction
    suspend fun saveReceiptImportWithLines(importEntity: ReceiptImportEntity, lines: List<ReceiptImportLineEntity>) {
        insertReceiptImport(importEntity)
        insertReceiptLines(lines)
    }
}
