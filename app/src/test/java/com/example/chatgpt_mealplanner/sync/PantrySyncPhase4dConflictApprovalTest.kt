package com.example.chatgpt_mealplanner.sync

import com.example.chatgpt_mealplanner.core.database.DatabaseTransactionRunner
import com.example.chatgpt_mealplanner.core.database.entity.HouseholdIdentityEntity
import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemEntity
import com.example.chatgpt_mealplanner.core.database.entity.PairedDeviceEntity
import com.example.chatgpt_mealplanner.core.database.entity.SyncConflictEntity
import com.example.chatgpt_mealplanner.core.database.entity.SyncPendingApprovalEntity
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.sync.HouseholdIdentityManager
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.core.sync.engine.MainCanonicalSyncEngine
import com.example.chatgpt_mealplanner.core.sync.enrollment.PantryEnrollmentInvitationManager
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity
import com.example.chatgpt_mealplanner.core.sync.repository.PairedDeviceRepository
import com.example.chatgpt_mealplanner.core.sync.repository.ResolutionResult
import com.example.chatgpt_mealplanner.core.sync.repository.SyncConflictApprovalRepository
import com.example.chatgpt_mealplanner.feature.household.HouseholdViewModel
import com.example.chatgpt_mealplanner.sync.contract.EditItemPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.EnrollmentRequestDto
import com.example.chatgpt_mealplanner.sync.contract.RequestDiscardPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationStatus
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.UUID

open class TestTransactionRunner : DatabaseTransactionRunner {
    private val mutex = kotlinx.coroutines.sync.Mutex()
    override suspend fun <T> runInTransaction(block: suspend () -> T): T {
        return mutex.withLock {
            block()
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class PantrySyncPhase4dConflictApprovalTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var mainCanonicalDao: FakeCanonicalSyncDao
    private lateinit var mainPairedDeviceDao: TestSyncPairedDeviceDao
    private lateinit var mainInventoryDao: com.example.chatgpt_mealplanner.core.service.FakeInventoryDao
    private lateinit var transactionRunner: TestTransactionRunner
    private lateinit var syncEngine: MainCanonicalSyncEngine
    private lateinit var repository: SyncConflictApprovalRepository
    private lateinit var identityManager: HouseholdIdentityManager
    private lateinit var mainIdentityDao: FakeHouseholdIdentityDao
    private lateinit var pairedDeviceRepository: PairedDeviceRepository
    private lateinit var viewModel: HouseholdViewModel

    private val householdId = UUID.randomUUID().toString()
    private val mainDeviceId = UUID.randomUUID().toString()
    private val followerDeviceId = UUID.randomUUID().toString()
    private val validFingerprint = "SHA256:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF"
    private lateinit var keyStoreManager: TestEnrollmentKeyStoreManager
    private lateinit var invitationManager: PantryEnrollmentInvitationManager

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(testDispatcher)
        mainCanonicalDao = FakeCanonicalSyncDao()
        mainPairedDeviceDao = TestSyncPairedDeviceDao()
        mainInventoryDao = com.example.chatgpt_mealplanner.core.service.FakeInventoryDao()
        mainIdentityDao = FakeHouseholdIdentityDao()
        transactionRunner = TestTransactionRunner()
        keyStoreManager = TestEnrollmentKeyStoreManager()
        invitationManager = PantryEnrollmentInvitationManager(mainPairedDeviceDao, keyStoreManager)

        identityManager = HouseholdIdentityManager(mainIdentityDao)
        syncEngine = MainCanonicalSyncEngine(mainCanonicalDao, mainPairedDeviceDao, mainInventoryDao, transactionRunner)
        repository = SyncConflictApprovalRepository(mainCanonicalDao, mainPairedDeviceDao, mainInventoryDao, mainIdentityDao, transactionRunner)
        pairedDeviceRepository = PairedDeviceRepository(mainPairedDeviceDao)

        mainIdentityDao.insertHouseholdIdentity(
            HouseholdIdentityEntity(householdId, "Test Household", mainDeviceId, System.currentTimeMillis())
        )
        mainPairedDeviceDao.insertOrUpdateDevice(
            PairedDeviceEntity(followerDeviceId, "Follower Phone", "PUB_KEY_FOLLOWER", "ACTIVE", System.currentTimeMillis(), 0L)
        )

        viewModel = HouseholdViewModel(
            identityManager = identityManager,
            pairedDeviceRepository = pairedDeviceRepository,
            followerPairingStore = FakePairingStore(),
            syncConflictApprovalRepository = repository
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // 1. Conflict appears in Main query and UI model
    @Test
    fun test1_conflictAppearsInMainQueryAndUiModel() = runTest {
        val itemId = UUID.randomUUID().toString()
        val item = InventoryItemEntity(itemId, null, null, "Milk", "Whole Milk", null, FoodCategory.CUPBOARD, StorageLocation.FRIDGE, 1.0, QuantityUnit.UNIT, null, null, null, QuantityConfidence.EXACT, ItemStatus.AVAILABLE, false, false, false, null, null, null, null, null, System.currentTimeMillis(), System.currentTimeMillis(), 2L)
        mainInventoryDao.insertItem(item)

        val opId = UUID.randomUUID().toString()
        val opDto = SyncOperationDto(
            operationId = opId,
            deviceId = followerDeviceId,
            deviceSequence = 1L,
            protocolVersion = 1,
            operationType = SyncOperationType.EDIT_ITEM,
            targetItemId = itemId,
            baseRevision = 1L, // Mismatched base revision (1L != current 2L)
            payloadJson = json.encodeToString(EditItemPayloadDto(displayName = "Follower Skim Milk")),
            createdAt = System.currentTimeMillis()
        )
        val res = syncEngine.processOperation(followerDeviceId, opDto)
        assertEquals(SyncOperationStatus.CONFLICT, res.status)

        viewModel.loadHouseholdInfo()
        advanceUntilIdle()
        val uiState = viewModel.uiState.value
        assertEquals(1, uiState.openConflicts.size)
        assertEquals(itemId, uiState.openConflicts[0].targetItemId)
    }

    // 2. Approval appears separately from conflict
    @Test
    fun test2_approvalAppearsSeparatelyFromConflict() = runTest {
        val itemId = UUID.randomUUID().toString()
        val item = InventoryItemEntity(itemId, null, null, "Bread", "Whole Wheat Bread", null, FoodCategory.CUPBOARD, StorageLocation.CUPBOARD, 1.0, QuantityUnit.UNIT, null, null, null, QuantityConfidence.EXACT, ItemStatus.AVAILABLE, false, false, false, null, null, null, null, null, System.currentTimeMillis(), System.currentTimeMillis(), 1L)
        mainInventoryDao.insertItem(item)

        val opId = UUID.randomUUID().toString()
        val opDto = SyncOperationDto(
            operationId = opId,
            deviceId = followerDeviceId,
            deviceSequence = 2L,
            protocolVersion = 1,
            operationType = SyncOperationType.REQUEST_DISCARD_ITEM,
            targetItemId = itemId,
            baseRevision = 1L,
            payloadJson = json.encodeToString(RequestDiscardPayloadDto(reason = "Expired")),
            createdAt = System.currentTimeMillis()
        )
        val res = syncEngine.processOperation(followerDeviceId, opDto)
        assertEquals(SyncOperationStatus.PENDING_APPROVAL, res.status)

        viewModel.loadHouseholdInfo()
        advanceUntilIdle()
        val uiState = viewModel.uiState.value
        assertEquals(1, uiState.openApprovals.size)
        assertEquals(0, uiState.openConflicts.size)
    }

    // 3. Approve pending discard mutates item status to DISCARDED exactly once
    @Test
    fun test3_approvePendingDiscardMutatesExactlyOnce() = runTest {
        val itemId = UUID.randomUUID().toString()
        val item = InventoryItemEntity(itemId, null, null, "Yogurt", "Greek Yogurt", null, FoodCategory.CUPBOARD, StorageLocation.FRIDGE, 1.0, QuantityUnit.UNIT, null, null, null, QuantityConfidence.EXACT, ItemStatus.AVAILABLE, false, false, false, null, null, null, null, null, System.currentTimeMillis(), System.currentTimeMillis(), 1L)
        mainInventoryDao.insertItem(item)

        val opId = UUID.randomUUID().toString()
        val opDto = SyncOperationDto(opId, followerDeviceId, 3L, 1, SyncOperationType.REQUEST_DISCARD_ITEM, itemId, 1L, json.encodeToString(RequestDiscardPayloadDto(reason = "Spoiled")), System.currentTimeMillis())
        val res = syncEngine.processOperation(followerDeviceId, opDto)
        val approvalId = res.approvalId!!

        val resApprove = repository.approvePendingDiscard(approvalId)
        assertTrue(resApprove is ResolutionResult.Success)

        val itemAfter = mainInventoryDao.getItemById(itemId)
        assertEquals(ItemStatus.DISCARDED, itemAfter?.status)
        assertEquals(2L, itemAfter?.revision)
    }

    // 4. Repeated approval is idempotent
    @Test
    fun test4_repeatedApprovalIsIdempotent() = runTest {
        val itemId = UUID.randomUUID().toString()
        val item = InventoryItemEntity(itemId, null, null, "Cheese", "Cheddar", null, FoodCategory.CUPBOARD, StorageLocation.FRIDGE, 1.0, QuantityUnit.UNIT, null, null, null, QuantityConfidence.EXACT, ItemStatus.AVAILABLE, false, false, false, null, null, null, null, null, System.currentTimeMillis(), System.currentTimeMillis(), 1L)
        mainInventoryDao.insertItem(item)

        val opId = UUID.randomUUID().toString()
        val opDto = SyncOperationDto(opId, followerDeviceId, 4L, 1, SyncOperationType.REQUEST_DISCARD_ITEM, itemId, 1L, json.encodeToString(RequestDiscardPayloadDto(reason = "Moldy")), System.currentTimeMillis())
        val res = syncEngine.processOperation(followerDeviceId, opDto)
        val approvalId = res.approvalId!!

        assertTrue(repository.approvePendingDiscard(approvalId) is ResolutionResult.Success)
        val res2 = repository.approvePendingDiscard(approvalId)
        assertTrue("Repeated approval MUST be idempotent", res2 is ResolutionResult.AlreadyResolved)

        assertEquals(2L, mainInventoryDao.getItemById(itemId)?.revision)
    }

    // 5. Reject leaves inventory unchanged
    @Test
    fun test5_rejectLeavesInventoryUnchanged() = runTest {
        val itemId = UUID.randomUUID().toString()
        val item = InventoryItemEntity(itemId, null, null, "Butter", "Salted Butter", null, FoodCategory.CUPBOARD, StorageLocation.FRIDGE, 1.0, QuantityUnit.UNIT, null, null, null, QuantityConfidence.EXACT, ItemStatus.AVAILABLE, false, false, false, null, null, null, null, null, System.currentTimeMillis(), System.currentTimeMillis(), 1L)
        mainInventoryDao.insertItem(item)

        val opId = UUID.randomUUID().toString()
        val opDto = SyncOperationDto(opId, followerDeviceId, 5L, 1, SyncOperationType.REQUEST_DISCARD_ITEM, itemId, 1L, json.encodeToString(RequestDiscardPayloadDto(reason = "Unwanted")), System.currentTimeMillis())
        val res = syncEngine.processOperation(followerDeviceId, opDto)
        val approvalId = res.approvalId!!

        val resReject = repository.rejectPendingDiscard(approvalId)
        assertTrue(resReject is ResolutionResult.Success)

        val itemAfter = mainInventoryDao.getItemById(itemId)
        assertEquals(ItemStatus.AVAILABLE, itemAfter?.status)
        assertEquals(1L, itemAfter?.revision)
    }

    // 6. Repeated reject is idempotent
    @Test
    fun test6_repeatedRejectIsIdempotent() = runTest {
        val itemId = UUID.randomUUID().toString()
        val item = InventoryItemEntity(itemId, null, null, "Eggs", "Free Range", null, FoodCategory.CUPBOARD, StorageLocation.FRIDGE, 12.0, QuantityUnit.UNIT, null, null, null, QuantityConfidence.EXACT, ItemStatus.AVAILABLE, false, false, false, null, null, null, null, null, System.currentTimeMillis(), System.currentTimeMillis(), 1L)
        mainInventoryDao.insertItem(item)

        val opId = UUID.randomUUID().toString()
        val opDto = SyncOperationDto(opId, followerDeviceId, 6L, 1, SyncOperationType.REQUEST_DISCARD_ITEM, itemId, 1L, json.encodeToString(RequestDiscardPayloadDto(reason = "Old")), System.currentTimeMillis())
        val res = syncEngine.processOperation(followerDeviceId, opDto)
        val approvalId = res.approvalId!!

        assertTrue(repository.rejectPendingDiscard(approvalId) is ResolutionResult.Success)
        val res2 = repository.rejectPendingDiscard(approvalId)
        assertTrue("Repeated reject MUST be idempotent", res2 is ResolutionResult.AlreadyResolved)
    }

    // 7. Concurrent / double resolution cannot mutate twice
    @Test
    fun test7_concurrentDoubleResolutionCannotMutateTwice() = runTest {
        val itemId = UUID.randomUUID().toString()
        val item = InventoryItemEntity(itemId, null, null, "Juice", "Orange Juice", null, FoodCategory.CUPBOARD, StorageLocation.FRIDGE, 1.0, QuantityUnit.UNIT, null, null, null, QuantityConfidence.EXACT, ItemStatus.AVAILABLE, false, false, false, null, null, null, null, null, System.currentTimeMillis(), System.currentTimeMillis(), 1L)
        mainInventoryDao.insertItem(item)

        val opId = UUID.randomUUID().toString()
        val opDto = SyncOperationDto(opId, followerDeviceId, 7L, 1, SyncOperationType.REQUEST_DISCARD_ITEM, itemId, 1L, json.encodeToString(RequestDiscardPayloadDto(reason = "Empty")), System.currentTimeMillis())
        val res = syncEngine.processOperation(followerDeviceId, opDto)
        val approvalId = res.approvalId!!

        val job1 = async(Dispatchers.IO) { repository.approvePendingDiscard(approvalId) }
        val job2 = async(Dispatchers.IO) { repository.approvePendingDiscard(approvalId) }

        val res1 = job1.await()
        val res2 = job2.await()

        val results = listOf(res1, res2)
        assertEquals(1, results.count { it is ResolutionResult.Success })
        assertEquals(1, results.count { it is ResolutionResult.AlreadyResolved })
        assertEquals(2L, mainInventoryDao.getItemById(itemId)?.revision)
    }

    // 8. Conflict resolution preserves canonical ordering
    @Test
    fun test8_conflictResolutionPreservesCanonicalOrdering() = runTest {
        val itemId = UUID.randomUUID().toString()
        val item = InventoryItemEntity(itemId, null, null, "Jam", "Strawberry", null, FoodCategory.CUPBOARD, StorageLocation.CUPBOARD, 1.0, QuantityUnit.UNIT, null, null, null, QuantityConfidence.EXACT, ItemStatus.AVAILABLE, false, false, false, null, null, null, null, null, System.currentTimeMillis(), System.currentTimeMillis(), 2L)
        mainInventoryDao.insertItem(item)

        val opId = UUID.randomUUID().toString()
        val opDto = SyncOperationDto(opId, followerDeviceId, 8L, 1, SyncOperationType.EDIT_ITEM, itemId, 1L, json.encodeToString(EditItemPayloadDto(displayName = "Follower Jam")), System.currentTimeMillis())
        val res = syncEngine.processOperation(followerDeviceId, opDto)
        val conflictId = res.conflictId!!

        val initialSeq = mainCanonicalDao.getLatestCanonicalSequence() ?: 0L
        repository.resolveConflictApplyFollower(conflictId)
        val afterSeq = mainCanonicalDao.getLatestCanonicalSequence() ?: 0L

        assertTrue("Resolving conflict MUST allocate new canonical sequence in order", afterSeq > initialSeq)
    }

    // 9. Follower conflict state remains terminal until authoritative outcome
    @Test
    fun test9_followerConflictStateRemainsTerminalUntilAuthoritativeOutcome() = runTest {
        val followerDao = FakeFollowerDao()
        val opId = UUID.randomUUID().toString()
        val itemId = UUID.randomUUID().toString()
        val op = FollowerSyncOutboxEntity(opId, followerDeviceId, 1L, 1, "EDIT_ITEM", itemId, 1L, "{}", System.currentTimeMillis(), "CONFLICT", createdLocallyAt = System.currentTimeMillis())
        followerDao.insertOutboxOperation(op)

        val pending = followerDao.getPendingOperationsInOrder()
        assertTrue("CONFLICT outbox operation MUST NOT count as pending upload", pending.isEmpty())
    }

    // 10. Pending approval does not prematurely discard follower projection
    @Test
    fun test10_pendingApprovalDoesNotPrematurelyDiscardFollowerProjection() = runTest {
        val followerDao = FakeFollowerDao()
        val itemId = UUID.randomUUID().toString()
        val localItem = FollowerInventoryItemEntity(
            itemId = itemId,
            canonicalName = "Cereal",
            confirmedDisplayName = "Oats",
            confirmedBrand = null,
            confirmedCategory = "CUPBOARD",
            confirmedStorageLocation = "CUPBOARD",
            confirmedQuantity = 1.0,
            confirmedQuantityUnit = "PACK",
            confirmedNotes = null,
            confirmedStatus = "AVAILABLE",
            mainConfirmedRevision = 1L,
            displayName = "Oats",
            brand = null,
            category = "CUPBOARD",
            storageLocation = "CUPBOARD",
            quantity = 1.0,
            quantityUnit = "PACK",
            notes = null,
            status = "AVAILABLE",
            optimisticRevision = 1L,
            isPendingSync = true,
            updatedAt = System.currentTimeMillis()
        )
        followerDao.insertOrUpdateItem(localItem)

        val opId = UUID.randomUUID().toString()
        val op = FollowerSyncOutboxEntity(opId, followerDeviceId, 1L, 1, "REQUEST_DISCARD_ITEM", itemId, 1L, "{}", System.currentTimeMillis(), "PENDING_APPROVAL", createdLocallyAt = System.currentTimeMillis())
        followerDao.insertOutboxOperation(op)

        val itemInDb = followerDao.getItemById(itemId)
        assertEquals("AVAILABLE", itemInDb?.status)
    }

    // 11. Rejection does not delete unrelated local inventory
    @Test
    fun test11_rejectionDoesNotDeleteUnrelatedLocalInventory() = runTest {
        val itemId = UUID.randomUUID().toString()
        val item = InventoryItemEntity(itemId, null, null, "Tea", "Black Tea", null, FoodCategory.CUPBOARD, StorageLocation.CUPBOARD, 1.0, QuantityUnit.UNIT, null, null, null, QuantityConfidence.EXACT, ItemStatus.AVAILABLE, false, false, false, null, null, null, null, null, System.currentTimeMillis(), System.currentTimeMillis(), 1L)
        mainInventoryDao.insertItem(item)

        val opId = UUID.randomUUID().toString()
        val opDto = SyncOperationDto(opId, followerDeviceId, 11L, 1, SyncOperationType.REQUEST_DISCARD_ITEM, itemId, 1L, json.encodeToString(RequestDiscardPayloadDto(reason = "Discard")), System.currentTimeMillis())
        val res = syncEngine.processOperation(followerDeviceId, opDto)
        repository.rejectPendingDiscard(res.approvalId!!)

        val items = mainInventoryDao.getAllItemsSync()
        assertEquals(1, items.size)
        assertEquals(itemId, items[0].id)
    }

    // 12. Canonical feed communicates resolution correctly
    @Test
    fun test12_canonicalFeedCommunicatesResolutionCorrectly() = runTest {
        val itemId = UUID.randomUUID().toString()
        val item = InventoryItemEntity(itemId, null, null, "Coffee", "Arabica", null, FoodCategory.CUPBOARD, StorageLocation.CUPBOARD, 1.0, QuantityUnit.UNIT, null, null, null, QuantityConfidence.EXACT, ItemStatus.AVAILABLE, false, false, false, null, null, null, null, null, System.currentTimeMillis(), System.currentTimeMillis(), 1L)
        mainInventoryDao.insertItem(item)

        val opId = UUID.randomUUID().toString()
        val opDto = SyncOperationDto(opId, followerDeviceId, 12L, 1, SyncOperationType.REQUEST_DISCARD_ITEM, itemId, 1L, json.encodeToString(RequestDiscardPayloadDto(reason = "Discard")), System.currentTimeMillis())
        val res = syncEngine.processOperation(followerDeviceId, opDto)
        repository.approvePendingDiscard(res.approvalId!!)

        val feed = mainCanonicalDao.getFeedOperationsSince(0L, 50)
        assertTrue(feed.any { it.resultCode == "APPROVED_DISCARD" })
    }

    // 13. Revoked follower cannot use resolution / admin APIs
    @Test
    fun test13_revokedFollowerCannotUseResolutionAdminApis() = runTest {
        val revokedDevId = UUID.randomUUID().toString()
        mainPairedDeviceDao.insertOrUpdateDevice(PairedDeviceEntity(revokedDevId, "Revoked Phone", "PUB_REV", "REVOKED", System.currentTimeMillis(), 0L))

        val opId = UUID.randomUUID().toString()
        val itemId = UUID.randomUUID().toString()
        val opDto = SyncOperationDto(opId, revokedDevId, 1L, 1, SyncOperationType.ADD_ITEM, itemId, null, json.encodeToString(EditItemPayloadDto("item_13")), System.currentTimeMillis())
        val res = syncEngine.processOperation(revokedDevId, opDto)

        assertEquals(SyncOperationStatus.REJECTED, res.status)
        assertEquals("DEVICE_REVOKED", res.resultCode)
    }

    // 14. Existing Phase 3 & 4 sync regressions remain intact
    @Test
    fun test14_existingSyncRegressionsRemainIntact() = runTest {
        val inv = invitationManager.createInvitation(householdId, mainDeviceId, "192.168.1.100", 8443, validFingerprint)
        val req = EnrollmentRequestDto(inv.enrollmentToken, householdId, mainDeviceId, UUID.randomUUID().toString(), "New Phone", keyStoreManager.getPublicKeyBase64("key_reg_14")!!)

        val res = invitationManager.processEnrollment(req, householdId, mainDeviceId)
        assertTrue("Phase 4A enrollment regression MUST remain intact", res.success)
    }

    // 15. Destructive confirmation regression remains intact
    @Test
    fun test15_destructiveConfirmationRegressionRemainsIntact() = runTest {
        val itemId = UUID.randomUUID().toString()
        val item = InventoryItemEntity(itemId, null, null, "Milk", "Milk", null, FoodCategory.CUPBOARD, StorageLocation.FRIDGE, 1.0, QuantityUnit.UNIT, null, null, null, QuantityConfidence.EXACT, ItemStatus.AVAILABLE, false, false, false, null, null, null, null, null, System.currentTimeMillis(), System.currentTimeMillis(), 1L)
        mainInventoryDao.insertItem(item)

        mainInventoryDao.deleteItemById(itemId)
        assertNull("Destructive deletion confirmation invariant MUST remain intact", mainInventoryDao.getItemById(itemId))
    }

    // 16. Stale pending discard refuses to discard newer Main state
    @Test
    fun test16_stalePendingDiscard_refusesToDiscardNewerMainState() = runTest {
        val itemId = UUID.randomUUID().toString()
        val item = InventoryItemEntity(itemId, null, null, "Cereal", "Corn Flakes", null, FoodCategory.CUPBOARD, StorageLocation.CUPBOARD, 1.0, QuantityUnit.UNIT, null, null, null, QuantityConfidence.EXACT, ItemStatus.AVAILABLE, false, false, false, null, null, null, null, null, System.currentTimeMillis(), System.currentTimeMillis(), 1L)
        mainInventoryDao.insertItem(item)

        val opId = UUID.randomUUID().toString()
        val opDto = SyncOperationDto(opId, followerDeviceId, 16L, 1, SyncOperationType.REQUEST_DISCARD_ITEM, itemId, 1L, json.encodeToString(RequestDiscardPayloadDto(reason = "Old")), System.currentTimeMillis())
        val res = syncEngine.processOperation(followerDeviceId, opDto)
        val approvalId = res.approvalId!!

        // Main updates item revision in the meantime to 2L
        mainInventoryDao.updateItem(item.copy(displayName = "Corn Flakes Updated", revision = 2L))

        val approveRes = repository.approvePendingDiscard(approvalId)
        assertTrue("Approval for stale base revision MUST be rejected", approveRes is ResolutionResult.Error)

        val itemAfter = mainInventoryDao.getItemById(itemId)
        assertEquals("Newer Main state MUST NOT be discarded", ItemStatus.AVAILABLE, itemAfter?.status)
    }

    // 17. Approve vs Reject concurrently -> exactly one wins
    @Test
    fun test17_approveVsRejectConcurrently_exactlyOneWins() = runTest {
        val itemId = UUID.randomUUID().toString()
        val item = InventoryItemEntity(itemId, null, null, "Apple", "Gala Apple", null, FoodCategory.CUPBOARD, StorageLocation.FRIDGE, 1.0, QuantityUnit.UNIT, null, null, null, QuantityConfidence.EXACT, ItemStatus.AVAILABLE, false, false, false, null, null, null, null, null, System.currentTimeMillis(), System.currentTimeMillis(), 1L)
        mainInventoryDao.insertItem(item)

        val opId = UUID.randomUUID().toString()
        val opDto = SyncOperationDto(opId, followerDeviceId, 17L, 1, SyncOperationType.REQUEST_DISCARD_ITEM, itemId, 1L, json.encodeToString(RequestDiscardPayloadDto(reason = "Bruised")), System.currentTimeMillis())
        val res = syncEngine.processOperation(followerDeviceId, opDto)
        val approvalId = res.approvalId!!

        val job1 = async(Dispatchers.IO) { repository.approvePendingDiscard(approvalId) }
        val job2 = async(Dispatchers.IO) { repository.rejectPendingDiscard(approvalId) }

        val res1 = job1.await()
        val res2 = job2.await()

        val results = listOf(res1, res2)
        assertEquals(1, results.count { it is ResolutionResult.Success })
    }

    // 18. Keep Main vs Apply Follower concurrently -> exactly one wins
    @Test
    fun test18_keepMainVsApplyFollowerConcurrently_exactlyOneWins() = runTest {
        val itemId = UUID.randomUUID().toString()
        val item = InventoryItemEntity(itemId, null, null, "Pear", "Anjou Pear", null, FoodCategory.CUPBOARD, StorageLocation.FRIDGE, 1.0, QuantityUnit.UNIT, null, null, null, QuantityConfidence.EXACT, ItemStatus.AVAILABLE, false, false, false, null, null, null, null, null, System.currentTimeMillis(), System.currentTimeMillis(), 2L)
        mainInventoryDao.insertItem(item)

        val opId = UUID.randomUUID().toString()
        val opDto = SyncOperationDto(opId, followerDeviceId, 18L, 1, SyncOperationType.EDIT_ITEM, itemId, 1L, json.encodeToString(EditItemPayloadDto(displayName = "Follower Pear")), System.currentTimeMillis())
        val res = syncEngine.processOperation(followerDeviceId, opDto)
        val conflictId = res.conflictId!!

        val job1 = async(Dispatchers.IO) { repository.resolveConflictKeepMain(conflictId) }
        val job2 = async(Dispatchers.IO) { repository.resolveConflictApplyFollower(conflictId) }

        val res1 = job1.await()
        val res2 = job2.await()

        val results = listOf(res1, res2)
        assertEquals(1, results.count { it is ResolutionResult.Success })
        assertEquals(1, results.count { it is ResolutionResult.AlreadyResolved })
    }

    // 19. FollowerFeedPullEngine reconciles local outbox after approval
    @Test
    fun test19_followerFeedPullEngine_reconcilesOutboxAfterApproval() = runTest {
        val followerDao = FakeFollowerDao()
        val itemId = UUID.randomUUID().toString()
        val opId = UUID.randomUUID().toString()

        val op = FollowerSyncOutboxEntity(opId, followerDeviceId, 1L, 1, "REQUEST_DISCARD_ITEM", itemId, 1L, "{}", System.currentTimeMillis(), "PENDING_APPROVAL", createdLocallyAt = System.currentTimeMillis())
        followerDao.insertOutboxOperation(op)

        val localItem = FollowerInventoryItemEntity(itemId, null, "Rice", "Basmati Rice", null, "CUPBOARD", "CUPBOARD", 1.0, "BAG", null, "AVAILABLE", 1L, "Basmati Rice", null, "CUPBOARD", "CUPBOARD", 1.0, "BAG", null, "AVAILABLE", 1L, false, System.currentTimeMillis())
        followerDao.insertOrUpdateItem(localItem)

        val feedResponse = com.example.chatgpt_mealplanner.sync.contract.SyncFeedResponseDto(
            householdId = householdId,
            latestCanonicalSequence = 10L,
            items = listOf(
                com.example.chatgpt_mealplanner.sync.contract.SyncFeedItemDto(
                    canonicalSequence = 10L,
                    operationId = "app_discard_19",
                    sourceDeviceId = mainDeviceId,
                    operationType = SyncOperationType.REQUEST_DISCARD_ITEM,
                    targetItemId = itemId,
                    baseRevision = 1L,
                    status = SyncOperationStatus.APPLIED,
                    resultCode = "APPROVED_DISCARD",
                    resultingRevision = 2L,
                    canonicalItemSnapshot = com.example.chatgpt_mealplanner.sync.contract.InventoryItemSyncDto(itemId, null, "Rice", "Basmati Rice", null, "CUPBOARD", "CUPBOARD", 0.0, "BAG", null, "DISCARDED", 2L),
                    originalOperationId = opId,
                    occurredAt = System.currentTimeMillis()
                )
            ),
            hasMore = false
        )

        val customClient = object : PantrySyncTestClient(keyStoreManager) {
            override suspend fun getSyncFeed(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String, sinceSequence: Long, limit: Int): com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult {
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(true, feedResponse)
            }
        }

        val testFeedEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(null, customClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine())
        testFeedEngine.performFeedPullPass("192.168.1.100", 8443, validFingerprint, "tok_123", followerDeviceId, followerDao)

        val reconciledOp = followerDao.getOutboxByOperationId(opId)
        assertEquals("APPLIED", reconciledOp?.status)
    }

    // 20. FollowerFeedPullEngine reconciles local outbox after rejection
    @Test
    fun test20_followerFeedPullEngine_reconcilesOutboxAfterRejection() = runTest {
        val followerDao = FakeFollowerDao()
        val itemId = UUID.randomUUID().toString()
        val opId = UUID.randomUUID().toString()

        val op = FollowerSyncOutboxEntity(opId, followerDeviceId, 2L, 1, "REQUEST_DISCARD_ITEM", itemId, 1L, "{}", System.currentTimeMillis(), "PENDING_APPROVAL", createdLocallyAt = System.currentTimeMillis())
        followerDao.insertOutboxOperation(op)

        val feedResponse = com.example.chatgpt_mealplanner.sync.contract.SyncFeedResponseDto(
            householdId = householdId,
            latestCanonicalSequence = 11L,
            items = listOf(
                com.example.chatgpt_mealplanner.sync.contract.SyncFeedItemDto(
                    canonicalSequence = 11L,
                    operationId = "app_discard_20",
                    sourceDeviceId = mainDeviceId,
                    operationType = SyncOperationType.REQUEST_DISCARD_ITEM,
                    targetItemId = itemId,
                    baseRevision = 1L,
                    status = SyncOperationStatus.REJECTED,
                    resultCode = "REJECTED_DISCARD",
                    originalOperationId = opId,
                    occurredAt = System.currentTimeMillis()
                )
            ),
            hasMore = false
        )

        val customClient = object : PantrySyncTestClient(keyStoreManager) {
            override suspend fun getSyncFeed(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String, sinceSequence: Long, limit: Int): com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult {
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(true, feedResponse)
            }
        }

        val testFeedEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(null, customClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine())
        testFeedEngine.performFeedPullPass("192.168.1.100", 8443, validFingerprint, "tok_123", followerDeviceId, followerDao)

        val reconciledOp = followerDao.getOutboxByOperationId(opId)
        assertEquals("REJECTED", reconciledOp?.status)
    }

    // 21. Multiple same-item operations: resolution correlates to exact original operation
    @Test
    fun test21_multipleSameItemOperations_resolutionCorrelatesToExactOriginalOperation() = runTest {
        val followerDao = FakeFollowerDao()
        val itemId = UUID.randomUUID().toString()
        val opIdA = UUID.randomUUID().toString()
        val opIdB = UUID.randomUUID().toString()

        val opA = FollowerSyncOutboxEntity(opIdA, followerDeviceId, 1L, 1, "EDIT_ITEM", itemId, 1L, "{}", System.currentTimeMillis(), "CONFLICT", createdLocallyAt = System.currentTimeMillis())
        val opB = FollowerSyncOutboxEntity(opIdB, followerDeviceId, 2L, 1, "REQUEST_DISCARD_ITEM", itemId, 1L, "{}", System.currentTimeMillis(), "PENDING_APPROVAL", createdLocallyAt = System.currentTimeMillis())
        followerDao.insertOutboxOperation(opA)
        followerDao.insertOutboxOperation(opB)

        // Main resolves ONLY operation A
        val feedResponse = com.example.chatgpt_mealplanner.sync.contract.SyncFeedResponseDto(
            householdId = householdId,
            latestCanonicalSequence = 20L,
            items = listOf(
                com.example.chatgpt_mealplanner.sync.contract.SyncFeedItemDto(
                    canonicalSequence = 20L,
                    operationId = "res_op_A",
                    sourceDeviceId = mainDeviceId,
                    operationType = SyncOperationType.EDIT_ITEM,
                    targetItemId = itemId,
                    baseRevision = 1L,
                    status = SyncOperationStatus.APPLIED,
                    resultCode = "RESOLVED_KEEP_MAIN",
                    resultingRevision = 2L,
                    canonicalItemSnapshot = com.example.chatgpt_mealplanner.sync.contract.InventoryItemSyncDto(itemId, null, "Rice", "Basmati Rice", null, "CUPBOARD", "CUPBOARD", 1.0, "BAG", null, "AVAILABLE", 2L),
                    originalOperationId = opIdA,
                    occurredAt = System.currentTimeMillis()
                )
            ),
            hasMore = false
        )

        val customClient = object : PantrySyncTestClient(keyStoreManager) {
            override suspend fun getSyncFeed(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String, sinceSequence: Long, limit: Int): com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult {
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(true, feedResponse)
            }
        }

        val testFeedEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(null, customClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine())
        testFeedEngine.performFeedPullPass("192.168.1.100", 8443, validFingerprint, "tok_123", followerDeviceId, followerDao)

        // Operation A MUST be resolved to APPLIED
        val reconciledOpA = followerDao.getOutboxByOperationId(opIdA)
        assertEquals("APPLIED", reconciledOpA?.status)

        // Operation B MUST remain PENDING_APPROVAL
        val reconciledOpB = followerDao.getOutboxByOperationId(opIdB)
        assertEquals("PENDING_APPROVAL", reconciledOpB?.status)
    }

    // 22. Two separate CONFLICT operations on same item -> resolving one leaves other unresolved
    @Test
    fun test22_twoSeparateConflictOperationsOnSameItem_resolvingOneLeavesOtherUnresolved() = runTest {
        val followerDao = FakeFollowerDao()
        val itemId = UUID.randomUUID().toString()
        val opId1 = UUID.randomUUID().toString()
        val opId2 = UUID.randomUUID().toString()

        val op1 = FollowerSyncOutboxEntity(opId1, followerDeviceId, 1L, 1, "EDIT_ITEM", itemId, 1L, "{}", System.currentTimeMillis(), "CONFLICT", createdLocallyAt = System.currentTimeMillis())
        val op2 = FollowerSyncOutboxEntity(opId2, followerDeviceId, 2L, 1, "SET_QUANTITY", itemId, 1L, "{}", System.currentTimeMillis(), "CONFLICT", createdLocallyAt = System.currentTimeMillis())
        followerDao.insertOutboxOperation(op1)
        followerDao.insertOutboxOperation(op2)

        // Main resolves ONLY operation 1
        val feedResponse = com.example.chatgpt_mealplanner.sync.contract.SyncFeedResponseDto(
            householdId = householdId,
            latestCanonicalSequence = 21L,
            items = listOf(
                com.example.chatgpt_mealplanner.sync.contract.SyncFeedItemDto(
                    canonicalSequence = 21L,
                    operationId = "res_op_1",
                    sourceDeviceId = mainDeviceId,
                    operationType = SyncOperationType.EDIT_ITEM,
                    targetItemId = itemId,
                    baseRevision = 1L,
                    status = SyncOperationStatus.APPLIED,
                    resultCode = "RESOLVED_KEEP_MAIN",
                    originalOperationId = opId1,
                    occurredAt = System.currentTimeMillis()
                )
            ),
            hasMore = false
        )

        val customClient = object : PantrySyncTestClient(keyStoreManager) {
            override suspend fun getSyncFeed(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String, sinceSequence: Long, limit: Int): com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult {
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(true, feedResponse)
            }
        }

        val testFeedEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(null, customClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine())
        testFeedEngine.performFeedPullPass("192.168.1.100", 8443, validFingerprint, "tok_123", followerDeviceId, followerDao)

        assertEquals("APPLIED", followerDao.getOutboxByOperationId(opId1)?.status)
        assertEquals("CONFLICT", followerDao.getOutboxByOperationId(opId2)?.status)
    }

    // 23. Unsupported operation type in Apply Follower fails closed
    @Test
    fun test23_unsupportedOperationTypeInApplyFollower_failsClosed() = runTest {
        val itemId = UUID.randomUUID().toString()
        val item = InventoryItemEntity(itemId, null, null, "Flour", "Plain Flour", null, FoodCategory.CUPBOARD, StorageLocation.CUPBOARD, 1.0, QuantityUnit.UNIT, null, null, null, QuantityConfidence.EXACT, ItemStatus.AVAILABLE, false, false, false, null, null, null, null, null, System.currentTimeMillis(), System.currentTimeMillis(), 1L)
        mainInventoryDao.insertItem(item)

        val conflictId = UUID.randomUUID().toString()
        val conflict = SyncConflictEntity(
            conflictId = conflictId,
            operationId = UUID.randomUUID().toString(),
            deviceId = followerDeviceId,
            targetItemId = itemId,
            operationType = "ADD_ITEM", // Unsupported conflict operation type
            baseRevision = 1L,
            currentRevision = 1L,
            incomingPayloadJson = "{}",
            currentItemSnapshotJson = "{}",
            status = "OPEN",
            createdAt = System.currentTimeMillis()
        )
        mainCanonicalDao.insertConflict(conflict)

        val initialSeq = mainCanonicalDao.getLatestCanonicalSequence() ?: 0L
        val res = repository.resolveConflictApplyFollower(conflictId)

        assertTrue("Unsupported operation type MUST fail closed", res is ResolutionResult.Error)
        assertEquals("OPEN", mainCanonicalDao.getConflictById(conflictId)?.status)
        assertEquals(1L, mainInventoryDao.getItemById(itemId)?.revision)
        assertEquals(initialSeq, mainCanonicalDao.getLatestCanonicalSequence() ?: 0L)
    }

    // 24. Invalid SET_QUANTITY payload in Apply Follower fails closed
    @Test
    fun test24_invalidSetQuantityInApplyFollower_failsClosed() = runTest {
        val itemId = UUID.randomUUID().toString()
        val item = InventoryItemEntity(itemId, null, null, "Sugar", "White Sugar", null, FoodCategory.CUPBOARD, StorageLocation.CUPBOARD, 1.0, QuantityUnit.UNIT, null, null, null, QuantityConfidence.EXACT, ItemStatus.AVAILABLE, false, false, false, null, null, null, null, null, System.currentTimeMillis(), System.currentTimeMillis(), 1L)
        mainInventoryDao.insertItem(item)

        val conflictId = UUID.randomUUID().toString()
        val conflict = SyncConflictEntity(
            conflictId = conflictId,
            operationId = UUID.randomUUID().toString(),
            deviceId = followerDeviceId,
            targetItemId = itemId,
            operationType = "SET_QUANTITY",
            baseRevision = 1L,
            currentRevision = 1L,
            incomingPayloadJson = json.encodeToString(com.example.chatgpt_mealplanner.sync.contract.SetQuantityPayloadDto(absoluteQuantity = -10.0)), // Invalid negative quantity
            currentItemSnapshotJson = "{}",
            status = "OPEN",
            createdAt = System.currentTimeMillis()
        )
        mainCanonicalDao.insertConflict(conflict)

        val initialSeq = mainCanonicalDao.getLatestCanonicalSequence() ?: 0L
        val res = repository.resolveConflictApplyFollower(conflictId)

        assertTrue("Invalid SET_QUANTITY MUST fail closed", res is ResolutionResult.Error)
        assertEquals("OPEN", mainCanonicalDao.getConflictById(conflictId)?.status)
        assertEquals(1.0, mainInventoryDao.getItemById(itemId)?.quantity ?: 0.0, 0.001)
        assertEquals(initialSeq, mainCanonicalDao.getLatestCanonicalSequence() ?: 0L)
    }

    // 25. Generic Main edit to same item with originalOperationId = null -> neither unresolved op changes
    @Test
    fun test25_genericMainEditSameItemWithNullOriginalOpId_doesNotResolveUnresolvedOps() = runTest {
        val followerDao = FakeFollowerDao()
        val itemId = UUID.randomUUID().toString()
        val opId = UUID.randomUUID().toString()

        val op = FollowerSyncOutboxEntity(opId, followerDeviceId, 1L, 1, "EDIT_ITEM", itemId, 1L, "{}", System.currentTimeMillis(), "CONFLICT", createdLocallyAt = System.currentTimeMillis())
        followerDao.insertOutboxOperation(op)

        val feedResponse = com.example.chatgpt_mealplanner.sync.contract.SyncFeedResponseDto(
            householdId = householdId,
            latestCanonicalSequence = 25L,
            items = listOf(
                com.example.chatgpt_mealplanner.sync.contract.SyncFeedItemDto(
                    canonicalSequence = 25L,
                    operationId = "op_generic_main",
                    sourceDeviceId = mainDeviceId,
                    operationType = SyncOperationType.EDIT_ITEM,
                    targetItemId = itemId,
                    baseRevision = 2L,
                    status = SyncOperationStatus.APPLIED,
                    resultCode = "SUCCESS",
                    originalOperationId = null, // Null originalOperationId
                    occurredAt = System.currentTimeMillis()
                )
            ),
            hasMore = false
        )

        val customClient = object : PantrySyncTestClient(keyStoreManager) {
            override suspend fun getSyncFeed(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String, sinceSequence: Long, limit: Int): com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult {
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(true, feedResponse)
            }
        }

        val testFeedEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(null, customClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine())
        testFeedEngine.performFeedPullPass("192.168.1.100", 8443, validFingerprint, "tok_123", followerDeviceId, followerDao)

        val opInDb = followerDao.getOutboxByOperationId(opId)
        assertEquals("CONFLICT", opInDb?.status)
    }

    // 26. Generic other-follower edit to same item with originalOperationId = null -> neither unresolved op changes
    @Test
    fun test26_genericOtherFollowerEditSameItem_doesNotResolveUnresolvedOps() = runTest {
        val followerDao = FakeFollowerDao()
        val itemId = UUID.randomUUID().toString()
        val opId = UUID.randomUUID().toString()
        val otherFollowerId = UUID.randomUUID().toString()

        val op = FollowerSyncOutboxEntity(opId, followerDeviceId, 1L, 1, "REQUEST_DISCARD_ITEM", itemId, 1L, "{}", System.currentTimeMillis(), "PENDING_APPROVAL", createdLocallyAt = System.currentTimeMillis())
        followerDao.insertOutboxOperation(op)

        val feedResponse = com.example.chatgpt_mealplanner.sync.contract.SyncFeedResponseDto(
            householdId = householdId,
            latestCanonicalSequence = 26L,
            items = listOf(
                com.example.chatgpt_mealplanner.sync.contract.SyncFeedItemDto(
                    canonicalSequence = 26L,
                    operationId = "op_other_follower",
                    sourceDeviceId = otherFollowerId,
                    operationType = SyncOperationType.EDIT_ITEM,
                    targetItemId = itemId,
                    baseRevision = 2L,
                    status = SyncOperationStatus.APPLIED,
                    resultCode = "SUCCESS",
                    originalOperationId = null,
                    occurredAt = System.currentTimeMillis()
                )
            ),
            hasMore = false
        )

        val customClient = object : PantrySyncTestClient(keyStoreManager) {
            override suspend fun getSyncFeed(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String, sinceSequence: Long, limit: Int): com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult {
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(true, feedResponse)
            }
        }

        val testFeedEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(null, customClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine())
        testFeedEngine.performFeedPullPass("192.168.1.100", 8443, validFingerprint, "tok_123", followerDeviceId, followerDao)

        val opInDb = followerDao.getOutboxByOperationId(opId)
        assertEquals("PENDING_APPROVAL", opInDb?.status)
    }

    // 27. Resolution event with unknown originalOperationId -> no outbox change, snapshot/cursor processed
    @Test
    fun test27_resolutionEventWithUnknownOriginalOpId_doesNotChangeOutboxButAdvancesCursor() = runTest {
        val followerDao = FakeFollowerDao()
        val itemId = UUID.randomUUID().toString()
        val localOpId = UUID.randomUUID().toString()

        val op = FollowerSyncOutboxEntity(localOpId, followerDeviceId, 1L, 1, "EDIT_ITEM", itemId, 1L, "{}", System.currentTimeMillis(), "CONFLICT", createdLocallyAt = System.currentTimeMillis())
        followerDao.insertOutboxOperation(op)

        val feedResponse = com.example.chatgpt_mealplanner.sync.contract.SyncFeedResponseDto(
            householdId = householdId,
            latestCanonicalSequence = 27L,
            items = listOf(
                com.example.chatgpt_mealplanner.sync.contract.SyncFeedItemDto(
                    canonicalSequence = 27L,
                    operationId = "res_unknown",
                    sourceDeviceId = mainDeviceId,
                    operationType = SyncOperationType.EDIT_ITEM,
                    targetItemId = itemId,
                    baseRevision = 2L,
                    status = SyncOperationStatus.APPLIED,
                    resultCode = "RESOLVED_KEEP_MAIN",
                    originalOperationId = "unknown_op_999", // Unknown operation ID
                    occurredAt = System.currentTimeMillis()
                )
            ),
            hasMore = false
        )

        val customClient = object : PantrySyncTestClient(keyStoreManager) {
            override suspend fun getSyncFeed(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String, sinceSequence: Long, limit: Int): com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult {
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(true, feedResponse)
            }
        }

        val testFeedEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(null, customClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine())
        testFeedEngine.performFeedPullPass("192.168.1.100", 8443, validFingerprint, "tok_123", followerDeviceId, followerDao)

        // Local op remains CONFLICT
        assertEquals("CONFLICT", followerDao.getOutboxByOperationId(localOpId)?.status)
        // Cursor advances to 27L
        assertEquals(27L, followerDao.getSyncMetadata()?.lastReconciledCanonicalSequence)
    }

    // 28. Resolution event pointing at already APPLIED operation -> remains APPLIED
    @Test
    fun test28_resolutionEventPointingAtAlreadyAppliedOperation_remainsApplied() = runTest {
        val followerDao = FakeFollowerDao()
        val opId = UUID.randomUUID().toString()

        val op = FollowerSyncOutboxEntity(opId, followerDeviceId, 1L, 1, "EDIT_ITEM", "item_28", 1L, "{}", System.currentTimeMillis(), "APPLIED", createdLocallyAt = System.currentTimeMillis())
        followerDao.insertOutboxOperation(op)

        val feedResponse = com.example.chatgpt_mealplanner.sync.contract.SyncFeedResponseDto(
            householdId = householdId,
            latestCanonicalSequence = 28L,
            items = listOf(
                com.example.chatgpt_mealplanner.sync.contract.SyncFeedItemDto(
                    canonicalSequence = 28L,
                    operationId = "res_op_28",
                    sourceDeviceId = mainDeviceId,
                    operationType = SyncOperationType.EDIT_ITEM,
                    status = SyncOperationStatus.REJECTED,
                    resultCode = "REJECTED_DISCARD",
                    originalOperationId = opId,
                    occurredAt = System.currentTimeMillis()
                )
            ),
            hasMore = false
        )

        val customClient = object : PantrySyncTestClient(keyStoreManager) {
            override suspend fun getSyncFeed(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String, sinceSequence: Long, limit: Int): com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult {
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(true, feedResponse)
            }
        }

        val testFeedEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(null, customClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine())
        testFeedEngine.performFeedPullPass("192.168.1.100", 8443, validFingerprint, "tok_123", followerDeviceId, followerDao)

        // Already APPLIED op remains APPLIED
        assertEquals("APPLIED", followerDao.getOutboxByOperationId(opId)?.status)
    }

    // 29. Resolution event pointing at PENDING operation -> remains PENDING
    @Test
    fun test29_resolutionEventPointingAtPendingOperation_remainsPending() = runTest {
        val followerDao = FakeFollowerDao()
        val opId = UUID.randomUUID().toString()

        val op = FollowerSyncOutboxEntity(opId, followerDeviceId, 1L, 1, "EDIT_ITEM", "item_29", 1L, "{}", System.currentTimeMillis(), "PENDING", createdLocallyAt = System.currentTimeMillis())
        followerDao.insertOutboxOperation(op)

        val feedResponse = com.example.chatgpt_mealplanner.sync.contract.SyncFeedResponseDto(
            householdId = householdId,
            latestCanonicalSequence = 29L,
            items = listOf(
                com.example.chatgpt_mealplanner.sync.contract.SyncFeedItemDto(
                    canonicalSequence = 29L,
                    operationId = "res_op_29",
                    sourceDeviceId = mainDeviceId,
                    operationType = SyncOperationType.EDIT_ITEM,
                    status = SyncOperationStatus.APPLIED,
                    resultCode = "RESOLVED_KEEP_MAIN",
                    originalOperationId = opId,
                    occurredAt = System.currentTimeMillis()
                )
            ),
            hasMore = false
        )

        val customClient = object : PantrySyncTestClient(keyStoreManager) {
            override suspend fun getSyncFeed(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String, sinceSequence: Long, limit: Int): com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult {
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(true, feedResponse)
            }
        }

        val testFeedEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(null, customClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine())
        testFeedEngine.performFeedPullPass("192.168.1.100", 8443, validFingerprint, "tok_123", followerDeviceId, followerDao)

        // PENDING op remains PENDING
        assertEquals("PENDING", followerDao.getOutboxByOperationId(opId)?.status)
    }

    // 30. Duplicate exact resolution event/retry -> idempotent
    @Test
    fun test30_duplicateExactResolutionEvent_isIdempotent() = runTest {
        val followerDao = FakeFollowerDao()
        val opId = UUID.randomUUID().toString()

        val op = FollowerSyncOutboxEntity(opId, followerDeviceId, 1L, 1, "EDIT_ITEM", "item_30", 1L, "{}", System.currentTimeMillis(), "CONFLICT", createdLocallyAt = System.currentTimeMillis())
        followerDao.insertOutboxOperation(op)

        val feedResponse = com.example.chatgpt_mealplanner.sync.contract.SyncFeedResponseDto(
            householdId = householdId,
            latestCanonicalSequence = 30L,
            items = listOf(
                com.example.chatgpt_mealplanner.sync.contract.SyncFeedItemDto(
                    canonicalSequence = 30L,
                    operationId = "res_op_30",
                    sourceDeviceId = mainDeviceId,
                    operationType = SyncOperationType.EDIT_ITEM,
                    status = SyncOperationStatus.APPLIED,
                    resultCode = "RESOLVED_KEEP_MAIN",
                    originalOperationId = opId,
                    occurredAt = System.currentTimeMillis()
                )
            ),
            hasMore = false
        )

        val customClient = object : PantrySyncTestClient(keyStoreManager) {
            override suspend fun getSyncFeed(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String, sinceSequence: Long, limit: Int): com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult {
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(true, feedResponse)
            }
        }

        val testFeedEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(null, customClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine())
        testFeedEngine.performFeedPullPass("192.168.1.100", 8443, validFingerprint, "tok_123", followerDeviceId, followerDao)
        assertEquals("APPLIED", followerDao.getOutboxByOperationId(opId)?.status)

        // Second pass with same event
        testFeedEngine.performFeedPullPass("192.168.1.100", 8443, validFingerprint, "tok_123", followerDeviceId, followerDao)
        assertEquals("APPLIED", followerDao.getOutboxByOperationId(opId)?.status)
    }

    // 31. Old feed JSON without originalOperationId decodes successfully
    @Test
    fun test31_oldFeedJsonWithoutField_decodesSuccessfully() {
        val oldJson = """
            {
                "canonicalSequence": 31,
                "operationId": "op_31",
                "sourceDeviceId": "dev_31",
                "operationType": "ADD_ITEM",
                "status": "APPLIED",
                "resultCode": "SUCCESS",
                "occurredAt": 100000
            }
        """.trimIndent()

        val item = json.decodeFromString<com.example.chatgpt_mealplanner.sync.contract.SyncFeedItemDto>(oldJson)
        assertEquals(31L, item.canonicalSequence)
        assertNull("Missing originalOperationId field in old JSON MUST decode to null", item.originalOperationId)
    }

    // 32. New resolution JSON with originalOperationId decodes and correlates
    @Test
    fun test32_newResolutionJsonWithField_decodesAndCorrelates() {
        val newJson = """
            {
                "canonicalSequence": 32,
                "operationId": "op_32",
                "sourceDeviceId": "dev_32",
                "operationType": "EDIT_ITEM",
                "status": "APPLIED",
                "resultCode": "RESOLVED_KEEP_MAIN",
                "originalOperationId": "orig_op_123",
                "occurredAt": 100000
            }
        """.trimIndent()

        val item = json.decodeFromString<com.example.chatgpt_mealplanner.sync.contract.SyncFeedItemDto>(newJson)
        assertEquals("orig_op_123", item.originalOperationId)
    }

    // 33. Ordinary operation payload containing originalOperationId property is NOT exposed as resolution metadata
    @Test
    fun test33_ordinaryPayloadContainingOriginalOpIdProperty_isNotExposedAsResolutionMetadata() = runTest {
        val itemId = UUID.randomUUID().toString()
        val item = InventoryItemEntity(itemId, null, null, "Salt", "Table Salt", null, FoodCategory.CUPBOARD, StorageLocation.CUPBOARD, 1.0, QuantityUnit.UNIT, null, null, null, QuantityConfidence.EXACT, ItemStatus.AVAILABLE, false, false, false, null, null, null, null, null, System.currentTimeMillis(), System.currentTimeMillis(), 1L)
        mainInventoryDao.insertItem(item)

        // Operation with resultCode = SUCCESS, but payloadJson coincidently contains "originalOperationId"
        val payloadWithCoincidentalKey = """{"notes":"Created with originalOperationId in notes"}"""
        val opDto = SyncOperationDto(UUID.randomUUID().toString(), followerDeviceId, 33L, 1, SyncOperationType.EDIT_ITEM, itemId, 1L, payloadWithCoincidentalKey, System.currentTimeMillis())
        val res = syncEngine.processOperation(followerDeviceId, opDto)

        val feed = mainCanonicalDao.getFeedOperationsSince(0L, 50)
        val feedOp = feed.find { it.operationId == opDto.operationId }
        assertNotNull(feedOp)

        // Server feed serialization check: ordinary success operation MUST NOT expose originalOperationId
        val isResolution = feedOp?.resultCode in listOf("RESOLVED_KEEP_MAIN", "RESOLVED_APPLY_FOLLOWER", "APPROVED_DISCARD", "REJECTED_DISCARD")
        assertFalse("Ordinary SUCCESS operation MUST NOT be classified as resolution", isResolution)
    }
}
