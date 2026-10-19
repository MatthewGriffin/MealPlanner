package com.example.chatgpt_mealplanner.sync

import androidx.room.Room
import com.example.chatgpt_mealplanner.core.database.entity.toEntity
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.example.chatgpt_mealplanner.core.sync.follower.database.*
import com.example.chatgpt_mealplanner.core.sync.follower.engine.*
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.*
import com.example.chatgpt_mealplanner.core.repository.MealRepositoryImpl
import com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import com.example.chatgpt_mealplanner.core.database.AppDatabase
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.sync.contract.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FollowerMealPlanSyncTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun meal(title: String = "Pasta") = MealWithIngredientsSyncDto(
        MealSyncDto("meal_1", "2026-10-19", title = title, instructions = "Bake until cooked."),
        listOf(MealIngredientSyncDto("ingredient_1", "meal_1", displayName = "Pasta", plannedQuantity = 200.0, plannedUnit = "GRAM")))
    private fun snapshot(meals: List<MealWithIngredientsSyncDto>?) = BootstrapResponseDto(
        householdId = "home", snapshotCanonicalSequence = 20L, inventoryItems = emptyList(), mealPlan = meals)

    @Test fun enrolledFollowerWithIncidentalLocalIdentityReadsSyncedMeals() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, FollowerDatabase::class.java).build()
        val main = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            FollowerMealPlanSyncEngine(db, FakeBootstrapSyncTestClient()).install(snapshot(listOf(meal())))
            val identities = FakeHouseholdIdentityDao()
            val incidental = com.example.chatgpt_mealplanner.core.sync.HouseholdIdentityManager(identities).getOrCreateHouseholdIdentity()
            val store = object : FollowerPairingStore(null) {
                override fun isPaired() = true
                override fun getPairingConfig() = com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
                    "localhost", pinnedCertFingerprint = "pin", deviceId = "follower", householdId = "home", mainDeviceId = "remote-main")
            }
            val resolver = AppRoleResolver(store, identities)
            val repo = MealRepositoryImpl(main.mealDao(), db, resolver)
            assertTrue(resolver.isFollowerAsync())
            assertTrue(resolver.isFollower())
            assertEquals("Pasta", repo.observeMealsForDate("2026-10-19").first().single().meal.title)
            assertEquals(incidental, identities.getHouseholdIdentity())
        } finally { db.close(); main.close() }
    }

    @Test fun alreadyBootstrappedCoordinatorRefreshesMealsAndReportsMissingSnapshot() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, FollowerDatabase::class.java).build()
        try {
            val client = FakeBootstrapSyncTestClient()
            val store = object : FollowerPairingStore(null) {
                override fun isPaired() = true
                override fun isBootstrapVerifiedV1() = true
                override fun getPairingConfig() = com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
                    "localhost", pinnedCertFingerprint = "pin", deviceId = "follower", sessionToken = "token", householdId = "home")
            }
            val local = FollowerLocalSyncEngine(db, null, null, db.mealRequestDao())
            val status = com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusRepository(store)
            val coordinator = FollowerSyncCoordinator(db, null,
                FollowerFeedPullEngine(db, client, local, db.mealRequestDao()), null, client, store, status,
                FollowerMealPlanSyncEngine(db, client))
            val json = kotlinx.serialization.json.Json
            client.bootstrapToReturn = json.decodeFromString<BootstrapResponseDto>(
                json.encodeToString(BootstrapResponseDto.serializer(), snapshot(listOf(meal()))))
            assertEquals(CoordinatorPassOutcome.SUCCESS, coordinator.executeSyncPass("MANUAL_SYNC"))
            assertEquals(1, db.mealDao().getAll().size)
            assertEquals(1, client.bootstrapCallCount)
            client.bootstrapToReturn = snapshot(null)
            assertEquals(CoordinatorPassOutcome.RECOVERABLE_FAILURE, coordinator.executeSyncPass("MANUAL_SYNC"))
            assertEquals(1, db.mealDao().getAll().size)
            val message = status.lastSyncErrorMessage.value
            assertTrue(message, message!!.contains("Main did not publish"))
        } finally { db.close() }
    }

    @Test fun mainSnapshotPublishesExistingMealPlanWithIngredients() = runBlocking {
        val main = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val model = com.example.chatgpt_mealplanner.core.model.Meal("meal_1", "2026-10-19", title = "Pasta", instructions = "Bake.", createdAt = 123L)
            main.mealDao().insertMeal(model.toEntity())
            val server = com.example.chatgpt_mealplanner.core.sync.server.PantrySyncServer(
                identityManager = com.example.chatgpt_mealplanner.core.sync.HouseholdIdentityManager(FakeHouseholdIdentityDao()),
                sessionManager = org.mockito.kotlin.mock(), syncEngine = org.mockito.kotlin.mock(),
                canonicalSyncDao = main.canonicalSyncDao(), inventoryDao = main.inventoryDao(),
                transactionRunner = com.example.chatgpt_mealplanner.core.database.RoomTransactionRunner(main),
                mealChangeRequestDao = main.mealChangeRequestDao(), mealDao = main.mealDao())
            val snapshot = server.createBootstrapSnapshot()
            assertEquals("Pasta", snapshot.mealPlan!!.single().meal.title)
            assertEquals("Bake.", snapshot.mealPlan!!.single().meal.instructions)
            assertEquals(123L, snapshot.mealPlan!!.single().meal.createdAt)
        } finally { main.close() }
    }

    @Test fun refreshProjectsMealsAndIngredientsAndPreservesInventoryOutboxAndCursor() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, FollowerDatabase::class.java).build()
        val main = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val local = FollowerLocalSyncEngine(db, null, null, db.mealRequestDao())
            val op = local.recordLocalAddItem("device", "Milk", "Milk", "FRIDGE", "FRIDGE", 2.0, "LITRE", null, null)
            val inventory = db.followerDao().getAllItems()
            val metadata = FollowerSyncMetadataEntity(1, 7L)
            db.followerDao().updateSyncMetadata(metadata)
            val client = FakeBootstrapSyncTestClient()
            client.bootstrapToReturn = snapshot(listOf(meal()))
            val engine = FollowerMealPlanSyncEngine(db, client)
            assertTrue(engine.refresh("localhost", 8443, null, "token", { "token" }, "home"))
            val store = object : FollowerPairingStore(null) { override fun isPaired() = true }
            val repo = MealRepositoryImpl(main.mealDao(), db, AppRoleResolver(store))
            val projected = repo.observeMealsForDate("2026-10-19").first().single()
            assertEquals("Pasta", projected.meal.title)
            assertEquals("Bake until cooked.", projected.meal.instructions)
            assertEquals(200.0, projected.ingredients.single().plannedQuantity, 0.0)
            assertEquals(projected, repo.getMealWithIngredients("meal_1"))
            assertTrue(main.mealDao().getAllMealsWithIngredientsSync().isEmpty())
            engine.install(snapshot(listOf(meal("New title"))))
            assertEquals("New title", repo.getMealWithIngredients("meal_1")?.meal?.title)
            engine.install(snapshot(null)) // Older Main must not erase cached meals.
            assertNotNull(repo.getMealWithIngredients("meal_1"))
            engine.install(snapshot(emptyList())) // Authoritative removal.
            assertTrue(repo.observeMealsForDate("2026-10-19").first().isEmpty())
            assertEquals(inventory, db.followerDao().getAllItems())
            assertEquals(op, db.followerDao().getOutboxByOperationId(op.operationId))
            assertEquals(metadata, db.followerDao().getSyncMetadata())
        } finally { db.close(); main.close() }
    }

    @Test fun invalidOrFailedMealSnapshotLeavesExistingProjectionUntouched() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, FollowerDatabase::class.java).build()
        try {
            val engine = FollowerMealPlanSyncEngine(db, FakeBootstrapSyncTestClient())
            engine.install(snapshot(listOf(meal())))
            val before = db.mealDao().getAll()
            try { engine.install(snapshot(listOf(meal(), meal()))); fail("Duplicate accepted") } catch (_: IllegalArgumentException) {}
            assertEquals(before, db.mealDao().getAll())
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_meal_install BEFORE INSERT ON follower_meals BEGIN SELECT RAISE(ABORT, 'injected'); END")
            try { engine.install(snapshot(listOf(meal("Replacement")))); fail("Write should fail") } catch (_: android.database.sqlite.SQLiteException) {}
            assertEquals(before, db.mealDao().getAll())
        } finally { db.close() }
    }

    @Test fun migration5To6PreservesExistingFollowerData() = runBlocking {
        val name = "meal_migration_${java.util.UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, FollowerDatabase::class.java, name).addMigrations(FOLLOWER_MIGRATION_5_6).build()
        var db = open()
        try {
            val local = FollowerLocalSyncEngine(db, null, null, db.mealRequestDao())
            val op = local.recordLocalMealChangeRequest("device", "req_${java.util.UUID.randomUUID()}", "meal_1", "Please change")
            val before = db.mealRequestDao().observeAllRequests().first()
            db.followerDao().updateSyncMetadata(FollowerSyncMetadataEntity(1, 12L))
            db.close()
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, 0).use {
                it.execSQL("DROP TABLE follower_meals"); it.version = 5
            }
            db = open()
            assertEquals(before, db.mealRequestDao().observeAllRequests().first())
            assertEquals(op, db.followerDao().getOutboxByOperationId(op.operationId))
            assertEquals(12L, db.followerDao().getSyncMetadata()?.lastReconciledCanonicalSequence)
            assertEquals(2L, db.followerDao().getSequenceEntity()?.nextSequence)
            assertTrue(db.mealDao().getAll().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun knownFollowerQuantityIsNotDisplayedAsUnknown() {
        val item = FollowerInventoryItemEntity(itemId = "milk", canonicalName = "Milk",
            confirmedDisplayName = "Milk", confirmedCategory = "FRIDGE", confirmedStorageLocation = "FRIDGE",
            confirmedStatus = "AVAILABLE", displayName = "Milk", category = "FRIDGE", storageLocation = "FRIDGE",
            quantity = 2.0, quantityUnit = "LITRE", status = "AVAILABLE", updatedAt = 1L)
        assertEquals(QuantityConfidence.APPROXIMATE, item.toDomainModel().quantityConfidence)
        assertEquals(2.0, item.toDomainModel().quantity!!, 0.0)
        assertEquals(QuantityConfidence.UNKNOWN, item.copy(quantity = null).toDomainModel().quantityConfidence)
    }
}
