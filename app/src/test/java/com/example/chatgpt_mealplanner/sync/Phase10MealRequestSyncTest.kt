package com.example.chatgpt_mealplanner.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.chatgpt_mealplanner.core.database.AppDatabase
import com.example.chatgpt_mealplanner.core.database.RoomTransactionRunner
import com.example.chatgpt_mealplanner.core.database.entity.PairedDeviceEntity
import com.example.chatgpt_mealplanner.core.database.entity.MealChangeRequestEntity
import com.example.chatgpt_mealplanner.core.database.entity.toEntity
import com.example.chatgpt_mealplanner.core.service.MealDomainServiceImpl
import com.example.chatgpt_mealplanner.core.sync.HouseholdIdentityManager
import com.example.chatgpt_mealplanner.core.model.Meal
import com.example.chatgpt_mealplanner.core.repository.MealChangeRequestRepositoryImpl
import com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
import com.example.chatgpt_mealplanner.core.sync.engine.MainCanonicalSyncEngine
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerMealRequestProjector
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerMealRequestEntity
import com.example.chatgpt_mealplanner.sync.contract.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
// Keep the test runtime at or above the app minSdk (30), so package parsing succeeds.
@Config(sdk = [30], application = android.app.Application::class)
class Phase10MealRequestSyncTest {
    private lateinit var main: AppDatabase
    private lateinit var follower: FollowerDatabase
    private lateinit var local: FollowerLocalSyncEngine
    private val deviceId = UUID.randomUUID().toString()
    private val requestId = "req_${UUID.randomUUID()}"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        main = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        follower = Room.inMemoryDatabaseBuilder(context, FollowerDatabase::class.java).allowMainThreadQueries().build()
        local = FollowerLocalSyncEngine(follower, null, null, follower.mealRequestDao())
    }

    @After
    fun tearDown() { main.close(); follower.close() }

    private fun event(sequence: Long, type: SyncOperationType, payload: String, source: String = "main_device") =
        SyncFeedItemDto(sequence, UUID.randomUUID().toString(), source, type,
            payloadJson = payload, status = SyncOperationStatus.APPLIED, resultCode = "SUCCESS", occurredAt = 10L)

    private suspend fun queue() = local.recordLocalMealChangeRequest(deviceId, requestId, "meal_1", "Change dinner")

    private suspend fun assertFails(block: suspend () -> Unit) {
        try { block(); fail("Expected operation to fail") } catch (expected: Exception) { /* expected */ }
    }

    @Test
    fun deletingMainMealPublishesExactLinkedRequestDeletionsAndFollowerReplayIsIdempotent() = runBlocking {
        val mealId = "meal_delete"
        main.mealDao().insertMeal(Meal(mealId, "2026-10-18", title = "Dinner").toEntity())
        val linkedRequestId = "req_${UUID.randomUUID()}"
        val unrelatedRequestId = "req_${UUID.randomUUID()}"
        main.mealChangeRequestDao().insertRequest(
            MealChangeRequestEntity(linkedRequestId, mealId, deviceId, "Change dinner", "PENDING", null, 10L, 10L)
        )

        val pairingStore = FollowerPairingStore(null)
        val identityManager = HouseholdIdentityManager(main.householdIdentityDao())
        identityManager.getOrCreateHouseholdIdentity()
        val roleResolver = AppRoleResolver(pairingStore, main.householdIdentityDao())
        val service = MealDomainServiceImpl(
            main.mealDao(), main.inventoryDao(), main.transactionDao(), main.leftoverDao(),
            RoomTransactionRunner(main), roleResolver, main.canonicalSyncDao(), identityManager,
            main.mealChangeRequestDao()
        )

        val result = service.deletePlannedMeal(mealId)
        assertTrue("Main meal deletion should succeed", result is com.example.chatgpt_mealplanner.core.model.DomainResult.Success)
        assertNull(main.mealDao().getMealWithIngredients(mealId))
        assertTrue(main.mealChangeRequestDao().getAllRequestsSync().isEmpty())

        val deletionEvent = main.canonicalSyncDao().getFeedOperationsSince(0L, 10).single()
        assertEquals(SyncOperationType.UPDATE_MEAL_CHANGE_REQUEST_STATUS.name, deletionEvent.operationType)
        val deletionPayload = Json.decodeFromString<UpdateMealChangeRequestStatusPayloadDto>(deletionEvent.payloadJson)
        assertEquals(linkedRequestId, deletionPayload.requestId)
        assertEquals("DELETED", deletionPayload.status)

        follower.mealRequestDao().upsert(
            FollowerMealRequestEntity(linkedRequestId, mealId, deviceId, "Change dinner", "PENDING", null, 10L, 10L, 1L)
        )
        follower.mealRequestDao().upsert(
            FollowerMealRequestEntity(unrelatedRequestId, "another_meal", deviceId, "Keep this request", "PENDING", null, 11L, 11L, 1L)
        )
        val feedEvent = SyncFeedItemDto(
            canonicalSequence = deletionEvent.canonicalSequence!!,
            operationId = deletionEvent.operationId,
            sourceDeviceId = deletionEvent.deviceId,
            operationType = SyncOperationType.valueOf(deletionEvent.operationType),
            payloadJson = deletionEvent.payloadJson,
            status = SyncOperationStatus.valueOf(deletionEvent.status),
            resultCode = deletionEvent.resultCode,
            occurredAt = deletionEvent.createdAt
        )
        FollowerMealRequestProjector.apply(follower.mealRequestDao(), feedEvent)
        FollowerMealRequestProjector.apply(follower.mealRequestDao(), feedEvent)

        assertNull("Only the linked request should be removed", follower.mealRequestDao().getById(linkedRequestId))
        assertNotNull("Unrelated request must survive", follower.mealRequestDao().getById(unrelatedRequestId))
    }

    @Test
    fun mainCannotCreateMealChangeRequests() = runBlocking {
        val store = FollowerPairingStore(null)
        val repository = MealChangeRequestRepositoryImpl(main.mealChangeRequestDao(), store,
            AppRoleResolver(store), main.canonicalSyncDao(), RoomTransactionRunner(main), follower.mealRequestDao(), local)
        assertFails { repository.submitRequest("meal_1", "Change dinner") }
        assertTrue(main.mealChangeRequestDao().getAllRequestsSync().isEmpty())
        assertTrue(follower.mealRequestDao().observeAllRequests().first().isEmpty())
        assertTrue(follower.followerDao().getAllOutboxOps().isEmpty())
        assertNull(follower.followerDao().getSequenceEntity())
        assertNull(main.canonicalSyncDao().getSequenceEntity())
    }

    @Test
    fun requestRoundTrip_mainPersistsOnceAndFollowerReceivesDecision() = runBlocking {
        main.mealDao().insertMeal(Meal("meal_1", "2026-10-18", title = "Dinner").toEntity())
        main.pairedDeviceDao().insertOrUpdateDevice(PairedDeviceEntity(deviceId, "Follower", "key", "ACTIVE", 1L, 1L))
        val op = queue()
        assertEquals("QUEUED", follower.mealRequestDao().getById(requestId)?.status)
        val engine = MainCanonicalSyncEngine(main.canonicalSyncDao(), main.pairedDeviceDao(),
            main.inventoryDao(), RoomTransactionRunner(main), main.mealChangeRequestDao(), main.mealDao())
        val dto = SyncOperationDto(op.operationId, op.deviceId, op.deviceSequence,
            operationType = SyncOperationType.CREATE_MEAL_CHANGE_REQUEST, payloadJson = op.payloadJson, createdAt = op.createdAt)
        val accepted = engine.processOperation(deviceId, dto)
        assertEquals(SyncOperationStatus.APPLIED, accepted.status)
        val retry = engine.processOperation(deviceId, dto)
        assertEquals(accepted.canonicalSequence, retry.canonicalSequence)
        assertEquals(1, main.mealChangeRequestDao().getAllRequestsSync().size)
        local.reconcileMealRequestUpload(op, accepted)
        assertEquals("PENDING", follower.mealRequestDao().getById(requestId)?.status)

        val store = FollowerPairingStore(null)
        val repository = MealChangeRequestRepositoryImpl(main.mealChangeRequestDao(), store,
            AppRoleResolver(store), main.canonicalSyncDao(), RoomTransactionRunner(main), follower.mealRequestDao(), local)
        main.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_meal_decision BEFORE UPDATE ON meal_change_requests
            BEGIN SELECT RAISE(ABORT, 'injected status failure'); END""")
        assertFails { repository.updateRequestStatus(requestId, "ACCEPTED", "Agreed") }
        assertEquals("PENDING", main.mealChangeRequestDao().getRequestById(requestId)?.status)
        assertTrue(main.canonicalSyncDao().getFeedOperationsSince(accepted.canonicalSequence!!, 10).isEmpty())
        main.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_meal_decision")
        repository.updateRequestStatus(requestId, "ACCEPTED", "Agreed")
        val decision = main.canonicalSyncDao().getFeedOperationsSince(accepted.canonicalSequence!!, 10).single()
        val client = FakeFeedSyncTestClient()
        client.feedResponseToReturn = SyncFeedResponseDto("home", decision.canonicalSequence,
            listOf(SyncFeedItemDto(decision.canonicalSequence, decision.operationId, decision.deviceId,
                SyncOperationType.valueOf(decision.operationType), payloadJson = decision.payloadJson,
                status = SyncOperationStatus.APPLIED, resultCode = decision.resultCode, occurredAt = decision.createdAt)), false)
        val pull = FollowerFeedPullEngine(follower, client, local, follower.mealRequestDao())
        pull.performFeedPullPass("localhost", pinnedCertFingerprint = null, sessionToken = "token", followerDeviceId = deviceId)
        assertEquals("ACCEPTED", follower.mealRequestDao().getById(requestId)?.status)
        assertEquals("Agreed", follower.mealRequestDao().getById(requestId)?.mainResponse)
        assertEquals(decision.canonicalSequence, follower.followerDao().getSyncMetadata()?.lastReconciledCanonicalSequence)
        // Lost upload acknowledgement replay must not revert Main's later decision.
        local.reconcileMealRequestUpload(op, accepted)
        assertEquals("ACCEPTED", follower.mealRequestDao().getById(requestId)?.status)
        assertTrue(follower.followerDao().getAllItems().isEmpty())
        assertTrue(main.inventoryDao().getAllItemsSync().isEmpty())
    }

    @Test
    fun discussionThenDecision_syncsInOrderAndTerminalDecisionCannotBeChanged() = runBlocking {
        main.mealDao().insertMeal(Meal("meal_1", "2026-10-18", title = "Dinner").toEntity())
        main.pairedDeviceDao().insertOrUpdateDevice(PairedDeviceEntity(deviceId, "Follower", "key", "ACTIVE", 1L, 1L))
        val op = queue()
        val engine = MainCanonicalSyncEngine(main.canonicalSyncDao(), main.pairedDeviceDao(),
            main.inventoryDao(), RoomTransactionRunner(main), main.mealChangeRequestDao(), main.mealDao())
        val accepted = engine.processOperation(deviceId, SyncOperationDto(op.operationId, deviceId, op.deviceSequence,
            operationType = SyncOperationType.CREATE_MEAL_CHANGE_REQUEST, payloadJson = op.payloadJson, createdAt = op.createdAt))
        local.reconcileMealRequestUpload(op, accepted)
        val store = FollowerPairingStore(null)
        val repository = MealChangeRequestRepositoryImpl(main.mealChangeRequestDao(), store,
            AppRoleResolver(store), main.canonicalSyncDao(), RoomTransactionRunner(main), follower.mealRequestDao(), local)
        val client = FakeFeedSyncTestClient()
        val pull = FollowerFeedPullEngine(follower, client, local, follower.mealRequestDao())
        suspend fun pullDecisions() {
            val since = follower.followerDao().getSyncMetadata()?.lastReconciledCanonicalSequence ?: 0L
            val operations = main.canonicalSyncDao().getFeedOperationsSince(since, 10)
            client.feedResponseToReturn = SyncFeedResponseDto("home", operations.last().canonicalSequence,
                operations.map { entry -> SyncFeedItemDto(entry.canonicalSequence, entry.operationId, entry.deviceId,
                    SyncOperationType.valueOf(entry.operationType), payloadJson = entry.payloadJson,
                    status = SyncOperationStatus.APPLIED, resultCode = entry.resultCode, occurredAt = entry.createdAt) }, false)
            pull.performFeedPullPass("localhost", pinnedCertFingerprint = null, sessionToken = "token", followerDeviceId = deviceId)
        }
        repository.updateRequestStatus(requestId, "DISCUSSED", "Try another ingredient?")
        pullDecisions()
        assertEquals("DISCUSSED", follower.mealRequestDao().getById(requestId)?.status)
        assertEquals("Try another ingredient?", follower.mealRequestDao().getById(requestId)?.mainResponse)
        repository.updateRequestStatus(requestId, "ACCEPTED", "Use beans")
        pullDecisions()
        val finalRequest = follower.mealRequestDao().getById(requestId)
        assertEquals("ACCEPTED", finalRequest?.status)
        assertEquals("Use beans", finalRequest?.mainResponse)
        val sequence = main.canonicalSyncDao().getSequenceEntity()
        assertFails { repository.updateRequestStatus(requestId, "DECLINED", "Too late") }
        assertEquals(sequence, main.canonicalSyncDao().getSequenceEntity())
        assertTrue(main.canonicalSyncDao().getFeedOperationsSince(finalRequest!!.canonicalSequence, 10).isEmpty())
        assertEquals("ACCEPTED", main.mealChangeRequestDao().getRequestById(requestId)?.status)
        local.reconcileMealRequestUpload(op, accepted)
        assertEquals(finalRequest, follower.mealRequestDao().getById(requestId))
    }

    @Test
    fun duplicateQueue_rollsBackOutboxAndDeviceSequence() = runBlocking {
        queue()
        assertFails { queue() }
        assertEquals(1, follower.followerDao().getAllOutboxOps().size)
        assertEquals(2L, follower.followerDao().getSequenceEntity()?.nextSequence)
    }

    @Test
    fun followerRepository_queuesAndObservesRequest_withoutWritingMainOrAllowingDecisions() = runBlocking {
        val store = object : FollowerPairingStore(null) {
            override fun isPaired() = true
            override fun getPairingConfig() = FollowerPairingConfig("localhost", pinnedCertFingerprint = "pin", deviceId = deviceId)
        }
        val repository = MealChangeRequestRepositoryImpl(main.mealChangeRequestDao(), store,
            AppRoleResolver(store), main.canonicalSyncDao(), RoomTransactionRunner(main), follower.mealRequestDao(), local)
        repository.submitRequest("meal_1", "Please swap dinner")
        val request = repository.observeRequestsForMeal("meal_1").first().single()
        assertEquals("QUEUED", request.status)
        assertEquals(deviceId, request.authorDeviceId)
        assertEquals(1, follower.followerDao().getPendingOperationsInOrder().size)
        assertTrue(main.mealChangeRequestDao().getAllRequestsSync().isEmpty())
        assertFails { repository.updateRequestStatus(request.id, "ACCEPTED", null) }
        assertEquals("QUEUED", follower.mealRequestDao().getById(request.id)?.status)
    }

    @Test
    fun migration4To5_preservesInventoryOutboxSequenceAndCursor() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "phase10_migration_${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, FollowerDatabase::class.java, name)
            .allowMainThreadQueries()
            .addMigrations(com.example.chatgpt_mealplanner.core.sync.follower.database.FOLLOWER_MIGRATION_4_5,
                com.example.chatgpt_mealplanner.core.sync.follower.database.FOLLOWER_MIGRATION_5_6,
                com.example.chatgpt_mealplanner.core.sync.follower.database.FOLLOWER_MIGRATION_6_7).build()
        var disk = open()
        try {
            val diskLocal = FollowerLocalSyncEngine(disk, null, null, disk.mealRequestDao())
            val op = diskLocal.recordLocalAddItem(deviceId, "Milk", "Milk", "FRESH_PANTRY", "FRIDGE", 2.0, "LITRE", null, null)
            disk.followerDao().updateSyncMetadata(FollowerSyncMetadataEntity(1, 19L))
            val before = disk.followerDao().getItemById(op.targetItemId!!)
            disk.close()
            // Remove the v5 addition to recreate the v4 table layout, then exercise
            // Room's migration and generated schema validation on reopen.
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, 0).use { raw ->
                raw.execSQL("DROP TABLE follower_meal_requests")
                raw.version = 4
            }
            disk = open()
            assertEquals(before, disk.followerDao().getItemById(op.targetItemId!!))
            assertEquals(op, disk.followerDao().getOutboxByOperationId(op.operationId))
            assertEquals(2L, disk.followerDao().getSequenceEntity()?.nextSequence)
            assertEquals(19L, disk.followerDao().getSyncMetadata()?.lastReconciledCanonicalSequence)
            assertTrue(disk.mealRequestDao().observeAllRequests().first().isEmpty())
        } finally {
            disk.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun migration4To5_legacyMealOutboxReconcilesAcceptedAndRejectedRequests() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "phase10_legacy_outbox_${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, FollowerDatabase::class.java, name)
            .allowMainThreadQueries()
            .addMigrations(com.example.chatgpt_mealplanner.core.sync.follower.database.FOLLOWER_MIGRATION_4_5,
                com.example.chatgpt_mealplanner.core.sync.follower.database.FOLLOWER_MIGRATION_5_6, com.example.chatgpt_mealplanner.core.sync.follower.database.FOLLOWER_MIGRATION_6_7).build()
        var disk = open()
        try {
            val beforeUpgrade = FollowerLocalSyncEngine(disk, null, null, disk.mealRequestDao())
            val inventory = beforeUpgrade.recordLocalAddItem(deviceId, "Milk", "Milk", "FRESH_PANTRY", "FRIDGE", 2.0, "LITRE", null, null)
            val beforeItem = disk.followerDao().getItemById(inventory.targetItemId!!)
            val accepted = beforeUpgrade.recordLocalMealChangeRequest(deviceId, requestId, "meal_1", "Change dinner")
            val rejectedId = "req_${UUID.randomUUID()}"
            val rejected = beforeUpgrade.recordLocalMealChangeRequest(deviceId, rejectedId, "missing_meal", "Change lunch")
            disk.followerDao().updateSyncMetadata(FollowerSyncMetadataEntity(1, 19L))
            disk.close()
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, 0).use { raw ->
                raw.execSQL("DROP TABLE follower_meal_requests")
                raw.version = 4
            }
            disk = open()
            assertNull(disk.mealRequestDao().getById(requestId))
            val afterUpgrade = FollowerLocalSyncEngine(disk, null, null, disk.mealRequestDao())
            afterUpgrade.reconcileMealRequestUpload(accepted, SyncOperationResultDto(accepted.operationId, deviceId,
                SyncOperationStatus.APPLIED, canonicalSequence = 20L, resultCode = "SUCCESS"))
            afterUpgrade.reconcileMealRequestUpload(rejected, SyncOperationResultDto(rejected.operationId, deviceId,
                SyncOperationStatus.REJECTED, canonicalSequence = 21L, resultCode = "MEAL_NOT_FOUND"))
            val recovered = disk.mealRequestDao().getById(requestId)!!
            assertEquals("PENDING", recovered.status)
            assertEquals("meal_1", recovered.mealId)
            assertEquals(deviceId, recovered.authorDeviceId)
            assertEquals("Change dinner", recovered.requestText)
            assertEquals(accepted.createdAt, recovered.createdAt)
            assertEquals(20L, recovered.canonicalSequence)
            assertEquals("SYNC_FAILED", disk.mealRequestDao().getById(rejectedId)?.status)
            assertTrue(disk.mealRequestDao().getById(rejectedId)!!.mainResponse!!.contains("MEAL_NOT_FOUND"))
            assertEquals("APPLIED", disk.followerDao().getOutboxByOperationId(accepted.operationId)?.status)
            assertEquals("REJECTED", disk.followerDao().getOutboxByOperationId(rejected.operationId)?.status)
            assertEquals(beforeItem, disk.followerDao().getItemById(inventory.targetItemId!!))
            assertEquals(inventory, disk.followerDao().getOutboxByOperationId(inventory.operationId))
            assertEquals(4L, disk.followerDao().getSequenceEntity()?.nextSequence)
            assertEquals(19L, disk.followerDao().getSyncMetadata()?.lastReconciledCanonicalSequence)
        } finally {
            disk.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun legacyAcknowledgement_outboxWriteFailureRollsBackRecoveredProjection() = runBlocking {
        val op = queue()
        // Recreate an outbox-only request written before the v5 projection existed.
        follower.openHelper.writableDatabase.execSQL("DELETE FROM follower_meal_requests")
        follower.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_meal_ack BEFORE UPDATE ON follower_sync_outbox
            BEGIN SELECT RAISE(ABORT, 'injected acknowledgement failure'); END""")
        val result = SyncOperationResultDto(op.operationId, deviceId,
            SyncOperationStatus.APPLIED, canonicalSequence = 1L, resultCode = "SUCCESS")
        assertFails { local.reconcileMealRequestUpload(op, result) }
        assertNull(follower.mealRequestDao().getById(requestId))
        assertEquals(op, follower.followerDao().getOutboxByOperationId(op.operationId))
        follower.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_meal_ack")
        local.reconcileMealRequestUpload(op, result)
        assertEquals("PENDING", follower.mealRequestDao().getById(requestId)?.status)
        assertEquals("APPLIED", follower.followerDao().getOutboxByOperationId(op.operationId)?.status)
        assertEquals(2L, follower.followerDao().getSequenceEntity()?.nextSequence)
        assertNull(follower.followerDao().getSyncMetadata())
    }

    @Test
    fun rejectedUpload_isVisibleAndRemainsTerminal() = runBlocking {
        val op = queue()
        local.reconcileMealRequestUpload(op, SyncOperationResultDto(op.operationId, deviceId,
            SyncOperationStatus.REJECTED, canonicalSequence = 1L, resultCode = "MEAL_NOT_FOUND"))
        assertEquals("SYNC_FAILED", follower.mealRequestDao().getById(requestId)?.status)
        assertEquals("REJECTED", follower.followerDao().getOutboxByOperationId(op.operationId)?.status)
        assertTrue(follower.followerDao().getPendingOperationsInOrder().isEmpty())
    }

    @Test
    fun ownCreateFeed_isNotSkipped_andStaleReplayCannotReopenDecision() = runBlocking {
        queue()
        val create = event(1L, SyncOperationType.CREATE_MEAL_CHANGE_REQUEST,
            Json.encodeToString(CreateMealChangeRequestPayloadDto(requestId, "meal_1", "Change dinner")), deviceId)
        val client = FakeFeedSyncTestClient()
        client.feedResponseToReturn = SyncFeedResponseDto("home", 1L, listOf(create), false)
        FollowerFeedPullEngine(follower, client, local, follower.mealRequestDao())
            .performFeedPullPass("localhost", pinnedCertFingerprint = null, sessionToken = "token", followerDeviceId = deviceId)
        assertEquals("PENDING", follower.mealRequestDao().getById(requestId)?.status)
        val decision = event(2L, SyncOperationType.UPDATE_MEAL_CHANGE_REQUEST_STATUS,
            Json.encodeToString(UpdateMealChangeRequestStatusPayloadDto(requestId, "DECLINED", "Keep it")))
        FollowerMealRequestProjector.apply(follower.mealRequestDao(), decision)
        FollowerMealRequestProjector.apply(follower.mealRequestDao(), create)
        FollowerMealRequestProjector.apply(follower.mealRequestDao(), decision)
        assertEquals("DECLINED", follower.mealRequestDao().getById(requestId)?.status)
        assertEquals(2L, follower.mealRequestDao().getById(requestId)?.canonicalSequence)
    }

    @Test
    fun statusSnapshot_recoversMissingProjectionWithoutLocalMeal() = runBlocking {
        val snapshot = MealChangeRequestSyncDto(requestId, "absent_meal", deviceId, "Change dinner",
            "DISCUSSED", "Talk tonight", 1L, 10L)
        FollowerMealRequestProjector.apply(follower.mealRequestDao(), event(4L,
            SyncOperationType.UPDATE_MEAL_CHANGE_REQUEST_STATUS,
            Json.encodeToString(UpdateMealChangeRequestStatusPayloadDto(requestId, "DISCUSSED", "Talk tonight", snapshot))))
        assertEquals("DISCUSSED", follower.mealRequestDao().getById(requestId)?.status)
        assertTrue(follower.followerDao().getAllItems().isEmpty())
    }

    @Test
    fun cursorWriteFailure_rollsBackMealProjection_andAllowsReplay() = runBlocking {
        follower.followerDao().updateSyncMetadata(FollowerSyncMetadataEntity(1, 0L))
        follower.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_meal_cursor BEFORE INSERT ON follower_sync_metadata
            BEGIN SELECT RAISE(ABORT, 'injected cursor failure'); END""")
        val client = FakeFeedSyncTestClient()
        client.feedResponseToReturn = SyncFeedResponseDto("home", 1L, listOf(event(1L,
            SyncOperationType.CREATE_MEAL_CHANGE_REQUEST,
            Json.encodeToString(CreateMealChangeRequestPayloadDto(requestId, "meal_1", "Change dinner")), deviceId)), false)
        val pull = FollowerFeedPullEngine(follower, client, local, follower.mealRequestDao())
        assertFails {
            pull.performFeedPullPass("localhost", pinnedCertFingerprint = null, sessionToken = "token", followerDeviceId = deviceId)
        }
        assertNull(follower.mealRequestDao().getById(requestId))
        assertEquals(0L, follower.followerDao().getSyncMetadata()?.lastReconciledCanonicalSequence)
        follower.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_meal_cursor")
        pull.performFeedPullPass("localhost", pinnedCertFingerprint = null, sessionToken = "token", followerDeviceId = deviceId)
        assertEquals("PENDING", follower.mealRequestDao().getById(requestId)?.status)
        assertEquals(1L, follower.followerDao().getSyncMetadata()?.lastReconciledCanonicalSequence)
    }

    @Test
    fun missingLegacyRequest_stopsBeforeCursorAdvance() = runBlocking {
        val client = FakeFeedSyncTestClient()
        client.feedResponseToReturn = SyncFeedResponseDto("home", 2L, listOf(event(2L,
            SyncOperationType.UPDATE_MEAL_CHANGE_REQUEST_STATUS,
            Json.encodeToString(UpdateMealChangeRequestStatusPayloadDto(requestId, "ACCEPTED")))), false)
        val pull = FollowerFeedPullEngine(follower, client, local, follower.mealRequestDao())
        assertFails {
            pull.performFeedPullPass("localhost", pinnedCertFingerprint = null, sessionToken = "token", followerDeviceId = deviceId)
        }
        assertNull(follower.followerDao().getSyncMetadata())
    }

    @Test
    fun bootstrap_installsExistingDecisionBeforeWatermark_andPreservesQueuedRequests() = runBlocking {
        val queued = queue()
        val snapshotId = "req_${UUID.randomUUID()}"
        val client = FakeBootstrapSyncTestClient()
        client.bootstrapToReturn = BootstrapResponseDto(householdId = "home", snapshotCanonicalSequence = 12L,
            inventoryItems = emptyList(), mealRequests = listOf(MealChangeRequestSyncDto(
                snapshotId, "meal_2", deviceId, "Swap lunch", "ACCEPTED", "Agreed", 1L, 10L)))
        val pull = FollowerFeedPullEngine(follower, client, local, follower.mealRequestDao())
        val bootstrap = FollowerBootstrapEngine(follower, client, pull,
            followerLocalSyncEngine = local, mealRequestDao = follower.mealRequestDao())
        val summary = bootstrap.performBootstrapPass("localhost", pinnedCertFingerprint = null,
            sessionToken = "token", followerDeviceId = deviceId, expectedHouseholdId = "home")
        assertTrue(summary.success)
        assertEquals("ACCEPTED", follower.mealRequestDao().getById(snapshotId)?.status)
        assertEquals(12L, follower.followerDao().getSyncMetadata()?.lastReconciledCanonicalSequence)
        assertEquals("QUEUED", follower.mealRequestDao().getById(requestId)?.status)
        assertEquals(queued, follower.followerDao().getOutboxByOperationId(queued.operationId))
    }
}
