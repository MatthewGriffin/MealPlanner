package com.example.chatgpt_mealplanner.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatgpt_mealplanner.core.sync.follower.database.FOLLOWER_MIGRATION_1_2
import com.example.chatgpt_mealplanner.core.sync.follower.database.FOLLOWER_MIGRATION_2_3
import com.example.chatgpt_mealplanner.core.sync.follower.database.FOLLOWER_MIGRATION_3_4
import com.example.chatgpt_mealplanner.core.sync.follower.database.FOLLOWER_MIGRATION_4_5
import com.example.chatgpt_mealplanner.core.sync.follower.database.FOLLOWER_MIGRATION_5_6
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PantryPhase5FollowerDatabaseMigrationTest {

    private lateinit var context: Context
    private val dbName = "test_follower_migration.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun realRoom_followerDatabase_migration3To6_preservesOutboxAndMetadata() = runBlocking {
        // 1. Create a raw SQLite database at FollowerDatabase v3
        val openHelper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `follower_inventory_items` (
                                `itemId` TEXT NOT NULL,
                                `barcode` TEXT,
                                `canonicalName` TEXT NOT NULL,
                                `confirmedDisplayName` TEXT NOT NULL DEFAULT '',
                                `confirmedBrand` TEXT,
                                `confirmedCategory` TEXT NOT NULL DEFAULT '',
                                `confirmedStorageLocation` TEXT NOT NULL DEFAULT '',
                                `confirmedQuantity` REAL,
                                `confirmedQuantityUnit` TEXT,
                                `confirmedNotes` TEXT,
                                `confirmedStatus` TEXT NOT NULL DEFAULT 'AVAILABLE',
                                `mainConfirmedRevision` INTEGER NOT NULL DEFAULT 1,
                                `displayName` TEXT NOT NULL,
                                `brand` TEXT,
                                `category` TEXT NOT NULL,
                                `storageLocation` TEXT NOT NULL,
                                `quantity` REAL,
                                `quantityUnit` TEXT,
                                `notes` TEXT,
                                `status` TEXT NOT NULL,
                                `optimisticRevision` INTEGER NOT NULL DEFAULT 1,
                                `isPendingSync` INTEGER NOT NULL DEFAULT 0,
                                `updatedAt` INTEGER NOT NULL,
                                PRIMARY KEY(`itemId`)
                            )
                        """.trimIndent())

                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `follower_sync_outbox` (
                                `operationId` TEXT NOT NULL,
                                `deviceId` TEXT NOT NULL,
                                `deviceSequence` INTEGER NOT NULL,
                                `protocolVersion` INTEGER NOT NULL,
                                `operationType` TEXT NOT NULL,
                                `targetItemId` TEXT,
                                `baseRevision` INTEGER,
                                `payloadJson` TEXT NOT NULL,
                                `createdAt` INTEGER NOT NULL,
                                `status` TEXT NOT NULL,
                                `attemptCount` INTEGER NOT NULL DEFAULT 0,
                                `lastAttemptAt` INTEGER,
                                `lastErrorCode` TEXT,
                                `createdLocallyAt` INTEGER NOT NULL,
                                PRIMARY KEY(`operationId`)
                            )
                        """.trimIndent())

                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `follower_device_sequence` (
                                `singletonId` INTEGER NOT NULL,
                                `nextSequence` INTEGER NOT NULL,
                                PRIMARY KEY(`singletonId`)
                            )
                        """.trimIndent())

                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `follower_sync_metadata` (
                                `singletonId` INTEGER NOT NULL,
                                `lastReconciledCanonicalSequence` INTEGER NOT NULL,
                                `lastSuccessfulPullAt` INTEGER,
                                PRIMARY KEY(`singletonId`)
                            )
                        """.trimIndent())
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )

        val v3Db = openHelper.writableDatabase

        // 2. Insert v3 historical data
        v3Db.execSQL("""
            INSERT INTO `follower_inventory_items` (
                `itemId`, `canonicalName`, `confirmedDisplayName`, `confirmedCategory`,
                `confirmedStorageLocation`, `confirmedQuantity`, `confirmedQuantityUnit`, `confirmedStatus`,
                `mainConfirmedRevision`, `displayName`, `category`, `storageLocation`,
                `quantity`, `quantityUnit`, `status`, `optimisticRevision`, `isPendingSync`, `updatedAt`
            ) VALUES (
                'item-f3-1', 'milk', 'Whole Milk', 'REFRIGERATED',
                'FRIDGE', 2.0, 'LITRE', 'AVAILABLE',
                3, 'Whole Milk', 'REFRIGERATED', 'FRIDGE',
                2.0, 'LITRE', 'AVAILABLE', 3, 1, 1000
            )
        """.trimIndent())

        v3Db.execSQL("""
            INSERT INTO `follower_sync_outbox` (
                `operationId`, `deviceId`, `deviceSequence`, `protocolVersion`,
                `operationType`, `targetItemId`, `baseRevision`, `payloadJson`,
                `createdAt`, `status`, `createdLocallyAt`
            ) VALUES (
                'op-out-f3-1', 'follower-dev-1', 1, 1,
                'REQUEST_DISCARD_ITEM', 'item-f3-1', 3, '{}',
                1000, 'PENDING_APPROVAL', 1000
            )
        """.trimIndent())

        v3Db.execSQL("""
            INSERT INTO `follower_sync_metadata` (`singletonId`, `lastReconciledCanonicalSequence`, `lastSuccessfulPullAt`)
            VALUES (1, 10, 1000)
        """.trimIndent())

        v3Db.close()

        // 3. Open migrated database using Room FollowerDatabase version 6
        val migratedDb = Room.databaseBuilder(context, FollowerDatabase::class.java, dbName)
            .addMigrations(FOLLOWER_MIGRATION_1_2, FOLLOWER_MIGRATION_2_3, FOLLOWER_MIGRATION_3_4, FOLLOWER_MIGRATION_4_5, FOLLOWER_MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()

        assertEquals(6, migratedDb.openHelper.readableDatabase.version)
        assertTrue(migratedDb.mealDao().getAll().isEmpty())
        val dao = migratedDb.followerDao()

        // 4. Assert data preservation across the full 3-to-6 migration chain
        val itemAfter = dao.getItemById("item-f3-1")
        assertNotNull("Historical item-f3-1 MUST survive migration to v6", itemAfter)
        assertEquals("Whole Milk", itemAfter!!.confirmedDisplayName)
        assertEquals(2.0, itemAfter.confirmedQuantity!!, 0.001)
        assertEquals(3L, itemAfter.mainConfirmedRevision)

        val outboxAfter = dao.getOutboxByOperationId("op-out-f3-1")
        assertNotNull("Historical outbox operation op-out-f3-1 MUST survive migration to v6", outboxAfter)
        assertEquals("PENDING_APPROVAL", outboxAfter!!.status)
        assertEquals(1L, outboxAfter.deviceSequence)

        val metaAfter = dao.getSyncMetadata()
        assertNotNull("Sync metadata MUST survive migration to v6", metaAfter)
        assertEquals(10L, metaAfter!!.lastReconciledCanonicalSequence)
        assertTrue("bootstrapCompleted MUST be migrated to true for existing data", metaAfter.bootstrapCompleted)

        migratedDb.close()
    }
}
