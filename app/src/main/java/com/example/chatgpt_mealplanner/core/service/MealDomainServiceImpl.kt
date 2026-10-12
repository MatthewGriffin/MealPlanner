package com.example.chatgpt_mealplanner.core.service

import com.example.chatgpt_mealplanner.core.database.DatabaseTransactionRunner
import com.example.chatgpt_mealplanner.core.database.dao.InventoryDao
import com.example.chatgpt_mealplanner.core.database.dao.LeftoverDao
import com.example.chatgpt_mealplanner.core.database.dao.MealDao
import com.example.chatgpt_mealplanner.core.database.dao.TransactionDao
import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemEntity
import com.example.chatgpt_mealplanner.core.database.entity.InventoryTransactionEntity
import com.example.chatgpt_mealplanner.core.database.entity.LeftoverEntity
import com.example.chatgpt_mealplanner.core.database.entity.toDomainModel
import com.example.chatgpt_mealplanner.core.database.entity.toEntity
import com.example.chatgpt_mealplanner.core.database.dao.CanonicalSyncDao
import com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncOperationEntity
import com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncSequenceEntity
import com.example.chatgpt_mealplanner.core.model.ActualIngredientOverride
import com.example.chatgpt_mealplanner.core.sync.AppRole
import com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
import com.example.chatgpt_mealplanner.core.sync.HouseholdIdentityManager
import com.example.chatgpt_mealplanner.sync.contract.SetQuantityPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.SYNC_PROTOCOL_VERSION
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationStatus
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.IngredientSubstitution
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.LeftoverStatus
import com.example.chatgpt_mealplanner.core.model.Meal
import com.example.chatgpt_mealplanner.core.model.MealIngredient
import com.example.chatgpt_mealplanner.core.model.MealStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.model.TransactionType
import com.example.chatgpt_mealplanner.core.util.UnitConverter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

class TransactionAbortException(val domainError: DomainError) : RuntimeException()

suspend fun <T> DatabaseTransactionRunner.runInDomainTransaction(
    block: suspend () -> DomainResult<T>
): DomainResult<T> {
    return try {
        runInTransaction {
            when (val result = block()) {
                is DomainResult.Success -> result
                is DomainResult.Failure -> throw TransactionAbortException(result.error)
            }
        }
    } catch (e: TransactionAbortException) {
        DomainResult.Failure(e.domainError)
    }
}

@Singleton
class MealDomainServiceImpl @Inject constructor(
    private val mealDao: MealDao,
    private val inventoryDao: InventoryDao,
    private val transactionDao: TransactionDao,
    private val leftoverDao: LeftoverDao,
    private val transactionRunner: DatabaseTransactionRunner,
    private val appRoleResolver: AppRoleResolver? = null,
    private val canonicalSyncDao: CanonicalSyncDao? = null,
    private val householdIdentityManager: HouseholdIdentityManager? = null
) : MealDomainService {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun createPlannedMeal(
        meal: Meal,
        ingredients: List<MealIngredient>
    ): DomainResult<Meal> {
        val validationError = validatePortionInvariants(meal)
        if (validationError != null) return validationError

        mealDao.insertMeal(meal.toEntity())
        mealDao.insertMealIngredients(ingredients.map { it.toEntity() })
        return DomainResult.Success(meal)
    }

    override suspend fun moveMeal(
        mealId: String,
        newDate: String
    ): DomainResult<Unit> {
        val mealWithIngredients = mealDao.getMealWithIngredients(mealId)
            ?: return DomainResult.Failure(DomainError.ItemNotFound(mealId))

        val updatedMeal = mealWithIngredients.meal.toDomainModel().copy(
            date = newDate,
            updatedAt = System.currentTimeMillis()
        )
        mealDao.insertMeal(updatedMeal.toEntity())
        return DomainResult.Success(Unit)
    }

    override suspend fun replaceMeal(
        oldMealId: String,
        newMeal: Meal,
        newIngredients: List<MealIngredient>
    ): DomainResult<Meal> {
        val existing = mealDao.getMealWithIngredients(oldMealId)
            ?: return DomainResult.Failure(DomainError.ItemNotFound(oldMealId))

        if (existing.meal.status == MealStatus.COOKED) {
            return DomainResult.Failure(
                DomainError.TransactionFailed("Cooked meals cannot be replaced")
            )
        }

        val validationError = validatePortionInvariants(newMeal)
        if (validationError != null) return validationError

        mealDao.deleteMeal(oldMealId)
        mealDao.insertMeal(newMeal.toEntity())
        mealDao.insertMealIngredients(newIngredients.map { it.toEntity() })
        return DomainResult.Success(newMeal)
    }

    override suspend fun deletePlannedMeal(mealId: String): DomainResult<Unit> {
        val existing = mealDao.getMealWithIngredients(mealId)
            ?: return DomainResult.Failure(DomainError.ItemNotFound(mealId))

        if (existing.meal.status != MealStatus.PLANNED) {
            return DomainResult.Failure(
                DomainError.TransactionFailed("Cannot delete meal with status ${existing.meal.status}. Only PLANNED meals can be deleted.")
            )
        }

        mealDao.deleteMeal(mealId)
        return DomainResult.Success(Unit)
    }

    override suspend fun markMealSkipped(mealId: String): DomainResult<Unit> {
        val existing = mealDao.getMealWithIngredients(mealId)
            ?: return DomainResult.Failure(DomainError.ItemNotFound(mealId))

        if (existing.meal.status == MealStatus.COOKED) {
            return DomainResult.Failure(
                DomainError.TransactionFailed("Cooked meals cannot be skipped")
            )
        }

        val updatedMeal = existing.meal.toDomainModel().copy(
            status = MealStatus.SKIPPED,
            updatedAt = System.currentTimeMillis()
        )
        mealDao.insertMeal(updatedMeal.toEntity())
        return DomainResult.Success(Unit)
    }

    override suspend fun markMealCancelled(mealId: String): DomainResult<Unit> {
        val existing = mealDao.getMealWithIngredients(mealId)
            ?: return DomainResult.Failure(DomainError.ItemNotFound(mealId))

        if (existing.meal.status == MealStatus.COOKED) {
            return DomainResult.Failure(
                DomainError.TransactionFailed("Cooked meals cannot be cancelled")
            )
        }

        val updatedMeal = existing.meal.toDomainModel().copy(
            status = MealStatus.CANCELLED,
            updatedAt = System.currentTimeMillis()
        )
        mealDao.insertMeal(updatedMeal.toEntity())
        return DomainResult.Success(Unit)
    }

    override suspend fun restoreMealToPlanned(mealId: String): DomainResult<Unit> {
        val existing = mealDao.getMealWithIngredients(mealId)
            ?: return DomainResult.Failure(DomainError.ItemNotFound(mealId))

        if (existing.meal.status == MealStatus.COOKED) {
            return DomainResult.Failure(
                DomainError.TransactionFailed("Cooked meals cannot be modified")
            )
        }

        val updatedMeal = existing.meal.toDomainModel().copy(
            status = MealStatus.PLANNED,
            updatedAt = System.currentTimeMillis()
        )
        mealDao.insertMeal(updatedMeal.toEntity())
        return DomainResult.Success(Unit)
    }

    override suspend fun markMealCooked(
        mealId: String,
        actualIngredientOverrides: List<ActualIngredientOverride>,
        substitutions: List<IngredientSubstitution>,
        leftoverStorageLocation: StorageLocation
    ): DomainResult<Unit> {
        // Phase A: Pre-validation & Resolution (READ-ONLY)
        val mealWithIngs = mealDao.getMealWithIngredients(mealId)
            ?: return DomainResult.Failure(DomainError.ItemNotFound(mealId))

        val meal = mealWithIngs.meal.toDomainModel()
        if (meal.status == MealStatus.COOKED) {
            return DomainResult.Failure(
                DomainError.DuplicateOperation("Meal $mealId is already marked as COOKED")
            )
        }
        if (meal.status == MealStatus.CANCELLED || meal.status == MealStatus.SKIPPED) {
            return DomainResult.Failure(
                DomainError.TransactionFailed("Cannot cook a ${meal.status} meal directly. Restore to PLANNED first.")
            )
        }

        if (transactionDao.hasCookedTransactionForMeal(mealId)) {
            return DomainResult.Failure(
                DomainError.DuplicateOperation("Cooked inventory transaction already exists for meal $mealId")
            )
        }

        val portionError = validatePortionInvariants(meal)
        if (portionError != null) return portionError

        // Validate substitution target XOR rule
        for (sub in substitutions) {
            if (!sub.isValid()) {
                return DomainResult.Failure(
                    DomainError.TransactionFailed("Invalid substitution for ingredient ${sub.mealIngredientId}: exactly one target must be specified")
                )
            }
        }

        val ingredients = mealWithIngs.ingredients.map { it.toDomainModel() }

        data class DeductionRequirement(
            val ingredient: MealIngredient,
            val effectiveQty: Double,
            val effectiveUnit: QuantityUnit,
            val selectedLotId: String?,
            val selectedCanonicalId: String?,
            val updatedIngredientModel: MealIngredient
        )

        val requirements = mutableListOf<DeductionRequirement>()

        for (ing in ingredients) {
            val override = actualIngredientOverrides.find { it.mealIngredientId == ing.id }
            val sub = substitutions.find { it.mealIngredientId == ing.id }

            val actualQty = override?.actualQuantity ?: sub?.actualQuantity ?: ing.actualQuantity
            val actualUnit = override?.actualUnit ?: sub?.actualUnit ?: ing.actualUnit ?: ing.plannedUnit

            if (actualQty != null && actualQty < 0.0) {
                return DomainResult.Failure(DomainError.InvalidQuantity(actualQty))
            }

            // Check optional ingredient omission
            if (ing.optional && (actualQty == null || actualQty == 0.0) && sub == null) {
                requirements.add(
                    DeductionRequirement(
                        ingredient = ing,
                        effectiveQty = 0.0,
                        effectiveUnit = actualUnit,
                        selectedLotId = null,
                        selectedCanonicalId = null,
                        updatedIngredientModel = ing.copy(actualQuantity = 0.0, actualUnit = actualUnit)
                    )
                )
                continue
            }

            // Non-optional required ingredient with actual quantity = 0
            if ((actualQty == 0.0) && sub == null) {
                requirements.add(
                    DeductionRequirement(
                        ingredient = ing,
                        effectiveQty = 0.0,
                        effectiveUnit = actualUnit,
                        selectedLotId = null,
                        selectedCanonicalId = null,
                        updatedIngredientModel = ing.copy(actualQuantity = 0.0, actualUnit = actualUnit)
                    )
                )
                continue
            }

            val requiredQty = actualQty ?: ing.plannedQuantity
            val targetLotId = sub?.selectedInventoryItemId
            val targetCanonicalId = sub?.selectedCanonicalIngredientId ?: ing.canonicalIngredientId

            val updatedModel = ing.copy(
                actualQuantity = requiredQty,
                actualUnit = actualUnit,
                substitutedCanonicalIngredientId = sub?.selectedCanonicalIngredientId,
                substitutedDisplayName = if (sub != null) "Substituted Ingredient" else null
            )

            requirements.add(
                DeductionRequirement(
                    ingredient = ing,
                    effectiveQty = requiredQty,
                    effectiveUnit = actualUnit,
                    selectedLotId = targetLotId,
                    selectedCanonicalId = targetCanonicalId,
                    updatedIngredientModel = updatedModel
                )
            )
        }

        // Validate stock availability for non-zero requirements
        data class PlanDeductionStep(
            val requirement: DeductionRequirement,
            val candidateLots: List<InventoryItemEntity>
        )

        val planSteps = mutableListOf<PlanDeductionStep>()

        for (req in requirements) {
            if (req.effectiveQty <= 0.0) continue

            val candidateLots = when {
                req.selectedLotId != null -> {
                    val lot = inventoryDao.getItemById(req.selectedLotId)
                    if (lot != null && lot.status in listOf(ItemStatus.AVAILABLE, ItemStatus.LOW, ItemStatus.USE_SOON)) {
                        listOf(lot)
                    } else emptyList()
                }
                req.selectedCanonicalId != null -> {
                    inventoryDao.getUsableItemsByCanonicalIngredientId(req.selectedCanonicalId)
                }
                else -> {
                    return DomainResult.Failure(
                        DomainError.TransactionFailed("Ingredient ${req.ingredient.displayName} has no canonical ID or explicit lot target")
                    )
                }
            }

            // Order lots FIFO: opened first, then useByDate ASC, bestBeforeDate ASC, purchaseDate ASC, createdAt ASC
            val sortedLots = candidateLots.sortedWith(
                compareByDescending<InventoryItemEntity> { it.opened == true }
                    .thenBy(nullsLast()) { it.useByDate }
                    .thenBy(nullsLast()) { it.bestBeforeDate }
                    .thenBy(nullsLast()) { it.purchaseDate }
                    .thenBy { it.createdAt }
            )

            // Aggregate usable quantity
            var usableTotal = 0.0
            var hasUnknownLot = false

            for (lot in sortedLots) {
                if (lot.quantityConfidence == QuantityConfidence.UNKNOWN) {
                    hasUnknownLot = true
                    continue
                }
                val lotQty = lot.quantity ?: 0.0
                val unitResult = UnitConverter.convert(
                    value = lotQty,
                    from = lot.quantityUnit ?: req.effectiveUnit,
                    to = req.effectiveUnit,
                    packSize = lot.packSize,
                    packSizeUnit = lot.packSizeUnit
                )
                when (unitResult) {
                    is DomainResult.Success -> usableTotal += unitResult.value
                    is DomainResult.Failure -> return unitResult
                }
            }

            if (usableTotal < req.effectiveQty) {
                if (hasUnknownLot) {
                    return DomainResult.Failure(
                        DomainError.NeedsConfirmation(
                            itemId = req.ingredient.id,
                            displayName = req.ingredient.displayName,
                            requiredQuantity = req.effectiveQty,
                            unit = req.effectiveUnit
                        )
                    )
                } else {
                    return DomainResult.Failure(
                        DomainError.InsufficientQuantity(
                            available = usableTotal,
                            requested = req.effectiveQty,
                            unit = req.effectiveUnit
                        )
                    )
                }
            }

            planSteps.add(PlanDeductionStep(req, sortedLots))
        }

        // Phase B: Execution (MUTATION inside guaranteed atomic rollback transaction)
        val appRole = appRoleResolver?.getAppRoleAsync()
        if (appRole == AppRole.FOLLOWER) {
            return DomainResult.Failure(DomainError.TransactionFailed("Follower meal deduction not supported directly on Main storage"))
        }

        val syncDao = canonicalSyncDao
        val identityMgr = householdIdentityManager
        val mainDeviceId = identityMgr?.getOrCreateHouseholdIdentity()?.mainDeviceId.orEmpty()

        if (appRole == AppRole.MAIN) {
            if (syncDao == null) {
                return DomainResult.Failure(DomainError.TransactionFailed("CanonicalSyncDao unavailable on Main"))
            }
            if (identityMgr == null || mainDeviceId.isBlank()) {
                return DomainResult.Failure(DomainError.TransactionFailed("Household identity dependencies unavailable on Main"))
            }
        }

        return transactionRunner.runInDomainTransaction {
            val now = System.currentTimeMillis()

            suspend fun publishSetQuantity(oldLot: InventoryItemEntity, newQty: Double) {
                if (newQty.isNaN() || newQty.isInfinite() || newQty < 0.0) {
                    throw TransactionAbortException(DomainError.InvalidQuantity(newQty))
                }

                if (syncDao != null && mainDeviceId.isNotBlank()) {
                    val seqEntity = syncDao.getSequenceEntity() ?: CanonicalSyncSequenceEntity(singletonId = 1, nextSequence = 1L)
                    val allocatedSequence = seqEntity.nextSequence
                    syncDao.updateSequenceEntity(seqEntity.copy(nextSequence = allocatedSequence + 1L))

                    val setQtyPayload = SetQuantityPayloadDto(absoluteQuantity = newQty)
                    val payloadJson = json.encodeToString(setQtyPayload)

                    val canonicalOp = CanonicalSyncOperationEntity(
                        operationId = UUID.randomUUID().toString(),
                        deviceId = mainDeviceId,
                        deviceSequence = allocatedSequence,
                        protocolVersion = SYNC_PROTOCOL_VERSION,
                        operationType = SyncOperationType.SET_QUANTITY.name,
                        targetItemId = oldLot.id,
                        baseRevision = oldLot.revision,
                        payloadJson = payloadJson,
                        createdAt = now,
                        canonicalSequence = allocatedSequence,
                        receivedAt = now,
                        status = SyncOperationStatus.APPLIED.name,
                        resultCode = "APPLIED",
                        resultPayloadJson = null
                    )
                    syncDao.insertCanonicalOperation(canonicalOp)
                }
            }

            for (step in planSteps) {
                var remainingToDeduct = step.requirement.effectiveQty

                for (lot in step.candidateLots) {
                    if (remainingToDeduct <= 0.0) break
                    if (lot.quantityConfidence == QuantityConfidence.UNKNOWN) continue

                    val lotUnit = lot.quantityUnit ?: step.requirement.effectiveUnit

                    // Calculate requirement in lot's stored unit
                    val reqInLotUnitResult = UnitConverter.convert(
                        value = remainingToDeduct,
                        from = step.requirement.effectiveUnit,
                        to = lotUnit,
                        packSize = lot.packSize,
                        packSizeUnit = lot.packSizeUnit
                    )
                    val reqInLotUnit = when (reqInLotUnitResult) {
                        is DomainResult.Success -> reqInLotUnitResult.value
                        is DomainResult.Failure -> return@runInDomainTransaction reqInLotUnitResult
                    }

                    if (lot.opened == true || lot.packCount == null) {
                        val lotQty = lot.quantity ?: 0.0
                        val actualDeductInLotUnit = minOf(lotQty, reqInLotUnit)
                        val newQty = lotQty - actualDeductInLotUnit
                        val newStatus = if (newQty <= 0.0) ItemStatus.OUT else lot.status

                        val updatedLot = lot.copy(
                            quantity = newQty,
                            status = newStatus,
                            updatedAt = now,
                            revision = lot.revision + 1L
                        )
                        inventoryDao.updateItem(updatedLot)
                        publishSetQuantity(lot, newQty)

                        val tx = InventoryTransactionEntity(
                            id = UUID.randomUUID().toString(),
                            inventoryItemId = lot.id,
                            type = TransactionType.COOKED,
                            quantityChange = -actualDeductInLotUnit,
                            unit = lotUnit,
                            mealId = mealId,
                            note = "Deducted for cooked meal: ${meal.title}",
                            timestamp = now
                        )
                        transactionDao.insertTransaction(tx)

                        val deductedInReqUnitResult = UnitConverter.convert(
                            value = actualDeductInLotUnit,
                            from = lotUnit,
                            to = step.requirement.effectiveUnit,
                            packSize = lot.packSize,
                            packSizeUnit = lot.packSizeUnit
                        )
                        val deductedInReqUnit = when (deductedInReqUnitResult) {
                            is DomainResult.Success -> deductedInReqUnitResult.value
                            is DomainResult.Failure -> return@runInDomainTransaction deductedInReqUnitResult
                        }
                        remainingToDeduct -= deductedInReqUnit

                    } else if (lot.packCount > 0) {
                        val packSize = lot.packSize ?: lot.quantity ?: 0.0
                        val totalUnopenedQty = lot.packCount * packSize

                        if (reqInLotUnit >= totalUnopenedQty) {
                            // Case: Consume whole unopened multipack completely
                            val updatedLot = lot.copy(
                                packCount = 0,
                                quantity = 0.0,
                                status = ItemStatus.OUT,
                                updatedAt = now,
                                revision = lot.revision + 1L
                            )
                            inventoryDao.updateItem(updatedLot)
                            publishSetQuantity(lot, 0.0)

                            val tx = InventoryTransactionEntity(
                                id = UUID.randomUUID().toString(),
                                inventoryItemId = lot.id,
                                type = TransactionType.COOKED,
                                quantityChange = -totalUnopenedQty,
                                unit = lotUnit,
                                mealId = mealId,
                                note = "Deducted full multipack for cooked meal: ${meal.title}",
                                timestamp = now
                            )
                            transactionDao.insertTransaction(tx)

                            remainingToDeduct -= convertValue(totalUnopenedQty, lotUnit, step.requirement.effectiveUnit, packSize, lot.packSizeUnit)
                        } else {
                            // Check exact inner unit deduction vs split
                            val fullPacksToDeduct = (reqInLotUnit / packSize).toInt()
                            val remainderInLotUnit = reqInLotUnit - (fullPacksToDeduct * packSize)

                            var currentPackCount = lot.packCount - fullPacksToDeduct
                            var consumedQty = fullPacksToDeduct * packSize

                            if (remainderInLotUnit == 0.0) {
                                // Case A: Consumes exact whole inner units (e.g. 405g from 2x405g -> packCount 1, no residual opened lot)
                                val newUnopenedQty = currentPackCount * packSize
                                val updatedLot = lot.copy(
                                    packCount = currentPackCount,
                                    quantity = newUnopenedQty,
                                    status = if (currentPackCount <= 0) ItemStatus.OUT else lot.status,
                                    updatedAt = now,
                                    revision = lot.revision + 1L
                                )
                                inventoryDao.updateItem(updatedLot)
                                publishSetQuantity(lot, newUnopenedQty)

                                val tx = InventoryTransactionEntity(
                                    id = UUID.randomUUID().toString(),
                                    inventoryItemId = lot.id,
                                    type = TransactionType.COOKED,
                                    quantityChange = -reqInLotUnit,
                                    unit = lotUnit,
                                    mealId = mealId,
                                    note = "Deducted exact pack unit(s) for cooked meal: ${meal.title}",
                                    timestamp = now
                                )
                                transactionDao.insertTransaction(tx)
                                remainingToDeduct = 0.0
                            } else {
                                // Case B / C: Consumes partial inner unit -> split 1 pack into opened residual lot
                                currentPackCount -= 1
                                consumedQty += remainderInLotUnit

                                val newUnopenedQty = currentPackCount * packSize
                                val updatedLot = lot.copy(
                                    packCount = currentPackCount,
                                    quantity = newUnopenedQty,
                                    status = if (currentPackCount <= 0) ItemStatus.OUT else lot.status,
                                    updatedAt = now,
                                    revision = lot.revision + 1L
                                )
                                inventoryDao.updateItem(updatedLot)
                                publishSetQuantity(lot, newUnopenedQty)

                                val tx = InventoryTransactionEntity(
                                    id = UUID.randomUUID().toString(),
                                    inventoryItemId = lot.id,
                                    type = TransactionType.COOKED,
                                    quantityChange = -consumedQty,
                                    unit = lotUnit,
                                    mealId = mealId,
                                    note = "Deducted from multipack for cooked meal: ${meal.title}",
                                    timestamp = now
                                )
                                transactionDao.insertTransaction(tx)

                                // Create opened residual lot
                                val openedResidualQty = packSize - remainderInLotUnit
                                if (openedResidualQty > 0.0) {
                                    val residualLot = InventoryItemEntity(
                                        id = UUID.randomUUID().toString(),
                                        barcode = lot.barcode,
                                        canonicalIngredientId = lot.canonicalIngredientId,
                                        canonicalName = lot.canonicalName,
                                        displayName = lot.displayName,
                                        brand = lot.brand,
                                        category = lot.category,
                                        storageLocation = lot.storageLocation,
                                        quantity = openedResidualQty,
                                        quantityUnit = lot.quantityUnit,
                                        packCount = null,
                                        packSize = lot.packSize,
                                        packSizeUnit = lot.packSizeUnit,
                                        quantityConfidence = lot.quantityConfidence,
                                        status = ItemStatus.AVAILABLE,
                                        opened = true,
                                        frozen = lot.frozen,
                                        staple = lot.staple,
                                        purchaseDate = lot.purchaseDate,
                                        openedDate = java.time.LocalDate.now().toString(),
                                        useByDate = lot.useByDate,
                                        bestBeforeDate = lot.bestBeforeDate,
                                        notes = lot.notes,
                                        createdAt = now,
                                        updatedAt = now
                                    )
                                    inventoryDao.insertItem(residualLot)
                                }
                                remainingToDeduct = 0.0
                            }
                        }
                    }
                }
            }

            // Leftover Creation
            if (meal.leftoverPortions > 0) {
                val leftover = LeftoverEntity(
                    id = UUID.randomUUID().toString(),
                    sourceMealId = mealId,
                    name = meal.title,
                    initialPortions = meal.leftoverPortions,
                    portions = meal.leftoverPortions,
                    storageLocation = leftoverStorageLocation,
                    status = LeftoverStatus.AVAILABLE,
                    createdDate = meal.date,
                    consumeByDate = null,
                    createdAt = now,
                    updatedAt = now
                )
                leftoverDao.insertLeftover(leftover)
            }

            // Update Meal Status & Meal Ingredients Actuals
            val updatedMeal = meal.copy(
                status = MealStatus.COOKED,
                cookedAt = now,
                updatedAt = now
            )
            mealDao.insertMeal(updatedMeal.toEntity())

            val updatedIngredients = requirements.map { it.updatedIngredientModel }
            mealDao.insertMealIngredients(updatedIngredients.map { it.toEntity() })

            DomainResult.Success(Unit)
        }
    }

    override suspend fun consumeLeftoverPortions(
        leftoverId: String,
        portionsToConsume: Int
    ): DomainResult<Unit> {
        val entity = leftoverDao.getLeftoverById(leftoverId)
            ?: return DomainResult.Failure(DomainError.ItemNotFound(leftoverId))

        if (entity.status != LeftoverStatus.AVAILABLE) {
            return DomainResult.Failure(DomainError.TransactionFailed("Leftover is not available"))
        }

        val newPortions = (entity.portions - portionsToConsume).coerceAtLeast(0)
        val newStatus = if (newPortions == 0) LeftoverStatus.CONSUMED else entity.status

        val updated = entity.copy(
            portions = newPortions,
            status = newStatus,
            updatedAt = System.currentTimeMillis()
        )
        leftoverDao.insertLeftover(updated)
        return DomainResult.Success(Unit)
    }

    override suspend fun discardLeftover(leftoverId: String): DomainResult<Unit> {
        val entity = leftoverDao.getLeftoverById(leftoverId)
            ?: return DomainResult.Failure(DomainError.ItemNotFound(leftoverId))

        val updated = entity.copy(
            portions = 0,
            status = LeftoverStatus.DISCARDED,
            updatedAt = System.currentTimeMillis()
        )
        leftoverDao.insertLeftover(updated)
        return DomainResult.Success(Unit)
    }

    private fun validatePortionInvariants(meal: Meal): DomainResult.Failure? {
        if (meal.servings <= 0 || meal.batchPortions <= 0 || meal.immediatePortions < 0 || meal.leftoverPortions < 0) {
            return DomainResult.Failure(
                DomainError.TransactionFailed("Portion counts must be non-negative, with servings and batchPortions > 0")
            )
        }
        if (meal.batchPortions != meal.immediatePortions + meal.leftoverPortions) {
            return DomainResult.Failure(
                DomainError.TransactionFailed("batchPortions (${meal.batchPortions}) must equal immediatePortions (${meal.immediatePortions}) + leftoverPortions (${meal.leftoverPortions})")
            )
        }
        return null
    }

    private fun convertValue(
        value: Double,
        from: QuantityUnit,
        to: QuantityUnit,
        packSize: Double?,
        packSizeUnit: QuantityUnit?
    ): Double {
        return when (val res = UnitConverter.convert(value, from, to, packSize, packSizeUnit)) {
            is DomainResult.Success -> res.value
            is DomainResult.Failure -> value
        }
    }
}
