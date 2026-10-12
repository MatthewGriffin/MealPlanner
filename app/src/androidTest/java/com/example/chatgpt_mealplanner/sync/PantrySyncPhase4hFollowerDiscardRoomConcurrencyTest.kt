package com.example.chatgpt_mealplanner.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatgpt_mealplanner.core.sync.follower.database.FOLLOWER_MIGRATION_1_2
import com.example.chatgpt_mealplanner.core.sync.follower.database.FOLLOWER_MIGRATION_2_3
import com.example.chatgpt_mealplanner.core.sync.follower.database.FOLLOWER_MIGRATION_3_4
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity
import com.example.chatgpt_mealplanner.core.sync.follower.engine.DuplicateDiscardRequestException
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationType
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PantrySyncPhase4hFollowerDiscardRoomConcurrencyTest {

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

    @Test
    fun realRoom_followerConcurrentDuplicateDiscardRequest_preventsDuplicateOutboxAndSequence() = runBlocking {
        val dao = followerDb.followerDao()

        val itemId = "item-follower-race-100"
        val item = FollowerInventoryItemEntity(
            itemId = itemId,
            barcode = null,
            canonicalName = "butter",
            confirmedDisplayName = "Butter",
            confirmedBrand = null,
            confirmedCategory = "REFRIGERATED",
            confirmedStorageLocation = "FRIDGE",
            confirmedQuantity = 1.0,
            confirmedQuantityUnit = "UNIT",
            confirmedNotes = null,
            confirmedStatus = "AVAILABLE",
            mainConfirmedRevision = 1L,
            displayName = "Butter",
            brand = null,
            category = "REFRIGERATED",
            storageLocation = "FRIDGE",
            quantity = 1.0,
            quantityUnit = "UNIT",
            notes = null,
            status = "AVAILABLE",
            optimisticRevision = 1L,
            isPendingSync = false,
            updatedAt = System.currentTimeMillis()
        )
        dao.insertOrUpdateItem(item)

        val engine = FollowerLocalSyncEngine(followerDb)

        // Launch 2 coroutines targeting the exact same item concurrently
        val job1 = async {
            try {
                engine.recordLocalRequestDiscard("follower-dev-1", itemId, "Concurrent test 1")
            } catch (e: Throwable) {
                e
            }
        }
        val job2 = async {
            try {
                engine.recordLocalRequestDiscard("follower-dev-1", itemId, "Concurrent test 2")
            } catch (e: Throwable) {
                e
            }
        }

        val res1 = job1.await()
        val res2 = job2.await()

        // Assertions:
        // 1. Exactly one request succeeded and returned FollowerSyncOutboxEntity
        val successCount = listOf(res1, res2).count { it is FollowerSyncOutboxEntity }
        assertEquals("Exactly 1 concurrent request MUST succeed", 1, successCount)

        // 2. Exactly one request threw DuplicateDiscardRequestException
        val duplicateErrCount = listOf(res1, res2).count { it is DuplicateDiscardRequestException }
        assertEquals("Exactly 1 concurrent request MUST fail with DuplicateDiscardRequestException", 1, duplicateErrCount)

        // 3. Query REAL SQLite database: exactly 1 outbox row inserted
        val unresolvedOps = dao.getUnresolvedOutboxOpsForTargetItem(itemId)
        assertEquals("Exactly 1 unresolved outbox operation MUST exist in REAL SQLite database", 1, unresolvedOps.size)
        assertEquals(SyncOperationType.REQUEST_DISCARD_ITEM.name, unresolvedOps[0].operationType)

        // 4. Device sequence pointer advanced by exactly 1 (to 2L)
        val seqEntity = dao.getSequenceEntity()
        assertEquals("Follower device sequence MUST advance by exactly 1 (to 2L)", 2L, seqEntity?.nextSequence)

        // 5. Item working projection updated
        val itemAfter = dao.getItemById(itemId)
        assertNotNull(itemAfter)
        assertEquals("Item status MUST remain AVAILABLE before Main approval", "AVAILABLE", itemAfter!!.status)
        assertTrue("isPendingSync MUST be true", itemAfter.isPendingSync)
    }

    @Test
    fun realRoom_followerPendingApprovalDiscardRequest_blocksDuplicateRequest() = runBlocking {
        val dao = followerDb.followerDao()

        val itemId = "item-follower-pending-200"
        val item = FollowerInventoryItemEntity(
            itemId = itemId,
            barcode = null,
            canonicalName = "milk",
            confirmedDisplayName = "Milk",
            confirmedBrand = null,
            confirmedCategory = "REFRIGERATED",
            confirmedStorageLocation = "FRIDGE",
            confirmedQuantity = 1.0,
            confirmedQuantityUnit = "LITRE",
            confirmedNotes = null,
            confirmedStatus = "AVAILABLE",
            mainConfirmedRevision = 1L,
            displayName = "Milk",
            brand = null,
            category = "REFRIGERATED",
            storageLocation = "FRIDGE",
            quantity = 1.0,
            quantityUnit = "LITRE",
            notes = null,
            status = "AVAILABLE",
            optimisticRevision = 1L,
            isPendingSync = true,
            updatedAt = System.currentTimeMillis()
        )
        dao.insertOrUpdateItem(item)

        // Pre-insert a PENDING_APPROVAL outbox entity for this item
        val pendingOp = FollowerSyncOutboxEntity(
            operationId = UUID.randomUUID().toString(),
            deviceId = "follower-dev-1",
            deviceSequence = 1L,
            protocolVersion = 1,
            operationType = SyncOperationType.REQUEST_DISCARD_ITEM.name,
            targetItemId = itemId,
            baseRevision = 1L,
            payloadJson = "{}",
            createdAt = System.currentTimeMillis(),
            status = "PENDING_APPROVAL",
            createdLocallyAt = System.currentTimeMillis()
        )
        dao.insertOutboxOperation(pendingOp)

        val engine = FollowerLocalSyncEngine(followerDb)

        var thrownError: Throwable? = null
        try {
            engine.recordLocalRequestDiscard("follower-dev-1", itemId, "Second request while pending approval")
        } catch (e: Throwable) {
            thrownError = e
        }

        assertNotNull("Request MUST throw exception when PENDING_APPROVAL op exists", thrownError)
        assertTrue("Exception MUST be DuplicateDiscardRequestException", thrownError is DuplicateDiscardRequestException)

        // Query REAL SQLite database: exactly 1 outbox operation remains
        val unresolvedOps = dao.getUnresolvedOutboxOpsForTargetItem(itemId)
        assertEquals("Outbox count MUST remain 1 in REAL SQLite database", 1, unresolvedOps.size)
        assertEquals("PENDING_APPROVAL", unresolvedOps[0].status)
    }
}
