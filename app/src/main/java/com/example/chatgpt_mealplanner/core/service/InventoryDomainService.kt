package com.example.chatgpt_mealplanner.core.service

import com.example.chatgpt_mealplanner.core.barcode.model.ConfirmedProductInput
import com.example.chatgpt_mealplanner.core.model.DeductionRequest
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence

interface InventoryDomainService {
    suspend fun addItem(item: InventoryItem): DomainResult<Unit>
    suspend fun addLotFromProductInput(input: ConfirmedProductInput): DomainResult<InventoryItem>
    suspend fun updateItem(item: InventoryItem): DomainResult<Unit>
    suspend fun deductQuantityForMeal(request: DeductionRequest): DomainResult<Unit>
    suspend fun recordUserCorrection(
        itemId: String,
        newQuantity: Double?,
        newUnit: com.example.chatgpt_mealplanner.core.model.QuantityUnit? = null,
        newConfidence: QuantityConfidence,
        newStatus: ItemStatus? = null,
        note: String? = null
    ): DomainResult<Unit>
    suspend fun markAsOut(itemId: String): DomainResult<Unit>
    suspend fun discardItem(itemId: String, reason: String? = null): DomainResult<Unit>
    suspend fun restockItem(itemId: String, addedQuantity: Double? = null): DomainResult<Unit>
    suspend fun toggleStapleStatus(itemId: String): DomainResult<Boolean>
    suspend fun setStapleStatus(itemId: String, isStaple: Boolean): DomainResult<Boolean>
    suspend fun mergeInventoryItems(
        survivorItemId: String,
        mergedItemId: String,
        preferredDisplayName: String? = null,
        preferredRuleThreshold: Double? = null,
        preferredRuleTarget: Double? = null
    ): DomainResult<InventoryItem>
    suspend fun unmergeInventoryItems(
        mergeLogId: String,
        survivorStockAllocation: Double? = null,
        restoredStockAllocation: Double? = null
    ): DomainResult<Pair<InventoryItem, InventoryItem>>
    suspend fun getAssociatedBarcodes(inventoryItemId: String): List<String>
    suspend fun associateBarcode(inventoryItemId: String, rawBarcode: String): DomainResult<Unit>
    suspend fun removeBarcodeAssociation(inventoryItemId: String, rawBarcode: String): DomainResult<Unit>
    suspend fun repairOrphanStapleRulesFromMergeLogs(): DomainResult<Int>
}
