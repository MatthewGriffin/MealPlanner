package com.example.chatgpt_mealplanner.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.chatgpt_mealplanner.core.database.entity.ProductBarcodeMappingEntity

@Dao
interface BarcodeMappingDao {

    @Query("SELECT * FROM product_barcode_mappings WHERE barcode = :barcode")
    suspend fun getMappingByBarcode(barcode: String): ProductBarcodeMappingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMapping(mapping: ProductBarcodeMappingEntity)

    @Query("DELETE FROM product_barcode_mappings WHERE barcode = :barcode")
    suspend fun deleteMapping(barcode: String)
}
