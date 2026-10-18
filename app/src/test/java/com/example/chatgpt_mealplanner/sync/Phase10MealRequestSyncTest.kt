package com.example.chatgpt_mealplanner.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.chatgpt_mealplanner.core.database.AppDatabase
import com.example.chatgpt_mealplanner.core.database.RoomTransactionRunner
import com.example.chatgpt_mealplanner.core.database.entity.PairedDeviceEntity
import com.example.chatgpt_mealplanner.core.database.entity.toEntity
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
            .addMigrations(com.example.chatgpt_mealplanner.core.sync.follower.database.FOLLOWER_MIGRATION_4_5).build()
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
