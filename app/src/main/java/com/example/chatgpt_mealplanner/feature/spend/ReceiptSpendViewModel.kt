package com.example.chatgpt_mealplanner.feature.spend

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatgpt_mealplanner.core.database.dao.InventoryDao
import com.example.chatgpt_mealplanner.core.database.dao.ReceiptSpendDao
import com.example.chatgpt_mealplanner.core.database.dao.ShoppingDao
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.receipt.ReceiptOcrService
import com.example.chatgpt_mealplanner.core.receipt.ReceiptSpendCategory
import com.example.chatgpt_mealplanner.core.receipt.ReceiptSpendDraft
import com.example.chatgpt_mealplanner.core.receipt.ReceiptSpendItemDraft
import com.example.chatgpt_mealplanner.core.service.ReceiptSpendDomainService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

data class RetailerSpendSummary(
    val retailerId: String,
    val totalPence: Long,
    val receiptCount: Int
)

data class CategorySpendSummary(
    val category: String,
    val totalPence: Long
)

data class SpendDashboardState(
    val thisMonthNetPaidPence: Long = 0L,
    val lastMonthNetPaidPence: Long = 0L,
    val percentageChange: Double = 0.0,
    val thisMonthGrossItemsPence: Long = 0L,
    val thisMonthSavingsPence: Long = 0L,
    val thisMonthUnrecognisedGrossPence: Long = 0L,
    val retailerBreakdown: List<RetailerSpendSummary> = emptyList(),
    val categoryBreakdown: List<CategorySpendSummary> = emptyList()
)

enum class ReceiptLinkType { INVENTORY, SHOPPING_LIST }

data class ReceiptLinkCandidate(
    val id: String,
    val displayName: String,
    val type: ReceiptLinkType
)

data class ReceiptSpendUiState(
    val isScanning: Boolean = false,
    val linkCandidates: List<ReceiptLinkCandidate> = emptyList(),
    val draft: ReceiptSpendDraft? = null,
    val errorMessage: String? = null,
    val isSaveSuccess: Boolean = false,
    val dashboardState: SpendDashboardState = SpendDashboardState()
)

@HiltViewModel
class ReceiptSpendViewModel @Inject constructor(
    private val ocrService: ReceiptOcrService,
    private val spendDomainService: ReceiptSpendDomainService,
    private val receiptSpendDao: ReceiptSpendDao,
    private val inventoryDao: InventoryDao,
    private val shoppingDao: ShoppingDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReceiptSpendUiState())
    val uiState: StateFlow<ReceiptSpendUiState> = _uiState.asStateFlow()

    init {
        loadSpendDashboard()
        loadReceiptLinkCandidates()
    }

    private fun loadReceiptLinkCandidates() {
        viewModelScope.launch {
            runCatching {
                val inventoryCandidates = inventoryDao.getAllItemsSync().map { item ->
                    ReceiptLinkCandidate(item.id, item.displayName, ReceiptLinkType.INVENTORY)
                }
                val shoppingList = shoppingDao.getActiveShoppingList()
                val shoppingCandidates = shoppingList?.let { list ->
                    shoppingDao.getShoppingListWithItems(list.id)?.items.orEmpty()
                        .filter { it.item.status.name !in setOf("PURCHASED_CONFIRMED", "CHECKED") }
                        .map { withSources ->
                            ReceiptLinkCandidate(
                                withSources.item.id,
                                withSources.item.displayName,
                                ReceiptLinkType.SHOPPING_LIST
                            )
                        }
                }.orEmpty()
                (inventoryCandidates + shoppingCandidates).distinctBy { "${it.type}:${it.id}" }
            }.onSuccess { candidates ->
                _uiState.update { it.copy(linkCandidates = candidates) }
            }
        }
    }

    private fun loadSpendDashboard() {
        viewModelScope.launch {
            receiptSpendDao.observeAllReceiptSpends().collect { spends ->
                val now = Calendar.getInstance()
                val currentMonthStr = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(now.time)

                now.add(Calendar.MONTH, -1)
                val lastMonthStr = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(now.time)

                val thisMonthSpends = spends.filter { it.purchasedAt.startsWith(currentMonthStr) }
                val lastMonthSpends = spends.filter { it.purchasedAt.startsWith(lastMonthStr) }

                val thisMonthNetPaid = thisMonthSpends.sumOf { it.totalMinorPence }
                val lastMonthNetPaid = lastMonthSpends.sumOf { it.totalMinorPence }

                val pctChange = if (lastMonthNetPaid > 0) {
                    ((thisMonthNetPaid - lastMonthNetPaid).toDouble() / lastMonthNetPaid) * 100.0
                } else 0.0

                val retailerGroups = thisMonthSpends.groupBy { it.retailerId }
                    .map { (ret, list) -> RetailerSpendSummary(ret, list.sumOf { it.totalMinorPence }, list.size) }

                var thisMonthGrossItems = 0L
                var thisMonthSavings = 0L
                var thisMonthUnrecognisedGross = 0L
                val categoryPenceMap = mutableMapOf<String, Long>()

                thisMonthSpends.forEach { spend ->
                    val items = receiptSpendDao.getItemsForSpend(spend.id).filter { !it.isDiscount }
                    val recognisedGrossItemSpend = items.sumOf { it.lineAmountMinorPence }
                    val unallocatedReceiptWideSavings = spend.discountMinorPence
                    val explicitGrossTotal = spend.subtotalMinorPence ?: (spend.totalMinorPence + unallocatedReceiptWideSavings)

                    val unrecognisedGrossSpend = maxOf(0L, explicitGrossTotal - recognisedGrossItemSpend)

                    thisMonthGrossItems += recognisedGrossItemSpend
                    thisMonthSavings += unallocatedReceiptWideSavings
                    thisMonthUnrecognisedGross += unrecognisedGrossSpend

                    items.forEach { item ->
                        val catName = ReceiptSpendCategory.fromName(item.category).displayName
                        categoryPenceMap[catName] = (categoryPenceMap[catName] ?: 0L) + item.lineAmountMinorPence
                    }

                    if (unrecognisedGrossSpend > 0L) {
                        val uncatName = ReceiptSpendCategory.UNCATEGORISED.displayName
                        categoryPenceMap[uncatName] = (categoryPenceMap[uncatName] ?: 0L) + unrecognisedGrossSpend
                    }
                }

                val catGroups = categoryPenceMap.map { (catName, pence) -> CategorySpendSummary(catName, pence) }

                _uiState.update {
                    it.copy(
                        dashboardState = SpendDashboardState(
                            thisMonthNetPaidPence = thisMonthNetPaid,
                            lastMonthNetPaidPence = lastMonthNetPaid,
                            percentageChange = pctChange,
                            thisMonthGrossItemsPence = thisMonthGrossItems,
                            thisMonthSavingsPence = thisMonthSavings,
                            thisMonthUnrecognisedGrossPence = thisMonthUnrecognisedGross,
                            retailerBreakdown = retailerGroups,
                            categoryBreakdown = catGroups
                        )
                    )
                }
            }
        }
    }

    fun processReceiptImage(uri: Uri) {
        _uiState.update { it.copy(isScanning = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val ocrResult = ocrService.recognizeTextFromUri(uri)
                when (val result = spendDomainService.processReceiptOcr(ocrResult, uri.toString())) {
                    is DomainResult.Success -> {
                        val draft = result.value
                        if (draft.items.isEmpty()) {
                            android.util.Log.i("AI_MEALPLANNER", "[RECEIPT_SCAN_INVALID] retailer=\"${draft.retailerId}\" itemCount=0")
                            _uiState.update { it.copy(isScanning = false, errorMessage = "No receipt items could be parsed from this image. Please try a clearer photo.") }
                        } else {
                            _uiState.update { it.copy(isScanning = false, draft = draft) }
                        }
                    }
                    is DomainResult.Failure -> {
                        android.util.Log.i("AI_MEALPLANNER", "[RECEIPT_SCAN_PROCESSING_ERROR] error=\"${result.error}\"")
                        _uiState.update { it.copy(isScanning = false, errorMessage = result.error.toString()) }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.i("AI_MEALPLANNER", "[RECEIPT_SCAN_PROCESSING_ERROR] exception=${e.javaClass.simpleName} message=\"${e.message}\"")
                _uiState.update { it.copy(isScanning = false, errorMessage = "Failed to process receipt image: ${e.localizedMessage}") }
            }
        }
    }

    fun cancelScan() {
        android.util.Log.i("AI_MEALPLANNER", "[RECEIPT_SCAN_DISCARDED]")
        android.util.Log.i("AI_MEALPLANNER", "[RECEIPT_SCAN_RESET]")
        _uiState.update { it.copy(draft = null, errorMessage = null, isScanning = false, isSaveSuccess = false) }
    }

    fun updateItemDraft(updatedItem: ReceiptSpendItemDraft) {
        _uiState.update { state ->
            val currentDraft = state.draft ?: return@update state
            val updatedItems = currentDraft.linesMatch(updatedItem)
            state.copy(draft = currentDraft.copy(items = updatedItems))
        }
    }

    fun updateSummaryPaidTotal(paidTotalPence: Long) {
        _uiState.update { state ->
            val currentDraft = state.draft ?: return@update state
            val canSave = paidTotalPence > 0L && currentDraft.retailerId.isNotBlank() && currentDraft.purchasedAt != null && !currentDraft.dateNeedsConfirmation
            state.copy(
                draft = currentDraft.copy(
                    totalMinorPence = paidTotalPence,
                    explicitFinalPaidMinorPence = paidTotalPence,
                    userConfirmedSummary = true,
                    needsReview = false,
                    canSave = canSave,
                    reviewWarning = if (!canSave) currentDraft.reviewWarning else null
                )
            )
        }
    }

    fun deleteItemDraft(itemId: String) {
        _uiState.update { state ->
            val currentDraft = state.draft ?: return@update state
            val remainingItems = currentDraft.items.filter { it.id != itemId }
            val newCalculatedSum = remainingItems.sumOf { it.lineAmountMinorPence }
            val grossTarget = currentDraft.explicitGrossTotalMinorPence ?: currentDraft.calculatedItemsSumMinorPence
            val isReconciled = newCalculatedSum == grossTarget
            val needsReview = (currentDraft.expectedItemCount != null && remainingItems.size != currentDraft.expectedItemCount) || !isReconciled
            val warningText = if (needsReview) {
                if (currentDraft.expectedItemCount != null && remainingItems.size != currentDraft.expectedItemCount) {
                    val finalPaidStr = if (currentDraft.totalMinorPence != null) "£%.2f".format(currentDraft.totalMinorPence / 100.0) else "Not detected"
                    "Only ${remainingItems.size} of ${currentDraft.expectedItemCount} item lines were recognized. Your receipt spend ($finalPaidStr) can still be saved."
                } else {
                    "Discrepancy of £${"%.2f".format(Math.abs(grossTarget - newCalculatedSum) / 100.0)} detected between receipt total and items sum."
                }
            } else null

            state.copy(
                draft = currentDraft.copy(
                    items = remainingItems,
                    itemCount = remainingItems.size,
                    calculatedItemsSumMinorPence = newCalculatedSum,
                    isReconciled = isReconciled,
                    needsReview = needsReview,
                    reviewWarning = warningText
                )
            )
        }
    }

    fun saveReceiptSpend() {
        val draft = _uiState.value.draft ?: return
        viewModelScope.launch {
            when (val result = spendDomainService.saveReceiptSpend(draft)) {
                is DomainResult.Success -> {
                    // A receipt confirms the list item was bought, but this spend-only flow
                    // does not import a physical inventory lot. Mark it checked, not imported.
                    val linkedShoppingItemIds = draft.items
                        .mapNotNull { it.linkedShoppingListItemId }
                        .distinct()
                    val failedShoppingItemIds = mutableListOf<String>()
                    linkedShoppingItemIds.forEach { itemId ->
                        try {
                            shoppingDao.updateItemStatus(itemId, "CHECKED")
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failedShoppingItemIds += itemId
                            android.util.Log.e(
                                "AI_MEALPLANNER",
                                "[RECEIPT_LINK_STATUS_UPDATE_FAILED] itemId=$itemId",
                                e
                            )
                        }
                    }
                    // Refresh candidates so successfully checked items disappear. If an
                    // update failed, the item remains available for correction and is reported.
                    loadReceiptLinkCandidates()
                    _uiState.update {
                        it.copy(
                            isSaveSuccess = true,
                            draft = null,
                            errorMessage = if (failedShoppingItemIds.isEmpty()) null else {
                                "Receipt saved, but ${failedShoppingItemIds.size} shopping-list " +
                                    "item(s) could not be marked as checked. Please check your shopping list."
                            }
                        )
                    }
                }
                is DomainResult.Failure -> {
                    _uiState.update { it.copy(errorMessage = result.error.toString()) }
                }
            }
        }
    }

    fun resetSaveSuccess() {
        _uiState.update { it.copy(isSaveSuccess = false) }
    }

    private fun ReceiptSpendDraft.linesMatch(updated: ReceiptSpendItemDraft): List<ReceiptSpendItemDraft> {
        return items.map { if (it.id == updated.id) updated else it }
    }
}
