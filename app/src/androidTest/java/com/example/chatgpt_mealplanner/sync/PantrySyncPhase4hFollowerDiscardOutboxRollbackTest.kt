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
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PantrySyncPhase4hFollowerDiscardOutboxRollbackTest {

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
    fun realRoom_followerOutboxRollback_onProjectionFailure_rollsBackOutboxAndSequence() = runBlocking {
        val dao = followerDb.followerDao()

        val itemId = "item-follower-roll-2"
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
            isPendingSync = false,
            updatedAt = System.currentTimeMillis()
        )
        dao.insertOrUpdateItem(item)

        // Pre-populate starting device sequence = 1L
        dao.updateSequenceEntity(com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerDeviceSequenceEntity(1, 1L))

        // Inject SQLite trigger failure on follower_sync_outbox insert
        val writableDb = followerDb.openHelper.writableDatabase
        writableDb.execSQL("""
            CREATE TRIGGER fail_on_outbox_insert
            BEFORE INSERT ON follower_sync_outbox
            BEGIN
                SELECT RAISE(ABORT, 'Injected SQLite error on follower outbox write');
            END;
        """.trimIndent())

        val engine = FollowerLocalSyncEngine(followerDb)

        var thrownException: Throwable? = null
        try {
            engine.recordLocalRequestDiscard("follower-1", itemId, "Projection trigger test")
        } catch (e: Throwable) {
            thrownException = e
        }

        assertNotNull("Exception MUST be thrown on projection trigger error", thrownException)

        // Query REAL SQLite database and prove rollback:
        // 1. Outbox operation rolled back (0 rows)
        val outboxOps = dao.getUnresolvedOutboxOpsForTargetItem(itemId)
        assertEquals("Outbox operations count MUST remain 0 after rollback", 0, outboxOps.size)

        // 2. Follower device sequence pointer rolled back (remains 1L)
        val seqEntity = dao.getSequenceEntity()
        assertEquals("Follower device sequence MUST remain 1L after rollback", 1L, seqEntity?.nextSequence ?: 1L)

        // 3. Item in follower_inventory_items remains status = AVAILABLE, isPendingSync = false
        val itemAfter = dao.getItemById(itemId)
        assertNotNull(itemAfter)
        assertEquals("Item status MUST remain AVAILABLE", "AVAILABLE", itemAfter!!.status)
        assertFalse("isPendingSync MUST remain false", itemAfter.isPendingSync)
    }
}
