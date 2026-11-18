package com.example.chatgpt_mealplanner.feature.spend

import com.example.chatgpt_mealplanner.core.util.rethrowIfCancelled
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatgpt_mealplanner.core.database.dao.InventoryDao
import com.example.chatgpt_mealplanner.core.database.dao.ReceiptSpendDao
import com.example.chatgpt_mealplanner.core.database.dao.ShoppingDao
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.receipt.ReceiptDescriptionNormalizer
import com.example.chatgpt_mealplanner.core.receipt.ReceiptOcrException
import com.example.chatgpt_mealplanner.core.receipt.ReceiptOcrService
import com.example.chatgpt_mealplanner.core.receipt.ReceiptSpendCategory
import com.example.chatgpt_mealplanner.core.receipt.ReceiptSpendDraft
import com.example.chatgpt_mealplanner.core.receipt.ReceiptSpendItemDraft
import com.example.chatgpt_mealplanner.core.service.ReceiptSpendDomainService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    /** The currency every figure here is in. Receipts in other currencies are counted in [otherCurrencyReceipts], not added in. */
    val currencyCode: String = "GBP",
    val otherCurrencyReceipts: Int = 0,
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
    /** Shown with a scanned draft, e.g. when Claude was unavailable and the phone read it. */
    val scanNote: String? = null,
    val isSaveSuccess: Boolean = false,
    val dashboardState: SpendDashboardState = SpendDashboardState()
)

@HiltViewModel
class ReceiptSpendViewModel @Inject constructor(
    private val ocrService: ReceiptOcrService,
    private val spendDomainService: ReceiptSpendDomainService,
    private val receiptSpendDao: ReceiptSpendDao,
    private val inventoryDao: InventoryDao,
    private val shoppingDao: ShoppingDao,
    /** Role-aware: on a follower the pantry lives in the follower's own database, not the main one. */
    private val inventoryRepository: com.example.chatgpt_mealplanner.core.repository.InventoryRepository? = null,
    /** When both are present Claude reads the receipt photo; the phone's own text recognition is the fallback. */
    private val aiProvider: com.example.chatgpt_mealplanner.core.ai.provider.MealPlannerAiProvider? = null,
    private val photoSource: com.example.chatgpt_mealplanner.core.ai.packet.ReceiptPhotoSource? = null,
    /** Role-aware (a follower's additions go to the main phone): used to add food from a receipt that is not in the pantry yet. */
    private val inventoryDomainService: com.example.chatgpt_mealplanner.core.service.InventoryDomainService? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReceiptSpendUiState())
    val uiState: StateFlow<ReceiptSpendUiState> = _uiState.asStateFlow()

    init {
        loadSpendDashboard()
        loadReceiptLinkCandidates()
    }

    private fun loadReceiptLinkCandidates() {
        viewModelScope.launch {
            runCatching { fetchLinkCandidates() }.onSuccess { candidates ->
                _uiState.update { it.copy(linkCandidates = candidates) }
            }
        }
    }

    private suspend fun fetchLinkCandidates(): List<ReceiptLinkCandidate> {
        return run {
            run {
                val inventoryCandidates = (
                    inventoryRepository?.getAllItems()?.first()?.map { Triple(it.id, it.displayName, it.status) }
                        ?: inventoryDao.getAllItemsSync().map { Triple(it.id, it.displayName, it.status) }
                    )
                    .filter { (_, _, status) -> status != com.example.chatgpt_mealplanner.core.model.ItemStatus.DISCARDED }
                    .map { (id, name, _) -> ReceiptLinkCandidate(id, name, ReceiptLinkType.INVENTORY) }
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
            }
        }
    }

    /** Links each new line to the same product on the shopping list or in the pantry; the user can change any of them. */
    private suspend fun withAutoLinks(draft: ReceiptSpendDraft): ReceiptSpendDraft {
        val candidates = runCatching { fetchLinkCandidates() }.getOrNull() ?: return draft
        _uiState.update { it.copy(linkCandidates = candidates) }
        return draft.copy(
            items = com.example.chatgpt_mealplanner.core.receipt.ReceiptAutoLinker.suggestLinks(
                draft.items,
                pantry = candidates.filter { it.type == ReceiptLinkType.INVENTORY }.map { com.example.chatgpt_mealplanner.core.receipt.LinkTarget(it.id, it.displayName) },
                shopping = candidates.filter { it.type == ReceiptLinkType.SHOPPING_LIST }.map { com.example.chatgpt_mealplanner.core.receipt.LinkTarget(it.id, it.displayName) }
            )
        )
    }

    /**
     * Product lines (food, drink and household goods) whose generic product is not in the pantry are added to it (so "Warburtons Soft White Bread" becomes
     * "White bread" in the pantry), and a line with no link is linked to the new item. Lines already linked by the user keep their link.
     */
    private suspend fun withNewPantryItems(draft: ReceiptSpendDraft): ReceiptSpendDraft {
        val service = inventoryDomainService ?: return draft
        val pantry = runCatching { fetchLinkCandidates() }.getOrNull()
            ?.filter { it.type == ReceiptLinkType.INVENTORY }
            ?.map { com.example.chatgpt_mealplanner.core.receipt.LinkTarget(it.id, it.displayName) } ?: return draft
        var items = draft.items
        for ((item, lineIds) in com.example.chatgpt_mealplanner.core.receipt.ReceiptAutoLinker.newPantryItems(draft.items, pantry, draft.purchasedAt)) {
            val added = try { service.addItem(item) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            if (added is DomainResult.Success) {
                items = items.map { line ->
                    if (line.id in lineIds && line.linkedInventoryItemId == null && !line.linkLocked) line.copy(linkedInventoryItemId = item.id, autoLinked = true) else line
                }
            }
        }
        return draft.copy(items = items)
    }

    private fun loadSpendDashboard() {
        viewModelScope.launch {
            receiptSpendDao.observeAllReceiptSpends().collect { spends ->
                val now = Calendar.getInstance()
                val currentMonthStr = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(now.time)

                now.add(Calendar.MONTH, -1)
                val lastMonthStr = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(now.time)

                // Money in different currencies is never added together: show the currency used most this month (the phone's own on a tie).
                val monthSpends = spends.filter { it.purchasedAt.startsWith(currentMonthStr) || it.purchasedAt.startsWith(lastMonthStr) }
                val phoneCurrency = com.example.chatgpt_mealplanner.core.util.MoneyFormat.defaultCurrencyCode()
                val currencyCode = monthSpends.groupingBy { it.currencyCode }.eachCount()
                    .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key == phoneCurrency })
                    .firstOrNull()?.key ?: phoneCurrency
                val otherCurrencyReceipts = monthSpends.count { it.currencyCode != currencyCode }
                val thisMonthSpends = spends.filter { it.currencyCode == currencyCode && it.purchasedAt.startsWith(currentMonthStr) }
                val lastMonthSpends = spends.filter { it.currencyCode == currencyCode && it.purchasedAt.startsWith(lastMonthStr) }

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
                            currencyCode = currencyCode,
                            otherCurrencyReceipts = otherCurrencyReceipts,
                            retailerBreakdown = retailerGroups,
                            categoryBreakdown = catGroups
                        )
                    )
                }
            }
        }
    }

    fun processReceiptImage(uri: Uri, onFinished: () -> Unit = {}) {
        _uiState.update { it.copy(isScanning = true, errorMessage = null, scanNote = null) }
        viewModelScope.launch {
            try {
                val claudeNote = readWithClaude(uri)
                if (claudeNote is ClaudeRead.Done) {
                    val linked = withAutoLinks(claudeNote.draft)
                    _uiState.update { it.copy(isScanning = false, draft = linked) }
                    return@launch
                }
                val ocrResult = ocrService.recognizeTextFromUri(uri)
                when (val result = spendDomainService.processReceiptOcr(ocrResult, uri.toString())) {
                    is DomainResult.Success -> {
                        // A scan with no recognised items can still have a usable total and date,
                        // and items can be added by hand, so only a scan with no text at all fails.
                        if (ocrResult.lines.isEmpty()) {
                            android.util.Log.i("AI_MEALPLANNER", "[RECEIPT_SCAN_INVALID] retailer=\"${result.value.retailerId}\" noText=true")
                            _uiState.update { it.copy(isScanning = false, errorMessage = "No text was found in this photo. Flatten the receipt, use good light, and try again.") }
                        } else {
                            val note = (claudeNote as? ClaudeRead.Unavailable)?.note
                            val linked = withAutoLinks(result.value)
                            _uiState.update {
                                it.copy(isScanning = false, draft = linked, scanNote = note)
                            }
                        }
                    }
                    is DomainResult.Failure -> {
                        android.util.Log.i("AI_MEALPLANNER", "[RECEIPT_SCAN_PROCESSING_ERROR] error=\"${result.error}\"")
                        _uiState.update { it.copy(isScanning = false, errorMessage = result.error.toString()) }
                    }
                }
            } catch (e: Exception) {
                rethrowIfCancelled()
                android.util.Log.e("AI_MEALPLANNER", "[RECEIPT_SCAN_PROCESSING_ERROR]", e)
                val message = (e as? ReceiptOcrException)?.message
                    ?: "Failed to process receipt image: ${e.localizedMessage}"
                _uiState.update { it.copy(isScanning = false, errorMessage = message) }
            } finally {
                onFinished()
            }
        }
    }

    private sealed interface ClaudeRead {
        data class Done(val draft: ReceiptSpendDraft) : ClaudeRead
        /** [note] says why the phone's own reader was used; null when Claude simply isn't set up here. */
        data class Unavailable(val note: String?) : ClaudeRead
    }

    private suspend fun readWithClaude(uri: Uri): ClaudeRead {
        val provider = aiProvider ?: return ClaudeRead.Unavailable(null)
        val source = photoSource ?: return ClaudeRead.Unavailable(null)
        val images = source.prepare(uri)
        if (images.isEmpty()) return ClaudeRead.Unavailable(null)
        return when (val read = provider.readReceipt(images)) {
            is com.example.chatgpt_mealplanner.core.ai.provider.ReceiptReadResult.Failed ->
                ClaudeRead.Unavailable("Claude couldn't read this receipt (${read.message.take(160)}), so your phone read it instead. Please check the items.")
            is com.example.chatgpt_mealplanner.core.ai.provider.ReceiptReadResult.Read -> {
                val draft = com.example.chatgpt_mealplanner.core.receipt.ReceiptReadingDraft.toDraft(read.receipt, uri.toString())
                when (val finished = spendDomainService.finishDraft(draft)) {
                    is DomainResult.Success -> ClaudeRead.Done(finished.value)
                    is DomainResult.Failure -> ClaudeRead.Unavailable(null)
                }
            }
        }
    }

    fun cancelScan() {
        android.util.Log.i("AI_MEALPLANNER", "[RECEIPT_SCAN_DISCARDED]")
        android.util.Log.i("AI_MEALPLANNER", "[RECEIPT_SCAN_RESET]")
        _uiState.update { it.copy(draft = null, errorMessage = null, scanNote = null, isScanning = false, isSaveSuccess = false) }
    }

    fun updateItemDraft(updatedItem: ReceiptSpendItemDraft) {
        editDraft { draft -> draft.withUserEdits(items = draft.items.map { if (it.id == updatedItem.id) updatedItem else it }) }
    }

    fun updateItemDetails(itemId: String, description: String, pricePence: Long) {
        val normalized = ReceiptDescriptionNormalizer.normalize(description)
        editDraft { draft ->
            draft.withUserEdits(
                items = draft.items.map { item ->
                    if (item.id != itemId) return@map item
                    item.copy(
                        normalizedDescription = normalized,
                        lineAmountMinorPence = pricePence,
                        unitPriceMinorPence = pricePence / item.quantity.coerceAtLeast(1),
                        category = if (normalized == item.normalizedDescription) item.category else ReceiptSpendCategory.classifyDescription(normalized).name
                    )
                }
            )
        }
    }

    fun addItemDraft(description: String, pricePence: Long) {
        val normalized = ReceiptDescriptionNormalizer.normalize(description)
        editDraft { draft ->
            draft.withUserEdits(
                items = draft.items + ReceiptSpendItemDraft(
                    rawDescription = description.trim(),
                    normalizedDescription = normalized,
                    lineAmountMinorPence = pricePence,
                    unitPriceMinorPence = pricePence,
                    category = ReceiptSpendCategory.classifyDescription(normalized).name
                )
            )
        }
    }

    fun updateSummaryPaidTotal(paidTotalPence: Long) {
        editDraft { it.recomputeWithPaidTotal(paidTotalPence) }
    }

    fun updatePurchaseDate(isoDate: String) {
        editDraft { it.withUserEdits(purchasedAt = isoDate) }
    }

    fun deleteItemDraft(itemId: String) {
        editDraft { draft -> draft.withUserEdits(items = draft.items.filter { it.id != itemId }) }
    }

    private fun editDraft(edit: (ReceiptSpendDraft) -> ReceiptSpendDraft) {
        _uiState.update { state ->
            val draft = state.draft ?: return@update state
            state.copy(draft = edit(draft), errorMessage = null)
        }
    }

    fun saveReceiptSpend() {
        val reviewed = _uiState.value.draft ?: return
        viewModelScope.launch {
            val draft = if (reviewed.isProbableDuplicate) reviewed else withNewPantryItems(reviewed)
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
}
