package com.example.chatgpt_mealplanner.core.service

import com.example.chatgpt_mealplanner.core.database.dao.RetailDao
import com.example.chatgpt_mealplanner.core.database.entity.RetailPriceSnapshotEntity
import com.example.chatgpt_mealplanner.core.database.entity.RetailProductEntity
import com.example.chatgpt_mealplanner.retail.contract.RetailAvailability
import com.example.chatgpt_mealplanner.retail.contract.RetailStorageClass
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface ReceiptRetailBridgeService {
    suspend fun recordReceiptObservations(
        receiptId: String,
        retailerIdStr: String,
        observedAtMs: Long,
        items: List<ReceiptObservationItem>
    )
}

data class ReceiptObservationItem(
    val lineId: String,
    val description: String,
    val unitPriceMinorPence: Long,
    val netQuantity: Double? = null,
    val netQuantityUnit: String? = null,
    val canonicalIngredientId: String? = null
)

@Singleton
class ReceiptRetailBridgeServiceDelegate @Inject constructor(
    private val retailDao: RetailDao
) : ReceiptRetailBridgeService {

    override suspend fun recordReceiptObservations(
        receiptId: String,
        retailerIdStr: String,
        observedAtMs: Long,
        items: List<ReceiptObservationItem>
    ) {
        val retailerId = runCatching { RetailerId.valueOf(retailerIdStr.uppercase().trim()) }.getOrDefault(RetailerId.ASDA)

        for (item in items) {
            if (item.unitPriceMinorPence <= 0L) continue

            val synthProductId = "RECEIPT_OBS_${item.lineId}"
            val existingProd = retailDao.getProduct(retailerId, synthProductId)
            val productInserted = existingProd == null

            val retailProduct = RetailProductEntity(
                id = "${retailerId.name}_$synthProductId",
                retailerId = retailerId,
                retailerProductId = synthProductId,
                name = item.description,
                brand = null,
                canonicalIngredientId = item.canonicalIngredientId,
                barcode = null,
                category = null,
                imageUrl = null,
                packageType = null,
                netQuantity = item.netQuantity,
                netQuantityUnit = item.netQuantityUnit,
                packCount = null,
                packSize = item.netQuantity,
                packSizeUnit = item.netQuantityUnit,
                storageClass = RetailStorageClass.UNKNOWN,
                createdAt = existingProd?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            retailDao.insertOrUpdateProduct(retailProduct)

            val snapshotId = UUID.nameUUIDFromBytes("SNAP_${item.lineId}".toByteArray()).toString()

            val priceSnapshot = RetailPriceSnapshotEntity(
                id = snapshotId,
                retailerId = retailerId,
                retailerProductId = synthProductId,
                priceMinorPence = item.unitPriceMinorPence,
                currencyCode = "GBP",
                promotionJson = null,
                availability = RetailAvailability.IN_STOCK,
                observedAt = observedAtMs
            )
            retailDao.insertPriceSnapshot(priceSnapshot)

            safeLog("[RECEIPT_RETAIL_OBSERVATION] retailer=${retailerId.name} receiptId=$receiptId lineId=${item.lineId} retailerProductId=$synthProductId priceMinorPence=${item.unitPriceMinorPence} observedAt=$observedAtMs sourceType=RECEIPT priceScope=IN_STORE productInserted=$productInserted")
        }
    }

    private fun safeLog(msg: String) {
        try {
            android.util.Log.i("AI_MEALPLANNER", msg)
        } catch (_: Throwable) {
            println("[AI_MEALPLANNER] $msg")
        }
    }
}
