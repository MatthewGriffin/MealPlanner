package com.example.chatgpt_mealplanner.feature.shopping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.database.dao.PurchasePriceDao
import com.example.chatgpt_mealplanner.core.service.ShopPriceComparator
import com.example.chatgpt_mealplanner.core.service.ShopPriceOption
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ShoppingViewModel @Inject constructor(
    private val shoppingDomainService: ShoppingDomainService,
    private val shoppingRepository: ShoppingRepository,
    private val stapleRuleRepository: StapleRuleRepository,
    private val appRoleResolver: AppRoleResolver,
    private val purchasePriceDao: PurchasePriceDao? = null,
    private val followerDatabase: com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase? = null,
    private val shoppingSessions: com.example.chatgpt_mealplanner.core.shopping.ShoppingSessionManager? = null,
    private val motionTracker: com.example.chatgpt_mealplanner.core.shopping.ShopMotionTracker? = null
) : ViewModel() {

    private val activeTabFlow = MutableStateFlow(ShoppingTab.SHOPPING_LIST)
    private val userMessageFlow = MutableStateFlow<String?>(null)
    private val isLoadingFlow = MutableStateFlow(false)
    private val selectedItemDetailFlow = MutableStateFlow<ShoppingListItemWithSources?>(null)
    private val showAddManualDialogFlow = MutableStateFlow(false)
    private val manualItemNameFlow = MutableStateFlow("")
    private val manualItemNotesFlow = MutableStateFlow("")
    private val shopPriceOptionsFlow = MutableStateFlow<Map<String, List<ShopPriceOption>>>(emptyMap())
    private var localPriceLookupJob: kotlinx.coroutines.Job? = null
    private var lastPriceLookupSignature: String? = null

    /** On a follower the meals behind each item come from Main's sync, so the trip splitting works there too. */
    private val followerSourcesFlow = if (appRoleResolver.isFollower() && followerDatabase != null)
        followerDatabase.shoppingSourceDao().observeAll() else kotlinx.coroutines.flow.flowOf(emptyList())

    private val activeShoppingListFlow = kotlinx.coroutines.flow.combine(
        shoppingRepository.observeActiveShoppingListWithItems(), followerSourcesFlow
    ) { list, extra ->
        if (list == null || extra.isEmpty()) list else {
            val byItem = extra.groupBy { it.shoppingListItemId }
            list.copy(items = list.items.map { entry ->
                val rows = byItem[entry.item.id]
                if (entry.sources.isNotEmpty() || rows == null) entry else entry.copy(sources = rows.map {
                    com.example.chatgpt_mealplanner.core.model.ShoppingRequirementSource(
                        id = it.id, shoppingListItemId = it.shoppingListItemId, mealId = it.mealId,
                        mealIngredientId = it.mealIngredientId, mealTitle = it.mealTitle, mealDate = it.mealDate,
                        requiredQuantity = it.requiredQuantity,
                        requiredUnit = runCatching { com.example.chatgpt_mealplanner.core.model.QuantityUnit.valueOf(it.requiredUnit) }
                            .getOrDefault(com.example.chatgpt_mealplanner.core.model.QuantityUnit.UNIT)
                    )
                })
            })
        }
    }
        .onEach { listWithItems ->
            val requirementSignature = listWithItems?.let { list ->
                list.shoppingList.id + "|" + list.items.joinToString("|") { entry ->
                    val item = entry.item
                    listOf(
                        item.id,
                        item.displayName,
                        item.suggestedPurchaseQuantity,
                        item.suggestedPurchaseUnit,
                        item.reason,
                        item.requirementConfidence,
                        item.quantityUncertain
                    ).joinToString(":")
                }
            } ?: "empty"
            if (requirementSignature != lastPriceLookupSignature) {
                lastPriceLookupSignature = requirementSignature
                runLocalPriceLookup(listWithItems)
            }
        }
    private val stapleRulesFlow = stapleRuleRepository.observeAllStapleRules()

    private val storesFlow = purchasePriceDao?.observeActiveStores() ?: kotlinx.coroutines.flow.flowOf(emptyList())
    private val openSessionFlow = shoppingSessions?.observeOpenSession() ?: kotlinx.coroutines.flow.flowOf(null)

    /** The shop being shopped in and what is known of its order, or null when no trip is open. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val activeShopFlow = combine(openSessionFlow, storesFlow) { session, stores -> session to stores }
        .flatMapLatest { (session, stores) ->
            if (session == null || shoppingSessions == null) kotlinx.coroutines.flow.flowOf(null)
            else shoppingSessions.observeOrder(session.storeId).map { rows ->
                ActiveShop(
                    storeId = session.storeId,
                    storeName = stores.firstOrNull { it.id == session.storeId }?.name ?: "your shop",
                    orderByKey = orderFrom(rows)
                )
            }
        }

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<ShoppingUiState> = combine(
        activeShoppingListFlow,
        stapleRulesFlow,
        activeTabFlow,
        userMessageFlow,
        isLoadingFlow,
        selectedItemDetailFlow,
        showAddManualDialogFlow,
        manualItemNameFlow,
        manualItemNotesFlow,
        shopPriceOptionsFlow,
        activeShopFlow,
        storesFlow
    ) { flows ->
        val listWithItems = flows[0] as ShoppingListWithItems?
        val stapleRules = flows[1] as List<StapleRule>
        val activeTab = flows[2] as ShoppingTab
        val userMessage = flows[3] as String?
        val isLoading = flows[4] as Boolean
        val itemDetail = flows[5] as ShoppingListItemWithSources?
        val showAddDialog = flows[6] as Boolean
        val manualItemName = flows[7] as String
        val manualItemNotes = flows[8] as String
        val shopPriceOptions = flows[9] as Map<String, List<ShopPriceOption>>
        val activeShop = flows[10] as ActiveShop?
        val stores = flows[11] as List<com.example.chatgpt_mealplanner.core.database.entity.UserStoreEntity>

        ShoppingUiState(
            activeListWithItems = listWithItems,
            isFollower = appRoleResolver.isFollower(),
            stapleRules = stapleRules,
            activeTab = activeTab,
            userMessage = userMessage,
            isLoading = isLoading,
            selectedItemForDetail = itemDetail,
            showAddManualDialog = showAddDialog,
            shopPriceOptions = shopPriceOptions,
            manualItemName = manualItemName,
            manualItemNotes = manualItemNotes,
            activeShop = activeShop,
            motion = motionTracker?.let { com.example.chatgpt_mealplanner.feature.shopping.MotionOptions(available = it.available(), enabled = it.enabled, hasPermission = it.hasPermission()) } ?: MotionOptions(),
            shops = stores.map { ShopChoice(it.id, it.name) }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ShoppingUiState()
    )

    /** Shopping prices only come from locally recorded purchases, so unknown prices stay unknown. */
    private fun runLocalPriceLookup(listWithItems: ShoppingListWithItems?) {
        localPriceLookupJob?.cancel()
        val dao = purchasePriceDao
        val items = listWithItems?.items.orEmpty()
        if (dao == null || items.isEmpty()) {
            shopPriceOptionsFlow.value = emptyMap()
            return
        }
        localPriceLookupJob = viewModelScope.launch {
            try {
                val matches = withContext(Dispatchers.IO) {
                    val history = dao.getActiveStorePriceHistory()
                    items.associate { entry ->
                        val item = entry.item
                        val matching = history.filter {
                            ShopPriceComparator.matchesItem(item.canonicalIngredientId, item.displayName, it.observation)
                        }
                        item.id to ShopPriceComparator.compare(
                            neededQuantity = item.suggestedPurchaseQuantity,
                            neededUnit = item.suggestedPurchaseUnit,
                            prices = matching
                        )
                    }.filterValues { it.isNotEmpty() }
                }
                shopPriceOptionsFlow.value = matches
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                shopPriceOptionsFlow.value = emptyMap()
            }
        }
    }

    init {
        purchasePriceDao?.let { dao ->
            viewModelScope.launch {
                dao.observeRecentPriceHistory().collect {
                    runLocalPriceLookup(uiState.value.activeListWithItems)
                }
            }
        }
        if (!appRoleResolver.isFollower()) {
            regenerateShoppingList()
        }
    }

    fun onEvent(event: ShoppingUiEvent) {
        when (event) {
            is ShoppingUiEvent.SelectTab -> activeTabFlow.value = event.tab
            ShoppingUiEvent.RegenerateShoppingList -> if (!appRoleResolver.isFollower()) regenerateShoppingList()
            ShoppingUiEvent.ClearCompletedItems -> if (!appRoleResolver.isFollower()) clearCompletedItems()
            is ShoppingUiEvent.ToggleItemChecked -> toggleItemChecked(event.itemId, event.currentStatus)
            is ShoppingUiEvent.RemoveItem -> removeItem(event.itemId)
            is ShoppingUiEvent.SelectItemDetail -> {
                selectedItemDetailFlow.value = event.itemWithSources
            }
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
            is ShoppingUiEvent.StartShopping -> startShopping(event.storeId, event.learnLayout)
            ShoppingUiEvent.FinishShopping -> finishShopping()
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

    /**
     * Items whose spot in the shop has been sensed are ordered by walking to the nearest next, which follows the real
     * layout; the others keep the position learned from the order they were ticked in.
     */
    private fun orderFrom(rows: List<com.example.chatgpt_mealplanner.core.database.entity.StoreItemOrderEntity>): Map<String, Double> {
        val sensed = rows.filter { it.avgX != null && it.avgY != null && it.posSamples > 0 }
            .associate { it.itemKey to com.example.chatgpt_mealplanner.core.shopping.ShopPoint(it.avgX!!, it.avgY!!) }
        val byRoute = if (sensed.size >= MIN_SENSED_ITEMS) com.example.chatgpt_mealplanner.core.shopping.StoreRoute.rankByRoute(sensed) else emptyMap()
        return rows.associate { it.itemKey to (byRoute[it.itemKey] ?: it.avgRank) }
    }

    private fun startShopping(storeId: String, learnLayout: Boolean?) {
        val sessions = shoppingSessions ?: return
        motionTracker?.let { tracker ->
            if (learnLayout != null) tracker.enabled = learnLayout
        }
        viewModelScope.launch {
            runCatching { sessions.start(storeId) }
            motionTracker?.stop()
            motionTracker?.start()
        }
    }

    private fun finishShopping() {
        val sessions = shoppingSessions ?: return
        motionTracker?.stop()
        viewModelScope.launch {
            runCatching { sessions.finishOpenTrip() }
            userMessageFlow.value = "Shopping finished. The order you ticked things in is remembered for next time."
        }
    }

    private fun toggleItemChecked(itemId: String, currentStatus: ShoppingItemStatus) {
        // Ticks are noted as they happen: regenerating the list rebuilds it and loses them.
        if (currentStatus != ShoppingItemStatus.CHECKED) {
            val name = uiState.value.activeListWithItems?.items?.firstOrNull { it.item.id == itemId }?.item?.displayName
            if (name != null && shoppingSessions != null) viewModelScope.launch { runCatching { shoppingSessions.recordTick(name, motionTracker?.position()) } }
        }
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

    private companion object {
        /** Fewer sensed items than this give no route worth following; the tick order is used instead. */
        const val MIN_SENSED_ITEMS = 4
    }
}
