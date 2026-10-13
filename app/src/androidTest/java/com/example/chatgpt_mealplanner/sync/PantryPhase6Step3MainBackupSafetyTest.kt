package com.example.chatgpt_mealplanner.sync

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatgpt_mealplanner.core.backup.MainBackupSnapshotBuilder
import com.example.chatgpt_mealplanner.core.database.AppDatabase
import com.example.chatgpt_mealplanner.core.database.DatabaseTransactionRunner
import com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncOperationEntity
import com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncSequenceEntity
import com.example.chatgpt_mealplanner.core.database.entity.HouseholdIdentityEntity
import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemEntity
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PantryPhase6Step3MainBackupSafetyTest {

    private lateinit var context: Context
    private val walDbName = "test_pantry_wal_backup.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(walDbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(walDbName)
    }

    private class RoomTransactionRunner(private val db: AppDatabase) : DatabaseTransactionRunner {
        override suspend fun <T> runInTransaction(block: suspend () -> T): T {
            return db.runInTransaction<T> {
                runBlocking { block() }
            }
        }
    }

    @Test
    fun walMode_fileBackedDatabase_capturesRecentlyCommittedWalRecords() = runBlocking {
        // 1. Create a REAL file-backed Room database with WAL journal mode
        val db = Room.databaseBuilder(context, AppDatabase::class.java, walDbName)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .allowMainThreadQueries()
            .build()

        try {
            // 2. Seed synthetic records into WAL-backed database
            db.householdIdentityDao().insertHouseholdIdentity(
                HouseholdIdentityEntity("house-wal-100", "WAL Household", "main-wal-1", 1000L)
            )
            val dbFile = context.getDatabasePath(walDbName)
            assertTrue("Database file MUST exist on disk after writes", dbFile.exists())
            db.canonicalSyncDao().updateSequenceEntity(
                CanonicalSyncSequenceEntity(1, 15L)
            )
            db.inventoryDao().insertItem(
                InventoryItemEntity(
                    id = "item-wal-1",
                    barcode = "999888777",
                    canonicalIngredientId = "ING_MILK",
                    canonicalName = "milk",
                    displayName = "Whole Milk",
                    brand = "Cravendale",
                    category = FoodCategory.CUPBOARD,
                    storageLocation = StorageLocation.FRIDGE,
                    quantity = 2.0,
                    quantityUnit = QuantityUnit.LITRE,
                    packCount = 1,
                    packSize = 2.0,
                    packSizeUnit = QuantityUnit.LITRE,
                    quantityConfidence = QuantityConfidence.EXACT,
                    status = ItemStatus.AVAILABLE,
                    opened = true,
                    frozen = false,
                    staple = true,
                    purchaseDate = "2026-10-10",
                    openedDate = "2026-10-11",
                    useByDate = "2026-10-20",
                    bestBeforeDate = null,
                    notes = "WAL Backup Safety Test",
                    createdAt = 1000L,
                    updatedAt = 2000L,
                    revision = 5L
                )
            )
            db.canonicalSyncDao().insertCanonicalOperation(
                CanonicalSyncOperationEntity(
                    operationId = "op-wal-10",
                    deviceId = "follower-wal-1",
                    deviceSequence = 1L,
                    protocolVersion = 1,
                    operationType = "SET_QUANTITY",
                    targetItemId = "item-wal-1",
                    baseRevision = 4L,
                    payloadJson = "{}",
                    createdAt = 1500L,
                    canonicalSequence = 10L,
                    receivedAt = 1600L,
                    status = "APPLIED",
                    resultCode = "OK",
                    resultPayloadJson = null
                )
            )

            // 3. Capture snapshot from WAL database
            val snapshotBuilder = MainBackupSnapshotBuilder(db, RoomTransactionRunner(db))
            val dto = snapshotBuilder.createMainBackupSnapshot(currentTimeMs = 3000000L)

            // 4. Assert WAL-committed data captured accurately
            assertNotNull(dto)
            assertEquals("pantry-household-backup-v1", dto.backupFormatVersion)
            assertEquals(16, dto.roomSchemaVersion)
            assertEquals("MAIN", dto.role)
            assertEquals("house-wal-100", dto.householdId)
            assertEquals(15L, dto.nextCanonicalSequence)

            assertEquals(1, dto.inventory.size)
            val inv = dto.inventory.first()
            assertEquals("item-wal-1", inv.id)
            assertEquals("Whole Milk", inv.displayName)
            assertEquals(2.0, inv.quantity, 0.001)
            assertEquals(5L, inv.revision)

            assertEquals(1, dto.canonicalOperations.size)
            val op = dto.canonicalOperations.first()
            assertEquals("op-wal-10", op.operationId)
            assertEquals(10L, op.canonicalSequence)
        } finally {
            db.close()
        }
    }

    @Test
    fun concurrentWrites_snapshotReadsSingleCoherentState() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        try {
            // Seed State A
            db.householdIdentityDao().insertHouseholdIdentity(
                HouseholdIdentityEntity("house-state-a", "State A Household", "main-1", 1000L)
            )
            db.canonicalSyncDao().updateSequenceEntity(
                CanonicalSyncSequenceEntity(1, 15L)
            )
            db.inventoryDao().insertItem(
                InventoryItemEntity(
                    id = "item-conc-1",
                    barcode = null,
                    canonicalIngredientId = "ING_BUTTER",
                    canonicalName = "butter",
                    displayName = "Butter",
                    brand = null,
                    category = FoodCategory.CUPBOARD,
                    storageLocation = StorageLocation.FRIDGE,
                    quantity = 7.0,
                    quantityUnit = QuantityUnit.UNIT,
                    packCount = null,
                    packSize = null,
                    packSizeUnit = null,
                    quantityConfidence = QuantityConfidence.EXACT,
                    status = ItemStatus.AVAILABLE,
                    opened = false,
                    frozen = false,
                    staple = true,
                    purchaseDate = null,
                    openedDate = null,
                    useByDate = null,
                    bestBeforeDate = null,
                    notes = null,
                    createdAt = 1000L,
                    updatedAt = 1000L,
                    revision = 5L
                )
            )
            db.canonicalSyncDao().insertCanonicalOperation(
                CanonicalSyncOperationEntity(
                    operationId = "op-conc-10",
                    deviceId = "follower-1",
                    deviceSequence = 1L,
                    protocolVersion = 1,
                    operationType = "SET_QUANTITY",
                    targetItemId = "item-conc-1",
                    baseRevision = 4L,
                    payloadJson = "{}",
                    createdAt = 1000L,
                    canonicalSequence = 10L,
                    receivedAt = 1000L,
                    status = "APPLIED",
                    resultCode = "OK",
                    resultPayloadJson = null
                )
            )

            val snapshotBuilder = MainBackupSnapshotBuilder(db, RoomTransactionRunner(db))

            // Execute snapshot and concurrent write
            val snapshotJob = async(Dispatchers.IO) {
                snapshotBuilder.createMainBackupSnapshot(currentTimeMs = 4000000L)
            }

            val writeJob = async(Dispatchers.IO) {
                db.runInTransaction {
                    runBlocking {
                        val currentItem = db.inventoryDao().getItemById("item-conc-1")
                        if (currentItem != null) {
                            db.inventoryDao().updateItem(
                                currentItem.copy(quantity = 9.0, revision = 6L, updatedAt = 2000L)
                            )
                        }
                        db.canonicalSyncDao().updateSequenceEntity(
                            CanonicalSyncSequenceEntity(1, 16L)
                        )
                        db.canonicalSyncDao().insertCanonicalOperation(
                            CanonicalSyncOperationEntity(
                                operationId = "op-conc-11",
                                deviceId = "follower-1",
                                deviceSequence = 2L,
                                protocolVersion = 1,
                                operationType = "SET_QUANTITY",
                                targetItemId = "item-conc-1",
                                baseRevision = 5L,
                                payloadJson = "{}",
                                createdAt = 2000L,
                                canonicalSequence = 11L,
                                receivedAt = 2000L,
                                status = "APPLIED",
                                resultCode = "OK",
                                resultPayloadJson = null
                            )
                        )
                    }
                }
            }

            val dto = snapshotJob.await()
            writeJob.await()

            assertNotNull(dto)
            val inv = dto.inventory.first()
            val opCount = dto.canonicalOperations.size
            val nextSeq = dto.nextCanonicalSequence

            // Coherence Assertion: DTO MUST reflect either COMPLETE STATE A or COMPLETE STATE B
            val isStateA = (inv.quantity == 7.0 && inv.revision == 5L && opCount == 1 && nextSeq == 15L)
            val isStateB = (inv.quantity == 9.0 && inv.revision == 6L && opCount == 2 && nextSeq == 16L)

            assertTrue(
                "Snapshot MUST reflect complete State A OR complete State B (never a mixed/inconsistent snapshot). Found qty=${inv.quantity}, rev=${inv.revision}, opCount=$opCount, nextSeq=$nextSeq",
                isStateA || isStateB
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun readFailure_propagatesExceptionAndFailsWithoutPartialSuccess() = runBlocking {
        val failingRunner = object : DatabaseTransactionRunner {
            override suspend fun <T> runInTransaction(block: suspend () -> T): T {
                throw android.database.sqlite.SQLiteException("Injected database read failure during snapshot transaction")
            }
        }

        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        try {
            val snapshotBuilder = MainBackupSnapshotBuilder(db, failingRunner)

            try {
                snapshotBuilder.createMainBackupSnapshot()
                fail("createMainBackupSnapshot MUST propagate exception and not return partial success")
            } catch (e: Exception) {
                assertTrue(
                    "Propagated exception MUST be SQLiteException or cause SQLiteException",
                    e is android.database.sqlite.SQLiteException || e.cause is android.database.sqlite.SQLiteException
                )
            }
        } finally {
            db.close()
        }
    }
}
