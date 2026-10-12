package com.example.chatgpt_mealplanner.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatgpt_mealplanner.core.security.AndroidKeyStoreManager
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.core.sync.follower.database.FOLLOWER_MIGRATION_1_2
import com.example.chatgpt_mealplanner.core.sync.follower.database.FOLLOWER_MIGRATION_2_3
import com.example.chatgpt_mealplanner.core.sync.follower.database.FOLLOWER_MIGRATION_3_4
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine
import com.example.chatgpt_mealplanner.sync.contract.InventoryItemSyncDto
import com.example.chatgpt_mealplanner.sync.contract.SyncFeedItemDto
import com.example.chatgpt_mealplanner.sync.contract.SyncFeedResponseDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationStatus
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PantrySyncPhase4hFollowerFeedCursorRollbackTest {

    private lateinit var followerDb: FollowerDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        followerDb = Room.inMemoryDatabaseBuilder(context, FollowerDatabase::class.java)
            .addMigrations(
                FOLLOWER_MIGRATION_1_2,
                FOLLOWER_MIGRATION_2_3,
                FOLLOWER_MIGRATION_3_4
            )
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        if (::followerDb.isInitialized && followerDb.isOpen) {
            followerDb.close()
        }
    }

    private fun createKeyStoreManager(): AndroidKeyStoreManager = object : AndroidKeyStoreManager() {}

    @Test
    fun realRoom_followerFeedCursorRollback_onTriggerFailure_rollsBackProjectionsOutboxAndCursor() = runBlocking {
        val dao = followerDb.followerDao()

        val itemId = "item-feed-rollback-1"
        val outboxOpId = "op-outbox-pending-123"

        // 1. Arrange initial Follower state in REAL SQLite database
        val item = FollowerInventoryItemEntity(
            itemId = itemId,
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
            isPendingSync = true,
            updatedAt = 1000L
        )
        dao.insertOrUpdateItem(item)

        val pendingOutbox = FollowerSyncOutboxEntity(
            operationId = outboxOpId,
            deviceId = "follower-dev-1",
            deviceSequence = 1L,
            protocolVersion = 1,
            operationType = SyncOperationType.REQUEST_DISCARD_ITEM.name,
            targetItemId = itemId,
            baseRevision = 1L,
            payloadJson = "{}",
            createdAt = 1000L,
            status = "PENDING_APPROVAL",
            createdLocallyAt = 1000L
        )
        dao.insertOutboxOperation(pendingOutbox)

        val initialMeta = FollowerSyncMetadataEntity(
            singletonId = 1,
            lastReconciledCanonicalSequence = 10L,
            bootstrapCompleted = true
        )
        dao.updateSyncMetadata(initialMeta)

        // 2. Prepare feed batch with sequence 11L (approved discard for item)
        val op11 = SyncFeedItemDto(
            canonicalSequence = 11L,
            operationId = "op-main-approved-11",
            sourceDeviceId = "main-dev-1",
            operationType = SyncOperationType.REQUEST_DISCARD_ITEM,
            targetItemId = itemId,
            baseRevision = 1L,
            payloadJson = "{}",
            status = SyncOperationStatus.APPLIED,
            resultCode = "APPROVED_DISCARD",
            resultingRevision = 2L,
            canonicalItemSnapshot = InventoryItemSyncDto(
                itemId = itemId,
                barcode = null,
                canonicalName = "milk",
                displayName = "Whole Milk",
                brand = null,
                category = "REFRIGERATED",
                storageLocation = "FRIDGE",
                quantity = 0.0,
                quantityUnit = "LITRE",
                notes = null,
                status = "DISCARDED",
                revision = 2L
            ),
            originalOperationId = outboxOpId,
            occurredAt = 2000L
        )

        val fakeClient = object : PantrySyncTestClient(createKeyStoreManager()) {
            override suspend fun getSyncFeed(
                hostIp: String,
                port: Int,
                pinnedCertFingerprint: String?,
                sessionToken: String,
                sinceSequence: Long,
                limit: Int
            ): com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult {
                val feedResp = SyncFeedResponseDto("house-1", 11L, listOf(op11), false)
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(true, feedResp, null)
            }
        }

        // 3. Inject SQLite trigger failure on follower_sync_metadata INSERT and UPDATE when cursor reaches 11L
        val writableDb = followerDb.openHelper.writableDatabase
        writableDb.execSQL("""
            CREATE TRIGGER fail_on_cursor_insert
            BEFORE INSERT ON follower_sync_metadata
            WHEN NEW.lastReconciledCanonicalSequence = 11
            BEGIN
                SELECT RAISE(ABORT, 'Injected SQLite error on cursor insert');
            END;
        """.trimIndent())
        writableDb.execSQL("""
            CREATE TRIGGER fail_on_cursor_update
            BEFORE UPDATE ON follower_sync_metadata
            WHEN NEW.lastReconciledCanonicalSequence = 11
            BEGIN
                SELECT RAISE(ABORT, 'Injected SQLite error on cursor update');
            END;
        """.trimIndent())

        val localEngine = FollowerLocalSyncEngine(followerDb)
        val feedPullEngine = FollowerFeedPullEngine(followerDb, fakeClient, localEngine)

        // 4. Execute feed pull pass -> attempts to update item confirmed state, outbox status, and cursor in 1 Room transaction
        var caughtTriggerError: Throwable? = null
        try {
            feedPullEngine.performFeedPullPass(
                hostIp = "127.0.0.1",
                port = 8443,
                pinnedCertFingerprint = null,
                sessionToken = "token",
                followerDeviceId = "follower-dev-1"
            )
        } catch (e: Throwable) {
            caughtTriggerError = e
        }

        assertNotNull("performFeedPullPass MUST throw SQLite trigger error on cursor write", caughtTriggerError)

        // 5. Query REAL SQLite database and prove 100% full transaction rollback:
        // A. Canonical cursor remains 10L (NOT advanced to 11L)
        val metaAfterFailure = dao.getSyncMetadata()
        assertNotNull(metaAfterFailure)
        assertEquals("Canonical cursor MUST remain 10L after rollback", 10L, metaAfterFailure!!.lastReconciledCanonicalSequence)

        // B. Item confirmed quantity remains 2.0 L, status remains AVAILABLE, mainConfirmedRevision remains 1L
        val itemAfterFailure = dao.getItemById(itemId)
        assertNotNull(itemAfterFailure)
        assertEquals("Confirmed quantity MUST remain 2.0 L", 2.0, itemAfterFailure!!.confirmedQuantity!!, 0.001)
        assertEquals("Confirmed status MUST remain AVAILABLE", "AVAILABLE", itemAfterFailure.confirmedStatus)
        assertEquals("Main confirmed revision MUST remain 1L", 1L, itemAfterFailure.mainConfirmedRevision)

        // C. Outbox operation status remains PENDING_APPROVAL (NOT updated to APPLIED)
        val outboxAfterFailure = dao.getOutboxByOperationId(outboxOpId)
        assertNotNull(outboxAfterFailure)
        assertEquals("Outbox status MUST remain PENDING_APPROVAL", "PENDING_APPROVAL", outboxAfterFailure!!.status)

        // 6. Post-Failure Replay Verification: Drop triggers and re-run feed pull pass
        writableDb.execSQL("DROP TRIGGER IF EXISTS fail_on_cursor_insert")
        writableDb.execSQL("DROP TRIGGER IF EXISTS fail_on_cursor_update")

        val pullResultReplay = feedPullEngine.performFeedPullPass(
            hostIp = "127.0.0.1",
            port = 8443,
            pinnedCertFingerprint = null,
            sessionToken = "token",
            followerDeviceId = "follower-dev-1"
        )

        assertTrue(pullResultReplay.success)

        // Assert successful feed application after trigger removal:
        // A. Cursor advanced to 11L
        val metaAfterReplay = dao.getSyncMetadata()
        assertEquals("Canonical cursor MUST advance to 11L after replay", 11L, metaAfterReplay!!.lastReconciledCanonicalSequence)

        // B. Confirmed quantity updated to 0.0 L, confirmedStatus = DISCARDED, mainConfirmedRevision = 2L
        val itemAfterReplay = dao.getItemById(itemId)
        assertNotNull(itemAfterReplay)
        assertEquals("Confirmed quantity MUST update to 0.0 L", 0.0, itemAfterReplay!!.confirmedQuantity!!, 0.001)
        assertEquals("Confirmed status MUST update to DISCARDED", "DISCARDED", itemAfterReplay.confirmedStatus)
        assertEquals("Main confirmed revision MUST update to 2L", 2L, itemAfterReplay.mainConfirmedRevision)

        // C. Outbox operation reconciled to APPLIED
        val outboxAfterReplay = dao.getOutboxByOperationId(outboxOpId)
        assertNotNull(outboxAfterReplay)
        assertEquals("Outbox status MUST update to APPLIED", "APPLIED", outboxAfterReplay!!.status)
    }
}
