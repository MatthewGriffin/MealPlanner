package com.example.chatgpt_mealplanner.core.backup

import com.example.chatgpt_mealplanner.core.database.AppDatabase
import com.example.chatgpt_mealplanner.core.database.DatabaseTransactionRunner
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class MainBackupSnapshotBuilder @Inject constructor(
    private val database: AppDatabase?,
    private val transactionRunner: DatabaseTransactionRunner
) {
    private val jsonFormatter = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    open suspend fun createMainBackupSnapshot(
        currentTimeMs: Long = System.currentTimeMillis()
    ): MainHouseholdBackupDto {
        val db = database ?: return MainHouseholdBackupDto(exportedAt = currentTimeMs)

        return transactionRunner.runInTransaction {
            val identity = db.householdIdentityDao().getHouseholdIdentity()
            val seqEntity = db.canonicalSyncDao().getSequenceEntity()
            val rawItems = db.inventoryDao().getAllItemsSync()
            val rawTx = db.transactionDao().getAllTransactionsSync()
            val rawMeals = db.mealDao().getAllMealsWithIngredientsSync()
            val rawLeftovers = db.leftoverDao().getAllLeftoversSync()
            val rawLists = db.shoppingDao().getAllListsWithItemsAndSourcesSync()
            val rawRules = db.stapleRuleDao().getAllRulesSync()
            val rawBarcodes = db.barcodeMappingDao().getAllMappingsSync()
            val rawConversations = db.chatDao().getAllConversationsWithMessagesSync()
            val rawOps = db.canonicalSyncDao().getAllOperationsSync()

            val inventoryExport = rawItems.map {
                InventoryItemExportDto(
                    id = it.id,
                    barcode = it.barcode,
                    canonicalIngredientId = it.canonicalIngredientId,
                    canonicalName = it.canonicalName,
                    displayName = it.displayName,
                    brand = it.brand,
                    category = it.category.name,
                    storageLocation = it.storageLocation.name,
                    quantity = it.quantity ?: 0.0,
                    quantityUnit = it.quantityUnit?.name,
                    packCount = it.packCount,
                    packSize = it.packSize,
                    packSizeUnit = it.packSizeUnit?.name,
                    quantityConfidence = it.quantityConfidence.name,
                    status = it.status.name,
                    opened = it.opened ?: false,
                    frozen = it.frozen,
                    staple = it.staple,
                    purchaseDate = it.purchaseDate,
                    openedDate = it.openedDate,
                    useByDate = it.useByDate,
                    bestBeforeDate = it.bestBeforeDate,
                    notes = it.notes,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                    revision = it.revision
                )
            }

            val txExport = rawTx.map {
                TransactionExportDto(
                    id = it.id,
                    inventoryItemId = it.inventoryItemId,
                    transactionType = it.type.name,
                    deltaQuantity = it.quantityChange ?: 0.0,
                    unit = it.unit?.name,
                    timestamp = it.timestamp,
                    source = it.mealId ?: it.shoppingTripId ?: "MANUAL",
                    referenceId = it.mealId ?: it.shoppingTripId,
                    note = it.note
                )
            }

            val mealExport = rawMeals.map { m ->
                MealExportDto(
                    id = m.meal.id,
                    date = m.meal.date,
                    mealType = m.meal.mealType.name,
                    title = m.meal.title,
                    servings = m.meal.servings,
                    status = m.meal.status.name,
                    batchPortions = m.meal.batchPortions,
                    immediatePortions = m.meal.immediatePortions,
                    leftoverPortions = m.meal.leftoverPortions,
                    cookInTwoBatches = m.meal.cookInTwoBatches,
                    tags = m.meal.tags.map { it.name }.toSet(),
                    instructions = m.meal.instructions,
                    notes = m.meal.notes,
                    cookedAt = m.meal.cookedAt,
                    createdAt = m.meal.createdAt,
                    updatedAt = m.meal.updatedAt,
                    ingredients = m.ingredients.map { ing ->
                        MealIngredientExportDto(
                            id = ing.id,
                            mealId = ing.mealId,
                            canonicalIngredientId = ing.canonicalIngredientId,
                            displayName = ing.displayName,
                            plannedQuantity = ing.plannedQuantity,
                            plannedUnit = ing.plannedUnit.name,
                            actualQuantity = ing.actualQuantity,
                            actualUnit = ing.actualUnit?.name,
                            optional = ing.optional,
                            substitutionAllowed = ing.substitutionAllowed,
                            substitutedCanonicalIngredientId = ing.substitutedCanonicalIngredientId,
                            substitutedDisplayName = ing.substitutedDisplayName
                        )
                    }
                )
            }

            val leftoverExport = rawLeftovers.map {
                LeftoverExportDto(
                    id = it.id,
                    sourceMealId = it.sourceMealId,
                    name = it.name,
                    initialPortions = it.initialPortions,
                    portions = it.portions,
                    storageLocation = it.storageLocation.name,
                    status = it.status.name,
                    createdDate = it.createdDate,
                    consumeByDate = it.consumeByDate,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt
                )
            }

            val listExport = rawLists.map { l ->
                ShoppingListExportDto(
                    id = l.shoppingList.id,
                    name = l.shoppingList.name,
                    startDate = l.shoppingList.startDate,
                    endDate = l.shoppingList.endDate,
                    status = l.shoppingList.status.name,
                    createdAt = l.shoppingList.createdAt,
                    updatedAt = l.shoppingList.updatedAt,
                    items = l.items.map { itemWithSources ->
                        val item = itemWithSources.item
                        ShoppingListItemExportDto(
                            id = item.id,
                            shoppingListId = item.shoppingListId,
                            canonicalIngredientId = item.canonicalIngredientId,
                            displayName = item.displayName,
                            requiredQuantity = item.requiredQuantity,
                            requiredUnit = item.requiredUnit?.name,
                            suggestedPurchaseQuantity = item.suggestedPurchaseQuantity,
                            suggestedPurchaseUnit = item.suggestedPurchaseUnit?.name,
                            packCount = item.packCount,
                            reason = item.reason,
                            priority = item.priority.name,
                            status = item.status.name,
                            optional = item.optional,
                            sourceType = item.sourceType.name,
                            hasMealRequirement = item.hasMealRequirement,
                            hasStapleRestock = item.hasStapleRestock,
                            mealShortfallQuantity = item.mealShortfallQuantity,
                            stapleReserveTopUpQuantity = item.stapleReserveTopUpQuantity,
                            expectedFinalInventory = item.expectedFinalInventory,
                            expectedFinalInventoryUnit = item.expectedFinalInventoryUnit?.name,
                            quantityUncertain = item.quantityUncertain,
                            uncertaintyReason = item.uncertaintyReason,
                            requirementConfidence = item.requirementConfidence.name,
                            wasteRisk = item.wasteRisk.name,
                            createdAt = item.createdAt,
                            updatedAt = item.updatedAt,
                            sources = itemWithSources.sources.map { s ->
                                ShoppingRequirementSourceExportDto(
                                    id = s.id,
                                    shoppingListItemId = s.shoppingListItemId,
                                    mealId = s.mealId,
                                    mealIngredientId = s.mealIngredientId,
                                    mealTitle = s.mealTitle,
                                    mealDate = s.mealDate,
                                    requiredQuantity = s.requiredQuantity,
                                    requiredUnit = s.requiredUnit.name
                                )
                            }
                        )
                    }
                )
            }

            val ruleExport = rawRules.map {
                StapleRuleExportDto(
                    id = it.id,
                    canonicalIngredientId = it.canonicalIngredientId,
                    displayName = it.displayName,
                    enabled = it.enabled,
                    mode = it.mode.name,
                    thresholdQuantity = it.thresholdQuantity,
                    thresholdUnit = it.thresholdUnit?.name,
                    targetQuantity = it.targetQuantity,
                    targetUnit = it.targetUnit?.name,
                    thresholdPackCount = it.thresholdPackCount,
                    targetPackCount = it.targetPackCount,
                    preferredStorageLocation = it.preferredStorageLocation?.name,
                    notes = it.notes,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt
                )
            }

            val barcodeExport = rawBarcodes.map {
                BarcodeMappingExportDto(
                    barcode = it.barcode,
                    canonicalIngredientId = it.canonicalIngredientId,
                    productName = it.displayName ?: it.canonicalName ?: "",
                    brand = it.brand,
                    defaultCategory = it.category?.name ?: "CUPBOARD",
                    defaultStorageLocation = it.defaultStorageLocation?.name ?: "CUPBOARD",
                    defaultQuantity = it.netQuantity,
                    defaultUnit = it.netQuantityUnit?.name,
                    packCount = it.packCount,
                    packSize = it.packSize,
                    packSizeUnit = it.packSizeUnit?.name,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt
                )
            }

            val conversationExport = rawConversations.map { convWithMessages ->
                ChatConversationExportDto(
                    id = convWithMessages.conversation.id,
                    title = convWithMessages.conversation.title,
                    createdAt = convWithMessages.conversation.createdAt,
                    updatedAt = convWithMessages.conversation.updatedAt,
                    messages = convWithMessages.messages.map { m ->
                        ChatMessageExportDto(
                            id = m.id,
                            conversationId = m.conversationId,
                            role = m.role,
                            content = m.content,
                            timestamp = m.timestamp
                        )
                    }
                )
            }

            val opExport = rawOps.map {
                CanonicalSyncOperationExportDto(
                    operationId = it.operationId,
                    deviceId = it.deviceId,
                    deviceSequence = it.deviceSequence,
                    protocolVersion = it.protocolVersion,
                    operationType = it.operationType,
                    targetItemId = it.targetItemId,
                    baseRevision = it.baseRevision,
                    payloadJson = it.payloadJson,
                    createdAt = it.createdAt,
                    canonicalSequence = it.canonicalSequence,
                    receivedAt = it.receivedAt,
                    status = it.status,
                    resultCode = it.resultCode,
                    resultPayloadJson = it.resultPayloadJson
                )
            }

            MainHouseholdBackupDto(
                exportedAt = currentTimeMs,
                householdId = identity?.householdId,
                householdName = identity?.householdName,
                mainDeviceId = identity?.mainDeviceId,
                nextCanonicalSequence = seqEntity?.nextSequence,
                inventory = inventoryExport,
                transactions = txExport,
                meals = mealExport,
                leftovers = leftoverExport,
                shoppingLists = listExport,
                stapleRules = ruleExport,
                productBarcodeMappings = barcodeExport,
                chatConversations = conversationExport,
                canonicalOperations = opExport
            )
        }
    }

    open suspend fun createMainBackupSnapshotJson(
        currentTimeMs: Long = System.currentTimeMillis()
    ): String {
        val dto = createMainBackupSnapshot(currentTimeMs)
        return jsonFormatter.encodeToString(dto)
    }
}
