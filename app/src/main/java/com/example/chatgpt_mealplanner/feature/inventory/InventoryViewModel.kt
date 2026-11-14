package com.example.chatgpt_mealplanner.feature.inventory

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.chatgpt_mealplanner.core.ai.provider.MealPlannerAiProvider
import com.example.chatgpt_mealplanner.core.ai.provider.PantryListResult
import com.example.chatgpt_mealplanner.core.ai.provider.ProductCandidate
import com.example.chatgpt_mealplanner.core.ai.provider.ProductMatchChoice
import com.example.chatgpt_mealplanner.core.ai.provider.ProductMatchLine
import com.example.chatgpt_mealplanner.core.ai.provider.ProductMatchResult
import com.example.chatgpt_mealplanner.core.barcode.BarcodeLookupCoordinator
import com.example.chatgpt_mealplanner.core.barcode.model.ConfirmedProductInput
import com.example.chatgpt_mealplanner.core.barcode.BarcodeLookupResult
import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.repository.InventoryRepository
import com.example.chatgpt_mealplanner.core.service.InventoryDomainService
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusRepository
import com.example.chatgpt_mealplanner.core.sync.follower.worker.FollowerSyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class InventoryViewModel @Inject constructor(
    private val inventoryRepository: InventoryRepository,
    private val inventoryDomainService: InventoryDomainService,
    @param:ApplicationContext private val context: Context? = null,
    private val followerPairingStore: FollowerPairingStore? = null,
    private val followerSyncStatusRepository: FollowerSyncStatusRepository? = null,
    private val followerSyncScheduler: FollowerSyncScheduler? = null,
    private val followerDatabase: FollowerDatabase? = null,
    private val barcodeLookupCoordinator: BarcodeLookupCoordinator? = null,
    private val aiProvider: MealPlannerAiProvider? = null,
    private val kitInstructionDao: com.example.chatgpt_mealplanner.core.database.dao.KitInstructionDao? = null,
    private val usageQuota: com.example.chatgpt_mealplanner.core.quota.UsageQuota? = null
) : ViewModel() {

    /** What is left of today's allowance for reading typed lists. */
    val usage: kotlinx.coroutines.flow.StateFlow<com.example.chatgpt_mealplanner.core.quota.UsageSnapshot> =
        usageQuota?.snapshot ?: kotlinx.coroutines.flow.MutableStateFlow(com.example.chatgpt_mealplanner.core.quota.UsageSnapshot(unlimited = true))

    private val _uiState = MutableStateFlow(InventoryUiState())
    val uiState: StateFlow<InventoryUiState> = _uiState.asStateFlow()

    private var workObserverJob: kotlinx.coroutines.Job? = null

    init {
        loadInventory()
        observeWorkManagerAndSyncStatus()
    }

    suspend fun searchProductsByName(query: String): List<BarcodeLookupResult> {
        val coordinator = barcodeLookupCoordinator ?: return emptyList()
        return coordinator.searchProductsByName(query)
    }

    fun triggerManualSync() {
        val scheduler = followerSyncScheduler ?: return
        val shouldEnqueue = scheduler.recordManualSyncIntent()
        if (!shouldEnqueue) {
            return
        }
        viewModelScope.launch {
            scheduler.scheduleSyncWorkAsync(context, "USER_MANUAL_TRIGGER", intentAlreadyRecorded = true)
        }
    }

    private fun observeWorkManagerAndSyncStatus() {
        workObserverJob?.cancel()
        val store = followerPairingStore ?: return
        if (!store.isPaired()) {
            _uiState.update { it.copy(isFollowerPaired = false, followerSyncStatus = null) }
            return
        }

        workObserverJob = viewModelScope.launch {
            val dao = followerDatabase?.followerDao()
            val ctx = context
            val workManager = try { if (ctx != null) WorkManager.getInstance(ctx) else null } catch (_: Exception) { null }

            if (workManager != null) {
                workManager.getWorkInfosForUniqueWorkFlow(FollowerSyncScheduler.WORK_NAME)
                    .collect { workInfos ->
                        val isWorkerRunning = workInfos.any { it.state == WorkInfo.State.RUNNING }
                        val status = followerSyncStatusRepository?.calculateStatus(dao, isWorkerRunning)
                        _uiState.update { it.copy(isFollowerPaired = true, followerSyncStatus = status) }
                    }
            } else {
                val status = followerSyncStatusRepository?.calculateStatus(dao, false)
                _uiState.update { it.copy(isFollowerPaired = true, followerSyncStatus = status) }
            }
        }
    }

    private fun loadInventory() {
        viewModelScope.launch {
            try {
                inventoryDomainService.repairOrphanStapleRulesFromMergeLogs()
            } catch (_: Exception) {}

            val savedKits = kitInstructionDao?.observeAll()
                ?: kotlinx.coroutines.flow.flowOf(emptyList())
            kotlinx.coroutines.flow.combine(inventoryRepository.getAllItems(), savedKits) { items, saved -> items to saved }.collect { (items, saved) ->
                val kits = items.filter { it.category == com.example.chatgpt_mealplanner.core.model.FoodCategory.KITS_MIXES && kitInstructionDao != null }
                val matches = kits.associate { item ->
                    val names = listOf(
                        com.example.chatgpt_mealplanner.core.database.entity.normalizeKitName(item.displayName),
                        com.example.chatgpt_mealplanner.core.database.entity.normalizeKitName(item.canonicalName)
                    )
                    item.id to saved.firstOrNull { com.example.chatgpt_mealplanner.core.database.entity.savedKitMatches(names, it.normalizedName) }
                }
                val unscanned = matches.filterValues { it == null }.keys
                val withInstructions = matches.mapNotNull { (id, entity) -> entity?.let { id to it } }.toMap()
                _uiState.update { currentState ->
                    val filtered = filterItems(items, currentState.activeFilter, currentState.searchQuery)
                    currentState.copy(
                        kitsWithoutInstructions = unscanned,
                        kitInstructionsByItem = withInstructions,
                        rawItems = items,
                        filteredItems = filtered,
                        isLoading = false
                    )
                }
            }
        }
    }

    fun onEvent(event: InventoryUiEvent) {
        when (event) {
            InventoryUiEvent.TriggerManualSync -> {
                triggerManualSync()
            }
            is InventoryUiEvent.SearchQueryChanged -> {
                _uiState.update { currentState ->
                    val filtered = filterItems(currentState.rawItems, currentState.activeFilter, event.query)
                    currentState.copy(searchQuery = event.query, filteredItems = filtered)
                }
            }
            is InventoryUiEvent.SelectFilter -> {
                _uiState.update { currentState ->
                    val filtered = filterItems(currentState.rawItems, event.filter, currentState.searchQuery)
                    currentState.copy(activeFilter = event.filter, filteredItems = filtered)
                }
            }
            is InventoryUiEvent.AddItemRequested -> {
                addItem(event.item)
            }
            is InventoryUiEvent.UpdateItemRequested -> {
                updateItem(event.item)
            }
            is InventoryUiEvent.ToggleStaple -> {
                toggleStaple(event.itemId)
            }
            is InventoryUiEvent.SetStaple -> {
                setStaple(event.itemId, event.isStaple)
            }
            is InventoryUiEvent.RecordCorrection -> {
                recordCorrection(event.itemId, event.newQuantity, event.newUnit, event.confidence, event.note)
            }
            is InventoryUiEvent.MarkAsOut -> {
                markAsOut(event.itemId)
            }
            is InventoryUiEvent.DiscardItem -> {
                discardItem(event.itemId, event.reason)
            }
            is InventoryUiEvent.SelectItemForEdit -> {
                _uiState.update { it.copy(selectedItemForEdit = event.item) }
            }
            is InventoryUiEvent.MergeRequested -> {
                mergeItems(event.survivorItemId, event.mergedItemId, event.preferredDisplayName)
            }
            is InventoryUiEvent.UnmergeRequested -> {
                unmergeItems(event.mergeLogId, event.survivorStockAllocation, event.restoredStockAllocation)
            }
            is InventoryUiEvent.AssociateBarcodeRequested -> {
                associateBarcode(event.itemId, event.rawBarcode)
            }
            is InventoryUiEvent.RemoveBarcodeRequested -> {
                removeBarcode(event.itemId, event.rawBarcode)
            }
            InventoryUiEvent.OpenBulkAdd -> _uiState.update { it.copy(bulkAdd = BulkAddState()) }
            InventoryUiEvent.CloseBulkAdd -> _uiState.update { it.copy(bulkAdd = null) }
            is InventoryUiEvent.BulkAddTextChanged -> updateBulk { it.copy(text = event.text, error = null) }
            InventoryUiEvent.EditBulkAddList -> updateBulk { it.copy(rows = emptyList(), error = null) }
            InventoryUiEvent.ReadBulkAddList -> readBulkAddList()
            InventoryUiEvent.RetryBulkLookup -> retryBulkLookup()
            InventoryUiEvent.KeepWordingForUndecided -> updateBulk { bulk ->
                bulk.copy(rows = bulk.rows.map { if (it.decided) it else it.copy(decided = true, matched = null, displayName = it.genericName) })
            }
            is InventoryUiEvent.BulkAddRowChanged -> updateBulk { bulk ->
                bulk.copy(rows = bulk.rows.map { if (it.id == event.row.id) event.row else it })
            }
            is InventoryUiEvent.BulkAddRowRemoved -> updateBulk { bulk -> bulk.copy(rows = bulk.rows.filterNot { it.id == event.rowId }) }
            InventoryUiEvent.SaveBulkAddRows -> saveBulkAddRows()
            is InventoryUiEvent.DeleteKitInstructions -> {
                viewModelScope.launch { kitInstructionDao?.delete(event.normalizedName) }
            }
            InventoryUiEvent.ClearMessage -> {
                _uiState.update { it.copy(userMessage = null) }
            }
        }
    }

    private fun updateBulk(change: (BulkAddState) -> BulkAddState) {
        _uiState.update { state -> state.bulkAdd?.let { state.copy(bulkAdd = change(it)) } ?: state }
    }

    private fun readBulkAddList() {
        val bulk = _uiState.value.bulkAdd ?: return
        if (bulk.isReading || bulk.text.isBlank()) return
        val provider = aiProvider
        if (provider == null) {
            updateBulk { it.copy(error = "Reading a list needs the AI, which isn't available here.") }
            return
        }
        val itemKind = com.example.chatgpt_mealplanner.core.quota.QuotaKind.ITEM_ENTRY
        if (usageQuota != null && !usageQuota.canUse(itemKind)) {
            updateBulk { it.copy(error = usageQuota.limitMessage(itemKind)) }
            return
        }
        updateBulk { it.copy(isReading = true, error = null, lookupNote = null, progress = "Reading your list…") }
        var capNote: String? = null
        viewModelScope.launch {
            val items = when (val result = provider.parsePantryList(bulk.text)) {
                is PantryListResult.Failed -> {
                    updateBulk { it.copy(isReading = false, progress = null, error = result.message) }
                    return@launch
                }
                is PantryListResult.Items -> {
                    // The whole list was read, so it is counted; only as many items as are left today are kept.
                    val left = usageQuota?.remaining(itemKind) ?: Int.MAX_VALUE
                    usageQuota?.record(itemKind, result.items.size.coerceAtMost(left))
                    if (result.items.size > left) {
                        capNote = usageQuota?.limitMessage(itemKind)
                    }
                    result.items.take(left)
                }
            }
            val base = items.map { item ->
                BulkAddRow(
                    id = java.util.UUID.randomUUID().toString(),
                    displayName = item.displayName,
                    canonicalName = item.canonicalName,
                    brand = item.brand,
                    category = item.category,
                    storageLocation = item.storageLocation,
                    quantityText = formatBulkQuantity(item.quantity),
                    unit = item.unit,
                    quantityGuessed = item.quantityGuessed,
                    searchText = item.searchText,
                    genericName = item.displayName
                )
            }
            val resolved = resolveProducts(provider, base.map { it.searchText })
            val rows = base.mapIndexed { i, row -> row.withResolution(resolved[i]) }
            updateBulk { it.copy(isReading = false, progress = null, rows = rows.sortedBy { r -> r.decided }, lookupNote = listOfNotNull(capNote, lookupNote(rows)).joinToString("\n").ifEmpty { null }) }
        }
    }

    private fun retryBulkLookup() {
        val bulk = _uiState.value.bulkAdd ?: return
        val provider = aiProvider ?: return
        val failedRows = bulk.rows.filter { it.lookupFailed }
        if (bulk.isReading || failedRows.isEmpty()) return
        updateBulk { it.copy(isReading = true, error = null, progress = "Looking up products…") }
        viewModelScope.launch {
            val resolved = resolveProducts(provider, failedRows.map { it.searchText })
            val byId = failedRows.mapIndexed { i, row -> row.id to resolved[i] }.toMap()
            updateBulk { current ->
                val rows = current.rows.map { row -> byId[row.id]?.let { row.withResolution(it) } ?: row }
                current.copy(isReading = false, progress = null, rows = rows.sortedBy { it.decided }, lookupNote = lookupNote(rows))
            }
        }
    }

    private fun lookupNote(rows: List<BulkAddRow>): String? {
        if (barcodeLookupCoordinator == null) return null
        val failed = rows.count { it.lookupFailed }
        val none = rows.count { it.candidates.isEmpty() && !it.lookupFailed }
        val parts = listOfNotNull(
            failed.takeIf { it > 0 }?.let { "$it couldn't be looked up because the food database was busy." },
            none.takeIf { it > 0 }?.let { "$it had no match in the food database and keep the name as you wrote it." }
        )
        return parts.joinToString(" ").ifEmpty { null }
    }

    /** What the food database and Claude's matching made of one line. */
    private data class Resolution(
        val candidates: List<com.example.chatgpt_mealplanner.core.barcode.BarcodeLookupResult>,
        val choice: ProductMatchChoice?,
        val failed: Boolean
    )

    private fun BulkAddRow.withResolution(resolution: Resolution): BulkAddRow {
        val found = resolution.candidates
        val choice = resolution.choice
        val matched = if (choice != null && choice.sure) choice.candidate?.let { found.getOrNull(it) } else null
        // Claude's best guess goes first, so the user finds it without searching the list.
        val ordered = choice?.candidate?.let { found.getOrNull(it) }?.let { best -> listOf(best) + (found - best) } ?: found
        // The name stays generic so brands merge; the product's own name and brand go in the barcode mapping.
        // Claude answering "none of these fits" is an answer too: there is nothing to ask the user about.
        val noneFit = choice != null && choice.candidate == null
        return copy(
            displayName = genericName,
            candidates = ordered,
            matched = matched,
            decided = found.isEmpty() || matched != null || noneFit,
            lookupFailed = resolution.failed
        )
    }

    private suspend fun resolveProducts(provider: MealPlannerAiProvider, texts: List<String>): List<Resolution> {
        val searched = lookUpProducts(texts)
        val chosen = chooseProducts(provider, texts, searched)
        return texts.indices.map { Resolution(searched[it] ?: emptyList(), chosen[it], failed = searched[it] == null) }
    }

    /**
     * Searches the food database for each line, two at a time. The free service limits how fast it
     * answers, so a busy answer is retried after a pause. A line it never answers is null.
     */
    private suspend fun lookUpProducts(queries: List<String>): List<List<com.example.chatgpt_mealplanner.core.barcode.BarcodeLookupResult>?> {
        val coordinator = barcodeLookupCoordinator ?: return queries.map { emptyList() }
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val gate = kotlinx.coroutines.sync.Semaphore(2)
        return kotlinx.coroutines.coroutineScope {
            queries.map { query ->
                async {
                    gate.withPermit {
                        var products: List<com.example.chatgpt_mealplanner.core.barcode.BarcodeLookupResult>? = null
                        for (attempt in 0..LOOKUP_RETRIES) {
                            products = try {
                                coordinator.searchProductsByNameOrNull(query)?.take(MAX_PRODUCT_CHOICES)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                null
                            }
                            if (products != null) break
                            if (attempt < LOOKUP_RETRIES) kotlinx.coroutines.delay(LOOKUP_BACKOFF_MS * (attempt + 1))
                        }
                        updateBulk { it.copy(progress = "Looking up products… ${done.incrementAndGet()} of ${queries.size}") }
                        products
                    }
                }
            }.awaitAll()
        }
    }

    /** One Claude call per 40 lines picks the likely product for each line that has candidates. */
    private suspend fun chooseProducts(
        provider: MealPlannerAiProvider,
        texts: List<String>,
        candidates: List<List<com.example.chatgpt_mealplanner.core.barcode.BarcodeLookupResult>?>
    ): Map<Int, ProductMatchChoice> {
        val lines = texts.indices.filter { !candidates[it].isNullOrEmpty() }.map { index ->
            ProductMatchLine(
                index = index,
                userText = texts[index],
                candidates = candidates[index].orEmpty().map { ProductCandidate(it.displayName, it.brand, it.sizeText()) }
            )
        }
        if (lines.isEmpty()) return emptyMap()
        updateBulk { it.copy(progress = "Matching products…") }
        val chosen = mutableMapOf<Int, ProductMatchChoice>()
        lines.chunked(40).forEach { chunk ->
            // If matching fails, those lines simply ask the user instead.
            (provider.chooseProducts(chunk) as? ProductMatchResult.Choices)?.choices?.forEach { chosen[it.index] = it }
        }
        return chosen
    }

    private fun saveBulkAddRows() {
        val bulk = _uiState.value.bulkAdd ?: return
        // Every line that couldn't be matched on its own needs the user's decision first.
        if (bulk.isSaving || bulk.rows.isEmpty() || bulk.rows.any { !it.decided }) return
        updateBulk { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            val failed = mutableListOf<BulkAddRow>()
            var saved = 0
            bulk.rows.forEach { row ->
                val input = row.toProductInput()
                if (input == null) {
                    failed += row
                    return@forEach
                }
                var result = inventoryDomainService.addLotFromProductInput(input)
                // The item this merges into may be counted in another unit, so try the amount in that unit.
                val conflict = (result as? DomainResult.Failure)?.error as? DomainError.InvalidUnitConversion
                if (conflict != null) {
                    val quantity = row.quantityText.trim().replace(',', '.').toDoubleOrNull() ?: 0.0
                    input.inUnit(conflict.from, quantity, row.unit)?.let { result = inventoryDomainService.addLotFromProductInput(it) }
                }
                when (result) {
                    is DomainResult.Success -> {
                        saved++
                        // Remember the product (its real name, brand, barcode and size), so the next list or scan
                        // that names it needs no lookup. Inventory itself keeps only the generic name.
                        row.toMappingInput()?.let { mapping ->
                            try { barcodeLookupCoordinator?.confirmAndSaveBarcodeMapping(mapping) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
                        }
                    }
                    is DomainResult.Failure -> failed += row
                }
            }
            _uiState.update { state ->
                state.copy(
                    bulkAdd = if (failed.isEmpty()) null else state.bulkAdd?.copy(
                        isSaving = false,
                        rows = failed,
                        error = "${failed.size} item${if (failed.size == 1) "" else "s"} couldn't be saved. Check the amount and name, then try again."
                    ),
                    userMessage = if (saved > 0) "Added $saved item${if (saved == 1) "" else "s"}" else state.userMessage
                )
            }
        }
    }

    private fun formatBulkQuantity(quantity: Double): String =
        if (quantity == Math.floor(quantity) && quantity < 1_000_000) quantity.toLong().toString() else quantity.toString()

    private fun addItem(item: InventoryItem) {
        viewModelScope.launch {
            when (val result = inventoryDomainService.addItem(item)) {
                is DomainResult.Success -> {
                    _uiState.update { it.copy(userMessage = "Item added successfully") }
                }
                is DomainResult.Failure -> {
                    _uiState.update { it.copy(userMessage = formatErrorMessage(result.error)) }
                }
            }
        }
    }

    private fun updateItem(item: InventoryItem) {
        viewModelScope.launch {
            when (val result = inventoryDomainService.updateItem(item)) {
                is DomainResult.Success -> {
                    _uiState.update { it.copy(userMessage = "Item updated successfully", selectedItemForEdit = null) }
                }
                is DomainResult.Failure -> {
                    _uiState.update { it.copy(userMessage = formatErrorMessage(result.error)) }
                }
            }
        }
    }

    private fun toggleStaple(itemId: String) {
        viewModelScope.launch {
            when (val result = inventoryDomainService.toggleStapleStatus(itemId)) {
                is DomainResult.Success -> {
                    val statusText = if (result.value) "Marked as staple" else "Removed from staples"
                    _uiState.update { it.copy(userMessage = statusText) }
                }
                is DomainResult.Failure -> {
                    _uiState.update { it.copy(userMessage = formatErrorMessage(result.error)) }
                }
            }
        }
    }

    private fun setStaple(itemId: String, isStaple: Boolean) {
        viewModelScope.launch {
            when (val result = inventoryDomainService.setStapleStatus(itemId, isStaple)) {
                is DomainResult.Success -> {
                    val statusText = if (result.value) "Marked as staple" else "Removed from staples"
                    _uiState.update { it.copy(userMessage = statusText) }
                }
                is DomainResult.Failure -> {
                    _uiState.update { it.copy(userMessage = formatErrorMessage(result.error)) }
                }
            }
        }
    }

    private fun recordCorrection(
        itemId: String,
        newQuantity: Double?,
        newUnit: QuantityUnit? = null,
        confidence: QuantityConfidence,
        note: String?
    ) {
        viewModelScope.launch {
            when (val result = inventoryDomainService.recordUserCorrection(
                itemId = itemId,
                newQuantity = newQuantity,
                newUnit = newUnit,
                newConfidence = confidence,
                note = note
            )) {
                is DomainResult.Success -> {
                    _uiState.update { it.copy(userMessage = "Correction applied successfully") }
                }
                is DomainResult.Failure -> {
                    _uiState.update { it.copy(userMessage = formatErrorMessage(result.error)) }
                }
            }
        }
    }

    private fun markAsOut(itemId: String) {
        viewModelScope.launch {
            when (val result = inventoryDomainService.markAsOut(itemId)) {
                is DomainResult.Success -> {
                    _uiState.update { it.copy(userMessage = "Item marked as Out of Stock") }
                }
                is DomainResult.Failure -> {
                    _uiState.update { it.copy(userMessage = formatErrorMessage(result.error)) }
                }
            }
        }
    }

    private fun discardItem(itemId: String, reason: String?) {
        viewModelScope.launch {
            when (val result = inventoryDomainService.discardItem(itemId, reason)) {
                is DomainResult.Success -> {
                    _uiState.update { it.copy(userMessage = "Item discarded") }
                }
                is DomainResult.Failure -> {
                    _uiState.update { it.copy(userMessage = formatErrorMessage(result.error)) }
                }
            }
        }
    }

    private fun mergeItems(survivorItemId: String, mergedItemId: String, preferredDisplayName: String?) {
        viewModelScope.launch {
            when (val result = inventoryDomainService.mergeInventoryItems(survivorItemId, mergedItemId, preferredDisplayName)) {
                is DomainResult.Success -> {
                    _uiState.update { it.copy(userMessage = "Items successfully merged into '${result.value.displayName}'") }
                }
                is DomainResult.Failure -> {
                    _uiState.update { it.copy(userMessage = formatErrorMessage(result.error)) }
                }
            }
        }
    }

    private fun unmergeItems(mergeLogId: String, survivorStockAllocation: Double?, restoredStockAllocation: Double?) {
        viewModelScope.launch {
            when (val result = inventoryDomainService.unmergeInventoryItems(mergeLogId, survivorStockAllocation, restoredStockAllocation)) {
                is DomainResult.Success -> {
                    _uiState.update { it.copy(userMessage = "Items successfully unmerged") }
                }
                is DomainResult.Failure -> {
                    _uiState.update { it.copy(userMessage = formatErrorMessage(result.error)) }
                }
            }
        }
    }

    private fun associateBarcode(itemId: String, rawBarcode: String) {
        viewModelScope.launch {
            when (val result = inventoryDomainService.associateBarcode(itemId, rawBarcode)) {
                is DomainResult.Success -> {
                    _uiState.update { it.copy(userMessage = "Barcode associated successfully") }
                }
                is DomainResult.Failure -> {
                    _uiState.update { it.copy(userMessage = formatErrorMessage(result.error)) }
                }
            }
        }
    }

    private fun removeBarcode(itemId: String, rawBarcode: String) {
        viewModelScope.launch {
            when (val result = inventoryDomainService.removeBarcodeAssociation(itemId, rawBarcode)) {
                is DomainResult.Success -> {
                    _uiState.update { it.copy(userMessage = "Barcode association removed") }
                }
                is DomainResult.Failure -> {
                    _uiState.update { it.copy(userMessage = formatErrorMessage(result.error)) }
                }
            }
        }
    }

    private fun filterItems(items: List<InventoryItem>, filter: InventoryFilter, query: String): List<InventoryItem> {
        var result = items
        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            result = result.filter { it.displayName.lowercase().contains(q) || it.canonicalName.lowercase().contains(q) || (it.barcode?.contains(q) == true) }
        }
        if (filter != InventoryFilter.OUT) {
            result = result.filter { it.status != com.example.chatgpt_mealplanner.core.model.ItemStatus.OUT }
        }
        result = when (filter) {
            InventoryFilter.ALL -> result.filter { it.status != com.example.chatgpt_mealplanner.core.model.ItemStatus.DISCARDED }
            InventoryFilter.CUPBOARD -> result.filter { it.storageLocation == com.example.chatgpt_mealplanner.core.model.StorageLocation.CUPBOARD && it.status != com.example.chatgpt_mealplanner.core.model.ItemStatus.DISCARDED }
            InventoryFilter.FRIDGE -> result.filter { it.storageLocation == com.example.chatgpt_mealplanner.core.model.StorageLocation.FRIDGE && it.status != com.example.chatgpt_mealplanner.core.model.ItemStatus.DISCARDED }
            InventoryFilter.FREEZER -> result.filter { it.storageLocation == com.example.chatgpt_mealplanner.core.model.StorageLocation.FREEZER && it.status != com.example.chatgpt_mealplanner.core.model.ItemStatus.DISCARDED }
            InventoryFilter.USE_SOON -> result.filter { it.status == com.example.chatgpt_mealplanner.core.model.ItemStatus.USE_SOON }
            InventoryFilter.LOW -> result.filter { it.status == com.example.chatgpt_mealplanner.core.model.ItemStatus.LOW }
            InventoryFilter.KITS -> result.filter { it.category == com.example.chatgpt_mealplanner.core.model.FoodCategory.KITS_MIXES && it.status != com.example.chatgpt_mealplanner.core.model.ItemStatus.DISCARDED }
            InventoryFilter.OUT -> result.filter { it.status == com.example.chatgpt_mealplanner.core.model.ItemStatus.OUT }
        }
        return result.sortedWith(
            compareBy<InventoryItem> { it.useByDate ?: it.bestBeforeDate ?: "9999-12-31" }
                .thenBy { it.displayName.lowercase() }
        )
    }

    private fun formatErrorMessage(error: DomainError): String {
        return when (error) {
            is DomainError.ItemNotFound -> "Item not found"
            is DomainError.DuplicateOperation -> error.message
            is DomainError.InsufficientQuantity -> "Insufficient quantity available"
            is DomainError.InvalidQuantity -> "Invalid quantity"
            is DomainError.ItemNotUsable -> "Item is not usable (${error.status})"
            else -> "Operation failed"
        }
    }
}

private const val MAX_PRODUCT_CHOICES = 6
private const val LOOKUP_RETRIES = 2
private const val LOOKUP_BACKOFF_MS = 3_000L
