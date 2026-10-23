package com.example.chatgpt_mealplanner.core.repository

import com.example.chatgpt_mealplanner.core.database.dao.RetailDao
import com.example.chatgpt_mealplanner.core.database.entity.RetailPriceSnapshotEntity
import com.example.chatgpt_mealplanner.core.database.entity.RetailProductEntity
import com.example.chatgpt_mealplanner.core.database.entity.RetailProductMappingEntity
import com.example.chatgpt_mealplanner.retail.contract.Money
import com.example.chatgpt_mealplanner.retail.contract.RetailOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailOfferSourceType
import com.example.chatgpt_mealplanner.retail.contract.RetailPriceScope
import com.example.chatgpt_mealplanner.retail.contract.RetailProduct
import com.example.chatgpt_mealplanner.retail.contract.RetailProductMappingDecision
import com.example.chatgpt_mealplanner.retail.contract.RetailProductWithOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import com.example.chatgpt_mealplanner.retail.engine.UserProductMappingKey
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RetailRepositoryImpl @Inject constructor(
    private val retailDao: RetailDao
) : RetailRepository {

    override suspend fun getProductMappings(): Map<UserProductMappingKey, RetailProductMappingDecision> {
        val entities = retailDao.getAllProductMappings()
        return entities.associate { entity ->
            val key = UserProductMappingKey(
                retailerId = entity.retailerId.name,
                retailerProductId = entity.retailerProductId,
                canonicalIngredientId = entity.canonicalIngredientId
            )
            key to entity.decision
        }
    }

    override suspend fun saveProductMapping(
        retailerId: RetailerId,
        retailerProductId: String,
        canonicalIngredientId: String,
        decision: RetailProductMappingDecision,
        userConfirmed: Boolean
    ) {
        val entity = RetailProductMappingEntity(
            id = "${retailerId.name}_${retailerProductId}_$canonicalIngredientId",
            retailerId = retailerId,
            retailerProductId = retailerProductId,
            canonicalIngredientId = canonicalIngredientId,
            decision = decision,
            userConfirmed = userConfirmed,
            updatedAt = System.currentTimeMillis()
        )
        retailDao.insertOrUpdateMapping(entity)
    }

    override suspend fun getCandidateProductsWithOffers(): List<RetailProductWithOffer> {
        val products = retailDao.getAllProducts()
        if (products.isEmpty()) return emptyList()

        val result = mutableListOf<RetailProductWithOffer>()
        for (prodEntity in products) {
            val snapshot = retailDao.getLatestSnapshot(prodEntity.retailerId, prodEntity.retailerProductId)
                ?: continue

            val domainProduct = RetailProduct(
                retailerId = prodEntity.retailerId,
                retailerProductId = prodEntity.retailerProductId,
                name = prodEntity.name,
                brand = prodEntity.brand,
                canonicalIngredientId = prodEntity.canonicalIngredientId,
                barcode = prodEntity.barcode,
                category = prodEntity.category,
                imageUrl = prodEntity.imageUrl,
                packageType = prodEntity.packageType,
                netQuantity = prodEntity.netQuantity,
                netQuantityUnit = prodEntity.netQuantityUnit,
                packCount = prodEntity.packCount,
                packSize = prodEntity.packSize,
                packSizeUnit = prodEntity.packSizeUnit,
                storageClass = prodEntity.storageClass
            )

            val isReceipt = snapshot.retailerProductId.startsWith("RECEIPT_")
            val priceScope = if (isReceipt) RetailPriceScope.IN_STORE else RetailPriceScope.ONLINE
            val sourceType = if (isReceipt) RetailOfferSourceType.RECEIPT else RetailOfferSourceType.CACHE

            val domainOffer = RetailOffer(
                retailerId = snapshot.retailerId,
                retailerProductId = snapshot.retailerProductId,
                price = Money(snapshot.priceMinorPence, snapshot.currencyCode),
                promotion = null,
                availability = snapshot.availability,
                priceScope = priceScope,
                sourceType = sourceType,
                observedAt = snapshot.observedAt
            )

            if (prodEntity.retailerId == RetailerId.ASDA || isReceipt) {
                val ageMs = System.currentTimeMillis() - snapshot.observedAt
                val freshness = when {
                    ageMs > 30 * 86400000L -> "EXPIRED"
                    ageMs > 7 * 86400000L -> "STALE"
                    else -> "FRESH"
                }
                safeLog("[ASDA_RETAIL_CANDIDATE] productName=\"${prodEntity.name}\" retailerProductId=\"${prodEntity.retailerProductId}\" priceMinorPence=${snapshot.priceMinorPence} sourceType=$sourceType priceScope=$priceScope freshness=$freshness canonicalIngredientId=${prodEntity.canonicalIngredientId} packQuantityKnown=${prodEntity.packSize != null || prodEntity.netQuantity != null}")
            }

            result.add(RetailProductWithOffer(domainProduct, domainOffer))
        }
        return result
    }

    private fun safeLog(msg: String) {
        try {
            android.util.Log.i("AI_MEALPLANNER", msg)
        } catch (_: Throwable) {
            println("[AI_MEALPLANNER] $msg")
        }
    }

    override suspend fun replaceCatalogueWithOffers(
        retailerId: RetailerId,
        items: List<RetailProductWithOffer>
    ) {
        require(items.all { it.product.retailerId == retailerId && it.offer.retailerId == retailerId }) {
            "Catalogue items must all belong to the requested retailer."
        }
        val now = System.currentTimeMillis()
        val productEntities = items.map { item ->
            RetailProductEntity(
                id = "${item.product.retailerId.name}_${item.product.retailerProductId}",
                retailerId = item.product.retailerId,
                retailerProductId = item.product.retailerProductId,
                name = item.product.name,
                brand = item.product.brand,
                canonicalIngredientId = item.product.canonicalIngredientId,
                barcode = item.product.barcode,
                category = item.product.category,
                imageUrl = item.product.imageUrl,
                packageType = item.product.packageType,
                netQuantity = item.product.netQuantity,
                netQuantityUnit = item.product.netQuantityUnit,
                packCount = item.product.packCount,
                packSize = item.product.packSize,
                packSizeUnit = item.product.packSizeUnit,
                storageClass = item.product.storageClass,
                createdAt = now,
                updatedAt = now
            )
        }
        val snapshots = items.map { item ->
            require(item.offer.sourceType != RetailOfferSourceType.FIXTURE) {
                "Production RetailRepository cannot cache FIXTURE source offers."
            }
            RetailPriceSnapshotEntity(
                id = UUID.randomUUID().toString(),
                retailerId = item.offer.retailerId,
                retailerProductId = item.offer.retailerProductId,
                priceMinorPence = item.offer.price.amountMinor,
                currencyCode = item.offer.price.currencyCode,
                promotionJson = null,
                availability = item.offer.availability,
                observedAt = item.offer.observedAt
            )
        }
        // Room runs this delete-and-insert operation as one transaction. If any write fails,
        // the previous retailer catalogue and its price snapshots remain intact.
        retailDao.replaceRetailerCatalogue(retailerId, productEntities, snapshots)
    }

    override suspend fun cacheProductsWithOffers(items: List<RetailProductWithOffer>) {
        val prodEntities = items.map {
            RetailProductEntity(
                id = "${it.product.retailerId.name}_${it.product.retailerProductId}",
                retailerId = it.product.retailerId,
                retailerProductId = it.product.retailerProductId,
                name = it.product.name,
                brand = it.product.brand,
                canonicalIngredientId = it.product.canonicalIngredientId,
                barcode = it.product.barcode,
                category = it.product.category,
                imageUrl = it.product.imageUrl,
                packageType = it.product.packageType,
                netQuantity = it.product.netQuantity,
                netQuantityUnit = it.product.netQuantityUnit,
                packCount = it.product.packCount,
                packSize = it.product.packSize,
                packSizeUnit = it.product.packSizeUnit,
                storageClass = it.product.storageClass,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        }

        val snapshotEntities = items.map {
            // Production safety check: assert sourceType != FIXTURE
            require(it.offer.sourceType != RetailOfferSourceType.FIXTURE) {
                "Production RetailRepository cannot cache FIXTURE source offers."
            }

            RetailPriceSnapshotEntity(
                id = UUID.randomUUID().toString(),
                retailerId = it.offer.retailerId,
                retailerProductId = it.offer.retailerProductId,
                priceMinorPence = it.offer.price.amountMinor,
                currencyCode = it.offer.price.currencyCode,
                promotionJson = null,
                availability = it.offer.availability,
                observedAt = it.offer.observedAt
            )
        }

        retailDao.insertProducts(prodEntities)
        snapshotEntities.forEach { retailDao.insertPriceSnapshot(it) }
    }
}
