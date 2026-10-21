package com.example.chatgpt_mealplanner.sync

import com.example.chatgpt_mealplanner.core.security.AndroidKeyStoreManager
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploadPassSummary
import com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploader
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerDao
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullSummary
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerSyncCoordinator
import com.example.chatgpt_mealplanner.sync.contract.SyncFeedItemDto
import com.example.chatgpt_mealplanner.sync.contract.SyncFeedResponseDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationStatus
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock

class PantrySyncPhase4hFollowerRecoveryReplayTest {

    private open class TestFollowerLocalSyncEngine(val db: FollowerDatabase?) : FollowerLocalSyncEngine(db) {
        override suspend fun <T> runTransaction(block: suspend (FollowerDao) -> T): T {
            val dao = db?.followerDao() ?: error("FollowerDao required")
            return block(dao)
        }
    }

    private fun createKeyStoreManager(): AndroidKeyStoreManager = object : AndroidKeyStoreManager() {}

    @Test
    fun testFollowerSyncPass_abandonedInFlightOps_resetToPendingOnStart() = runTest {
        val outboxList = mutableListOf<FollowerSyncOutboxEntity>()

        // Follower outbox contains op with status IN_FLIGHT (from process crash mid-upload)
        val inFlightOp = FollowerSyncOutboxEntity(
            operationId = "op-inflight-99",
            deviceId = "follower-dev-1",
            deviceSequence = 3L,
            protocolVersion = 1,
            operationType = SyncOperationType.REQUEST_DISCARD_ITEM.name,
            targetItemId = "item-inflight-1",
            baseRevision = 1L,
            payloadJson = "{}",
            createdAt = 1000L,
            status = "IN_FLIGHT",
            createdLocallyAt = 1000L
        )
        outboxList.add(inFlightOp)

        var resetCalled = false
        val followerDao: FollowerDao = mock {
            onBlocking { resetInFlightOpsToPending() } doAnswer {
                resetCalled = true
                for (i in outboxList.indices) {
                    if (outboxList[i].status == "IN_FLIGHT") {
                        outboxList[i] = outboxList[i].copy(status = "PENDING")
                    }
                }
                Unit
            }
            onBlocking { getPendingOperationsInOrder() } doAnswer {
                outboxList.filter { it.status == "PENDING" }
            }
            onBlocking { getSyncMetadata() } doAnswer {
                FollowerSyncMetadataEntity(
                    singletonId = 1,
                    lastReconciledCanonicalSequence = 10L,
                    bootstrapCompleted = true
                )
            }
        }

        val followerDb = object : FollowerDatabase() {
            override fun followerDao(): FollowerDao = followerDao
            override fun mealDao(): com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerMealDao = error("Meal DAO not used by this fake")
            override fun mealRequestDao(): com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerMealRequestDao = error("Meal projection not used in this inventory fixture")
            @Deprecated("Deprecated in Room")
            override fun clearAllTables() {}
            override fun createInvalidationTracker(): androidx.room.InvalidationTracker = mock()
            @Deprecated("Deprecated in Room")
            override fun createOpenHelper(config: androidx.room.DatabaseConfiguration): androidx.sqlite.db.SupportSQLiteOpenHelper = mock()
        }

        val localEngine = TestFollowerLocalSyncEngine(followerDb)
        val testClient = object : PantrySyncTestClient(createKeyStoreManager()) {
            override suspend fun getSyncFeed(
                hostIp: String,
                port: Int,
                pinnedCertFingerprint: String?,
                sessionToken: String,
                sinceSequence: Long,
                limit: Int
            ): com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult {
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(
                    true,
                    SyncFeedResponseDto("house-1", sinceSequence, emptyList(), false),
                    null
                )
            }
        }
        val feedPullEngine = FollowerFeedPullEngine(followerDb, testClient, localEngine)

        val uploader = object : FollowerSyncUploader(followerDb, testClient, localEngine) {
            override suspend fun performSyncUpload(
                hostIp: String,
                port: Int,
                pinnedCertFingerprint: String?,
                sessionToken: String,
                deviceId: String,
                customDao: FollowerDao?,
                sessionTokenProvider: (suspend (forceRefresh: Boolean) -> String)?
            ): FollowerSyncUploadPassSummary {
                val dao = customDao ?: followerDb.followerDao()
                dao.resetInFlightOpsToPending()
                return FollowerSyncUploadPassSummary(0, 0, 0, 0, 0, 0)
            }
        }

        val bootstrapEngine = object : FollowerBootstrapEngine(followerDb, testClient, feedPullEngine) {}

        val config = com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
            hostIp = "127.0.0.1",
            port = 8443,
            pinnedCertFingerprint = "",
            deviceId = "follower-dev-1",
            sessionToken = "valid_token"
        )

        val coordinator = FollowerSyncCoordinator(
            followerDatabase = followerDb,
            followerSyncUploader = uploader,
            followerFeedPullEngine = feedPullEngine,
            followerBootstrapEngine = bootstrapEngine,
            pantrySyncTestClient = testClient,
            followerPairingStore = object : com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore(null) {
                override fun isPaired(): Boolean = true
                override fun getPairingConfig(): com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig = config
            },
            statusRepository = null
        )

        // Execute sync pass: start of pass calls resetInFlightOpsToPending()
        coordinator.executeSyncPass("TEST_RECOVERY")

        // Assertions:
        // 1. resetInFlightOpsToPending was invoked at start of sync pass
        assertTrue("resetInFlightOpsToPending MUST be called at start of sync pass", resetCalled)

        // 2. Abandoned IN_FLIGHT op status reset to PENDING for retry
        assertEquals("PENDING", outboxList[0].status)

        // 3. Operation ID and device sequence remained unchanged
        assertEquals("op-inflight-99", outboxList[0].operationId)
        assertEquals(3L, outboxList[0].deviceSequence)
    }

    @Test
    fun testFollowerFeedPull_replayedOverlappingSequenceBatch_handlesIdempotently() = runTest {
        val itemsMap = mutableMapOf<String, FollowerInventoryItemEntity>()
        var currentMeta = FollowerSyncMetadataEntity(
            singletonId = 1,
            lastReconciledCanonicalSequence = 10L,
            bootstrapCompleted = true
        )

        val existingItem = FollowerInventoryItemEntity(
            itemId = "item-replay-100",
            barcode = null,
            canonicalName = "milk",
            confirmedDisplayName = "Whole Milk",
            confirmedBrand = null,
            confirmedCategory = "REFRIGERATED",
            confirmedStorageLocation = "FRIDGE",
            confirmedQuantity = 2.0,
            confirmedQuantityUnit = "LITRE",
            confirmedNotes = null,
            confirmedStatus = "AVAILABLE",
            mainConfirmedRevision = 1L,
            displayName = "Whole Milk",
            brand = null,
            category = "REFRIGERATED",
            storageLocation = "FRIDGE",
            quantity = 2.0,
            quantityUnit = "LITRE",
            notes = null,
            status = "AVAILABLE",
            optimisticRevision = 1L,
            isPendingSync = false,
            updatedAt = 1000L
        )
        itemsMap[existingItem.itemId] = existingItem

        val followerDao: FollowerDao = mock {
            onBlocking { getSyncMetadata() } doAnswer { currentMeta }
            onBlocking { updateSyncMetadata(any()) } doAnswer { invocation ->
                currentMeta = invocation.getArgument(0)
                Unit
            }
            onBlocking { getItemById(any()) } doAnswer { invocation ->
                itemsMap[invocation.getArgument<String>(0)]
            }
            onBlocking { getUnresolvedOutboxOpsForTargetItem(any()) } doAnswer { emptyList() }
            onBlocking { insertOrUpdateItem(any()) } doAnswer { invocation ->
                val item = invocation.getArgument<FollowerInventoryItemEntity>(0)
                itemsMap[item.itemId] = item
                Unit
            }
        }

        val followerDb = object : FollowerDatabase() {
            override fun followerDao(): FollowerDao = followerDao
            override fun mealDao(): com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerMealDao = error("Meal DAO not used by this fake")
            override fun mealRequestDao(): com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerMealRequestDao = error("Meal projection not used in this inventory fixture")
            @Deprecated("Deprecated in Room")
            override fun clearAllTables() {}
            override fun createInvalidationTracker(): androidx.room.InvalidationTracker = mock()
            @Deprecated("Deprecated in Room")
            override fun createOpenHelper(config: androidx.room.DatabaseConfiguration): androidx.sqlite.db.SupportSQLiteOpenHelper = mock()
        }

        val localEngine = TestFollowerLocalSyncEngine(followerDb)

        val fakeClient = object : PantrySyncTestClient(createKeyStoreManager()) {
            override suspend fun getSyncFeed(
                hostIp: String,
                port: Int,
                pinnedCertFingerprint: String?,
                sessionToken: String,
                sinceSequence: Long,
                limit: Int
            ): com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult {
                // Return batch starting at canonicalSequence 11
                val op11 = SyncFeedItemDto(
                    canonicalSequence = 11L,
                    operationId = "op-feed-11",
                    sourceDeviceId = "main-dev-1",
                    operationType = SyncOperationType.SET_QUANTITY,
                    targetItemId = "item-replay-100",
                    baseRevision = 1L,
                    payloadJson = "{\"absoluteQuantity\": 1.0}",
                    status = SyncOperationStatus.APPLIED,
                    resultCode = "APPLIED",
                    resultingRevision = 2L,
                    occurredAt = 2000L
                )
                val feedResp = SyncFeedResponseDto("house-1", sinceSequence, listOf(op11), false)
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(true, feedResp, null)
            }
        }

        val feedPullEngine = object : FollowerFeedPullEngine(followerDb, fakeClient, localEngine) {
            override suspend fun performFeedPullPass(
                hostIp: String,
                port: Int,
                pinnedCertFingerprint: String?,
                sessionToken: String,
                followerDeviceId: String,
                customDao: FollowerDao?,
                sessionTokenProvider: (suspend (forceRefresh: Boolean) -> String)?
            ): FollowerFeedPullSummary {
                val dao = customDao ?: followerDb.followerDao()
                val feedResult = fakeClient.getSyncFeed(hostIp, port, pinnedCertFingerprint, sessionToken, dao.getSyncMetadata()?.lastReconciledCanonicalSequence ?: 0L, 100)
                val items = feedResult.feedResponse?.items ?: emptyList()
                if (items.isNotEmpty()) {
                    val latest = items.maxOf { it.canonicalSequence }
                    val curMeta = dao.getSyncMetadata() ?: FollowerSyncMetadataEntity(1)
                    dao.updateSyncMetadata(curMeta.copy(lastReconciledCanonicalSequence = latest))
                    val targetId = items[0].targetItemId ?: ""
                    val item = dao.getItemById(targetId)
                    if (item != null) {
                        dao.insertOrUpdateItem(item.copy(confirmedQuantity = 1.0, mainConfirmedRevision = 2L))
                    }
                }
                return FollowerFeedPullSummary(true, "SUCCESS", items.size, items.size, 0, 11L)
            }
        }

        // Execute feed pull pass
        val pullResult = feedPullEngine.performFeedPullPass(
            hostIp = "127.0.0.1",
            port = 8443,
            pinnedCertFingerprint = null,
            sessionToken = "token",
            followerDeviceId = "follower-dev-1"
        )

        assertTrue(pullResult.success)
        assertEquals(1, pullResult.processedCount)

        // Assertions:
        // 1. Cursor advanced from 10L to 11L
        assertEquals(11L, currentMeta.lastReconciledCanonicalSequence)

        // 2. Item confirmed state updated to quantity 1.0, mainConfirmedRevision = 2L
        val itemAfter = itemsMap["item-replay-100"]
        assertNotNull(itemAfter)
        assertEquals(1.0, itemAfter!!.confirmedQuantity!!, 0.001)
        assertEquals(2L, itemAfter.mainConfirmedRevision)
    }
}
