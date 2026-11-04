package com.example.chatgpt_mealplanner.core.service

import com.example.chatgpt_mealplanner.core.database.DatabaseTransactionRunner
import com.example.chatgpt_mealplanner.core.database.dao.ReceiptSpendDao
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptCategoryMappingEntity
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptSpendEntity
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptSpendItemEntity
import com.example.chatgpt_mealplanner.core.database.entity.RetailPriceSnapshotEntity
import com.example.chatgpt_mealplanner.core.database.entity.RetailProductEntity
import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.receipt.ReceiptOcrResult
import com.example.chatgpt_mealplanner.core.receipt.ReceiptSpendDraft
import com.example.chatgpt_mealplanner.core.receipt.ReceiptSpendParser
import com.example.chatgpt_mealplanner.retail.contract.RetailAvailability
import com.example.chatgpt_mealplanner.retail.contract.RetailStorageClass
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface ReceiptSpendDomainService {
    suspend fun processReceiptOcr(ocrResult: ReceiptOcrResult, sourceImagePath: String?): DomainResult<ReceiptSpendDraft>
    suspend fun saveReceiptSpend(draft: ReceiptSpendDraft): DomainResult<Unit>
}

@Singleton
class ReceiptSpendDomainServiceImpl @Inject constructor(
    private val receiptSpendDao: ReceiptSpendDao,
    private val receiptRetailBridgeService: ReceiptRetailBridgeService,
    private val transactionRunner: DatabaseTransactionRunner
) : ReceiptSpendDomainService {

    override suspend fun processReceiptOcr(
        ocrResult: ReceiptOcrResult,
        sourceImagePath: String?
    ): DomainResult<ReceiptSpendDraft> {
        val initialDraft = ReceiptSpendParser.parseOcrResult(ocrResult, sourceImagePath)

        val existingSpend = receiptSpendDao.getSpendByFingerprint(initialDraft.fingerprint)
        val isDuplicate = existingSpend != null

        // Apply remembered category mappings
        val enhancedItems = initialDraft.items.map { item ->
            val mapping = receiptSpendDao.getCategoryMapping(initialDraft.retailerId, item.normalizedDescription)
            if (mapping != null) {
                item.copy(category = mapping.category)
            } else {
                item
            }
        }

        val finalDraft = if (isDuplicate) {
            initialDraft.copy(
                items = enhancedItems,
                isProbableDuplicate = true,
                reviewWarning = "Possible duplicate receipt: A receipt with this retailer, date, and total was already saved."
            )
        } else {
            initialDraft.copy(items = enhancedItems)
        }

        return DomainResult.Success(finalDraft)
    }

    override suspend fun saveReceiptSpend(draft: ReceiptSpendDraft): DomainResult<Unit> {
        val existingSpend = receiptSpendDao.getSpendByFingerprint(draft.fingerprint)
        if (existingSpend != null) {
            safeLog("[RECEIPT_DUPLICATE_SKIPPED] retailer=\"${draft.retailerId}\" fingerprint=\"${draft.fingerprint}\" existingReceiptId=\"${existingSpend.id}\" incomingLineCount=${draft.items.size} existingLineCount=${existingSpend.itemCount}")
            return DomainResult.Failure(
                DomainError.DuplicateOperation("This receipt has already been saved. No duplicate was added.")
            )
        }

        return transactionRunner.runInDomainTransaction {
            val dateStr = draft.purchasedAt ?: SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val spendEntity = ReceiptSpendEntity(
                id = draft.id,
                retailerId = draft.retailerId,
                purchasedAt = dateStr,
                totalMinorPence = draft.totalMinorPence ?: 0L,
                subtotalMinorPence = draft.explicitGrossTotalMinorPence ?: draft.calculatedItemsSumMinorPence,
                discountMinorPence = draft.discountMinorPence,
                itemCount = draft.itemCount,
                fingerprint = draft.fingerprint,
                createdAt = System.currentTimeMillis()
            )

            val itemEntities = draft.items.map { item ->
                ReceiptSpendItemEntity(
                    id = item.id,
                    receiptSpendId = draft.id,
                    rawDescription = item.rawDescription,
                    normalizedDescription = item.normalizedDescription,
                    lineAmountMinorPence = item.lineAmountMinorPence,
                    quantity = item.quantity,
                    unitPriceMinorPence = item.unitPriceMinorPence,
                    category = item.category,
                    isDiscount = item.isDiscount,
                    createdAt = System.currentTimeMillis(),
                    linkedInventoryItemId = item.linkedInventoryItemId,
                    linkedShoppingListItemId = item.linkedShoppingListItemId
                )
            }

            receiptSpendDao.saveReceiptSpendWithItems(spendEntity, itemEntities)

            // Save remembered category mappings
            draft.items.forEach { item ->
                if (!item.isDiscount) {
                    val mapping = ReceiptCategoryMappingEntity(
                        id = UUID.randomUUID().toString(),
                        retailerId = draft.retailerId,
                        normalizedDescription = item.normalizedDescription,
                        category = item.category,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                    receiptSpendDao.insertOrUpdateCategoryMapping(mapping)
                }
            }

            // Record retail cache via shared ReceiptRetailBridgeService
            val obsAt = runCatching {
                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateStr)?.time ?: System.currentTimeMillis()
            }.getOrDefault(System.currentTimeMillis())

            val bridgeItems = draft.items
                .filter { !it.isDiscount && it.unitPriceMinorPence != null }
                .map { item ->
                    ReceiptObservationItem(
                        lineId = item.id,
                        description = item.rawDescription.ifBlank { item.normalizedDescription },
                        unitPriceMinorPence = item.unitPriceMinorPence!!,
                        netQuantity = item.quantity.toDouble(),
                        netQuantityUnit = null,
                        canonicalIngredientId = null
                    )
                }

            receiptRetailBridgeService.recordReceiptObservations(
                receiptId = draft.id,
                retailerIdStr = draft.retailerId,
                observedAtMs = obsAt,
                items = bridgeItems
            )

            DomainResult.Success(Unit)
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
