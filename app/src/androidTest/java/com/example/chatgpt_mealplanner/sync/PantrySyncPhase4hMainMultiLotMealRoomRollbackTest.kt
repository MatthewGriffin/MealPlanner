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
import com.example.chatgpt_mealplanner.core.database.entity.toEntity
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.Meal
import com.example.chatgpt_mealplanner.core.model.MealIngredient
import com.example.chatgpt_mealplanner.core.model.MealStatus
import com.example.chatgpt_mealplanner.core.model.MealType
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.service.MealDomainServiceImpl
import com.example.chatgpt_mealplanner.core.sync.AppRole
import com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
import com.example.chatgpt_mealplanner.core.sync.HouseholdIdentityManager
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PantrySyncPhase4hMainMultiLotMealRoomRollbackTest {

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
    fun realRoom_mainMultiLotRollback_onSecondCanonicalOpFailure_rollsBackAllLots() = runBlocking {
        val inventoryDao = appDb.inventoryDao()
        val canonicalSyncDao = appDb.canonicalSyncDao()
        val identityDao = appDb.householdIdentityDao()
        val mealDao = appDb.mealDao()

        // 1. Arrange identity & initial sequence = 10
        identityDao.insertHouseholdIdentity(HouseholdIdentityEntity("house-1", "Main Household", "main-device-100", System.currentTimeMillis()))
        canonicalSyncDao.updateSequenceEntity(CanonicalSyncSequenceEntity(1, 10L))

        // 2. Insert Lot 1 (1.0 L) and Lot 2 (2.0 L)
        val lot1 = createSampleItem("lot-multi-1", "ing-oil", "Olive Oil Lot 1", quantity = 1.0, revision = 1L)
        val lot2 = createSampleItem("lot-multi-2", "ing-oil", "Olive Oil Lot 2", quantity = 2.0, revision = 1L)
        inventoryDao.insertItem(lot1)
        inventoryDao.insertItem(lot2)

        // 3. Create planned meal requiring 2.5 L
        val now = System.currentTimeMillis()
        val meal = Meal(
            id = "meal-multi-real",
            date = "2026-10-12",
            mealType = MealType.DINNER,
            title = "Pasta Party",
            servings = 4,
            status = MealStatus.PLANNED,
            createdAt = now,
            updatedAt = now
        )
        val ingredient = MealIngredient(
            id = "meal-ing-multi",
            mealId = "meal-multi-real",
            canonicalIngredientId = "ing-oil",
            displayName = "Olive Oil",
            plannedQuantity = 2.5,
            plannedUnit = QuantityUnit.LITRE,
            actualQuantity = 2.5,
            actualUnit = QuantityUnit.LITRE
        )
        mealDao.insertMeal(meal.toEntity())
        mealDao.insertMealIngredients(listOf(ingredient.toEntity()))

        // 4. Inject SQLite trigger failure AFTER the first canonical op write
        val writableDb = appDb.openHelper.writableDatabase
        writableDb.execSQL("""
            CREATE TRIGGER fail_on_second_canonical_op
            BEFORE INSERT ON canonical_sync_operations
            WHEN (SELECT COUNT(*) FROM canonical_sync_operations) >= 1
            BEGIN
                SELECT RAISE(ABORT, 'Injected SQLite error on second canonical operation write');
            END;
        """.trimIndent())

        // 5. Execute production markMealCooked with REAL AppDatabase and RoomTransactionRunner
        val transactionRunner = RoomTransactionRunner(appDb)
        val appRoleResolver = object : AppRoleResolver(FollowerPairingStore(context), identityDao) {
            override suspend fun getAppRoleAsync(): AppRole = AppRole.MAIN
            override suspend fun isMainAsync(): Boolean = true
        }
        val identityManager = HouseholdIdentityManager(identityDao)

        val mealService = MealDomainServiceImpl(
            mealDao = mealDao,
            inventoryDao = inventoryDao,
            transactionDao = appDb.transactionDao(),
            leftoverDao = appDb.leftoverDao(),
            transactionRunner = transactionRunner,
            appRoleResolver = appRoleResolver,
            canonicalSyncDao = canonicalSyncDao,
            householdIdentityManager = identityManager
        )

        var caughtTriggerError: Throwable? = null
        try {
            mealService.markMealCooked("meal-multi-real")
        } catch (e: Throwable) {
            caughtTriggerError = e
        }

        assertNotNull("markMealCooked MUST throw SQLite trigger error on 2nd lot canonical op write", caughtTriggerError)

        // 6. Query REAL SQLite database and prove multi-lot rollback:
        val lot1After = inventoryDao.getItemById("lot-multi-1")
        assertNotNull(lot1After)
        assertEquals("Lot 1 quantity MUST remain 1.0 L", 1.0, lot1After!!.quantity!!, 0.001)
        assertEquals("Lot 1 revision MUST remain 1L", 1L, lot1After.revision)
        assertEquals("Lot 1 status MUST remain AVAILABLE", ItemStatus.AVAILABLE, lot1After.status)

        val lot2After = inventoryDao.getItemById("lot-multi-2")
        assertNotNull(lot2After)
        assertEquals("Lot 2 quantity MUST remain 2.0 L", 2.0, lot2After!!.quantity!!, 0.001)
        assertEquals("Lot 2 revision MUST remain 1L", 1L, lot2After.revision)
        assertEquals("Lot 2 status MUST remain AVAILABLE", ItemStatus.AVAILABLE, lot2After.status)

        val mealAfter = mealDao.getMealWithIngredients("meal-multi-real")
        assertNotNull(mealAfter)
        assertEquals("Meal status MUST remain PLANNED", MealStatus.PLANNED, mealAfter!!.meal.status)

        val seqAfter = canonicalSyncDao.getSequenceEntity()
        assertEquals("Canonical nextSequence MUST remain 10L", 10L, seqAfter?.nextSequence)

        val opsCount = canonicalSyncDao.getFeedOperationsSince(0L, 100).size
        assertEquals("Canonical operations count MUST remain 0", 0, opsCount)

        val txsCount = appDb.transactionDao().getTransactionsForMeal("meal-multi-real").size
        assertEquals("Inventory transactions count for meal MUST remain 0", 0, txsCount)
    }
}
