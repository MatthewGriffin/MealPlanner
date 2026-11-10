package com.example.chatgpt_mealplanner.core.barcode

import com.example.chatgpt_mealplanner.core.barcode.model.ConfirmedProductInput
import com.example.chatgpt_mealplanner.core.barcode.model.ProductDataSource
import com.example.chatgpt_mealplanner.core.barcode.model.ProductMatchConfidence
import com.example.chatgpt_mealplanner.core.barcode.repository.ProductLookupRepository
import com.example.chatgpt_mealplanner.core.barcode.repository.ProductLookupSearchResult
import com.example.chatgpt_mealplanner.core.barcode.util.BarcodeNormalizer
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
    val category: FoodCategory = FoodCategory.DRY_GOODS,
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
    private val barcodeMappingDao: BarcodeMappingDao,
    private val productLookupRepository: ProductLookupRepository? = null
) {

    suspend fun lookupBarcode(barcode: String): BarcodeLookupResult {
        // 1. Check user-confirmed local barcode mapping
        val mapping = barcodeMappingDao.getMappingByBarcode(barcode)
        if (mapping != null) {
            val isInternal = BarcodeNormalizer.isVariableWeightOrInternalBarcode(barcode)
            val parsedInfo = BarcodeNormalizer.parseProductTitle(mapping.displayName ?: "Scanned Product")

            return BarcodeLookupResult(
                barcode = barcode,
                displayName = mapping.displayName ?: "Scanned Product",
                brand = mapping.brand,
                canonicalIngredientId = mapping.canonicalIngredientId,
                canonicalName = mapping.canonicalName,
                category = mapping.category ?: FoodCategory.DRY_GOODS,
                storageLocation = mapping.defaultStorageLocation ?: StorageLocation.CUPBOARD,
                netQuantity = mapping.netQuantity,
                netQuantityUnit = mapping.netQuantityUnit,
                packCount = mapping.packCount ?: parsedInfo.extractedPackCount,
                packSize = mapping.packSize ?: parsedInfo.extractedPackSize,
                packSizeUnit = mapping.packSizeUnit ?: parsedInfo.extractedPackSizeUnit,
                quantityConfidence = if (isInternal) QuantityConfidence.APPROXIMATE else if (mapping.netQuantity != null) QuantityConfidence.EXACT else QuantityConfidence.UNKNOWN,
                sourceName = if (isInternal) "VARIABLE_WEIGHT_STORE_INTERNAL" else "USER_CONFIRMED_MAPPING"
            )
        }

        // 2. Unconfirmed / Variable-weight / Missing quantity fallback
        val isInternal = BarcodeNormalizer.isVariableWeightOrInternalBarcode(barcode)
        return BarcodeLookupResult(
            barcode = barcode,
            displayName = if (isInternal) "Variable Weight Item ($barcode)" else "Scanned Item ($barcode)",
            netQuantity = null, // Missing quantity MUST REMAIN NULL / UNKNOWN
            netQuantityUnit = null,
            quantityConfidence = if (isInternal) QuantityConfidence.APPROXIMATE else QuantityConfidence.UNKNOWN,
            sourceName = if (isInternal) "VARIABLE_WEIGHT_STORE_INTERNAL" else "EXTERNAL_INCOMPLETE"
        )
    }

    suspend fun searchProductsByName(query: String): List<BarcodeLookupResult> =
        searchProductsByNameOrNull(query) ?: emptyList()

    /**
     * Like [searchProductsByName], but null when the online service failed and nothing local was found,
     * so a caller can tell "no such product" from "the service was busy".
     */
    suspend fun searchProductsByNameOrNull(query: String): List<BarcodeLookupResult>? {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        var remoteFailed = false

        val resultsList = mutableListOf<BarcodeLookupResult>()
        val seenBarcodes = mutableSetOf<String>()

        // 1. Local Confirmed Product Mappings (Highest Priority, Saved Local Provenance)
        val localMappings = barcodeMappingDao.searchMappingsByName(trimmed)
        for (mapping in localMappings) {
            val isInternal = BarcodeNormalizer.isVariableWeightOrInternalBarcode(mapping.barcode)
            val parsedInfo = BarcodeNormalizer.parseProductTitle(mapping.displayName ?: "Scanned Product")
            val barcodeKey = mapping.barcode.trim()
            if (barcodeKey.isNotBlank()) seenBarcodes.add(barcodeKey)

            resultsList.add(
                BarcodeLookupResult(
                    barcode = mapping.barcode,
                    displayName = mapping.displayName ?: parsedInfo.cleanName,
                    brand = mapping.brand,
                    canonicalIngredientId = mapping.canonicalIngredientId,
                    canonicalName = mapping.canonicalName,
                    category = mapping.category ?: FoodCategory.DRY_GOODS,
                    storageLocation = mapping.defaultStorageLocation ?: StorageLocation.CUPBOARD,
                    netQuantity = mapping.netQuantity,
                    netQuantityUnit = mapping.netQuantityUnit,
                    packCount = mapping.packCount ?: parsedInfo.extractedPackCount,
                    packSize = mapping.packSize ?: parsedInfo.extractedPackSize,
                    packSizeUnit = mapping.packSizeUnit ?: parsedInfo.extractedPackSizeUnit,
                    quantityConfidence = if (isInternal) QuantityConfidence.APPROXIMATE else if (mapping.netQuantity != null) QuantityConfidence.EXACT else QuantityConfidence.UNKNOWN,
                    sourceName = "USER_CONFIRMED_MAPPING"
                )
            )
        }

        // 2. Remote OpenFoodFacts Online Product Search (Secondary Priority, Remote Provenance)
        val repo = productLookupRepository
        if (repo != null) {
            val remoteResult = runCatching { repo.searchProductsByName(trimmed) }.getOrNull()
            if (remoteResult == null || remoteResult is ProductLookupSearchResult.Error) remoteFailed = true
            if (remoteResult is ProductLookupSearchResult.Success) {
                for (remoteMeta in remoteResult.products) {
                    val barcodeKey = remoteMeta.barcode?.trim() ?: ""
                    if (barcodeKey.isNotBlank() && seenBarcodes.contains(barcodeKey)) {
                        continue // Suppress duplicate remote result if local confirmed mapping exists
                    }
                    if (barcodeKey.isNotBlank()) seenBarcodes.add(barcodeKey)

                    resultsList.add(
                        BarcodeLookupResult(
                            barcode = remoteMeta.barcode ?: "",
                            displayName = remoteMeta.displayName ?: "Remote Product",
                            brand = remoteMeta.brand,
                            canonicalIngredientId = remoteMeta.canonicalName,
                            canonicalName = remoteMeta.canonicalName,
                            category = FoodCategory.DRY_GOODS,
                            storageLocation = StorageLocation.CUPBOARD,
                            netQuantity = remoteMeta.netQuantity,
                            netQuantityUnit = remoteMeta.netQuantityUnit,
                            packCount = remoteMeta.packCount,
                            packSize = remoteMeta.packSize,
                            packSizeUnit = remoteMeta.packSizeUnit,
                            quantityConfidence = if (remoteMeta.netQuantity != null) QuantityConfidence.EXACT else QuantityConfidence.UNKNOWN,
                            sourceName = "OPEN_FOOD_FACTS"
                        )
                    )
                }
            }
        }

        return if (resultsList.isEmpty() && remoteFailed) null else resultsList
    }

    suspend fun confirmAndSaveBarcodeMapping(input: ConfirmedProductInput) {
        if (input.barcode.isNullOrBlank()) return

        val parsedInfo = BarcodeNormalizer.parseProductTitle(input.displayName)

        val entity = ProductBarcodeMappingEntity(
            barcode = input.barcode,
            displayName = parsedInfo.cleanName,
            brand = input.brand,
            canonicalIngredientId = input.canonicalIngredientId,
            canonicalName = input.canonicalName ?: parsedInfo.cleanName,
            category = input.category,
            defaultStorageLocation = input.storageLocation,
            netQuantity = input.netQuantity,
            netQuantityUnit = input.netQuantityUnit,
            packCount = input.packCount ?: parsedInfo.extractedPackCount,
            packSize = input.packSize ?: parsedInfo.extractedPackSize,
            packSizeUnit = input.packSizeUnit ?: parsedInfo.extractedPackSizeUnit,
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
