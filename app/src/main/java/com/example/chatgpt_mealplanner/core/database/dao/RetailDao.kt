package com.example.chatgpt_mealplanner.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.chatgpt_mealplanner.core.database.entity.BasketLineEntity
import com.example.chatgpt_mealplanner.core.database.entity.BasketPlanEntity
import com.example.chatgpt_mealplanner.core.database.entity.RetailPriceSnapshotEntity
import com.example.chatgpt_mealplanner.core.database.entity.RetailProductEntity
import com.example.chatgpt_mealplanner.core.database.entity.RetailProductMappingEntity
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import kotlinx.coroutines.flow.Flow

@Dao
interface RetailDao {

    @Query("SELECT * FROM retail_products WHERE retailerId = :retailerId AND retailerProductId = :productId")
    suspend fun getProduct(retailerId: RetailerId, productId: String): RetailProductEntity?

    @Query("SELECT * FROM retail_products")
    suspend fun getAllProducts(): List<RetailProductEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateProduct(product: RetailProductEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProducts(products: List<RetailProductEntity>)

    @Query("SELECT * FROM retail_price_snapshots WHERE retailerProductId = :productId ORDER BY observedAt DESC LIMIT 1")
    suspend fun getLatestSnapshotForProduct(productId: String): RetailPriceSnapshotEntity?

    @Query("SELECT * FROM retail_price_snapshots WHERE retailerId = :retailerId AND retailerProductId = :productId ORDER BY observedAt DESC LIMIT 1")
    suspend fun getLatestSnapshot(retailerId: RetailerId, productId: String): RetailPriceSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPriceSnapshot(snapshot: RetailPriceSnapshotEntity)

    @Query("SELECT * FROM retail_product_mappings WHERE canonicalIngredientId = :canonicalId")
    suspend fun getMappingsForCanonicalIngredient(canonicalId: String): List<RetailProductMappingEntity>

    @Query("SELECT * FROM retail_product_mappings")
    fun observeAllProductMappings(): Flow<List<RetailProductMappingEntity>>

    @Query("SELECT * FROM retail_product_mappings")
    suspend fun getAllProductMappings(): List<RetailProductMappingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateMapping(mapping: RetailProductMappingEntity)

    @Query("SELECT * FROM basket_plans ORDER BY generatedAt DESC LIMIT 1")
    suspend fun getLatestBasketPlan(): BasketPlanEntity?

    @Query("SELECT * FROM basket_lines WHERE basketPlanId = :planId")
    suspend fun getBasketLinesForPlan(planId: String): List<BasketLineEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBasketPlan(plan: BasketPlanEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBasketLines(lines: List<BasketLineEntity>)
}
