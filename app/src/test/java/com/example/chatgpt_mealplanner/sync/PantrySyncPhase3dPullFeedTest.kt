package com.example.chatgpt_mealplanner.sync

import com.example.chatgpt_mealplanner.core.security.AndroidKeyStoreManager
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine
import com.example.chatgpt_mealplanner.sync.contract.InventoryItemSyncDto
import com.example.chatgpt_mealplanner.sync.contract.SyncFeedItemDto
import com.example.chatgpt_mealplanner.sync.contract.SyncFeedResponseDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationStatus
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.UUID

class FakeFeedSyncTestClient : PantrySyncTestClient(AndroidKeyStoreManager()) {
    var feedResponseToReturn: SyncFeedResponseDto? = null

    override suspend fun getSyncFeed(
        hostIp: String,
        port: Int,
        pinnedCertFingerprint: String?,
        sessionToken: String,
        sinceSequence: Long,
        limit: Int
    ): TestClientFeedResult {
        val resp = feedResponseToReturn
        return if (resp != null) {
            TestClientFeedResult(true, resp, null)
        } else {
            TestClientFeedResult(false, null, "UNAUTHORIZED")
        }
    }
}

@RunWith(JUnit4::class)
class PantrySyncPhase3dPullFeedTest {

    private lateinit var fakeDao: FakeFollowerDao
    private lateinit var localEngine: TestableFollowerSyncEngine
    private lateinit var pullEngine: FollowerFeedPullEngine
    private lateinit var fakeClient: FakeFeedSyncTestClient

    private val followerDeviceId = UUID.randomUUID().toString()
    private val mainDeviceId = UUID.randomUUID().toString()

    @Before
    fun setUp() {
        fakeDao = FakeFollowerDao()
        localEngine = TestableFollowerSyncEngine(fakeDao)
        fakeClient = FakeFeedSyncTestClient()
        pullEngine = FollowerFeedPullEngine(null, fakeClient, localEngine)
    }

    @Test
    fun feed_requiresAuthentication_failsOnNullResponse() = runTest {
        fakeClient.feedResponseToReturn = null

        val summary = pullEngine.performFeedPullPass(
            hostIp = "192.168.0.126",
            port = 8443,
            pinnedCertFingerprint = null,
            sessionToken = "invalid_token",
            followerDeviceId = followerDeviceId,
            customDao = fakeDao
        )

        assertEquals(0, summary.processedCount)
        assertEquals(0L, summary.latestCursor)
    }

    @Test
    fun remoteApplied_updatesConfirmedBaseAndAdvancesCursor() = runTest {
        val itemId = UUID.randomUUID().toString()
        val snapshot = InventoryItemSyncDto(
            itemId = itemId,
            barcode = "5000159461122",
            canonicalName = "Milk",
            displayName = "Whole Milk",
            brand = "Craven",
            category = "FRESH_PANTRY",
            storageLocation = "FRIDGE",
            quantity = 4.0,
            quantityUnit = "LITRE",
            notes = "Remote add from Main",
            status = "AVAILABLE",
            revision = 1L
        )

        val feedItem = SyncFeedItemDto(
            canonicalSequence = 101L,
            operationId = UUID.randomUUID().toString(),
            sourceDeviceId = mainDeviceId,
            operationType = SyncOperationType.ADD_ITEM,
            targetItemId = itemId,
            baseRevision = null,
            payloadJson = "{}",
            status = SyncOperationStatus.APPLIED,
            resultCode = "SUCCESS",
            resultingRevision = 1L,
            canonicalItemSnapshot = snapshot,
            occurredAt = System.currentTimeMillis()
        )

        fakeClient.feedResponseToReturn = SyncFeedResponseDto(
            householdId = "household_123",
            latestCanonicalSequence = 101L,
            items = listOf(feedItem),
            hasMore = false
        )

        val summary = pullEngine.performFeedPullPass(
            hostIp = "192.168.0.126",
            port = 8443,
            pinnedCertFingerprint = null,
            sessionToken = "valid_token",
            followerDeviceId = followerDeviceId,
            customDao = fakeDao
        )

        assertEquals(1, summary.processedCount)
        assertEquals(1, summary.remoteItemsAppliedCount)
        assertEquals(101L, summary.latestCursor)

        val itemInDb = fakeDao.getItemById(itemId)
        assertNotNull("Remote item MUST be created in Follower projection", itemInDb)
        assertEquals("Whole Milk", itemInDb?.confirmedDisplayName)
        assertEquals(4.0, itemInDb?.confirmedQuantity ?: 0.0, 0.001)

        val meta = fakeDao.getSyncMetadata()
        assertEquals("Cursor MUST advance to 101L", 101L, meta?.lastReconciledCanonicalSequence)
    }

    @Test
    fun ownOperations_skipProjectionUpdate_butAdvanceCursor() = runTest {
        val itemId = UUID.randomUUID().toString()
        val feedItem = SyncFeedItemDto(
            canonicalSequence = 102L,
            operationId = UUID.randomUUID().toString(),
            sourceDeviceId = followerDeviceId, // Own operation!
            operationType = SyncOperationType.ADD_ITEM,
            targetItemId = itemId,
            baseRevision = null,
            payloadJson = "{}",
            status = SyncOperationStatus.APPLIED,
            resultCode = "SUCCESS",
            resultingRevision = 1L,
            canonicalItemSnapshot = null,
            occurredAt = System.currentTimeMillis()
        )

        fakeClient.feedResponseToReturn = SyncFeedResponseDto(
            householdId = "household_123",
            latestCanonicalSequence = 102L,
            items = listOf(feedItem),
            hasMore = false
        )

        val summary = pullEngine.performFeedPullPass(
            hostIp = "192.168.0.126",
            port = 8443,
            pinnedCertFingerprint = null,
            sessionToken = "valid_token",
            followerDeviceId = followerDeviceId,
            customDao = fakeDao
        )

        assertEquals(1, summary.processedCount)
        assertEquals(1, summary.ownItemsSkippedCount)
        assertEquals(102L, summary.latestCursor)

        val meta = fakeDao.getSyncMetadata()
        assertEquals("Own operation MUST STILL advance cursor to 102L", 102L, meta?.lastReconciledCanonicalSequence)
    }
}
