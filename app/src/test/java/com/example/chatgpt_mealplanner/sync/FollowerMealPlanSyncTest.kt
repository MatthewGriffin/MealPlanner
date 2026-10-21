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
            FollowerMealPlanSyncEngine(db, FakeBootstrapSyncTestClient(), main).install(snapshot(listOf(meal())))
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
        val main = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
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
                FollowerMealPlanSyncEngine(db, client, main))
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
        } finally { db.close(); main.close() }
    }

    @Test fun mainSnapshotPublishesExistingMealPlanWithIngredients() = runBlocking {
        val main = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val model = com.example.chatgpt_mealplanner.core.model.Meal("meal_1", "2026-10-19", title = "Pasta", instructions = "Bake.", createdAt = 123L)
            main.mealDao().insertMeal(model.toEntity())
            main.shoppingDao().insertShoppingList(com.example.chatgpt_mealplanner.core.database.entity.ShoppingListEntity(
                "list_1", "Weekly", null, null, com.example.chatgpt_mealplanner.core.model.ShoppingListStatus.ACTIVE, 1L, 2L))
            main.shoppingDao().insertShoppingListItem(com.example.chatgpt_mealplanner.core.database.entity.ShoppingListItemEntity(
                id = "item_1", shoppingListId = "list_1", canonicalIngredientId = "ING_PASTA", displayName = "Pasta",
                requiredQuantity = 1.0, requiredUnit = com.example.chatgpt_mealplanner.core.model.QuantityUnit.PACK,
                suggestedPurchaseQuantity = 1.0, suggestedPurchaseUnit = com.example.chatgpt_mealplanner.core.model.QuantityUnit.PACK,
                packCount = 1, reason = "Meal plan", priority = com.example.chatgpt_mealplanner.core.model.ShoppingPriority.ESSENTIAL,
                status = com.example.chatgpt_mealplanner.core.model.ShoppingItemStatus.CHECKED, optional = false,
                sourceType = com.example.chatgpt_mealplanner.core.model.ShoppingSourceType.MANUAL,
                hasMealRequirement = true, hasStapleRestock = false, mealShortfallQuantity = null,
                stapleReserveTopUpQuantity = null, expectedFinalInventory = null, expectedFinalInventoryUnit = null,
                quantityUncertain = false, uncertaintyReason = null,
                requirementConfidence = com.example.chatgpt_mealplanner.core.model.RequirementConfidence.EXACT,
                wasteRisk = com.example.chatgpt_mealplanner.core.model.WasteRisk.NONE, createdAt = 1L, updatedAt = 2L))
            main.stapleRuleDao().insertOrUpdateStapleRule(com.example.chatgpt_mealplanner.core.database.entity.StapleRuleEntity(
                id = "staple_1", canonicalIngredientId = "ING_SALT", displayName = "Salt", enabled = true,
                mode = com.example.chatgpt_mealplanner.core.model.StapleRuleMode.QUANTITY, thresholdQuantity = 1.0,
                thresholdUnit = com.example.chatgpt_mealplanner.core.model.QuantityUnit.PACK, targetQuantity = 3.0,
                targetUnit = com.example.chatgpt_mealplanner.core.model.QuantityUnit.PACK, thresholdPackCount = null,
                targetPackCount = null, preferredStorageLocation = com.example.chatgpt_mealplanner.core.model.StorageLocation.CUPBOARD,
                notes = null, createdAt = 1L, updatedAt = 2L))
            val server = com.example.chatgpt_mealplanner.core.sync.server.PantrySyncServer(
                identityManager = com.example.chatgpt_mealplanner.core.sync.HouseholdIdentityManager(FakeHouseholdIdentityDao()),
                sessionManager = org.mockito.kotlin.mock(), syncEngine = org.mockito.kotlin.mock(),
                canonicalSyncDao = main.canonicalSyncDao(), inventoryDao = main.inventoryDao(),
                transactionRunner = com.example.chatgpt_mealplanner.core.database.RoomTransactionRunner(main),
                mealChangeRequestDao = main.mealChangeRequestDao(), mealDao = main.mealDao(),
                shoppingDao = main.shoppingDao(), stapleRuleDao = main.stapleRuleDao())
            val snapshot = server.createBootstrapSnapshot()
            assertEquals("Pasta", snapshot.mealPlan!!.single().meal.title)
            assertEquals("Bake.", snapshot.mealPlan!!.single().meal.instructions)
            assertEquals(123L, snapshot.mealPlan!!.single().meal.createdAt)
            assertEquals("list_1", snapshot.shoppingLists!!.single().id)
            assertEquals("CHECKED", snapshot.shoppingItems!!.single().status)
            assertEquals("staple_1", snapshot.stapleRules!!.single().id)
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
            val engine = FollowerMealPlanSyncEngine(db, client, main)
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

    @Test fun followerInstallProjectsShoppingProgressAndStapleRules() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, FollowerDatabase::class.java).build()
        val appDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val shoppingList = ShoppingListSyncDto("list_sync", "Weekly", status = "ACTIVE", createdAt = 1L, updatedAt = 2L)
            val item = ShoppingItemSyncDto(
                id = "item_sync", shoppingListId = "list_sync", displayName = "Milk", requiredQuantity = 2.0,
                requiredUnit = "LITRE", suggestedPurchaseQuantity = 2.0, suggestedPurchaseUnit = "LITRE", packCount = 1,
                reason = "Meal plan", priority = "ESSENTIAL", status = "CHECKED", optional = false, sourceType = "MANUAL",
                hasMealRequirement = true, hasStapleRestock = false, quantityUncertain = false,
                requirementConfidence = "EXACT", wasteRisk = "NONE", createdAt = 1L, updatedAt = 2L)
            val staple = StapleRuleSyncDto(
                id = "staple_sync", canonicalIngredientId = "ING_SALT", displayName = "Salt", enabled = true,
                mode = "QUANTITY", thresholdQuantity = 1.0, thresholdUnit = "PACK", targetQuantity = 3.0,
                targetUnit = "PACK", preferredStorageLocation = "CUPBOARD", createdAt = 1L, updatedAt = 2L)
            val payload = snapshot(emptyList()).copy(
                shoppingLists = listOf(shoppingList), shoppingItems = listOf(item), stapleRules = listOf(staple))
            FollowerMealPlanSyncEngine(db, FakeBootstrapSyncTestClient(), appDb).install(payload)
            assertEquals("CHECKED", appDb.shoppingDao().getShoppingListWithItems("list_sync")!!.items.single().item.status.name)
            assertEquals("staple_sync", appDb.stapleRuleDao().getAllRulesSync().single().id)
        } finally { db.close(); appDb.close() }
    }

    @Test fun invalidOrFailedMealSnapshotLeavesExistingProjectionUntouched() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, FollowerDatabase::class.java).build()
        val main = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val engine = FollowerMealPlanSyncEngine(db, FakeBootstrapSyncTestClient(), main)
            engine.install(snapshot(listOf(meal())))
            val before = db.mealDao().getAll()
            try { engine.install(snapshot(listOf(meal(), meal()))); fail("Duplicate accepted") } catch (_: IllegalArgumentException) {}
            assertEquals(before, db.mealDao().getAll())
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_meal_install BEFORE INSERT ON follower_meals BEGIN SELECT RAISE(ABORT, 'injected'); END")
            try { engine.install(snapshot(listOf(meal("Replacement")))); fail("Write should fail") } catch (_: android.database.sqlite.SQLiteException) {}
            assertEquals(before, db.mealDao().getAll())
        } finally { db.close(); main.close() }
    }

    @Test fun migration5To6PreservesExistingFollowerData() = runBlocking {
        val name = "meal_migration_${java.util.UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, FollowerDatabase::class.java, name).addMigrations(FOLLOWER_MIGRATION_5_6, com.example.chatgpt_mealplanner.core.sync.follower.database.FOLLOWER_MIGRATION_6_7).build()
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
