package com.example.chatgpt_mealplanner.core.service

import com.example.chatgpt_mealplanner.core.receipt.ReceiptSpendItemDraft
import com.example.chatgpt_mealplanner.core.barcode.util.ParsedQuantityResult
import com.example.chatgpt_mealplanner.core.barcode.util.QuantityParser
import com.example.chatgpt_mealplanner.core.database.dao.InventoryDao
import com.example.chatgpt_mealplanner.core.database.dao.PurchasePriceDao
import com.example.chatgpt_mealplanner.core.database.dao.ShoppingDao
import com.example.chatgpt_mealplanner.core.database.entity.PurchasePriceObservationEntity
import com.example.chatgpt_mealplanner.core.database.entity.UserStoreEntity
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.database.DatabaseTransactionRunner
import com.example.chatgpt_mealplanner.core.database.dao.ReceiptSpendDao
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptCategoryMappingEntity
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptSpendEntity
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptSpendItemEntity
import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.receipt.DUPLICATE_RECEIPT_WARNING
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
    private val transactionRunner: DatabaseTransactionRunner,
    private val purchasePriceDao: PurchasePriceDao? = null,
    private val inventoryDao: InventoryDao? = null,
    private val shoppingDao: ShoppingDao? = null
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
                reviewWarning = DUPLICATE_RECEIPT_WARNING
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

        val result = transactionRunner.runInDomainTransaction {
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
                createdAt = System.currentTimeMillis(),
                currencyCode = draft.currencyCode
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

            DomainResult.Success(Unit)
        }

        if (result is DomainResult.Success) {
            recordLinkedItemPrices(draft)
        }
        return result
    }

    /**
     * Linking a receipt line says what was bought, so its price joins the price history. The
     * receipt is already saved at this point and must not fail because of this optional step.
     */
    private suspend fun recordLinkedItemPrices(draft: ReceiptSpendDraft) {
        val dao = purchasePriceDao ?: return
        val linkedItems = draft.items.filter {
            !it.isDiscount && (it.linkedInventoryItemId != null || it.linkedShoppingListItemId != null)
        }
        if (linkedItems.isEmpty()) return

        try {
            val storeId = findOrCreateStoreId(dao, draft.retailerId) ?: return
            val now = System.currentTimeMillis()
            val purchasedAt = draft.purchasedAt?.let { date ->
                runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(date)?.time }.getOrNull()
            } ?: now

            val observations = linkedItems.mapNotNull { item ->
                val priceMinorUnits = item.unitPriceMinorPence
                    ?: (item.lineAmountMinorPence / item.quantity.coerceAtLeast(1))
                if (priceMinorUnits <= 0L) return@mapNotNull null

                val inventoryItem = item.linkedInventoryItemId?.let { inventoryDao?.getItemById(it) }
                val shoppingItem = item.linkedShoppingListItemId?.let { shoppingDao?.getShoppingListItemById(it) }
                val parsedSize = parseReceiptPackSize(item)
                val hasInventorySize = inventoryItem?.packSize != null && inventoryItem.packSizeUnit != null

                PurchasePriceObservationEntity(
                    id = UUID.randomUUID().toString(),
                    storeId = storeId,
                    barcode = inventoryItem?.barcode,
                    productName = inventoryItem?.displayName ?: shoppingItem?.displayName ?: item.normalizedDescription,
                    brand = inventoryItem?.brand,
                    packQuantity = if (hasInventorySize) inventoryItem?.packSize else parsedSize?.packSize ?: parsedSize?.netQuantity,
                    packUnit = if (hasInventorySize) inventoryItem?.packSizeUnit?.name else (parsedSize?.packSizeUnit ?: parsedSize?.netQuantityUnit)?.name,
                    packCount = if (hasInventorySize) inventoryItem?.packCount else parsedSize?.packCount,
                    priceMinorUnits = priceMinorUnits,
                    currencyCode = draft.currencyCode,
                    purchasedAt = purchasedAt,
                    createdAt = now,
                    source = "RECEIPT_LINK",
                    canonicalName = inventoryItem?.let { it.canonicalIngredientId ?: it.canonicalName }
                        ?: shoppingItem?.let { it.canonicalIngredientId ?: it.displayName }
                )
            }
            if (observations.isNotEmpty()) {
                dao.insertPriceObservations(observations)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            safeLog("[RECEIPT_LINK_PRICE_FAILED] type=${e.javaClass.simpleName}")
        }
    }

    /** Receipts name a retailer rather than one of the user's shops, so match or add it by name. */
    private suspend fun findOrCreateStoreId(dao: PurchasePriceDao, retailerId: String): String? {
        if (retailerId.isBlank() || retailerId.equals("GENERIC", ignoreCase = true)) return null
        val key = storeNameKey(retailerId)
        dao.getAllStores().firstOrNull { storeNameKey(it.name) == key }?.let { return it.id }

        val store = UserStoreEntity(
            id = UUID.randomUUID().toString(),
            name = retailerId.split('_', ' ').filter { it.isNotEmpty() }.joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { it.uppercase() }
            }
        )
        dao.insertStore(store)
        return store.id
    }

    private fun storeNameKey(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    /** Only weights and volumes are trusted, because "2 PACK" on a receipt is not a pack size. */
    private fun parseReceiptPackSize(item: ReceiptSpendItemDraft): ParsedQuantityResult? {
        val parsed = QuantityParser.parse(item.rawDescription.ifBlank { item.normalizedDescription })
        return parsed.takeIf {
            it.netQuantity != null && it.netQuantityUnit in setOf(
                QuantityUnit.GRAM, QuantityUnit.KILOGRAM, QuantityUnit.MILLILITRE, QuantityUnit.LITRE
            )
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
