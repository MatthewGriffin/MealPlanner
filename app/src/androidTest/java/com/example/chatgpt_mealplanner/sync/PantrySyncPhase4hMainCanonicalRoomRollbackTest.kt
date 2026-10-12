package com.example.chatgpt_mealplanner.sync

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatgpt_mealplanner.core.database.ALL_MIGRATIONS
import com.example.chatgpt_mealplanner.core.database.AppDatabase
import com.example.chatgpt_mealplanner.core.database.RoomTransactionRunner
import com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncSequenceEntity
import com.example.chatgpt_mealplanner.core.database.entity.HouseholdIdentityEntity
import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemEntity
import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.service.InventoryDomainServiceImpl
import com.example.chatgpt_mealplanner.core.sync.AppRole
import com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
import com.example.chatgpt_mealplanner.core.sync.HouseholdIdentityManager
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PantrySyncPhase4hMainCanonicalRoomRollbackTest {

    private lateinit var appDb: AppDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext

        appDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addMigrations(*ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        if (::appDb.isInitialized && appDb.isOpen) {
            appDb.close()
        }
    }

    private fun createSampleItem(
        id: String,
        canonicalIngredientId: String,
        displayName: String,
        quantity: Double = 2.0,
        status: ItemStatus = ItemStatus.AVAILABLE,
        revision: Long = 1L
    ): InventoryItemEntity = InventoryItemEntity(
        id = id,
        barcode = null,
        canonicalIngredientId = canonicalIngredientId,
        canonicalName = displayName.lowercase(),
        displayName = displayName,
        brand = null,
        category = FoodCategory.CUPBOARD,
        storageLocation = StorageLocation.CUPBOARD,
        quantity = quantity,
        quantityUnit = QuantityUnit.LITRE,
        packCount = null,
        packSize = null,
        packSizeUnit = null,
        quantityConfidence = QuantityConfidence.EXACT,
        status = status,
        opened = true,
        frozen = false,
        staple = false,
        purchaseDate = null,
        openedDate = null,
        useByDate = null,
        bestBeforeDate = null,
        notes = null,
        createdAt = 1000L,
        updatedAt = 1000L,
        revision = revision
    )

    @Test
    fun realRoom_mainCanonicalRollback_onTriggerFailure_rollsBackInventoryAndSequence() = runBlocking {
        val inventoryDao = appDb.inventoryDao()
        val canonicalSyncDao = appDb.canonicalSyncDao()
        val identityDao = appDb.householdIdentityDao()

        // 1. Arrange identity & initial canonical sequence = 10
        identityDao.insertHouseholdIdentity(HouseholdIdentityEntity("house-1", "Main Household", "main-device-100", System.currentTimeMillis()))
        canonicalSyncDao.updateSequenceEntity(CanonicalSyncSequenceEntity(1, 10L))

        // 2. Insert item
        val lotId = "lot-main-rollback-1"
        val lot = createSampleItem(lotId, "ing-oil", "Olive Oil", quantity = 2.0, revision = 1L)
        inventoryDao.insertItem(lot)

        // 3. Inject SQLite trigger failure on canonical_sync_operations insert
        val writableDb = appDb.openHelper.writableDatabase
        writableDb.execSQL("""
            CREATE TRIGGER fail_on_canonical_op
            BEFORE INSERT ON canonical_sync_operations
            BEGIN
                SELECT RAISE(ABORT, 'Injected SQLite error on canonical operation write');
            END;
        """.trimIndent())

        // 4. Instantiate production InventoryDomainServiceImpl with REAL AppDatabase and RoomTransactionRunner
        val transactionRunner = RoomTransactionRunner(appDb)
        val appRoleResolver = object : AppRoleResolver(FollowerPairingStore(context), identityDao) {
            override suspend fun getAppRoleAsync(): AppRole = AppRole.MAIN
            override suspend fun isMainAsync(): Boolean = true
        }
        val identityManager = HouseholdIdentityManager(identityDao)

        val inventoryService = InventoryDomainServiceImpl(
            inventoryDao = inventoryDao,
            inventoryBarcodeDao = appDb.inventoryBarcodeDao(),
            transactionDao = appDb.transactionDao(),
            stapleRuleDao = appDb.stapleRuleDao(),
            transactionRunner = transactionRunner,
            appRoleResolver = appRoleResolver,
            canonicalSyncDao = canonicalSyncDao,
            householdIdentityManager = identityManager
        )

        // 5. Execute quantity correction that attempts to update item AND insert canonical operation inside production Room transaction
        var caughtTriggerError: Throwable? = null
        try {
            inventoryService.recordUserCorrection(
                itemId = lotId,
                newQuantity = 1.0,
                newConfidence = QuantityConfidence.EXACT,
                newStatus = ItemStatus.AVAILABLE,
                note = "Correction test"
            )
        } catch (e: Throwable) {
            caughtTriggerError = e
        }

        assertNotNull("recordUserCorrection MUST throw SQLite trigger error on canonical operation write", caughtTriggerError)

        // 6. Query REAL SQLite database and prove 100% full transaction rollback:
        val lotAfter = inventoryDao.getItemById(lotId)
        assertNotNull(lotAfter)
        assertEquals("Inventory lot quantity MUST remain 2.0 L", 2.0, lotAfter!!.quantity!!, 0.001)
        assertEquals("Inventory lot revision MUST remain 1L", 1L, lotAfter.revision)
        assertEquals("Inventory lot status MUST remain AVAILABLE", ItemStatus.AVAILABLE, lotAfter.status)

        val seqAfter = canonicalSyncDao.getSequenceEntity()
        assertEquals("Canonical nextSequence MUST remain 10L", 10L, seqAfter?.nextSequence)

        val opsCount = canonicalSyncDao.getFeedOperationsSince(0L, 100).size
        assertEquals("Canonical operations count MUST remain 0", 0, opsCount)
    }
}
