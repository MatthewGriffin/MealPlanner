package com.example.chatgpt_mealplanner.core.backup

import com.example.chatgpt_mealplanner.core.database.AppDatabase
import com.example.chatgpt_mealplanner.core.database.DatabaseTransactionRunner
import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemEntity
import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class BackupManager @Inject constructor(
    private val database: AppDatabase?,
    private val transactionRunner: DatabaseTransactionRunner
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun createExportJson(
        includeChatHistory: Boolean = false,
        currentTimeMs: Long = System.currentTimeMillis()
    ): String {
        // Collect authoritative Room records
        val items = database?.inventoryDao()?.getAllItemsSync()?.map {
            InventoryItemExportDto(
                id = it.id,
                barcode = it.barcode,
                canonicalIngredientId = it.canonicalIngredientId,
                canonicalName = it.canonicalName,
                displayName = it.displayName,
                brand = it.brand,
                category = it.category.name,
                storageLocation = it.storageLocation.name,
                quantity = it.quantity ?: 0.0,
                quantityUnit = it.quantityUnit?.name,
                packCount = it.packCount,
                packSize = it.packSize,
                packSizeUnit = it.packSizeUnit?.name,
                quantityConfidence = it.quantityConfidence.name,
                status = it.status.name,
                opened = it.opened ?: false,
                frozen = it.frozen,
                staple = it.staple,
                purchaseDate = it.purchaseDate,
                openedDate = it.openedDate,
                useByDate = it.useByDate,
                bestBeforeDate = it.bestBeforeDate,
                notes = it.notes,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt
            )
        }

        val export = PantryPlannerExportDto(
            exportedAt = currentTimeMs,
            inventory = items ?: emptyList()
        )

        return json.encodeToString(export)
    }

    suspend fun restoreImportJson(
        jsonString: String,
        currentTimeMs: Long = System.currentTimeMillis()
    ): DomainResult<Unit> {
        // PHASE A: VALIDATE EVERYTHING
        val export = runCatching { json.decodeFromString<PantryPlannerExportDto>(jsonString) }.getOrElse {
            return DomainResult.Failure(DomainError.TransactionFailed("Malformed export JSON archive."))
        }

        if (export.exportFormatVersion != "pantry-planner-export-v1") {
            return DomainResult.Failure(DomainError.TransactionFailed("Unsupported export format version: ${export.exportFormatVersion}"))
        }

        // Validate domain invariants
        val invalidQty = export.inventory.any { it.quantity < 0.0 }
        if (invalidQty) {
            return DomainResult.Failure(DomainError.InvalidQuantity(-1.0))
        }

        // PHASE B & C: ATOMIC ROOM RESTORATION
        return try {
            transactionRunner.runInTransaction {
                // Clear tables in safe FK order
                database?.inventoryDao()?.deleteAllItems()

                // Insert imported items
                val entities = export.inventory.map {
                    InventoryItemEntity(
                        id = it.id,
                        barcode = it.barcode,
                        canonicalIngredientId = it.canonicalIngredientId,
                        canonicalName = it.canonicalName,
                        displayName = it.displayName,
                        brand = it.brand,
                        category = runCatching { FoodCategory.valueOf(it.category.uppercase()) }.getOrDefault(FoodCategory.OTHER),
                        storageLocation = runCatching { StorageLocation.valueOf(it.storageLocation.uppercase()) }.getOrDefault(StorageLocation.OTHER),
                        quantity = it.quantity,
                        quantityUnit = it.quantityUnit?.let { u -> com.example.chatgpt_mealplanner.core.model.parseQuantityUnit(u) },
                        packCount = it.packCount,
                        packSize = it.packSize,
                        packSizeUnit = it.packSizeUnit?.let { u -> com.example.chatgpt_mealplanner.core.model.parseQuantityUnit(u) },
                        quantityConfidence = runCatching { QuantityConfidence.valueOf(it.quantityConfidence.uppercase()) }.getOrDefault(QuantityConfidence.EXACT),
                        status = runCatching { ItemStatus.valueOf(it.status.uppercase()) }.getOrDefault(ItemStatus.AVAILABLE),
                        opened = it.opened,
                        frozen = it.frozen,
                        staple = it.staple,
                        purchaseDate = it.purchaseDate,
                        openedDate = it.openedDate,
                        useByDate = it.useByDate,
                        bestBeforeDate = it.bestBeforeDate,
                        notes = it.notes,
                        createdAt = it.createdAt,
                        updatedAt = it.updatedAt
                    )
                }
                database?.inventoryDao()?.insertItems(entities)
            }
            DomainResult.Success(Unit)
        } catch (e: Exception) {
            DomainResult.Failure(DomainError.TransactionFailed("Import restoration failed: ${e.message}"))
        }
    }
}
