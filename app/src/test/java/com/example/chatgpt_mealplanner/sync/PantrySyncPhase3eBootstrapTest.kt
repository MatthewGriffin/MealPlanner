package com.example.chatgpt_mealplanner.sync

import com.example.chatgpt_mealplanner.core.security.AndroidKeyStoreManager
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.core.sync.client.TestClientBootstrapResult
import com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerDeviceSequenceEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine
import com.example.chatgpt_mealplanner.sync.contract.BootstrapResponseDto
import com.example.chatgpt_mealplanner.sync.contract.InventoryItemSyncDto
import com.example.chatgpt_mealplanner.sync.contract.SyncFeedItemDto
import com.example.chatgpt_mealplanner.sync.contract.SyncFeedResponseDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationStatus
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.UUID

class FakeBootstrapSyncTestClient : PantrySyncTestClient(AndroidKeyStoreManager()) {
    var bootstrapToReturn: BootstrapResponseDto? = null
    var feedToReturn: SyncFeedResponseDto? = null

    override suspend fun getBootstrapSnapshot(
        hostIp: String,
        port: Int,
        pinnedCertFingerprint: String?,
        sessionToken: String
    ): TestClientBootstrapResult {
        val resp = bootstrapToReturn
        return if (resp != null) {
            TestClientBootstrapResult(true, resp, null)
        } else {
            TestClientBootstrapResult(false, null, "UNAUTHORIZED")
        }
    }

    override suspend fun getSyncFeed(
        hostIp: String,
        port: Int,
        pinnedCertFingerprint: String?,
        sessionToken: String,
        sinceSequence: Long,
        limit: Int
    ): TestClientFeedResult {
        val resp = feedToReturn
        return if (resp != null) {
            TestClientFeedResult(true, resp, null)
        } else {
            TestClientFeedResult(true, SyncFeedResponseDto("household_123", sinceSequence, emptyList(), false), null)
        }
    }
}

@RunWith(JUnit4::class)
class PantrySyncPhase3eBootstrapTest {

    private lateinit var fakeDao: FakeFollowerDao
    private lateinit var localEngine: TestableFollowerSyncEngine
    private lateinit var pullEngine: FollowerFeedPullEngine
    private lateinit var bootstrapEngine: FollowerBootstrapEngine
    private lateinit var fakeClient: FakeBootstrapSyncTestClient

    private val followerDeviceId = UUID.randomUUID().toString()
    private val mainDeviceId = UUID.randomUUID().toString()

    @Before
    fun setUp() {
        fakeDao = FakeFollowerDao()
        localEngine = TestableFollowerSyncEngine(fakeDao)
        fakeClient = FakeBootstrapSyncTestClient()
        pullEngine = FollowerFeedPullEngine(null, fakeClient, localEngine)
        bootstrapEngine = FollowerBootstrapEngine(null, fakeClient, pullEngine)
    }

    @Test
    fun bootstrap_requiresAuthentication_failsOnNullResponse() = runTest {
        fakeClient.bootstrapToReturn = null

        val summary = bootstrapEngine.performBootstrapPass(
            hostIp = "192.168.0.126",
            port = 8443,
            pinnedCertFingerprint = null,
            sessionToken = "invalid_token",
            followerDeviceId = followerDeviceId,
            customDao = fakeDao
        )

        assertFalse("Unauthenticated bootstrap MUST fail", summary.success)
        assertEquals("UNAUTHORIZED", summary.resultCode)
    }

    @Test
    fun freshFollower_installsSnapshotAndCursorAtomically() = runTest {
        val itemId = UUID.randomUUID().toString()
        val itemSnapshot = InventoryItemSyncDto(
            itemId = itemId,
            barcode = "5000159461122",
            canonicalName = "Butter",
            displayName = "Salted Butter",
            brand = "Lurpak",
            category = "FRESH_PANTRY",
            storageLocation = "FRIDGE",
            quantity = 2.0,
            quantityUnit = "UNIT",
            notes = "Initial main item",
            status = "AVAILABLE",
            revision = 5L
        )

        fakeClient.bootstrapToReturn = BootstrapResponseDto(
            protocolVersion = 1,
            householdId = "household_123",
            snapshotCanonicalSequence = 500L,
            inventoryItems = listOf(itemSnapshot)
        )

        val summary = bootstrapEngine.performBootstrapPass(
            hostIp = "192.168.0.126",
            port = 8443,
            pinnedCertFingerprint = null,
            sessionToken = "valid_token",
            followerDeviceId = followerDeviceId,
            customDao = fakeDao
        )

        assertTrue(summary.success)
        assertEquals("SUCCESS", summary.resultCode)
        assertEquals(1, summary.installedItemsCount)
        assertEquals(500L, summary.snapshotWatermark)

        val installedItem = fakeDao.getItemById(itemId)
        assertNotNull(installedItem)
        assertEquals("Salted Butter", installedItem?.confirmedDisplayName)
        assertEquals(2.0, installedItem?.confirmedQuantity ?: 0.0, 0.001)

        val meta = fakeDao.getSyncMetadata()
        assertEquals(500L, meta?.lastReconciledCanonicalSequence)
        assertTrue("bootstrapCompleted MUST BE true", meta?.bootstrapCompleted == true)
    }

    @Test
    fun pendingOutbox_blocksDestructiveFreshBootstrap() = runTest {
        val itemId = UUID.randomUUID().toString()
        fakeDao.insertOutboxOperation(
            FollowerSyncOutboxEntity(
                operationId = "op_pending_1",
                deviceId = followerDeviceId,
                deviceSequence = 1L,
                protocolVersion = 1,
                operationType = "ADD_ITEM",
                targetItemId = itemId,
                baseRevision = null,
                payloadJson = "{}",
                createdAt = System.currentTimeMillis(),
                status = "PENDING",
                createdLocallyAt = System.currentTimeMillis()
            )
        )

        val summary = bootstrapEngine.performBootstrapPass(
            hostIp = "192.168.0.126",
            port = 8443,
            pinnedCertFingerprint = null,
            sessionToken = "valid_token",
            followerDeviceId = followerDeviceId,
            customDao = fakeDao
        )

        assertFalse(summary.success)
        assertEquals("BOOTSTRAP_BLOCKED_LOCAL_PENDING_OPERATIONS", summary.resultCode)
    }

    @Test
    fun establishedFollower_isNotFreshBootstrapped() = runTest {
        fakeDao.updateSyncMetadata(
            FollowerSyncMetadataEntity(
                singletonId = 1,
                lastReconciledCanonicalSequence = 100L,
                bootstrapCompleted = true,
                bootstrapCanonicalSequence = 100L
            )
        )

        val summary = bootstrapEngine.performBootstrapPass(
            hostIp = "192.168.0.126",
            port = 8443,
            pinnedCertFingerprint = null,
            sessionToken = "valid_token",
            followerDeviceId = followerDeviceId,
            customDao = fakeDao
        )

        assertTrue(summary.success)
        assertEquals("BOOTSTRAP_ALREADY_COMPLETED", summary.resultCode)
        assertEquals(0, summary.installedItemsCount)
    }

    @Test
    fun bootstrapThenFeedCatchUp_appliesPostSnapshotChanges() = runTest {
        val item1 = UUID.randomUUID().toString()
        val item2 = UUID.randomUUID().toString()

        val snap1 = InventoryItemSyncDto(
            itemId = item1,
            canonicalName = "Bread",
            displayName = "White Bread",
            category = "BREAD_BAKERY",
            storageLocation = "CUPBOARD",
            quantity = 1.0,
            status = "AVAILABLE",
            revision = 1L
        )

        fakeClient.bootstrapToReturn = BootstrapResponseDto(
            protocolVersion = 1,
            householdId = "household_123",
            snapshotCanonicalSequence = 500L,
            inventoryItems = listOf(snap1)
        )

        val snap2 = InventoryItemSyncDto(
            itemId = item2,
            canonicalName = "Milk",
            displayName = "Whole Milk",
            category = "FRESH_PANTRY",
            storageLocation = "FRIDGE",
            quantity = 2.0,
            status = "AVAILABLE",
            revision = 1L
        )

        val feedItemPostWatermark = SyncFeedItemDto(
            canonicalSequence = 501L,
            operationId = UUID.randomUUID().toString(),
            sourceDeviceId = mainDeviceId,
            operationType = SyncOperationType.ADD_ITEM,
            targetItemId = item2,
            payloadJson = "{}",
            status = SyncOperationStatus.APPLIED,
            resultCode = "SUCCESS",
            resultingRevision = 1L,
            canonicalItemSnapshot = snap2,
            occurredAt = System.currentTimeMillis()
        )

        fakeClient.feedToReturn = SyncFeedResponseDto(
            householdId = "household_123",
            latestCanonicalSequence = 501L,
            items = listOf(feedItemPostWatermark),
            hasMore = false
        )

        val summary = bootstrapEngine.performBootstrapPass(
            hostIp = "192.168.0.126",
            port = 8443,
            pinnedCertFingerprint = null,
            sessionToken = "valid_token",
            followerDeviceId = followerDeviceId,
            customDao = fakeDao
        )

        assertTrue(summary.success)
        assertNotNull(fakeDao.getItemById(item1))
        assertNotNull(fakeDao.getItemById(item2))

        val meta = fakeDao.getSyncMetadata()
        assertEquals(501L, meta?.lastReconciledCanonicalSequence)
    }

    @Test
    fun bootstrap_noCanonicalHistory_watermarkZero_isValid() = runTest {
        val itemId = UUID.randomUUID().toString()
        val snapPreLedger = InventoryItemSyncDto(
            itemId = itemId,
            canonicalName = "Eggs",
            displayName = "Farm Eggs",
            category = "FRESH_PANTRY",
            storageLocation = "FRIDGE",
            quantity = 12.0,
            status = "AVAILABLE",
            revision = 1L
        )

        fakeClient.bootstrapToReturn = BootstrapResponseDto(
            protocolVersion = 1,
            householdId = "household_123",
            snapshotCanonicalSequence = 0L,
            inventoryItems = listOf(snapPreLedger)
        )

        val summary = bootstrapEngine.performBootstrapPass(
            hostIp = "192.168.0.126",
            port = 8443,
            pinnedCertFingerprint = null,
            sessionToken = "valid_token",
            followerDeviceId = followerDeviceId,
            customDao = fakeDao
        )

        assertTrue(summary.success)
        assertEquals(0L, summary.snapshotWatermark)

        val meta = fakeDao.getSyncMetadata()
        assertEquals(0L, meta?.lastReconciledCanonicalSequence)
        assertTrue("Watermark 0 bootstrap MUST still mark bootstrapCompleted = true", meta?.bootstrapCompleted == true)
    }

    @Test
    fun watermarkZeroCompletedBootstrap_restartDoesNotBootstrapAgain() = runTest {
        fakeDao.updateSyncMetadata(
            FollowerSyncMetadataEntity(
                singletonId = 1,
                lastReconciledCanonicalSequence = 0L,
                bootstrapCompleted = true,
                bootstrapCanonicalSequence = 0L
            )
        )

        val summary = bootstrapEngine.performBootstrapPass(
            hostIp = "192.168.0.126",
            port = 8443,
            pinnedCertFingerprint = null,
            sessionToken = "valid_token",
            followerDeviceId = followerDeviceId,
            customDao = fakeDao
        )

        assertTrue(summary.success)
        assertEquals("BOOTSTRAP_ALREADY_COMPLETED", summary.resultCode)
        assertEquals(0, summary.installedItemsCount)
    }

    @Test
    fun pendingOutbox_blocksBootstrap_preservesCompleteEnvelope() = runTest {
        val itemId = UUID.randomUUID().toString()
        val originalOutbox = FollowerSyncOutboxEntity(
            operationId = "op_blocked_1",
            deviceId = followerDeviceId,
            deviceSequence = 5L,
            protocolVersion = 1,
            operationType = "SET_QUANTITY",
            targetItemId = itemId,
            baseRevision = 2L,
            payloadJson = "{\"absoluteQuantity\":10.0}",
            createdAt = 1762000000000L,
            status = "PENDING",
            createdLocallyAt = 1762000000000L
        )
        fakeDao.insertOutboxOperation(originalOutbox)

        val summary = bootstrapEngine.performBootstrapPass(
            hostIp = "192.168.0.126",
            port = 8443,
            pinnedCertFingerprint = null,
            sessionToken = "valid_token",
            followerDeviceId = followerDeviceId,
            customDao = fakeDao
        )

        assertFalse(summary.success)
        assertEquals("BOOTSTRAP_BLOCKED_LOCAL_PENDING_OPERATIONS", summary.resultCode)

        val outboxAfter = fakeDao.getOutboxByOperationId("op_blocked_1")
        assertNotNull(outboxAfter)
        assertEquals(originalOutbox.operationId, outboxAfter?.operationId)
        assertEquals(originalOutbox.deviceId, outboxAfter?.deviceId)
        assertEquals(originalOutbox.deviceSequence, outboxAfter?.deviceSequence)
        assertEquals(originalOutbox.protocolVersion, outboxAfter?.protocolVersion)
        assertEquals(originalOutbox.operationType, outboxAfter?.operationType)
        assertEquals(originalOutbox.targetItemId, outboxAfter?.targetItemId)
        assertEquals(originalOutbox.baseRevision, outboxAfter?.baseRevision)
        assertEquals(originalOutbox.payloadJson, outboxAfter?.payloadJson)
        assertEquals(originalOutbox.createdAt, outboxAfter?.createdAt)
        assertEquals(originalOutbox.status, outboxAfter?.status)
    }

    @Test
    fun bootstrap_doesNotResetDeviceSequenceAllocator() = runTest {
        val seqEntity = FollowerDeviceSequenceEntity(
            singletonId = 1,
            nextSequence = 42L
        )
        fakeDao.updateSequenceEntity(seqEntity)

        fakeClient.bootstrapToReturn = BootstrapResponseDto(
            protocolVersion = 1,
            householdId = "household_123",
            snapshotCanonicalSequence = 100L,
            inventoryItems = emptyList()
        )

        bootstrapEngine.performBootstrapPass(
            hostIp = "192.168.0.126",
            port = 8443,
            pinnedCertFingerprint = null,
            sessionToken = "valid_token",
            followerDeviceId = followerDeviceId,
            customDao = fakeDao
        )

        val seqAfter = fakeDao.getSequenceEntity()
        assertEquals("Bootstrap MUST NOT reset or corrupt device sequence allocator", 42L, seqAfter?.nextSequence)
    }
}
