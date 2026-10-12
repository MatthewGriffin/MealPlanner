package com.example.chatgpt_mealplanner.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatgpt_mealplanner.core.security.AndroidKeyStoreManager
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.core.sync.client.TestClientBootstrapResult
import com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine
import com.example.chatgpt_mealplanner.sync.contract.BootstrapResponseDto
import com.example.chatgpt_mealplanner.sync.contract.InventoryItemSyncDto
import com.example.chatgpt_mealplanner.sync.contract.SyncFeedResponseDto
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

open class AndroidTestClient : PantrySyncTestClient(AndroidKeyStoreManager()) {
    var bootstrapToReturn: BootstrapResponseDto? = null

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
        return TestClientFeedResult(true, SyncFeedResponseDto("household_123", sinceSequence, emptyList(), false), null)
    }
}

@RunWith(AndroidJUnit4::class)
class FollowerRoomRollbackTest {

    private lateinit var followerDb: FollowerDatabase
    private lateinit var fakeClient: AndroidTestClient
    private lateinit var syncEngine: FollowerLocalSyncEngine
    private lateinit var pullEngine: FollowerFeedPullEngine
    private lateinit var bootstrapEngine: FollowerBootstrapEngine

    private val followerDeviceId = UUID.randomUUID().toString()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        followerDb = Room.inMemoryDatabaseBuilder(context, FollowerDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        fakeClient = AndroidTestClient()
        syncEngine = FollowerLocalSyncEngine(followerDb)
        pullEngine = FollowerFeedPullEngine(followerDb, fakeClient, syncEngine)
        bootstrapEngine = FollowerBootstrapEngine(followerDb, fakeClient, pullEngine)
    }

    @After
    fun tearDown() {
        if (::followerDb.isInitialized && followerDb.isOpen) {
            followerDb.close()
        }
    }

    @Test
    fun realRoom_followerRollbackTest_executesProductionWithTransaction() = runBlocking {
        val dao = followerDb.followerDao()

        // 1. Arrange pre-existing item in REAL FollowerDatabase
        val preExistingItem = FollowerInventoryItemEntity(
            itemId = "pre_existing_1",
            canonicalName = "Existing Salt",
            confirmedDisplayName = "Table Salt",
            confirmedBrand = null,
            confirmedCategory = "FRESH_PANTRY",
            confirmedStorageLocation = "CUPBOARD",
            confirmedQuantity = 1.0,
            confirmedQuantityUnit = "BAG",
            confirmedNotes = null,
            confirmedStatus = "AVAILABLE",
            mainConfirmedRevision = 5L,
            displayName = "Table Salt",
            brand = null,
            category = "FRESH_PANTRY",
            storageLocation = "CUPBOARD",
            quantity = 1.0,
            quantityUnit = "BAG",
            notes = null,
            status = "AVAILABLE",
            optimisticRevision = 5L,
            isPendingSync = false,
            updatedAt = System.currentTimeMillis()
        )
        dao.insertOrUpdateItem(preExistingItem)

        // 2. Arrange pre-existing metadata: cursor = 5, bootstrapCompleted = false, bootstrapCompletedAt = null, bootstrapCanonicalSequence = null
        val preExistingMeta = FollowerSyncMetadataEntity(
            singletonId = 1,
            lastReconciledCanonicalSequence = 5L,
            lastSuccessfulPullAt = System.currentTimeMillis(),
            bootstrapCompleted = false,
            bootstrapCompletedAt = null,
            bootstrapCanonicalSequence = null
        )
        dao.updateSyncMetadata(preExistingMeta)

        // 3. Inject a deterministic SQLite exception AFTER the first snapshot item has actually been written to SQLite but BEFORE transaction commit
        val db = followerDb.openHelper.writableDatabase
        db.execSQL("""
            CREATE TRIGGER fail_on_second_snapshot_item
            BEFORE INSERT ON follower_inventory_items
            WHEN (SELECT COUNT(*) FROM follower_inventory_items) >= 1
            BEGIN
                SELECT RAISE(ABORT, 'Injected SQLite exception after first snapshot item write');
            END;
        """.trimIndent())

        // 4. Prepare bootstrap response containing at least two new items
        val item1Id = UUID.randomUUID().toString()
        val item2Id = UUID.randomUUID().toString()
        val item1 = InventoryItemSyncDto(item1Id, null, "Tea", "Black Tea", null, "BEVERAGE", "CUPBOARD", 1.0, "PACK", null, "AVAILABLE", 100L)
        val item2 = InventoryItemSyncDto(item2Id, null, "Coffee", "Beans", null, "BEVERAGE", "CUPBOARD", 1.0, "PACK", null, "AVAILABLE", 101L)

        fakeClient.bootstrapToReturn = BootstrapResponseDto(
            protocolVersion = 1,
            householdId = "household_123",
            snapshotCanonicalSequence = 100L,
            inventoryItems = listOf(item1, item2)
        )

        // 5. Execute performBootstrapPass (customDao = null, so it executes production followerDatabase.withTransaction { block() })
        val summary = bootstrapEngine.performBootstrapPass(
            hostIp = "127.0.0.1",
            port = 8443,
            pinnedCertFingerprint = null,
            sessionToken = "valid_token",
            followerDeviceId = followerDeviceId
        )

        println("BOOTSTRAP SUMMARY RESULT: $summary")
        assertFalse("Fault during install MUST cause pass to fail (summary=$summary)", summary.success)
        assertEquals("DATABASE_INSTALL_FAILED", summary.resultCode)

        // 6. Query the REAL Room database and prove full transaction rollback
        val allItemsInDb = dao.getAllItems()
        assertEquals("Pre-existing item MUST still exist unchanged and no snapshot items persisted (count = 1)", 1, allItemsInDb.size)
        assertEquals("pre_existing_1", allItemsInDb[0].itemId)

        assertNull("First snapshot item MUST NOT exist in real Room database", dao.getItemById(item1Id))
        assertNull("Second snapshot item MUST NOT exist in real Room database", dao.getItemById(item2Id))

        val metaAfter = dao.getSyncMetadata()
        assertEquals("Cursor MUST remain 5", 5L, metaAfter?.lastReconciledCanonicalSequence ?: 0L)
        assertFalse("bootstrapCompleted MUST remain false", metaAfter?.bootstrapCompleted == true)
        assertNull("bootstrapCompletedAt MUST remain null", metaAfter?.bootstrapCompletedAt)
        assertNull("bootstrapCanonicalSequence MUST remain null", metaAfter?.bootstrapCanonicalSequence)
    }
}
