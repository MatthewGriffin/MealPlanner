package com.example.chatgpt_mealplanner.core.service

import com.example.chatgpt_mealplanner.core.database.dao.BarcodeMappingDao
import com.example.chatgpt_mealplanner.core.database.dao.ChatDao
import com.example.chatgpt_mealplanner.core.database.dao.InventoryBarcodeDao
import com.example.chatgpt_mealplanner.core.database.dao.InventoryDao
import com.example.chatgpt_mealplanner.core.database.dao.LeftoverDao
import com.example.chatgpt_mealplanner.core.database.dao.MealDao
import com.example.chatgpt_mealplanner.core.database.dao.MealWithIngredientEntities
import com.example.chatgpt_mealplanner.core.database.dao.ShoppingDao
import com.example.chatgpt_mealplanner.core.database.dao.ShoppingListItemWithSourceEntities
import com.example.chatgpt_mealplanner.core.database.dao.ShoppingListWithItemEntities
import com.example.chatgpt_mealplanner.core.database.dao.StapleRuleDao
import com.example.chatgpt_mealplanner.core.database.dao.TransactionDao
import com.example.chatgpt_mealplanner.core.database.entity.AiToolExecutionLogEntity
import com.example.chatgpt_mealplanner.core.database.entity.ChatConversationEntity
import com.example.chatgpt_mealplanner.core.database.entity.ChatMessageEntity
import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemBarcodeEntity
import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemEntity
import com.example.chatgpt_mealplanner.core.database.entity.InventoryMergeLogEntity
import com.example.chatgpt_mealplanner.core.database.entity.InventoryTransactionEntity
import com.example.chatgpt_mealplanner.core.database.entity.LeftoverEntity
import com.example.chatgpt_mealplanner.core.database.entity.MealEntity
import com.example.chatgpt_mealplanner.core.database.entity.MealIngredientEntity
import com.example.chatgpt_mealplanner.core.database.entity.ProductBarcodeMappingEntity
import com.example.chatgpt_mealplanner.core.database.entity.ShoppingListEntity
import com.example.chatgpt_mealplanner.core.database.entity.ShoppingListItemEntity
import com.example.chatgpt_mealplanner.core.database.entity.ShoppingRequirementSourceEntity
import com.example.chatgpt_mealplanner.core.database.entity.StapleRuleEntity
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.LeftoverStatus
import com.example.chatgpt_mealplanner.core.model.ShoppingListStatus
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeInventoryBarcodeDao : InventoryBarcodeDao {
    val barcodes = mutableListOf<InventoryItemBarcodeEntity>()
    val mergeLogs = mutableListOf<InventoryMergeLogEntity>()

    override suspend fun getBarcodeEntity(normalizedBarcode: String) = barcodes.firstOrNull { it.normalizedBarcode == normalizedBarcode }
    override suspend fun getBarcodesForItem(inventoryItemId: String) = barcodes.filter { it.inventoryItemId == inventoryItemId }
    override fun observeBarcodesForItem(inventoryItemId: String) = MutableStateFlow(barcodes.filter { it.inventoryItemId == inventoryItemId })
    override suspend fun insertBarcode(entity: InventoryItemBarcodeEntity) {
        barcodes.removeAll { it.id == entity.id || it.normalizedBarcode == entity.normalizedBarcode }
        barcodes.add(entity)
    }
    override suspend fun insertBarcodes(entities: List<InventoryItemBarcodeEntity>) {
        entities.forEach { insertBarcode(it) }
    }
    override suspend fun deleteBarcode(id: String) { barcodes.removeAll { it.id == id } }
    override suspend fun transferBarcodes(oldInventoryItemId: String, newInventoryItemId: String) {
        val matching = barcodes.filter { it.inventoryItemId == oldInventoryItemId }
        matching.forEach {
            barcodes.remove(it)
            barcodes.add(it.copy(inventoryItemId = newInventoryItemId))
        }
    }
    override suspend fun updateBarcodeOwner(barcodeId: String, newInventoryItemId: String) {
        val bc = barcodes.firstOrNull { it.id == barcodeId }
        if (bc != null) {
            barcodes.remove(bc)
            barcodes.add(bc.copy(inventoryItemId = newInventoryItemId))
        }
    }
    override suspend fun insertMergeLog(log: InventoryMergeLogEntity) {
        mergeLogs.removeAll { it.id == log.id }
        mergeLogs.add(log)
    }
    override suspend fun updateMergeLog(log: InventoryMergeLogEntity) {
        mergeLogs.removeAll { it.id == log.id }
        mergeLogs.add(log)
    }
    override suspend fun getActiveMergeLogsForSurvivor(survivorItemId: String) = mergeLogs.filter { it.survivorItemId == survivorItemId && !it.unmerged }
    override suspend fun getActiveMergeLogs() = mergeLogs.filter { !it.unmerged }
    override suspend fun getMergeLogById(id: String) = mergeLogs.firstOrNull { it.id == id }
}

class FakeInventoryDao : InventoryDao {
    val itemsMap = mutableMapOf<String, InventoryItemEntity>()
    private val flow = MutableStateFlow<List<InventoryItemEntity>>(emptyList())

    private fun updateFlow() {
        flow.value = itemsMap.values.toList()
    }

    override fun getAllItems(): Flow<List<InventoryItemEntity>> = flow

    override suspend fun getAllItemsSync(): List<InventoryItemEntity> = itemsMap.values.toList()

    override fun getUsableItems(): Flow<List<InventoryItemEntity>> =
        flow.map { list -> list.filter { it.status in listOf(ItemStatus.AVAILABLE, ItemStatus.LOW, ItemStatus.USE_SOON) } }

    override fun getUsableItemsByLocation(location: StorageLocation): Flow<List<InventoryItemEntity>> =
        flow.map { list -> list.filter { it.storageLocation == location && it.status in listOf(ItemStatus.AVAILABLE, ItemStatus.LOW, ItemStatus.USE_SOON) } }

    override fun getLowStockItems(): Flow<List<InventoryItemEntity>> =
        flow.map { list -> list.filter { it.status == ItemStatus.LOW } }

    override fun getUseSoonItems(): Flow<List<InventoryItemEntity>> =
        flow.map { list -> list.filter { it.status == ItemStatus.USE_SOON } }

    override fun getOutOfStockItems(): Flow<List<InventoryItemEntity>> =
        flow.map { list -> list.filter { it.status == ItemStatus.OUT } }

    override fun getItemsByLocation(location: StorageLocation): Flow<List<InventoryItemEntity>> =
        flow.map { list -> list.filter { it.storageLocation == location } }

    override fun getItemsByStatus(status: ItemStatus): Flow<List<InventoryItemEntity>> =
        flow.map { list -> list.filter { it.status == status } }

    override suspend fun getItemById(id: String): InventoryItemEntity? = itemsMap[id]

    override suspend fun getItemByBarcode(barcode: String): InventoryItemEntity? =
        itemsMap.values.firstOrNull { it.barcode == barcode && it.status in listOf(ItemStatus.AVAILABLE, ItemStatus.LOW, ItemStatus.USE_SOON) }

    override suspend fun getUsableItemsByCanonicalIngredientId(ingredientId: String): List<InventoryItemEntity> =
        itemsMap.values.filter { it.canonicalIngredientId == ingredientId && it.status in listOf(ItemStatus.AVAILABLE, ItemStatus.LOW, ItemStatus.USE_SOON) }

    override suspend fun insertItem(item: InventoryItemEntity) {
        itemsMap[item.id] = item
        updateFlow()
    }

    override suspend fun insertItems(items: List<InventoryItemEntity>) {
        items.forEach { itemsMap[it.id] = it }
        updateFlow()
    }

    override suspend fun updateItem(item: InventoryItemEntity) {
        itemsMap[item.id] = item
        updateFlow()
    }

    override suspend fun deleteItem(item: InventoryItemEntity) {
        itemsMap.remove(item.id)
        updateFlow()
    }

    override suspend fun deleteItemById(id: String) {
        itemsMap.remove(id)
        updateFlow()
    }

    override suspend fun deleteAllItems() {
        itemsMap.clear()
        updateFlow()
    }
}

class FakeTransactionDao : TransactionDao {
    val transactions = mutableListOf<InventoryTransactionEntity>()
    private val flow = MutableStateFlow<List<InventoryTransactionEntity>>(emptyList())

    override fun getTransactionsForItem(itemId: String): Flow<List<InventoryTransactionEntity>> =
        flow.map { list -> list.filter { it.inventoryItemId == itemId } }

    override fun getAllTransactions(): Flow<List<InventoryTransactionEntity>> = flow

    override suspend fun getAllTransactionsSync(): List<InventoryTransactionEntity> = transactions.toList()

    override suspend fun hasCookedTransactionForMeal(mealId: String): Boolean =
        transactions.any { it.mealId == mealId && it.type == com.example.chatgpt_mealplanner.core.model.TransactionType.COOKED }

    override suspend fun getTransactionsForMeal(mealId: String): List<InventoryTransactionEntity> =
        transactions.filter { it.mealId == mealId }

    override suspend fun insertTransaction(transaction: InventoryTransactionEntity) {
        transactions.add(transaction)
        flow.value = transactions.toList()
    }

    override suspend fun insertTransactions(transactions: List<InventoryTransactionEntity>) {
        this.transactions.addAll(transactions)
        flow.value = this.transactions.toList()
    }
}

class FakeBarcodeMappingDao : BarcodeMappingDao {
    val mappings = mutableMapOf<String, ProductBarcodeMappingEntity>()

    override suspend fun getMappingByBarcode(barcode: String): ProductBarcodeMappingEntity? = mappings[barcode]

    override suspend fun searchMappingsByName(query: String): List<ProductBarcodeMappingEntity> {
        val q = query.lowercase()
        return mappings.values.filter {
            (it.displayName?.lowercase()?.contains(q) == true) || (it.brand?.lowercase()?.contains(q) == true)
        }
    }

    override suspend fun getAllMappingsSync(): List<ProductBarcodeMappingEntity> = mappings.values.toList()

    override suspend fun insertMapping(mapping: ProductBarcodeMappingEntity) {
        mappings[mapping.barcode] = mapping
    }

    override suspend fun deleteMapping(barcode: String) {
        mappings.remove(barcode)
    }
}

class FakeMealDao : MealDao {
    val mealsMap = mutableMapOf<String, MealEntity>()
    val ingredientsMap = mutableMapOf<String, MutableList<MealIngredientEntity>>()

    override fun observeMealsForDate(date: String): Flow<List<MealWithIngredientEntities>> {
        val list = mealsMap.values.filter { it.date == date }.map { MealWithIngredientEntities(it, ingredientsMap[it.id] ?: emptyList()) }
        return MutableStateFlow(list)
    }

    override fun observeMealsForWeek(startDate: String, endDate: String): Flow<List<MealWithIngredientEntities>> {
        val list = mealsMap.values.filter { it.date in startDate..endDate }.map { MealWithIngredientEntities(it, ingredientsMap[it.id] ?: emptyList()) }
        return MutableStateFlow(list)
    }

    override suspend fun getMealWithIngredients(mealId: String): MealWithIngredientEntities? {
        val meal = mealsMap[mealId] ?: return null
        val ings = ingredientsMap[mealId] ?: emptyList()
        return MealWithIngredientEntities(meal, ings)
    }

    override suspend fun getAllMealsWithIngredientsSync(): List<MealWithIngredientEntities> {
        return mealsMap.values.map { MealWithIngredientEntities(it, ingredientsMap[it.id] ?: emptyList()) }
    }

    override suspend fun getRecentCookedMeals(sinceTimestamp: Long): List<MealWithIngredientEntities> {
        return mealsMap.values
            .filter { it.status == com.example.chatgpt_mealplanner.core.model.MealStatus.COOKED && (it.cookedAt ?: 0L) >= sinceTimestamp }
            .map { MealWithIngredientEntities(it, ingredientsMap[it.id] ?: emptyList()) }
    }

    override suspend fun getUpcomingPlannedMeals(startDate: String, endDate: String): List<MealWithIngredientEntities> {
        return mealsMap.values
            .filter { it.status == com.example.chatgpt_mealplanner.core.model.MealStatus.PLANNED && it.date in startDate..endDate }
            .map { MealWithIngredientEntities(it, ingredientsMap[it.id] ?: emptyList()) }
    }
    override suspend fun insertMeal(meal: MealEntity) { mealsMap[meal.id] = meal }
    override suspend fun insertMealIngredients(ingredients: List<MealIngredientEntity>) {
        ingredients.forEach {
            ingredientsMap.getOrPut(it.mealId) { mutableListOf() }.add(it)
        }
    }
    override suspend fun deleteMealIngredients(mealId: String) { ingredientsMap.remove(mealId) }
    override suspend fun deleteMeal(mealId: String) { mealsMap.remove(mealId); ingredientsMap.remove(mealId) }
}

class FakeLeftoverDao : LeftoverDao {
    val leftoversMap = mutableMapOf<String, LeftoverEntity>()

    override fun observeAvailableLeftovers(): Flow<List<LeftoverEntity>> = MutableStateFlow(leftoversMap.values.filter { it.status == LeftoverStatus.AVAILABLE && it.portions > 0 })
    override suspend fun getLeftoverById(id: String): LeftoverEntity? = leftoversMap[id]
    override suspend fun getAllLeftoversSync(): List<LeftoverEntity> = leftoversMap.values.toList()
    override suspend fun insertLeftover(leftover: LeftoverEntity) { leftoversMap[leftover.id] = leftover }
    override suspend fun deleteLeftover(id: String) { leftoversMap.remove(id) }
}

class FakeShoppingDao : ShoppingDao {
    val listsMap = mutableMapOf<String, ShoppingListEntity>()
    val itemsMap = mutableMapOf<String, ShoppingListItemEntity>()
    val sourcesMap = mutableMapOf<String, MutableList<ShoppingRequirementSourceEntity>>()

    override suspend fun getActiveShoppingList(): ShoppingListEntity? = listsMap.values.firstOrNull { it.status == ShoppingListStatus.ACTIVE }
    override fun observeActiveShoppingListWithItems(): Flow<ShoppingListWithItemEntities?> = MutableStateFlow(null)
    override suspend fun getShoppingListWithItems(listId: String): ShoppingListWithItemEntities? {
        val list = listsMap[listId] ?: return null
        val items = itemsMap.values.filter { it.shoppingListId == listId }.map { item ->
            ShoppingListItemWithSourceEntities(item, sourcesMap[item.id] ?: emptyList())
        }
        return ShoppingListWithItemEntities(list, items)
    }
    override suspend fun getAllListsWithItemsAndSourcesSync(): List<ShoppingListWithItemEntities> {
        return listsMap.values.map { list ->
            val items = itemsMap.values.filter { it.shoppingListId == list.id }.map { item ->
                ShoppingListItemWithSourceEntities(item, sourcesMap[item.id] ?: emptyList())
            }
            ShoppingListWithItemEntities(list, items)
        }
    }
    override suspend fun getShoppingListItemById(itemId: String): ShoppingListItemEntity? = itemsMap[itemId]
    override suspend fun insertShoppingList(list: ShoppingListEntity) { listsMap[list.id] = list }
    override suspend fun updateListStatus(listId: String, status: String) {
        val list = listsMap[listId]
        if (list != null) {
            listsMap[listId] = list.copy(status = ShoppingListStatus.valueOf(status))
        }
    }
    override suspend fun insertShoppingListItem(item: ShoppingListItemEntity) { itemsMap[item.id] = item }
    override suspend fun insertShoppingListItems(items: List<ShoppingListItemEntity>) { items.forEach { itemsMap[it.id] = it } }
    override suspend fun updateItemStatus(itemId: String, status: String) {
        val item = itemsMap[itemId]
        if (item != null) {
            itemsMap[itemId] = item.copy(status = com.example.chatgpt_mealplanner.core.model.ShoppingItemStatus.valueOf(status))
        }
    }
    override suspend fun deleteShoppingListItem(itemId: String) { itemsMap.remove(itemId) }
    override suspend fun insertRequirementSources(sources: List<ShoppingRequirementSourceEntity>) {
        sources.forEach { sourcesMap.getOrPut(it.shoppingListItemId) { mutableListOf() }.add(it) }
    }
    override suspend fun deleteGeneratedItemsForList(listId: String) {
        itemsMap.values.removeAll { it.shoppingListId == listId && it.sourceType != com.example.chatgpt_mealplanner.core.model.ShoppingSourceType.MANUAL }
    }
}

class FakeStapleRuleDao : StapleRuleDao {
    val rulesMap = mutableMapOf<String, StapleRuleEntity>()

    override fun observeAllStapleRules(): Flow<List<StapleRuleEntity>> = MutableStateFlow(rulesMap.values.filter { !it.archivedByMerge })
    override suspend fun getEnabledStapleRules(): List<StapleRuleEntity> = rulesMap.values.filter { it.enabled && !it.archivedByMerge }
    override suspend fun getAllRulesSync(): List<StapleRuleEntity> = rulesMap.values.toList()
    override suspend fun getStapleRuleByCanonicalIngredientId(canonicalId: String): StapleRuleEntity? =
        rulesMap.values.firstOrNull { it.canonicalIngredientId == canonicalId }

    override suspend fun insertOrUpdateStapleRule(rule: StapleRuleEntity) {
        rulesMap[rule.id] = rule
    }

    override suspend fun deleteStapleRule(id: String) {
        rulesMap.remove(id)
    }

    override suspend fun deactivateOrphanStapleRules() {
        val orphanKeys = rulesMap.filter { (_, rule) -> rule.canonicalIngredientId.startsWith("item_") }.keys
        orphanKeys.forEach { key ->
            val rule = rulesMap[key]
            if (rule != null) {
                rulesMap[key] = rule.copy(archivedByMerge = true)
            }
        }
    }
}

class FakeChatDao : ChatDao {
    val conversations = mutableListOf<ChatConversationEntity>()
    val messages = mutableListOf<ChatMessageEntity>()
    val toolLogs = mutableListOf<AiToolExecutionLogEntity>()

    override suspend fun getAllConversationsWithMessagesSync(): List<com.example.chatgpt_mealplanner.core.database.dao.ChatConversationWithMessages> {
        return conversations.map { conv ->
            com.example.chatgpt_mealplanner.core.database.dao.ChatConversationWithMessages(conv, messages.filter { it.conversationId == conv.id })
        }
    }

    override fun observeAllConversations(): Flow<List<ChatConversationEntity>> = MutableStateFlow(conversations)
    override suspend fun getConversationById(id: String): ChatConversationEntity? = conversations.firstOrNull { it.id == id }
    override suspend fun insertOrUpdateConversation(conversation: ChatConversationEntity) {
        conversations.removeAll { it.id == conversation.id }
        conversations.add(conversation)
    }
    override suspend fun deleteConversation(id: String) { conversations.removeAll { it.id == id } }
    override fun observeMessagesForConversation(conversationId: String): Flow<List<ChatMessageEntity>> = MutableStateFlow(messages.filter { it.conversationId == conversationId })
    override suspend fun getMessagesForConversation(conversationId: String): List<ChatMessageEntity> = messages.filter { it.conversationId == conversationId }
    override suspend fun insertMessage(message: ChatMessageEntity) { messages.add(message) }
    override suspend fun deleteAllMessagesForConversation(conversationId: String) { messages.removeAll { it.conversationId == conversationId } }
    override suspend fun getToolExecutionLog(conversationId: String, toolCallId: String): AiToolExecutionLogEntity? =
        toolLogs.filter { it.conversationId == conversationId && it.toolCallId == toolCallId }
            .sortedByDescending { it.executionStatus == "SUCCEEDED" }
            .firstOrNull()

    override suspend fun insertOrUpdateToolExecutionLog(log: AiToolExecutionLogEntity) {
        toolLogs.removeAll { it.id == log.id }
        toolLogs.add(log)
    }
    override suspend fun deleteLogsOlderThan(timestamp: Long) { toolLogs.removeAll { (it.startedAt ?: 0L) < timestamp } }

    val pendingMutations = mutableMapOf<String, com.example.chatgpt_mealplanner.core.database.entity.PendingAiMutationEntity>()
    val activeProposals = mutableMapOf<String, com.example.chatgpt_mealplanner.core.database.entity.ActiveProposalEntity>()

    override suspend fun getPendingAiMutation(conversationId: String) = pendingMutations[conversationId]
    override suspend fun insertOrUpdatePendingAiMutation(entity: com.example.chatgpt_mealplanner.core.database.entity.PendingAiMutationEntity) { pendingMutations[entity.conversationId] = entity }
    override suspend fun deletePendingAiMutation(conversationId: String) { pendingMutations.remove(conversationId) }
    override suspend fun getActiveProposal(conversationId: String) = activeProposals[conversationId]
    override suspend fun insertOrUpdateActiveProposal(entity: com.example.chatgpt_mealplanner.core.database.entity.ActiveProposalEntity) { activeProposals[entity.conversationId] = entity }
    override suspend fun deleteActiveProposal(conversationId: String) { activeProposals.remove(conversationId) }
}
