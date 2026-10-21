package com.example.chatgpt_mealplanner.core.ai.dispatcher

import com.example.chatgpt_mealplanner.contract.ActionAuthorizationContext
import com.example.chatgpt_mealplanner.contract.AiToolCall
import com.example.chatgpt_mealplanner.contract.AiToolClass
import com.example.chatgpt_mealplanner.contract.AiToolId
import com.example.chatgpt_mealplanner.contract.AiToolPayload
import com.example.chatgpt_mealplanner.contract.AiToolResult
import com.example.chatgpt_mealplanner.contract.AuthorizedMutationScope
import com.example.chatgpt_mealplanner.contract.InventoryItemAiDto
import com.example.chatgpt_mealplanner.contract.LeftoverAiDto
import com.example.chatgpt_mealplanner.contract.MealAiDto
import com.example.chatgpt_mealplanner.contract.PlannedIngredientAiDto
import com.example.chatgpt_mealplanner.contract.ShoppingItemAiDto
import com.example.chatgpt_mealplanner.core.ai.context.UserMessageIntentClassifier
import com.example.chatgpt_mealplanner.core.database.dao.ChatDao
import com.example.chatgpt_mealplanner.core.database.dao.InventoryDao
import com.example.chatgpt_mealplanner.core.database.dao.LeftoverDao
import com.example.chatgpt_mealplanner.core.database.dao.MealDao
import com.example.chatgpt_mealplanner.core.database.dao.ShoppingDao
import com.example.chatgpt_mealplanner.core.database.entity.AiToolExecutionLogEntity
import com.example.chatgpt_mealplanner.core.database.entity.ShoppingListEntity
import com.example.chatgpt_mealplanner.core.database.entity.ShoppingListItemEntity
import com.example.chatgpt_mealplanner.core.database.entity.toDomainModel
import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.MealStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.RequirementConfidence
import com.example.chatgpt_mealplanner.core.model.ShoppingItemStatus
import com.example.chatgpt_mealplanner.core.model.ShoppingListStatus
import com.example.chatgpt_mealplanner.core.model.ShoppingPriority
import com.example.chatgpt_mealplanner.core.model.ShoppingSourceType
import com.example.chatgpt_mealplanner.core.model.WasteRisk
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.example.chatgpt_mealplanner.core.service.InventoryDomainService
import com.example.chatgpt_mealplanner.core.service.MealDomainService
import com.example.chatgpt_mealplanner.core.service.RetailShoppingDomainService
import com.example.chatgpt_mealplanner.core.service.RetailerPreferenceStore
import com.example.chatgpt_mealplanner.core.service.ShoppingDomainService
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiToolDispatcher @Inject constructor(
    private val inventoryDao: InventoryDao,
    private val mealDao: MealDao,
    private val leftoverDao: LeftoverDao,
    private val shoppingDao: ShoppingDao,
    private val inventoryDomainService: InventoryDomainService,
    private val mealDomainService: MealDomainService,
    private val shoppingDomainService: ShoppingDomainService,
    private val retailShoppingDomainService: RetailShoppingDomainService,
    private val chatDao: ChatDao,
    private val pendingMutationStore: com.example.chatgpt_mealplanner.core.ai.pending.PendingMutationStore,
    private val activeProposalStore: com.example.chatgpt_mealplanner.core.ai.proposal.ActiveProposalStore,
    private val retailerPreferenceStore: RetailerPreferenceStore = RetailerPreferenceStore(null)
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun executeToolCall(
        conversationId: String,
        authContext: ActionAuthorizationContext,
        call: AiToolCall
    ): AiToolResult {
        val entryLog = """
            [DISPATCH_TRACE] entered=true tool=${call.toolName} toolCallId=${call.toolCallId} conversationId=$conversationId
        """.trimIndent()
        safeAuditLog(entryLog)

        val toolId = AiToolId.fromToolName(call.toolName)
            ?: run {
                val errLog = "[DISPATCH_TRACE] returnPath=VALIDATION_FAILURE authorized=false gateEvaluated=false cacheUsed=false domainInvoked=false error=\"TOOL_NOT_FOUND\""
        safeAuditLog(errLog)
                return createErrorResult(call, "TOOL_NOT_FOUND", "Unknown tool name: ${call.toolName}")
            }

        val logKey = "${conversationId}_${call.toolCallId}"

        // FIRST: Authorization check / Confirmation gate evaluation MUST run before cache lookup
        var gateResult: ConfirmationGateResult? = null
        var isAuthorized = false

        if (toolId.toolClass == AiToolClass.WRITE_EXPLICIT) {
            isAuthorized = isToolCallAuthorized(toolId, authContext, call.argsJson)
            if (!isAuthorized) {
                val err = "Write tool ${toolId.toolName} is not authorized for this user message scope."
                logExecution(logKey, conversationId, call, toolId, "FAILED", null, "UNAUTHORIZED", err)
                val blockedLog = "[DISPATCH_TRACE] returnPath=CONFIRMATION_BLOCKED authorized=false gateEvaluated=true cacheUsed=false domainInvoked=false"
        safeAuditLog(blockedLog)
                return createErrorResult(call, "UNAUTHORIZED", err)
            }
        } else if (toolId.toolClass == AiToolClass.WRITE_CONFIRMATION_REQUIRED) {
            gateResult = evaluateConfirmationGate(conversationId, authContext, toolId, call)
            isAuthorized = gateResult.authorized
            if (!isAuthorized) {
                val err = gateResult.reason ?: "Destructive action ${toolId.toolName} requires explicit user confirmation."
                logExecution(logKey, conversationId, call, toolId, "FAILED", null, "UNAUTHORIZED", err)
                val blockedLog = "[DISPATCH_TRACE] returnPath=CONFIRMATION_BLOCKED authorized=false gateEvaluated=true cacheUsed=false domainInvoked=false"
        safeAuditLog(blockedLog)
                return createErrorResult(call, "UNAUTHORIZED", err)
            }
        } else {
            isAuthorized = true
        }

        // SECOND: Check idempotency execution cache ONLY AFTER authorization is granted
        val existingLog = chatDao.getToolExecutionLog(conversationId, call.toolCallId)
        val cachedEntryFound = existingLog != null
        val cacheUsed = cachedEntryFound && existingLog?.executionStatus == "SUCCEEDED" && existingLog?.resultPayloadJson != null

        val cacheTrace = """
            [CACHE_TRACE] toolCallId=${call.toolCallId} toolName=${call.toolName} cacheLookup=TRUE cachedEntryFound=$cachedEntryFound cachedStatus=${existingLog?.executionStatus ?: "NONE"} cachedAuthorized=${existingLog?.errorCode != "UNAUTHORIZED"} gateEvaluatedAfterCache=FALSE cacheUsed=$cacheUsed
        """.trimIndent()
        safeAuditLog(cacheTrace)

        if (cacheUsed) {
            val hitLog = "[DISPATCH_TRACE] returnPath=CACHE_HIT authorized=true gateEvaluated=true cacheUsed=true domainInvoked=false"
        safeAuditLog(hitLog)

            return AiToolResult(
                toolCallId = call.toolCallId,
                toolName = call.toolName,
                success = true,
                payloadJson = existingLog!!.resultPayloadJson
            )
        }

        val payloadResult: DomainResult<AiToolPayload> = try {
            when (toolId) {
                AiToolId.GET_INVENTORY_SUMMARY -> handleGetInventorySummary()
                AiToolId.GET_INVENTORY_FOR_INGREDIENT -> handleGetInventoryForIngredient(call.argsJson)
                AiToolId.GET_USABLE_INVENTORY -> handleGetUsableInventory()
                AiToolId.GET_LOW_STOCK_ITEMS -> handleGetLowStockItems()
                AiToolId.GET_USE_SOON_ITEMS -> handleGetUseSoonItems()
                AiToolId.GET_RECENT_COOKED_MEALS -> handleGetRecentCookedMeals()
                AiToolId.GET_UPCOMING_PLANNED_MEALS -> handleGetUpcomingPlannedMeals()
                AiToolId.GET_MEAL_DETAILS -> handleGetMealDetails(call.argsJson)
                AiToolId.GET_AVAILABLE_LEFTOVERS -> handleGetAvailableLeftovers()
                AiToolId.GET_ACTIVE_SHOPPING_LIST -> handleGetActiveShoppingList()
                AiToolId.CALCULATE_SHOPPING_REQUIREMENTS -> handleCalculateShoppingRequirements()
                AiToolId.GET_RETAIL_BASKET_COMPARISON -> handleGetRetailBasketComparison()
                AiToolId.GET_RETAIL_ITEM_OPTIONS -> handleGetRetailItemOptions(call.argsJson)
                AiToolId.SEARCH_RETAIL_PRODUCTS -> handleSearchRetailProducts(call.argsJson)
                AiToolId.OFFER_ADD_PLANNED_MEAL -> handleOfferAddPlannedMeal(conversationId, call.argsJson)
                AiToolId.ADD_PLANNED_MEAL -> handleAddPlannedMeal(conversationId, call.argsJson)
                AiToolId.MOVE_PLANNED_MEAL -> handleMovePlannedMeal(call.argsJson)
                AiToolId.CANCEL_PLANNED_MEAL -> handleCancelPlannedMeal(call.argsJson)
                AiToolId.RESTORE_MEAL_TO_PLANNED -> handleRestoreMealToPlanned(call.argsJson)
                AiToolId.PREPARE_MARK_MEAL_COOKED -> handlePrepareMarkMealCooked(call.argsJson)
                AiToolId.MARK_INVENTORY_LOT_OUT -> handleMarkInventoryLotOut(call.argsJson)
                AiToolId.MARK_ALL_INVENTORY_FOR_CANONICAL_INGREDIENT_OUT -> handleMarkAllInventoryForCanonicalOut(call.argsJson)
                AiToolId.ADJUST_INVENTORY_QUANTITY -> handleAdjustInventoryQuantity(call.argsJson)
                AiToolId.RESOLVE_INVENTORY_LOT_UNCERTAINTY -> handleResolveInventoryLotUncertainty(call.argsJson)
                AiToolId.REGENERATE_SHOPPING_LIST -> handleRegenerateShoppingList()
                AiToolId.ADD_MANUAL_SHOPPING_ITEM -> handleAddManualShoppingItem(conversationId, call)
                AiToolId.SET_STAPLE_STATUS -> handleSetStapleStatus(call.argsJson)
                AiToolId.DELETE_INVENTORY_ITEM -> handleDeleteInventoryItem(call.argsJson)
                AiToolId.DELETE_ALL_INVENTORY -> handleDeleteAllInventory()
            }
        } catch (e: Exception) {
            DomainResult.Failure(DomainError.TransactionFailed(e.message ?: "Execution error"))
        }

        return when (payloadResult) {
            is DomainResult.Success -> {
                if (gateResult?.decision == "EXECUTE_CONFIRMED") {
                    pendingMutationStore.clearPendingMutation(conversationId, reason = "EXECUTE_CONFIRMED_SUCCESS")
                }
                val payloadJsonStr = json.encodeToString(payloadResult.value)
                logExecution(logKey, conversationId, call, toolId, "SUCCEEDED", payloadJsonStr, null, null)
                AiToolResult(
                    toolCallId = call.toolCallId,
                    toolName = call.toolName,
                    success = true,
                    payloadJson = payloadJsonStr
                )
            }
            is DomainResult.Failure -> {
                val errCode = getErrorCode(payloadResult.error)
                val errMsg = getErrorMessage(payloadResult.error)
                logExecution(logKey, conversationId, call, toolId, "FAILED", null, errCode, errMsg)
                createErrorResult(call, errCode, errMsg)
            }
        }
    }

    private fun isToolCallAuthorized(
        toolId: AiToolId,
        authContext: ActionAuthorizationContext,
        argsJson: String
    ): Boolean {
        if (authContext.scopes.isEmpty()) return false

        return authContext.scopes.any { scope ->
            when (scope) {
                is AuthorizedMutationScope.InventoryOutScope -> {
                    if (toolId == AiToolId.MARK_ALL_INVENTORY_FOR_CANONICAL_INGREDIENT_OUT) {
                        val canonicalId = extractStringArg(argsJson, "canonicalIngredientId")
                        canonicalId != null && (scope.permittedCanonicalIngredientIds.isEmpty() ||
                                scope.permittedCanonicalIngredientIds.any {
                                    it.uppercase() == canonicalId.uppercase() ||
                                            it.uppercase().startsWith(canonicalId.uppercase()) ||
                                            canonicalId.uppercase().startsWith(it.uppercase())
                                })
                    } else if (toolId == AiToolId.MARK_INVENTORY_LOT_OUT) {
                        val itemId = extractStringArg(argsJson, "itemId")
                        itemId != null && (scope.permittedInventoryItemIds.isEmpty() || scope.permittedInventoryItemIds.contains(itemId))
                    } else false
                }
                is AuthorizedMutationScope.MoveMealScope -> {
                    if (toolId == AiToolId.MOVE_PLANNED_MEAL) {
                        val mealId = extractStringArg(argsJson, "mealId")
                        val destDate = extractStringArg(argsJson, "newDate")
                        mealId == scope.permittedMealId && destDate == scope.permittedDestinationDate
                    } else false
                }
                is AuthorizedMutationScope.CancelMealScope -> {
                    if (toolId == AiToolId.CANCEL_PLANNED_MEAL) {
                        val mealId = extractStringArg(argsJson, "mealId")
                        mealId == scope.permittedMealId
                    } else false
                }
                is AuthorizedMutationScope.RestoreMealScope -> {
                    if (toolId == AiToolId.RESTORE_MEAL_TO_PLANNED) {
                        val mealId = extractStringArg(argsJson, "mealId")
                        mealId == scope.permittedMealId
                    } else false
                }
                is AuthorizedMutationScope.AdjustInventoryScope -> {
                    if (toolId == AiToolId.ADJUST_INVENTORY_QUANTITY) {
                        val itemId = extractStringArg(argsJson, "itemId")
                        itemId == scope.permittedInventoryItemId
                    } else false
                }
                is AuthorizedMutationScope.ResolveUncertaintyScope -> {
                    if (toolId == AiToolId.RESOLVE_INVENTORY_LOT_UNCERTAINTY) {
                        val itemId = extractStringArg(argsJson, "itemId")
                        itemId == scope.permittedInventoryItemId
                    } else false
                }
                is AuthorizedMutationScope.AddPlannedMealScope -> {
                    toolId == AiToolId.ADD_PLANNED_MEAL || toolId == AiToolId.REGENERATE_SHOPPING_LIST || toolId == AiToolId.ADD_MANUAL_SHOPPING_ITEM
                }
                is AuthorizedMutationScope.RegenerateShoppingScope -> {
                    toolId == AiToolId.REGENERATE_SHOPPING_LIST
                }
                is AuthorizedMutationScope.AddManualShoppingScope -> {
                    toolId == AiToolId.ADD_MANUAL_SHOPPING_ITEM
                }
                is AuthorizedMutationScope.SetStapleScope -> {
                    toolId == AiToolId.SET_STAPLE_STATUS
                }
                is AuthorizedMutationScope.DeleteInventoryScope -> {
                    toolId == AiToolId.DELETE_INVENTORY_ITEM
                }
                is AuthorizedMutationScope.DeleteAllInventoryScope -> {
                    toolId == AiToolId.DELETE_ALL_INVENTORY
                }
                AuthorizedMutationScope.None -> false
            }
        }
    }

    private suspend fun handleGetInventorySummary(): DomainResult<AiToolPayload> {
        val items = inventoryDao.getAllItems().first().map { it.toDomainModel() }
        val usable = items.filter { it.status in listOf(ItemStatus.AVAILABLE, ItemStatus.LOW, ItemStatus.USE_SOON) }

        val dtos = usable.map { item ->
            InventoryItemAiDto(
                id = item.id,
                canonicalIngredientId = item.canonicalIngredientId,
                displayName = item.displayName,
                brand = item.brand,
                quantity = item.quantity,
                unit = item.quantityUnit?.name,
                confidence = item.quantityConfidence.name,
                status = item.status.name,
                storageLocation = item.storageLocation.name
            )
        }

        return DomainResult.Success(AiToolPayload.InventorySummaryPayload(dtos))
    }

    private suspend fun handleGetInventoryForIngredient(argsJson: String): DomainResult<AiToolPayload> {
        val ingredientId = extractStringArg(argsJson, "canonicalIngredientId")
        val searchQuery = extractStringArg(argsJson, "searchQuery")

        val items = inventoryDao.getAllItems().first().map { it.toDomainModel() }
        val usable = items.filter { it.status in listOf(ItemStatus.AVAILABLE, ItemStatus.LOW, ItemStatus.USE_SOON) }

        val matched = usable.filter { item ->
            val matchId = ingredientId != null && item.canonicalIngredientId?.uppercase() == ingredientId.uppercase()
            val matchQuery = searchQuery != null && (
                    item.displayName.contains(searchQuery, ignoreCase = true) ||
                            item.canonicalName.contains(searchQuery, ignoreCase = true) ||
                            item.canonicalIngredientId?.contains(searchQuery, ignoreCase = true) == true
                    )
            matchId || matchQuery
        }

        val dtos = matched.map { item ->
            InventoryItemAiDto(
                id = item.id,
                canonicalIngredientId = item.canonicalIngredientId,
                displayName = item.displayName,
                brand = item.brand,
                quantity = item.quantity,
                unit = item.quantityUnit?.name,
                confidence = item.quantityConfidence.name,
                status = item.status.name,
                storageLocation = item.storageLocation.name
            )
        }

        return DomainResult.Success(AiToolPayload.InventorySummaryPayload(dtos))
    }

    private suspend fun handleGetUsableInventory(): DomainResult<AiToolPayload> {
        return handleGetInventorySummary()
    }

    private suspend fun handleGetLowStockItems(): DomainResult<AiToolPayload> {
        val items = inventoryDao.getAllItems().first().map { it.toDomainModel() }
        val lowStock = items.filter { it.status == ItemStatus.LOW }

        val dtos = lowStock.map { item ->
            InventoryItemAiDto(
                id = item.id,
                canonicalIngredientId = item.canonicalIngredientId,
                displayName = item.displayName,
                brand = item.brand,
                quantity = item.quantity,
                unit = item.quantityUnit?.name,
                confidence = item.quantityConfidence.name,
                status = item.status.name,
                storageLocation = item.storageLocation.name
            )
        }

        return DomainResult.Success(AiToolPayload.InventorySummaryPayload(dtos))
    }

    private suspend fun handleGetUseSoonItems(): DomainResult<AiToolPayload> {
        val items = inventoryDao.getAllItems().first().map { it.toDomainModel() }
        val useSoon = items.filter { it.status == ItemStatus.USE_SOON }

        val dtos = useSoon.map { item ->
            InventoryItemAiDto(
                id = item.id,
                canonicalIngredientId = item.canonicalIngredientId,
                displayName = item.displayName,
                brand = item.brand,
                quantity = item.quantity,
                unit = item.quantityUnit?.name,
                confidence = item.quantityConfidence.name,
                status = item.status.name,
                storageLocation = item.storageLocation.name
 …6446 tokens truncated…ShoppingListStatus.ACTIVE,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            shoppingDao.insertShoppingList(newList)
            newList
        }

        val stableItemId = "manual_${conversationId}_${call.toolCallId}"

        val item = ShoppingListItemEntity(
            id = stableItemId,
            shoppingListId = activeList.id,
            canonicalIngredientId = extractStringArg(argsJson, "canonicalIngredientId"),
            displayName = displayName,
            requiredQuantity = extractDoubleArg(argsJson, "quantity"),
            requiredUnit = extractStringArg(argsJson, "unit")?.let { QuantityUnit.valueOf(it) },
            suggestedPurchaseQuantity = extractDoubleArg(argsJson, "quantity"),
            suggestedPurchaseUnit = extractStringArg(argsJson, "unit")?.let { QuantityUnit.valueOf(it) },
            packCount = 1,
            reason = "Manual user addition",
            priority = ShoppingPriority.ESSENTIAL,
            status = ShoppingItemStatus.NEEDED,
            optional = false,
            sourceType = ShoppingSourceType.MANUAL,
            hasMealRequirement = false,
            hasStapleRestock = false,
            mealShortfallQuantity = null,
            stapleReserveTopUpQuantity = null,
            expectedFinalInventory = null,
            expectedFinalInventoryUnit = null,
            quantityUncertain = false,
            uncertaintyReason = null,
            requirementConfidence = RequirementConfidence.EXACT,
            wasteRisk = WasteRisk.LOW,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        shoppingDao.insertShoppingListItem(item)
        return DomainResult.Success(AiToolPayload.TextPayload("Added manual item '$displayName' to shopping list"))
    }

    private suspend fun handleSetStapleStatus(argsJson: String): DomainResult<AiToolPayload> {
        val itemIdArg = extractStringArg(argsJson, "itemId")
        val searchArg = extractStringArg(argsJson, "searchQuery") ?: extractStringArg(argsJson, "displayName")
        val isStaple = extractBooleanArg(argsJson, "isStaple") ?: true

        val allItems = inventoryDao.getAllItems().first().map { it.toDomainModel() }

        val targetItem = if (itemIdArg != null) {
            allItems.firstOrNull { it.id == itemIdArg }
        } else if (searchArg != null) {
            val matching = allItems.filter {
                it.displayName.contains(searchArg, ignoreCase = true) ||
                        it.canonicalName.contains(searchArg, ignoreCase = true) ||
                        (it.brand?.contains(searchArg, ignoreCase = true) == true)
            }
            if (matching.size == 1) {
                matching.first()
            } else if (matching.size > 1) {
                return DomainResult.Failure(DomainError.DuplicateOperation("Multiple inventory items match '$searchArg'. Please specify which item to mark as a staple."))
            } else {
                null
            }
        } else {
            null
        }

        if (targetItem == null) {
            return DomainResult.Failure(DomainError.ItemNotFound("No inventory item matching '${searchArg ?: itemIdArg}' was found."))
        }

        val result = inventoryDomainService.setStapleStatus(targetItem.id, isStaple)
        return when (result) {
            is DomainResult.Success -> {
                val actionText = if (isStaple) "Marked '${targetItem.displayName}' as a staple" else "Removed '${targetItem.displayName}' from staples"
                DomainResult.Success(AiToolPayload.TextPayload(actionText))
            }
            is DomainResult.Failure -> DomainResult.Failure(result.error)
        }
    }

    private suspend fun handleDeleteInventoryItem(argsJson: String): DomainResult<AiToolPayload> {
        val itemIdArg = extractStringArg(argsJson, "itemId")
        val searchArg = extractStringArg(argsJson, "searchQuery") ?: extractStringArg(argsJson, "displayName")

        val allItems = inventoryDao.getAllItemsSync().map { it.toDomainModel() }

        val targetItem = if (itemIdArg != null) {
            allItems.firstOrNull { it.id == itemIdArg }
        } else if (searchArg != null) {
            val matching = allItems.filter { it.status != ItemStatus.DISCARDED && (
                it.displayName.contains(searchArg, ignoreCase = true) ||
                        it.canonicalName.contains(searchArg, ignoreCase = true) ||
                        (it.brand?.contains(searchArg, ignoreCase = true) == true)
            ) }
            if (matching.size == 1) {
                matching.first()
            } else if (matching.size > 1) {
                return DomainResult.Failure(DomainError.DuplicateOperation("Multiple inventory items match '$searchArg'. Please specify which item to delete."))
            } else {
                null
            }
        } else {
            null
        }

        if (targetItem == null) {
            val domainTrace = """
                [DOMAIN_TRACE] tool=delete_inventory_item targetItemId=${itemIdArg ?: "NULL"} targetItemName=${searchArg ?: "NULL"} statusBefore=UNKNOWN discardItemInvoked=false domainResult=ITEM_NOT_FOUND statusAfter=UNKNOWN mutationVerified=false
            """.trimIndent()
        safeAuditLog(domainTrace)
            return DomainResult.Failure(DomainError.ItemNotFound("No active inventory item matching '${searchArg ?: itemIdArg}' was found."))
        }

        val statusBefore = targetItem.status.name
        val result = inventoryDomainService.discardItem(targetItem.id, reason = "AI user requested deletion")
        val statusAfter = inventoryDao.getItemById(targetItem.id)?.status?.name ?: "UNKNOWN"
        val mutationVerified = result is DomainResult.Success && statusAfter == "DISCARDED"

        val domainTrace = """
            [DOMAIN_TRACE] tool=delete_inventory_item targetItemId=${targetItem.id} targetItemName="${targetItem.displayName}" statusBefore=$statusBefore discardItemInvoked=true domainResult=${if (result is DomainResult.Success) "SUCCESS" else "FAILURE"} statusAfter=$statusAfter mutationVerified=$mutationVerified
        """.trimIndent()
        safeAuditLog(domainTrace)

        return when (result) {
            is DomainResult.Success -> DomainResult.Success(AiToolPayload.TextPayload("Successfully removed '${targetItem.displayName}' from active inventory."))
            is DomainResult.Failure -> result
        }
    }

    private suspend fun handleDeleteAllInventory(): DomainResult<AiToolPayload> {
        val activeItems = inventoryDao.getAllItemsSync().filter { it.status != ItemStatus.DISCARDED }.map { it.toDomainModel() }
        if (activeItems.isEmpty()) {
            return DomainResult.Success(AiToolPayload.TextPayload("Your inventory is already empty."))
        }

        var deletedCount = 0
        activeItems.forEach { item ->
            val res = inventoryDomainService.discardItem(item.id, reason = "AI user cleared all inventory")
            if (res is DomainResult.Success) {
                deletedCount++
            }
        }

        return DomainResult.Success(AiToolPayload.TextPayload("Successfully removed all $deletedCount active item(s) from inventory."))
    }

    private fun extractStringArg(jsonStr: String, key: String): String? {
        return try {
            val element = json.parseToJsonElement(jsonStr)
            val obj = element as? kotlinx.serialization.json.JsonObject ?: return null
            val primitive = obj[key] as? kotlinx.serialization.json.JsonPrimitive ?: return null
            primitive.content.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractDoubleArg(jsonStr: String, key: String): Double? {
        return try {
            val element = json.parseToJsonElement(jsonStr)
            val obj = element as? kotlinx.serialization.json.JsonObject ?: return null
            val primitive = obj[key] as? kotlinx.serialization.json.JsonPrimitive ?: return null
            primitive.content.toDoubleOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun logDiagnostic(tag: String, message: String) {
        safeAuditLog("[$tag]")
    }

    private fun safeAuditLog(message: String) {
        val event = message.substringBefore(']').takeIf { it.startsWith("[") }?.plus("]") ?: "[AI_TOOL_EVENT]"
        try {
            android.util.Log.i("AI_MEALPLANNER", event)
        } catch (_: Throwable) {
            // Unstubbed in JVM unit tests
        }
    }

    private fun normalizeDateString(rawDate: String?): String {
        if (rawDate.isNullOrBlank()) return java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        val trimmed = rawDate.trim()
        return try {
            when {
                trimmed.equals("today", ignoreCase = true) -> java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                trimmed.equals("tomorrow", ignoreCase = true) -> java.time.LocalDate.now().plusDays(1).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                trimmed.equals("yesterday", ignoreCase = true) -> java.time.LocalDate.now().minusDays(1).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                trimmed.contains("/") -> {
                    val parts = trimmed.split("/")
                    if (parts.size == 3) {
                        val p1 = parts[0].toInt()
                        val p2 = parts[1].toInt()
                        val p3 = parts[2].toInt()
                        if (p1 > 1000) {
                            java.time.LocalDate.of(p1, p2, p3).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                        } else {
                            java.time.LocalDate.of(p3, p2, p1).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                        }
                    } else trimmed
                }
                else -> {
                    runCatching { java.time.LocalDate.parse(trimmed) }.getOrNull()?.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                        ?: trimmed
                }
            }
        } catch (_: Exception) {
            java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        }
    }

    private fun extractBooleanArg(jsonStr: String, key: String): Boolean? {
        return try {
            val element = json.parseToJsonElement(jsonStr)
            val obj = element as? kotlinx.serialization.json.JsonObject ?: return null
            val primitive = obj[key] as? kotlinx.serialization.json.JsonPrimitive ?: return null
            primitive.content.toBooleanStrictOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun createErrorResult(call: AiToolCall, code: String, message: String): AiToolResult {
        return AiToolResult(
            toolCallId = call.toolCallId,
            toolName = call.toolName,
            success = false,
            payloadJson = "{}",
            errorCode = code,
            errorMessage = message
        )
    }

    private suspend fun logExecution(
        logKey: String,
        conversationId: String,
        call: AiToolCall,
        toolId: AiToolId,
        status: String,
        payloadJson: String?,
        errorCode: String?,
        errorMessage: String?
    ) {
        val log = AiToolExecutionLogEntity(
            id = logKey,
            conversationId = conversationId,
            toolName = call.toolName,
            toolCallId = call.toolCallId,
            executionClass = toolId.toolClass.name,
            executionStatus = status,
            success = status == "SUCCEEDED",
            resultPayloadJson = payloadJson,
            errorCode = errorCode,
            errorMessage = errorMessage,
            startedAt = System.currentTimeMillis(),
            completedAt = if (status != "STARTED") System.currentTimeMillis() else null
        )
        try {
            chatDao.insertOrUpdateToolExecutionLog(log)
        } catch (_: Exception) {
            // Logging failures do not abort execution
        }
    }

    private fun getErrorCode(error: DomainError): String {
        return when (error) {
            is DomainError.ItemNotFound -> "ITEM_NOT_FOUND"
            is DomainError.InsufficientQuantity -> "INSUFFICIENT_QUANTITY"
            is DomainError.DuplicateOperation -> "DUPLICATE_OPERATION"
            is DomainError.InvalidQuantity -> "INVALID_QUANTITY"
            is DomainError.ItemNotUsable -> "ITEM_NOT_USABLE"
            is DomainError.TransactionFailed -> error.cause
            else -> "EXECUTION_FAILED"
        }
    }

    private fun getErrorMessage(error: DomainError): String {
        return when (error) {
            is DomainError.ItemNotFound -> "Item not found"
            is DomainError.InsufficientQuantity -> "Insufficient quantity available"
            is DomainError.DuplicateOperation -> error.message
            is DomainError.InvalidQuantity -> "Invalid quantity specified"
            is DomainError.ItemNotUsable -> "Item is not usable"
            is DomainError.TransactionFailed -> error.cause
            else -> "Execution failed"
        }
    }

    private data class ConfirmationGateResult(
        val authorized: Boolean,
        val decision: String,
        val reason: String? = null
    )

    private suspend fun evaluateConfirmationGate(
        conversationId: String,
        authContext: ActionAuthorizationContext,
        toolId: AiToolId,
        call: AiToolCall
    ): ConfirmationGateResult {
        val pending = pendingMutationStore.getPendingMutation(conversationId)
        val messageText = authContext.userMessageText.ifBlank { authContext.sourceUserMessageId }
        val userIntent = UserMessageIntentClassifier.classify(
            userMessageText = messageText,
            pendingMutation = pending
        )

        val pendingPresent = pending != null
        val pendingState = when (pending) {
            is com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.AddPlannedMeal -> pending.state.name
            is com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.DeleteInventoryItem -> pending.state.name
            is com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.DeleteAllInventory -> pending.state.name
            is com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.PrepareMarkMealCooked -> pending.state.name
            null -> "NONE"
        }

        val matchesPendingAction = pending != null && when (pending) {
            is com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.DeleteInventoryItem -> toolId == AiToolId.DELETE_INVENTORY_ITEM
            is com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.DeleteAllInventory -> toolId == AiToolId.DELETE_ALL_INVENTORY
            is com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.PrepareMarkMealCooked -> toolId == AiToolId.PREPARE_MARK_MEAL_COOKED
            is com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.AddPlannedMeal -> toolId == AiToolId.ADD_PLANNED_MEAL
        }

        val explicitConfirmation = userIntent == com.example.chatgpt_mealplanner.core.ai.context.UserMessageIntentClass.CONFIRM_PENDING_MUTATION
        val isUserCancelled = userIntent == com.example.chatgpt_mealplanner.core.ai.context.UserMessageIntentClass.CANCEL_PENDING

        val authorized = matchesPendingAction && explicitConfirmation
        val decision = if (authorized) "EXECUTE_CONFIRMED" else if (isUserCancelled) "CANCEL" else "BLOCK_AND_STAGE"
        val authorizationSource = if (authorized) "EXPLICIT_PENDING_CONFIRMATION" else "NONE"

        val authTrace = """
            [AUTH_TRACE] tool=${toolId.toolName} executionClass=${toolId.toolClass} userMessageText="$messageText" pendingMutation=${pending?.javaClass?.simpleName ?: "NONE"} pendingState=$pendingState authorizationContext=$authContext explicitConfirmation=$explicitConfirmation authorizationSource=$authorizationSource finalAuthorized=$authorized
        """.trimIndent()
        safeAuditLog(authTrace)

        val gateLog = "[AI_CONFIRMATION_GATE] tool=${toolId.toolName} toolCallId=${call.toolCallId} executionClass=${toolId.toolClass} pendingPresent=$pendingPresent pendingState=$pendingState matchesPendingAction=$matchesPendingAction userConfirmationIntent=$userIntent authorized=$authorized decision=$decision"
        safeAuditLog(gateLog)

        if (authorized) {
            return ConfirmationGateResult(authorized = true, decision = "EXECUTE_CONFIRMED")
        }

        if (isUserCancelled) {
            pendingMutationStore.clearPendingMutation(conversationId, reason = "USER_CANCELLED")
            return ConfirmationGateResult(authorized = false, decision = "CANCEL", reason = "Destructive action cancelled by user.")
        }

        // Otherwise: Block execution and stage/update the pending mutation
        when (toolId) {
            AiToolId.DELETE_INVENTORY_ITEM -> {
                val itemIdArg = extractStringArg(call.argsJson, "itemId")
                val searchArg = extractStringArg(call.argsJson, "searchQuery") ?: extractStringArg(call.argsJson, "displayName") ?: "specified item"
                val allItems = inventoryDao.getAllItemsSync().filter { it.status != ItemStatus.DISCARDED }.map { it.toDomainModel() }
                val targetItem = if (itemIdArg != null) allItems.firstOrNull { it.id == itemIdArg } else allItems.firstOrNull { it.displayName.contains(searchArg, ignoreCase = true) }
                val itemId = targetItem?.id ?: itemIdArg ?: searchArg
                val targetName = targetItem?.displayName ?: searchArg

                val staged = com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.DeleteInventoryItem(
                    itemId = itemId,
                    targetName = targetName,
                    state = com.example.chatgpt_mealplanner.core.ai.pending.PendingMutationState.AWAITING_CONFIRMATION
                )
                pendingMutationStore.setPendingMutation(conversationId, staged, reason = "BLOCK_AND_STAGE_DELETE")
            }
            AiToolId.DELETE_ALL_INVENTORY -> {
                val activeItems = inventoryDao.getAllItemsSync().filter { it.status != ItemStatus.DISCARDED }
                val staged = com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.DeleteAllInventory(
                    totalItemCount = activeItems.size,
                    state = com.example.chatgpt_mealplanner.core.ai.pending.PendingMutationState.AWAITING_CONFIRMATION
                )
                pendingMutationStore.setPendingMutation(conversationId, staged, reason = "BLOCK_AND_STAGE_DELETE_ALL")
            }
            AiToolId.PREPARE_MARK_MEAL_COOKED -> {
                val mealId = extractStringArg(call.argsJson, "mealId") ?: ""
                val mealTitle = extractStringArg(call.argsJson, "mealTitle") ?: "Planned Meal"
                val staged = com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.PrepareMarkMealCooked(
                    mealId = mealId,
                    mealTitle = mealTitle,
                    state = com.example.chatgpt_mealplanner.core.ai.pending.PendingMutationState.AWAITING_CONFIRMATION
                )
                pendingMutationStore.setPendingMutation(conversationId, staged, reason = "BLOCK_AND_STAGE_COOK")
            }
            else -> {}
        }

        return ConfirmationGateResult(
            authorized = false,
            decision = "BLOCK_AND_STAGE",
            reason = "Destructive action ${toolId.toolName} requires explicit user confirmation. Staged pending confirmation."
        )
    }
}
