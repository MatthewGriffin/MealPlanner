package com.example.chatgpt_mealplanner.core.barcode

import com.example.chatgpt_mealplanner.core.barcode.model.ConfirmedProductInput
import com.example.chatgpt_mealplanner.core.barcode.model.ProductDataSource
import com.example.chatgpt_mealplanner.core.barcode.model.ProductMatchConfidence
import com.example.chatgpt_mealplanner.core.database.dao.BarcodeMappingDao
import com.example.chatgpt_mealplanner.core.database.entity.ProductBarcodeMappingEntity
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import javax.inject.Inject
import javax.inject.Singleton

data class BarcodeLookupResult(
    val barcode: String,
    val displayName: String,
    val brand: String? = null,
    val canonicalIngredientId: String? = null,
    val canonicalName: String? = null,
    val category: FoodCategory = FoodCategory.CUPBOARD,
    val storageLocation: StorageLocation = StorageLocation.CUPBOARD,
    val netQuantity: Double? = null,
    val netQuantityUnit: QuantityUnit? = null,
    val packCount: Int? = null,
    val packSize: Double? = null,
    val packSizeUnit: QuantityUnit? = null,
    val quantityConfidence: QuantityConfidence = QuantityConfidence.UNKNOWN,
    val sourceName: String = "MANUAL_OR_EXTERNAL"
)

@Singleton
class BarcodeLookupCoordinator @Inject constructor(
    private val barcodeMappingDao: BarcodeMappingDao
) {

    suspend fun lookupBarcode(barcode: String): BarcodeLookupResult {
        // 1. Check user-confirmed local barcode mapping
        val mapping = barcodeMappingDao.getMappingByBarcode(barcode)
        if (mapping != null) {
            return BarcodeLookupResult(
                barcode = barcode,
                displayName = mapping.displayName ?: "Scanned Product",
                brand = mapping.brand,
                canonicalIngredientId = mapping.canonicalIngredientId,
                canonicalName = mapping.canonicalName,
                category = mapping.category ?: FoodCategory.CUPBOARD,
                storageLocation = mapping.defaultStorageLocation ?: StorageLocation.CUPBOARD,
                netQuantity = mapping.netQuantity,
                netQuantityUnit = mapping.netQuantityUnit,
                packCount = mapping.packCount,
                packSize = mapping.packSize,
                packSizeUnit = mapping.packSizeUnit,
                quantityConfidence = if (mapping.netQuantity != null) QuantityConfidence.EXACT else QuantityConfidence.UNKNOWN,
                sourceName = "USER_CONFIRMED_MAPPING"
            )
        }

        // 2. Unconfirmed / Missing quantity fallback: quantity remains UNKNOWN
        return BarcodeLookupResult(
            barcode = barcode,
            displayName = "Scanned Item ($barcode)",
            netQuantity = null, // Missing quantity MUST REMAIN NULL / UNKNOWN
            netQuantityUnit = null,
            quantityConfidence = QuantityConfidence.UNKNOWN,
            sourceName = "EXTERNAL_INCOMPLETE"
        )
    }

    suspend fun confirmAndSaveBarcodeMapping(input: ConfirmedProductInput) {
        if (input.barcode.isNullOrBlank()) return

        val entity = ProductBarcodeMappingEntity(
            barcode = input.barcode,
            displayName = input.displayName,
            brand = input.brand,
            canonicalIngredientId = input.canonicalIngredientId,
            canonicalName = input.canonicalName ?: input.displayName,
            category = input.category,
            defaultStorageLocation = input.storageLocation,
            netQuantity = input.netQuantity,
            netQuantityUnit = input.netQuantityUnit,
            packCount = input.packCount,
            packSize = input.packSize,
            packSizeUnit = input.packSizeUnit,
            imageUrl = null,
            source = ProductDataSource.MANUAL,
            confidence = ProductMatchConfidence.EXACT,
            lastVerifiedAt = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        barcodeMappingDao.insertMapping(entity)
    }
}
