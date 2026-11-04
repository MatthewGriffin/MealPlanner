package com.example.chatgpt_mealplanner.core.service

import com.example.chatgpt_mealplanner.core.barcode.model.ConfirmedProductInput
import com.example.chatgpt_mealplanner.core.database.DatabaseTransactionRunner
import com.example.chatgpt_mealplanner.core.database.dao.ReceiptDao
import com.example.chatgpt_mealplanner.core.database.dao.ShoppingDao
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptImportEntity
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptImportLineEntity
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptProductMappingEntity
import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.ShoppingItemStatus
import com.example.chatgpt_mealplanner.core.receipt.AsdaReceiptParser
import com.example.chatgpt_mealplanner.core.receipt.FoodWarehouseReceiptParser
import com.example.chatgpt_mealplanner.core.receipt.GenericReceiptParser
import com.example.chatgpt_mealplanner.core.receipt.IcelandReceiptParser
import com.example.chatgpt_mealplanner.core.receipt.ReceiptImportDraft
import com.example.chatgpt_mealplanner.core.receipt.ReceiptOcrResult
import com.example.chatgpt_mealplanner.core.receipt.ReceiptLineStatus
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface ReceiptImportDomainService {
    suspend fun processReceiptOcr(ocrResult: ReceiptOcrResult, sourceImagePath: String?): DomainResult<ReceiptImportDraft>
    suspend fun confirmAndImportReceipt(draft: ReceiptImportDraft): DomainResult<List<InventoryItem>>
}

@Singleton
class ReceiptImportDomainServiceImpl @Inject constructor(
    private val receiptDao: ReceiptDao,
    private val inventoryDomainService: InventoryDomainService,
    private val shoppingDao: ShoppingDao,
    private val transactionRunner: DatabaseTransactionRunner
) : ReceiptImportDomainService {

    private val parsers = listOf(
        AsdaReceiptParser(),
        IcelandReceiptParser(),
        FoodWarehouseReceiptParser(),
        GenericReceiptParser()
    )

    override suspend fun processReceiptOcr(
        ocrResult: ReceiptOcrResult,
        sourceImagePath: String?
    ): DomainResult<ReceiptImportDraft> {
        val parser = parsers.firstOrNull { it.canParse(ocrResult) } ?: GenericReceiptParser()
        val initialDraft = parser.parse(ocrResult, sourceImagePath)

        val existingImport = receiptDao.getImportByFingerprint(initialDraft.fingerprint)
        val isDuplicate = existingImport != null

        // Enhance lines with remembered mappings from receipt_product_mappings
        val enhancedLines = initialDraft.lines.map { line ->
            val mapping = receiptDao.getMapping(initialDraft.retailerId, line.normalizedText)
            if (mapping != null && mapping.userConfirmed) {
                line.copy(
                    candidateCanonicalIngredientId = mapping.canonicalIngredientId,
                    displayName = mapping.displayName,
                    netQuantity = mapping.knownNetQuantity,
                    netQuantityUnit = mapping.knownNetUnit?.let { runCatching { com.example.chatgpt_mealplanner.core.model.QuantityUnit.valueOf(it) }.getOrNull() },
                    packCount = mapping.knownPackCount ?: line.packCount,
                    storageLocation = runCatching { com.example.chatgpt_mealplanner.core.model.StorageLocation.valueOf(mapping.storageLocation ?: "") }.getOrDefault(line.storageLocation)
                )
            } else {
                line
            }
        }

        val finalDraft = initialDraft.copy(
            lines = enhancedLines
        )

        return if (isDuplicate) {
            DomainResult.Failure(DomainError.DuplicateOperation("This receipt may already have been imported."))
        } else {
            DomainResult.Success(finalDraft)
        }
    }

    override suspend fun confirmAndImportReceipt(draft: ReceiptImportDraft): DomainResult<List<InventoryItem>> {
        val parsedRetailer = runCatching { com.example.chatgpt_mealplanner.retail.contract.RetailerId.valueOf(draft.retailerId) }.getOrDefault(com.example.chatgpt_mealplanner.retail.contract.RetailerId.ASDA)
        safeLog("[RECEIPT_IMPORT_START] retailer=\"${draft.retailerId}\" lineCount=${draft.lines.size} receiptDate=\"${draft.purchasedAt}\"")
        safeLog("[RECEIPT_RETAILER_RESOLVED] retailer=\"${parsedRetailer.name}\"")

        val existingImport = receiptDao.getImportByFingerprint(draft.fingerprint)
        if (existingImport != null) {
            safeLog("[RECEIPT_DUPLICATE_SKIPPED] retailer=\"${draft.retailerId}\" fingerprint=\"${draft.fingerprint}\" existingReceiptId=\"${existingImport.id}\" incomingLineCount=${draft.lines.size} existingLineCount=${existingImport.itemCount}")
            return DomainResult.Success(emptyList())
        }

        return transactionRunner.runInDomainTransaction {
            val importEntity = ReceiptImportEntity(
                id = draft.id,
                retailerId = draft.retailerId,
                purchasedAt = draft.purchasedAt,
                totalAmountMinorPence = draft.totalAmountMinorPence,
                itemCount = draft.itemCount,
                fingerprint = draft.fingerprint,
                sourceImagePath = draft.sourceImagePath,
                createdAt = System.currentTimeMillis()
            )

            val lineEntities = draft.lines.map { line ->
                ReceiptImportLineEntity(
                    id = line.id,
                    receiptImportId = draft.id,
                    rawText = line.rawText,
                    normalizedText = line.normalizedText,
                    displayName = line.displayName,
                    quantityPurchased = line.quantityPurchased,
                    unitPriceMinorPence = line.unitPriceMinorPence,
                    lineTotalMinorPence = line.lineTotalMinorPence,
                    candidateCanonicalIngredientId = line.candidateCanonicalIngredientId,
                    netQuantity = line.netQuantity,
                    netQuantityUnit = line.netQuantityUnit?.name,
                    packCount = line.packCount,
                    confidence = line.confidence.name,
                    status = line.status.name,
                    createdAt = System.currentTimeMillis()
                )
            }

            receiptDao.saveReceiptImportWithLines(importEntity, lineEntities)

            val addedItems = mutableListOf<InventoryItem>()

            for (line in draft.lines) {
                if (line.status == ReceiptLineStatus.NON_PRODUCT || line.status == ReceiptLineStatus.IGNORED) {
                    continue
                }

                // Create physical package purchases
                val qtyConfidence = if (line.netQuantity != null && line.netQuantityUnit != null) {
                    QuantityConfidence.EXACT
                } else {
                    QuantityConfidence.UNKNOWN
                }

                val productInput = ConfirmedProductInput(
                    barcode = null,
                    displayName = line.displayName,
                    brand = null,
                    canonicalIngredientId = line.candidateCanonicalIngredientId,
                    canonicalName = line.displayName,
                    category = line.category,
                    storageLocation = line.storageLocation,
                    netQuantity = line.netQuantity,
                    netQuantityUnit = line.netQuantityUnit,
                    packCount = line.quantityPurchased,
                    packSize = line.netQuantity,
                    packSizeUnit = line.netQuantityUnit,
                    quantityConfidence = qtyConfidence
                )

                val result = inventoryDomainService.addLotFromProductInput(productInput)
                if (result is DomainResult.Success) {
                    addedItems.add(result.value)
                }

                // Save or update remembered receipt mapping
                val mapping = ReceiptProductMappingEntity(
                    id = UUID.randomUUID().toString(),
                    retailerId = draft.retailerId,
                    normalizedDescription = line.normalizedText,
                    displayName = line.displayName,
                    canonicalIngredientId = line.candidateCanonicalIngredientId,
                    knownNetQuantity = line.netQuantity,
                    knownNetUnit = line.netQuantityUnit?.name,
                    knownPackCount = line.packCount,
                    storageLocation = line.storageLocation.name,
                    userConfirmed = true,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                receiptDao.insertOrUpdateMapping(mapping)

                // Match and mark active shopping item as PURCHASED_CONFIRMED
                if (line.candidateCanonicalIngredientId != null) {
                    val activeList = shoppingDao.getActiveShoppingList()
                    if (activeList != null) {
                        val activeItems = shoppingDao.getShoppingListWithItems(activeList.id)?.items ?: emptyList()
                        val match = activeItems.firstOrNull { it.item.canonicalIngredientId == line.candidateCanonicalIngredientId }
                        if (match != null) {
                            shoppingDao.updateItemStatus(match.item.id, ShoppingItemStatus.PURCHASED_CONFIRMED.name)
                        }
                    }
                }
            }

            val productsInsertedCount = draft.lines.count { it.unitPriceMinorPence != null }
            val skippedCount = draft.lines.size - productsInsertedCount
            safeLog("[RECEIPT_IMPORT_COMPLETE] retailer=\"${parsedRetailer.name}\" receiptLines=${draft.lines.size} productsInserted=$productsInsertedCount snapshotsInserted=$productsInsertedCount skippedLines=$skippedCount success=true")

            DomainResult.Success(addedItems)
        }
    }

    private fun safeLog(msg: String) {
        if (!com.example.chatgpt_mealplanner.BuildConfig.DEBUG) return
        try {
            android.util.Log.i("AI_MEALPLANNER", msg)
        } catch (_: Throwable) {
            println("[AI_MEALPLANNER] $msg")
        }
    }
}
