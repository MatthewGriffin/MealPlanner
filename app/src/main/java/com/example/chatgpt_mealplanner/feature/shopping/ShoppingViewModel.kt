package com.example.chatgpt_mealplanner.feature.shopping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.ShoppingItemStatus
import com.example.chatgpt_mealplanner.core.model.ShoppingListItemWithSources
import com.example.chatgpt_mealplanner.core.model.ShoppingListWithItems
import com.example.chatgpt_mealplanner.core.model.StapleRule
import com.example.chatgpt_mealplanner.core.repository.ShoppingRepository
import com.example.chatgpt_mealplanner.core.repository.StapleRuleRepository
import com.example.chatgpt_mealplanner.core.service.ShoppingDomainService
import com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
import com.example.chatgpt_mealplanner.core.sync.follower.FollowerBasketComparisonCacheStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import com.example.chatgpt_mealplanner.core.service.RetailShoppingDomainService
import com.example.chatgpt_mealplanner.core.service.RetailerPreferenceStore
import com.example.chatgpt_mealplanner.core.service.RetailStorePreferenceStore
import com.example.chatgpt_mealplanner.retail.contract.BasketComparisonResult
import com.example.chatgpt_mealplanner.retail.contract.BasketLine
import com.example.chatgpt_mealplanner.retail.contract.Money
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import com.example.chatgpt_mealplanner.retail.contract.UserShoppingPreferences
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ShoppingViewModel @Inject constructor(
    private val shoppingDomainService: ShoppingDomainService,
    private val shoppingRepository: ShoppingRepository,
    private val stapleRuleRepository: StapleRuleRepository,
    private val retailShoppingDomainService: RetailShoppingDomainService,
    private val appRoleResolver: AppRoleResolver,
    private val retailerPreferenceStore: RetailerPreferenceStore = RetailerPreferenceStore(null),
    private val retailStorePreferenceStore: RetailStorePreferenceStore = RetailStorePreferenceStore(null),
    private val followerBasketComparisonCacheStore: FollowerBasketComparisonCacheStore = FollowerBasketComparisonCacheStore(null)
) : ViewModel() {

    private val activeTabFlow = MutableStateFlow(ShoppingTab.SHOPPING_LIST)
    private val userMessageFlow = MutableStateFlow<String?>(null)
    private val isLoadingFlow = MutableStateFlow(false)
    private val selectedItemDetailFlow = MutableStateFlow<ShoppingListItemWithSources?>(null)
    private val showAddManualDialogFlow = MutableStateFlow(false)
    private val manualItemNameFlow = MutableStateFlow("")
    private val manualItemNotesFlow = MutableStateFlow("")
    private val basketOptimizationFlow = MutableStateFlow<BasketComparisonResult?>(null)
    private var basketOptimizationJob: kotlinx.coroutines.Job? = null
    private var basketOptimizationVersion = 0L

    private val activeShoppingListFlow = combine(
        shoppingRepository.observeActiveShoppingListWithItems(),
        retailerPreferenceStore.observeEnabledRetailers(),
        retailStorePreferenceStore.observePreferredStores()
    ) { listWithItems, _, _ -> listWithItems }
        .onEach { listWithItems -> runBasketOptimization(listWithItems) }
    private val stapleRulesFlow = stapleRuleRepository.observeAllStapleRules()

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<ShoppingUiState> = combine(
        activeShoppingListFlow,
        stapleRulesFlow,
        activeTabFlow,
        userMessageFlow,
        isLoadingFlow,
        selectedItemDetailFlow,
        showAddManualDialogFlow,
        basketOptimizationFlow,
        manualItemNameFlow,
        manualItemNotesFlow
    ) { flows ->
        val listWithItems = flows[0] as ShoppingListWithItems?
        val stapleRules = flows[1] as List<StapleRule>
        val activeTab = flows[2] as ShoppingTab
        val userMessage = flows[3] as String?
        val isLoading = flows[4] as Boolean
        val itemDetail = flows[5] as ShoppingListItemWithSources?
        val showAddDialog = flows[6] as Boolean
        val basketResult = flows[7] as BasketComparisonResult?
        val manualItemName = flows[8] as String
        val manualItemNotes = flows[9] as String

        val items = listWithItems?.items ?: emptyList()
        val groups = computeRetailerGroups(items, basketResult)

        groups.forEach { group ->
            group.items.forEach { item ->
                val line = group.lineByItemId[item.item.id]
                val assignmentState = if (line != null) "ASSIGNED" else "UNASSIGNED"
                val retailerName = line?.retailerId?.name ?: group.retailerName
                println("[AI_MEALPLANNER] [SHOPPING_UI_STATE_ITEM] assignmentState=$assignmentState retailer=$retailerName")
            }
        }

        ShoppingUiState(
            activeListWithItems = listWithItems,
            isFollower = appRoleResolver.isFollower(),
            stapleRules = stapleRules,
            activeTab = activeTab,
            userMessage = userMessage,
            isLoading = isLoading,
            selectedItemForDetail = itemDetail,
            showAddManualDialog = showAddDialog,
            basketComparisonResult = basketResult,
            retailerGroups = groups,
            manualItemName = manualItemName,
            manualItemNotes = manualItemNotes
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ShoppingUiState()
    )

    private fun runBasketOptimization(listWithItems: ShoppingListWithItems?) {
        if (appRoleResolver.isFollower()) {
            val version = ++basketOptimizationVersion
            basketOptimizationJob?.cancel()
            basketOptimizationFlow.value = null
            if (listWithItems?.items.isNullOrEmpty()) return
            basketOptimizationJob = viewModelScope.launch {
                val cached = followerBasketComparisonCacheStore.getCachedComparison()
                val expectedIds = listWithItems?.items?.map { it.item.id }?.toSet().orEmpty()
                val cachedIds = cached?.let { result ->
                    (result.selectedPlan.lines.map { it.shoppingRequirementId } + result.unmatchedRequirements).toSet()
                }.orEmpty()
                if (version == basketOptimizationVersion) {
                    basketOptimizationFlow.value = cached?.takeIf { expectedIds == cachedIds }
                }
            }
            return
        }
        val version = ++basketOptimizationVersion
        basketOptimizationJob?.cancel()
        basketOptimizationFlow.value = null
        if (listWithItems?.items.isNullOrEmpty()) return

        basketOptimizationJob = viewModelScope.launch {
            val count = listWithItems?.items?.size ?: 0
            println("[AI_MEALPLANNER] [SHOPPING_ASSIGNMENT_CALL] shoppingItemCount=$count")
            val result = try {
                val preferences = UserShoppingPreferences(
                    enabledRetailers = retailerPreferenceStore.getEnabledRetailers(),
                    preferredStores = retailStorePreferenceStore.getPreferredStores()
                )
                val res = retailShoppingDomainService.optimizeShoppingBasket(preferences)
                if (res is DomainResult.Success) {
                    res.value.selectedPlan.lines.forEach { line ->
                        println("[AI_MEALPLANNER] [SHOPPING_ASSIGNMENT_RESULT] requirementId=${line.shoppingRequirementId} assigned=true retailer=${line.retailerId.name}")
                    }
                    res.value.selectedPlan.unmatchedRequirements.forEach { reqId ->
                        println("[AI_MEALPLANNER] [SHOPPING_ASSIGNMENT_RESULT] requirementId=$reqId assigned=false")
                    }
                }
                res
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                println("[AI_MEALPLANNER] [SHOPPING_OPTIMIZE_EXCEPTION] exceptionType=${e.javaClass.simpleName}")
                null
            }
            if (version == basketOptimizationVersion) {
                basketOptimizationFlow.value = if (result is DomainResult.Success) result.value else null
            }
        }
    }

    init {
        println("[AI_MEALPLANNER] [SHOPPING_VM_INIT] buildAudit=SHOPPING_AUDIT_20261001_02")
        if (!appRoleResolver.isFollower()) {
            regenerateShoppingList()
        } else {
            viewModelScope.launch {
                followerBasketComparisonCacheStore.observeCachedComparison().collect { cached ->
                    val expectedIds = uiState.value.activeListWithItems?.items?.map { it.item.id }?.toSet().orEmpty()
                    val cachedIds = cached?.let { result ->
                        (result.selectedPlan.lines.map { it.shoppingRequirementId } + result.unmatchedRequirements).toSet()
                    }.orEmpty()
                    basketOptimizationFlow.value = cached?.takeIf { expectedIds.isNotEmpty() && expectedIds == cachedIds }
                }
            }
        }
    }

    fun onEvent(event: ShoppingUiEvent) {
        when (event) {
            is ShoppingUiEvent.SelectTab -> activeTabFlow.value = event.tab
            ShoppingUiEvent.RegenerateShoppingList -> if (!appRoleResolver.isFollower()) regenerateShoppingList()
            ShoppingUiEvent.ClearCompletedItems -> if (!appRoleResolver.isFollower()) clearCompletedItems()
            is ShoppingUiEvent.ToggleItemChecked -> toggleItemChecked(event.itemId, event.currentStatus)
            is ShoppingUiEvent.RemoveItem -> removeItem(event.itemId)
            is ShoppingUiEvent.SelectItemDetail -> selectedItemDetailFlow.value = event.itemWithSources
            ShoppingUiEvent.RequestAddManualItem -> {
                manualItemNameFlow.value = ""
                manualItemNotesFlow.value = ""
                showAddManualDialogFlow.value = true
            }
            is ShoppingUiEvent.RequestLowStockShortcut -> prepareLowStockShortcut(event.item)
            ShoppingUiEvent.DismissAddManualDialog -> {
                showAddManualDialogFlow.value = false
                manualItemNameFlow.value = ""
                manualItemNotesFlow.value = ""
            }
            is ShoppingUiEvent.AddManualItem -> addManualItem(event.name, event.quantity, event.unit, event.notes)
            is ShoppingUiEvent.ResolveUncertainty -> resolveUncertainty(
                event.inventoryItemId,
                event.confirmedQuantity,
                event.unit,
                event.confidence
            )
            is ShoppingUiEvent.SaveStapleRule -> saveStapleRule(event.rule)
            is ShoppingUiEvent.DeleteStapleRule -> deleteStapleRule(event.ruleId)
            ShoppingUiEvent.ClearUserMessage -> userMessageFlow.value = null
        }
    }

    private fun regenerateShoppingList() {
        viewModelScope.launch {
            isLoadingFlow.value = true
            val result = shoppingDomainService.generateOrRegenerateShoppingList()
            isLoadingFlow.value = false

            when (result) {
                is DomainResult.Success -> Unit
                is DomainResult.Failure -> userMessageFlow.value = formatDomainError(result.error)
            }
        }
    }

    private fun toggleItemChecked(itemId: String, currentStatus: ShoppingItemStatus) {
        viewModelScope.launch {
            val nextStatus = if (currentStatus == ShoppingItemStatus.CHECKED) ShoppingItemStatus.NEEDED else ShoppingItemStatus.CHECKED
            val result = shoppingDomainService.updateItemStatus(itemId, nextStatus)
            if (result is DomainResult.Failure) {
                userMessageFlow.value = formatDomainError(result.error)
            }
        }
    }

    private fun clearCompletedItems() {
        val listId = uiState.value.activeListWithItems?.shoppingList?.id ?: return
        viewModelScope.launch {
            val removedCount = shoppingRepository.deleteCompletedItems(listId)
            userMessageFlow.value = if (removedCount == 0) {
                "No completed items to clear"
            } else {
                "Cleared $removedCount completed ${if (removedCount == 1) "item" else "items"}"
            }
        }
    }

    private fun removeItem(itemId: String) {
        viewModelScope.launch {
            val result = shoppingDomainService.removeShoppingItem(itemId)
            when (result) {
                is DomainResult.Success -> userMessageFlow.value = "Item removed"
                is DomainResult.Failure -> userMessageFlow.value = formatDomainError(result.error)
            }
        }
    }

    private fun prepareLowStockShortcut(item: InventoryItem) {
        activeTabFlow.value = ShoppingTab.SHOPPING_LIST
        manualItemNameFlow.value = item.displayName
        manualItemNotesFlow.value = "Low pantry stock. Check the amount on hand before choosing how much to buy."
        showAddManualDialogFlow.value = true
    }

    private fun addManualItem(name: String, quantity: Double?, unit: QuantityUnit?, notes: String?) {
        viewModelScope.launch {
            isLoadingFlow.value = true
            var activeList = when (val result = shoppingDomainService.getActiveShoppingList()) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> {
                    isLoadingFlow.value = false
                    userMessageFlow.value = formatDomainError(result.error)
                    return@launch
                }
            }
            if (activeList == null) {
                activeList = when (val result = shoppingDomainService.generateOrRegenerateShoppingList()) {
                    is DomainResult.Success -> result.value
                    is DomainResult.Failure -> {
                        isLoadingFlow.value = false
                        userMessageFlow.value = formatDomainError(result.error)
                        return@launch
                    }
                }
            }
            val duplicate = activeList.items.firstOrNull {
                it.item.displayName.trim().equals(name.trim(), ignoreCase = true)
            }
            if (duplicate != null) {
                isLoadingFlow.value = false
                showAddManualDialogFlow.value = false
                userMessageFlow.value = "${duplicate.item.displayName} is already on the shopping list"
                return@launch
            }
            val result = shoppingDomainService.addManualShoppingItem(activeList.shoppingList.id, name, quantity, unit, notes)
            isLoadingFlow.value = false
            showAddManualDialogFlow.value = false

            when (result) {
                is DomainResult.Success -> {
                    manualItemNameFlow.value = ""
                    manualItemNotesFlow.value = ""
                    userMessageFlow.value = "Item added to shopping list"
                }
                is DomainResult.Failure -> userMessageFlow.value = formatDomainError(result.error)
            }
        }
    }

    private fun resolveUncertainty(inventoryItemId: String, quantity: Double, unit: QuantityUnit, confidence: QuantityConfidence) {
        viewModelScope.launch {
            isLoadingFlow.value = true
            val result = shoppingDomainService.resolveInventoryLotUncertainty(inventoryItemId, quantity, unit, confidence)
            isLoadingFlow.value = false

            when (result) {
                is DomainResult.Success -> userMessageFlow.value = "Uncertainty resolved and shopping list recalculated"
                is DomainResult.Failure -> userMessageFlow.value = formatDomainError(result.error)
            }
        }
    }

    private fun saveStapleRule(rule: StapleRule) {
        viewModelScope.launch {
            stapleRuleRepository.saveStapleRule(rule)
            userMessageFlow.value = "Staple rule saved"
            regenerateShoppingList()
        }
    }

    private fun deleteStapleRule(ruleId: String) {
        viewModelScope.launch {
            stapleRuleRepository.deleteStapleRule(ruleId)
            userMessageFlow.value = "Staple rule deleted"
            regenerateShoppingList()
        }
    }

    private fun formatDomainError(error: DomainError): String {
        return when (error) {
            is DomainError.InsufficientQuantity -> "Quantity error: ${error.requested}"
            is DomainError.InvalidUnitConversion -> "Unit conversion error between ${error.from} and ${error.to}."
            is DomainError.NeedsConfirmation -> "Confirmation required: ${error.displayName}"
            is DomainError.DuplicateOperation -> "Already completed: ${error.message}"
            is DomainError.TransactionFailed -> "Transaction error: ${error.cause}"
            is DomainError.ItemNotFound -> "Item not found: ${error.itemId}"
            is DomainError.InvalidQuantity -> "Invalid quantity: ${error.quantity}"
            is DomainError.ItemNotUsable -> "Item not available: ${error.itemId}"
        }
    }

    companion object {
        fun computeRetailerGroups(
            items: List<ShoppingListItemWithSources>,
            basketResult: BasketComparisonResult?
        ): List<RetailerGroup> {
            if (items.isEmpty()) return emptyList()

            if (basketResult == null) {
                return listOf(
                    RetailerGroup(
                        retailerId = null,
                        retailerName = "All Required Items",
                        subtotal = null,
                        items = items
                    )
                )
            }

            val selectedPlan = basketResult.selectedPlan
            val lineByReqId = selectedPlan.lines.associateBy { it.shoppingRequirementId }
            val lineByCanonicalId = selectedPlan.lines
                .filter { it.productWithOffer.product.canonicalIngredientId != null }
                .associateBy { it.productWithOffer.product.canonicalIngredientId!! }
            val lineByDisplayName = selectedPlan.lines.associateBy { it.requirementDisplayName.lowercase() }

            fun findLineForItem(item: ShoppingListItemWithSources): BasketLine? {
                return lineByReqId[item.item.id]
                    ?: item.item.canonicalIngredientId?.let { lineByCanonicalId[it] }
                    ?: lineByDisplayName[item.item.displayName.lowercase()]
            }

            val itemMapByRetailer = items.groupBy { item ->
                findLineForItem(item)?.retailerId
            }

            val groups = mutableListOf<RetailerGroup>()

            // Display order must not imply that any retailer is preferred by the app.
            // The user's enabled-retailer settings control eligibility; group display is neutral.
            val orderedStores = selectedPlan.stores.sortedBy(::formatRetailerName)

            orderedStores.distinct().forEach { storeId ->
                val groupItems = itemMapByRetailer[storeId]
                if (!groupItems.isNullOrEmpty()) {
                    val groupLines = groupItems.mapNotNull { findLineForItem(it) }
                    val subtotal = if (groupLines.isNotEmpty()) {
                        groupLines.map { it.lineTotalCost }.reduce { acc, money -> acc + money }
                    } else null

                    val lineMap = groupItems.mapNotNull { item ->
                        val line = findLineForItem(item)
                        if (line != null) item.item.id to line else null
                    }.toMap()

                    groups.add(
                        RetailerGroup(
                            retailerId = storeId,
                            retailerName = formatRetailerName(storeId),
                            subtotal = subtotal,
                            items = groupItems,
                            lineByItemId = lineMap
                        )
                    )
                }
            }

            val unassignedItems = itemMapByRetailer[null]
            if (!unassignedItems.isNullOrEmpty()) {
                groups.add(
                    RetailerGroup(
                        retailerId = null,
                        retailerName = "Unassigned Items",
                        subtotal = null,
                        items = unassignedItems
                    )
                )
            }

            logDiagnostic("SHOPPING_UI_MAPPING", "shoppingItems=${items.size} basketLines=${selectedPlan.lines.size} optimizerUnmatched=${selectedPlan.unmatchedRequirements.size} retailerGroups=${groups.count { it.retailerId != null }} uiUnassigned=${unassignedItems?.size ?: 0}")

            selectedPlan.lines.forEach { line ->
                val found = items.any { findLineForItem(it) == line }
                logDiagnostic("BASKET_LINE_MAP", "requirementId=${line.shoppingRequirementId} retailer=${line.retailerId.name} lineCost=${line.lineTotalCost.toFormattedString()} shoppingItemFound=$found")
            }

            items.forEach { item ->
                val line = findLineForItem(item)
                val isUnmatched = selectedPlan.unmatchedRequirements.contains(item.item.id) || selectedPlan.unmatchedRequirements.contains(item.item.displayName)
                logDiagnostic("SHOPPING_ITEM_MAP", "shoppingItemId=${item.item.id} requirementIdentity=${item.item.id} matchedBasketLine=${line != null} optimizerUnmatched=$isUnmatched")
            }

            val groupedSubtotal = groups.filter { it.retailerId != null }
                .mapNotNull { it.subtotal }
                .fold(Money.zero()) { acc, money -> acc + money }

            if (selectedPlan.basketSubtotal > Money.zero() && groups.none { it.retailerId != null }) {
                logDiagnostic("SHOPPING_UI_INVARIANT", "basketSubtotal=${selectedPlan.basketSubtotal.toFormattedString()} groupedSubtotal=${groupedSubtotal.toFormattedString()} matchedLines=${selectedPlan.lines.size} retailerGroups=0 status=MAPPING_MISMATCH")
            }

            return groups
        }

        private fun logDiagnostic(tag: String, message: String) {
            println("[$tag] $message")
            try {
                android.util.Log.d(tag, message)
            } catch (_: Throwable) {
                // Unstubbed in JVM unit tests
            }
        }

        private fun formatRetailerName(retailerId: RetailerId): String {
            return when (retailerId) {
                RetailerId.ASDA -> "ASDA"
                RetailerId.ICELAND -> "Iceland"
                RetailerId.FOOD_WAREHOUSE -> "The Food Warehouse"
                RetailerId.TESCO -> "Tesco"
                RetailerId.MORRISONS -> "Morrisons"
                RetailerId.SAINSBURYS -> "Sainsbury's"
            }
        }
    }
}
