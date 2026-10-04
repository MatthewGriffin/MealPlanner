package com.example.chatgpt_mealplanner.sync

import com.example.chatgpt_mealplanner.core.security.AndroidKeyStoreManager
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity
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
            sourceDeviceId = followerDeviceId,
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

    @Test
    fun sameBarcode_remoteLotsRemainSeparate() = runTest {
        val itemId1 = UUID.randomUUID().toString()
        val itemId2 = UUID.randomUUID().toString()

        val snap1 = InventoryItemSyncDto(
            itemId = itemId1,
            barcode = "5000159461122",
            canonicalName = "Milk",
            displayName = "Whole Milk Lot A",
            brand = "Craven",
            category = "FRESH_PANTRY",
            storageLocation = "FRIDGE",
            quantity = 1.0,
            quantityUnit = "LITRE",
            notes = null,
            status = "AVAILABLE",
            revision = 1L
        )

        val snap2 = InventoryItemSyncDto(
            itemId = itemId2,
            barcode = "5000159461122",
            canonicalName = "Milk",
            displayName = "Whole Milk Lot B",
            brand = "Craven",
            category = "FRESH_PANTRY",
            storageLocation = "FRIDGE",
            quantity = 1.0,
            quantityUnit = "LITRE",
            notes = null,
            status = "AVAILABLE",
            revision = 1L
        )

        val feedItem1 = SyncFeedItemDto(
            canonicalSequence = 200L,
            operationId = UUID.randomUUID().toString(),
            sourceDeviceId = mainDeviceId,
            operationType = SyncOperationType.ADD_ITEM,
            targetItemId = itemId1,
            baseRevision = null,
            payloadJson = "{}",
            status = SyncOperationStatus.APPLIED,
            resultCode = "SUCCESS",
            resultingRevision = 1L,
            canonicalItemSnapshot = snap1,
            occurredAt = System.currentTimeMillis()
        )

        val feedItem2 = SyncFeedItemDto(
            canonicalSequence = 201L,
            operationId = UUID.randomUUID().toString(),
            sourceDeviceId = mainDeviceId,
            operationType = SyncOperationType.ADD_ITEM,
            targetItemId = itemId2,
            baseRevision = null,
            payloadJson = "{}",
            status = SyncOperationStatus.APPLIED,
            resultCode = "SUCCESS",
            resultingRevision = 1L,
            canonicalItemSnapshot = snap2,
            occurredAt = System.currentTimeMillis()
        )

        fakeClient.feedResponseToReturn = SyncFeedResponseDto(
            householdId = "household_123",
            latestCanonicalSequence = 201L,
            items = listOf(feedItem1, feedItem2),
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

        assertEquals(2, summary.processedCount)
        assertNotNull(fakeDao.getItemById(itemId1))
        assertNotNull(fakeDao.getItemById(itemId2))
        assertEquals(2, fakeDao.getAllItems().size)
    }

    @Test
    fun remoteConfirmedDiscard_updatesConfirmedStatus() = runTest {
        val itemId = UUID.randomUUID().toString()

        fakeDao.insertOrUpdateItem(
            FollowerInventoryItemEntity(
                itemId = itemId,
                canonicalName = "Milk",
                confirmedDisplayName = "Whole Milk",
                confirmedCategory = "FRESH_PANTRY",
                confirmedStorageLocation = "FRIDGE",
                confirmedQuantity = 1.0,
                confirmedQuantityUnit = "LITRE",
                confirmedNotes = null,
                confirmedStatus = "AVAILABLE",
                mainConfirmedRevision = 1L,
                displayName = "Whole Milk",
                brand = null,
                category = "FRESH_PANTRY",
                storageLocation = "FRIDGE",
                quantity = 1.0,
                quantityUnit = "LITRE",
                notes = null,
                status = "AVAILABLE",
                optimisticRevision = 1L,
                isPendingSync = false,
                updatedAt = System.currentTimeMillis()
            )
        )

        val snapDiscard = InventoryItemSyncDto(
            itemId = itemId,
            canonicalName = "Milk",
            displayName = "Whole Milk",
            category = "FRESH_PANTRY",
            storageLocation = "FRIDGE",
            quantity = 0.0,
            quantityUnit = "LITRE",
            notes = null,
            status = "OUT",
            revision = 2L
        )

        val feedItem = SyncFeedItemDto(
            canonicalSequence = 300L,
            operationId = UUID.randomUUID().toString(),
            sourceDeviceId = mainDeviceId,
            operationType = SyncOperationType.REQUEST_DISCARD_ITEM,
            targetItemId = itemId,
            baseRevision = 1L,
            payloadJson = "{}",
            status = SyncOperationStatus.APPLIED,
            resultCode = "SUCCESS",
            resultingRevision = 2L,
            canonicalItemSnapshot = snapDiscard,
            occurredAt = System.currentTimeMillis()
        )

        fakeClient.feedResponseToReturn = SyncFeedResponseDto(
            householdId = "household_123",
            latestCanonicalSequence = 300L,
            items = listOf(feedItem),
            hasMore = false
        )

        pullEngine.performFeedPullPass(
            hostIp = "192.168.0.126",
            port = 8443,
            pinnedCertFingerprint = null,
            sessionToken = "valid_token",
            followerDeviceId = followerDeviceId,
            customDao = fakeDao
        )

        val itemAfter = fakeDao.getItemById(itemId)
        assertEquals("OUT", itemAfter?.confirmedStatus)
        assertEquals(2L, itemAfter?.mainConfirmedRevision)
    }

    @Test
    fun remoteApplied_pendingLocalEdit_recomputesOverlay() = runTest {
        val itemId = UUID.randomUUID().toString()

        fakeDao.insertOrUpdateItem(
            FollowerInventoryItemEntity(
                itemId = itemId,
                canonicalName = "Apples",
                confirmedDisplayName = "Red Apples",
                confirmedCategory = "FRESH_PANTRY",
                confirmedStorageLocation = "FRIDGE",
                confirmedQuantity = 3.0,
                confirmedQuantityUnit = "UNIT",
                confirmedNotes = null,
                confirmedStatus = "AVAILABLE",
                mainConfirmedRevision = 1L,
                displayName = "Red Apples",
                brand = null,
                category = "FRESH_PANTRY",
                storageLocation = "FRIDGE",
                quantity = 2.0,
                quantityUnit = "UNIT",
                notes = null,
                status = "AVAILABLE",
                optimisticRevision = 1L,
                isPendingSync = true,
                updatedAt = System.currentTimeMillis()
            )
        )

        fakeDao.insertOutboxOperation(
            FollowerSyncOutboxEntity(
                operationId = "op_pending_edit_1",
                deviceId = followerDeviceId,
                deviceSequence = 10L,
                protocolVersion = 1,
                operationType = "ADJUST_QUANTITY",
                targetItemId = itemId,
                baseRevision = 1L,
                payloadJson = "{\"delta\":-1.0}",
                createdAt = System.currentTimeMillis(),
                status = "PENDING",
                createdLocallyAt = System.currentTimeMillis()
            )
        )

        val snapRemote = InventoryItemSyncDto(
            itemId = itemId,
            canonicalName = "Apples",
            displayName = "Red Apples",
            category = "FRESH_PANTRY",
            storageLocation = "FRIDGE",
            quantity = 5.0,
            quantityUnit = "UNIT",
            notes = null,
            status = "AVAILABLE",
            revision = 2L
        )

        val feedItem = SyncFeedItemDto(
            canonicalSequence = 400L,
            operationId = UUID.randomUUID().toString(),
            sourceDeviceId = mainDeviceId,
            operationType = SyncOperationType.SET_QUANTITY,
            targetItemId = itemId,
            baseRevision = 1L,
            payloadJson = "{}",
            status = SyncOperationStatus.APPLIED,
            resultCode = "SUCCESS",
            resultingRevision = 2L,
            canonicalItemSnapshot = snapRemote,
            occurredAt = System.currentTimeMillis()
        )

        fakeClient.feedResponseToReturn = SyncFeedResponseDto(
            householdId = "household_123",
            latestCanonicalSequence = 400L,
            items = listOf(feedItem),
            hasMore = false
        )

        pullEngine.performFeedPullPass(
            hostIp = "192.168.0.126",
            port = 8443,
            pinnedCertFingerprint = null,
            sessionToken = "valid_token",
            followerDeviceId = followerDeviceId,
            customDao = fakeDao
        )

        val itemAfter = fakeDao.getItemById(itemId)
        assertEquals("Confirmed quantity MUST update to 5.0", 5.0, itemAfter?.confirmedQuantity ?: 0.0, 0.001)
        assertEquals("Working display quantity MUST recompute to 5.0 + (-1.0) = 4.0", 4.0, itemAfter?.quantity ?: 0.0, 0.001)

        val outboxOp = fakeDao.getOutboxByOperationId("op_pending_edit_1")
        assertEquals("Pending outbox baseRevision MUST NOT be rewritten (remains 1L)", 1L, outboxOp?.baseRevision)
    }

    @Test
    fun confirmedRemoteDiscard_doesNotEraseUnresolvedLocalOutbox() = runTest {
        val itemId = UUID.randomUUID().toString()

        fakeDao.insertOrUpdateItem(
            FollowerInventoryItemEntity(
                itemId = itemId,
                canonicalName = "Bread",
                confirmedDisplayName = "White Bread",
                confirmedCategory = "BREAD_BAKERY",
                confirmedStorageLocation = "CUPBOARD",
                confirmedQuantity = 1.0,
                confirmedQuantityUnit = "UNIT",
                confirmedNotes = null,
                confirmedStatus = "AVAILABLE",
                mainConfirmedRevision = 1L,
                displayName = "White Bread",
                brand = null,
                category = "BREAD_BAKERY",
                storageLocation = "CUPBOARD",
                quantity = 1.0,
                quantityUnit = "UNIT",
                notes = null,
                status = "AVAILABLE",
                optimisticRevision = 1L,
                isPendingSync = true,
                updatedAt = System.currentTimeMillis()
            )
        )

        fakeDao.insertOutboxOperation(
            FollowerSyncOutboxEntity(
                operationId = "op_local_pending_1",
                deviceId = followerDeviceId,
                deviceSequence = 15L,
                protocolVersion = 1,
                operationType = "SET_QUANTITY",
                targetItemId = itemId,
                baseRevision = 1L,
                payloadJson = "{\"absoluteQuantity\":2.0}",
                createdAt = System.currentTimeMillis(),
                status = "PENDING",
                createdLocallyAt = System.currentTimeMillis()
            )
        )

        val snapRemoteDiscard = InventoryItemSyncDto(
            itemId = itemId,
            canonicalName = "Bread",
            displayName = "White Bread",
            category = "BREAD_BAKERY",
            storageLocation = "CUPBOARD",
            quantity = 0.0,
            quantityUnit = "UNIT",
            notes = null,
            status = "OUT",
            revision = 2L
        )

        val feedItem = SyncFeedItemDto(
            canonicalSequence = 500L,
            operationId = UUID.randomUUID().toString(),
            sourceDeviceId = mainDeviceId,
            operationType = SyncOperationType.REQUEST_DISCARD_ITEM,
            targetItemId = itemId,
            baseRevision = 1L,
            payloadJson = "{}",
            status = SyncOperationStatus.APPLIED,
            resultCode = "SUCCESS",
            resultingRevision = 2L,
            canonicalItemSnapshot = snapRemoteDiscard,
            occurredAt = System.currentTimeMillis()
        )

        fakeClient.feedResponseToReturn = SyncFeedResponseDto(
            householdId = "household_123",
            latestCanonicalSequence = 500L,
            items = listOf(feedItem),
            hasMore = false
        )

        pullEngine.performFeedPullPass(
            hostIp = "192.168.0.126",
            port = 8443,
            pinnedCertFingerprint = null,
            sessionToken = "valid_token",
            followerDeviceId = followerDeviceId,
            customDao = fakeDao
        )

        val itemAfter = fakeDao.getItemById(itemId)
        assertEquals("OUT", itemAfter?.confirmedStatus)

        val pendingOp = fakeDao.getOutboxByOperationId("op_local_pending_1")
        assertNotNull("Unresolved local outbox operation MUST NOT be erased by remote discard", pendingOp)
        assertEquals("PENDING", pendingOp?.status)
    }
}
