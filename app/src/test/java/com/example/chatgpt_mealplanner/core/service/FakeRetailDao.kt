package com.example.chatgpt_mealplanner.core.service

import com.example.chatgpt_mealplanner.core.database.dao.RetailDao
import com.example.chatgpt_mealplanner.core.database.entity.BasketLineEntity
import com.example.chatgpt_mealplanner.core.database.entity.BasketPlanEntity
import com.example.chatgpt_mealplanner.core.database.entity.RetailPriceSnapshotEntity
import com.example.chatgpt_mealplanner.core.database.entity.RetailProductEntity
import com.example.chatgpt_mealplanner.core.database.entity.RetailProductMappingEntity
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeRetailDao : RetailDao {

    private val products = mutableMapOf<String, RetailProductEntity>()
    private val snapshots = mutableListOf<RetailPriceSnapshotEntity>()
    private val mappings = mutableMapOf<String, RetailProductMappingEntity>()
    private val plans = mutableListOf<BasketPlanEntity>()
    private val lines = mutableListOf<BasketLineEntity>()

    override suspend fun getProduct(retailerId: RetailerId, productId: String): RetailProductEntity? {
        return products["${retailerId.name}_$productId"]
    }

    override suspend fun getAllProducts(): List<RetailProductEntity> {
        return products.values.toList()
    }

    override suspend fun insertOrUpdateProduct(product: RetailProductEntity) {
        products[product.id] = product
    }

    override suspend fun insertProducts(products: List<RetailProductEntity>) {
        products.forEach { this.products[it.id] = it }
    }

    override suspend fun getLatestSnapshotForProduct(productId: String): RetailPriceSnapshotEntity? {
        return snapshots.filter { it.retailerProductId == productId }.maxByOrNull { it.observedAt }
    }

    override suspend fun getLatestSnapshot(retailerId: RetailerId, productId: String): RetailPriceSnapshotEntity? {
        return snapshots.filter { it.retailerId == retailerId && it.retailerProductId == productId }.maxByOrNull { it.observedAt }
    }

    override suspend fun insertPriceSnapshot(snapshot: RetailPriceSnapshotEntity) {
        snapshots.add(snapshot)
    }

    override suspend fun getMappingsForCanonicalIngredient(canonicalId: String): List<RetailProductMappingEntity> {
        return mappings.values.filter { it.canonicalIngredientId == canonicalId }
    }

    override fun observeAllProductMappings(): Flow<List<RetailProductMappingEntity>> {
        return MutableStateFlow(mappings.values.toList())
    }

    override suspend fun getAllProductMappings(): List<RetailProductMappingEntity> {
        return mappings.values.toList()
    }

    override suspend fun insertOrUpdateMapping(mapping: RetailProductMappingEntity) {
        mappings[mapping.id] = mapping
    }

    override suspend fun getLatestBasketPlan(): BasketPlanEntity? {
        return plans.maxByOrNull { it.generatedAt }
    }

    override suspend fun getBasketLinesForPlan(planId: String): List<BasketLineEntity> {
        return lines.filter { it.basketPlanId == planId }
    }

    override suspend fun insertBasketPlan(plan: BasketPlanEntity) {
        plans.add(plan)
    }

    override suspend fun insertBasketLines(lines: List<BasketLineEntity>) {
        this.lines.addAll(lines)
    }
}
