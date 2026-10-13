package com.example.chatgpt_mealplanner.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatgpt_mealplanner.core.backup.MainBackupSnapshotBuilder
import com.example.chatgpt_mealplanner.core.database.AppDatabase
import com.example.chatgpt_mealplanner.core.database.DatabaseTransactionRunner
import com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncOperationEntity
import com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncSequenceEntity
import com.example.chatgpt_mealplanner.core.database.entity.HouseholdIdentityEntity
import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemEntity
import com.example.chatgpt_mealplanner.core.database.entity.LeftoverEntity
import com.example.chatgpt_mealplanner.core.database.entity.MealEntity
import com.example.chatgpt_mealplanner.core.database.entity.MealIngredientEntity
import com.example.chatgpt_mealplanner.core.database.entity.ShoppingListEntity
import com.example.chatgpt_mealplanner.core.database.entity.ShoppingListItemEntity
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.LeftoverStatus
import com.example.chatgpt_mealplanner.core.model.MealStatus
import com.example.chatgpt_mealplanner.core.model.MealTag
import com.example.chatgpt_mealplanner.core.model.MealType
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.RequirementConfidence
import com.example.chatgpt_mealplanner.core.model.ShoppingItemStatus
import com.example.chatgpt_mealplanner.core.model.ShoppingListStatus
import com.example.chatgpt_mealplanner.core.model.ShoppingPriority
import com.example.chatgpt_mealplanner.core.model.ShoppingSourceType
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.model.WasteRisk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PantryPhase6Step3MainBackupRoomTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        if (::database.isInitialized && database.isOpen) {
            database.close()
        }
    }

    private class RoomTransactionRunner(private val db: AppDatabase) : DatabaseTransactionRunner {
        override suspend fun <T> runInTransaction(block: suspend () -> T): T {
            return db.runInTransaction<T> {
                runBlocking { block() }
            }
        }
    }

    @Test
    fun realRoom_emptyDatabase_createsValidEmptyMainHouseholdBackupDto() = runBlocking {
        val snapshotBuilder = MainBackupSnapshotBuilder(database, RoomTransactionRunner(database))

        val dto = snapshotBuilder.createMainBackupSnapshot(currentTimeMs = 2000000L)

        assertNotNull(dto)
        assertEquals("pantry-household-backup-v1", dto.backupFormatVersion)
        assertEquals(16, dto.roomSchemaVersion)
        assertEquals("MAIN", dto.role)
        assertEquals(2000000L, dto.exportedAt)
        assertTrue("Inventory MUST be empty for empty DB", dto.inventory.isEmpty())
        assertTrue("Canonical operations MUST be empty for empty DB", dto.canonicalOperations.isEmpty())
    }

    @Test
    fun realRoom_populatedDatabase_capturesFullMainHouseholdStateAtomically() = runBlocking {
        // 1. Seed synthetic Main data into REAL Room database
        database.householdIdentityDao().insertHouseholdIdentity(
            HouseholdIdentityEntity("house-100", "Family Pantry", "main-dev-1", 1000L)
        )
        database.canonicalSyncDao().updateSequenceEntity(
            CanonicalSyncSequenceEntity(1, 15L)
        )
        database.inventoryDao().insertItem(
            InventoryItemEntity(
                id = "item-100",
                barcode = "123456789",
                canonicalIngredientId = "ING_OIL",
                canonicalName = "olive oil",
                displayName = "Extra Virgin Olive Oil",
                brand = "Asda",
                category = FoodCategory.CUPBOARD,
                storageLocation = StorageLocation.CUPBOARD,
                quantity = 3.5,
                quantityUnit = QuantityUnit.LITRE,
                packCount = 1,
                packSize = 1.0,
                packSizeUnit = QuantityUnit.LITRE,
                quantityConfidence = QuantityConfidence.EXACT,
                status = ItemStatus.AVAILABLE,
                opened = true,
                frozen = false,
                staple = true,
                purchaseDate = "2026-10-01",
                openedDate = "2026-10-02",
                useByDate = "2027-10-01",
                bestBeforeDate = null,
                notes = "Real Room Backup Test",
                createdAt = 1000L,
                updatedAt = 2000L,
                revision = 5L
            )
        )
        database.canonicalSyncDao().insertCanonicalOperation(
            CanonicalSyncOperationEntity(
                operationId = "op-100",
                deviceId = "follower-dev-1",
                deviceSequence = 1L,
                protocolVersion = 1,
                operationType = "SET_QUANTITY",
                targetItemId = "item-100",
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
        database.mealDao().insertMeal(
            MealEntity(
                id = "meal-100",
                date = "2026-10-13",
                mealType = MealType.DINNER,
                title = "Pasta Primavera",
                servings = 4,
                status = MealStatus.COOKED,
                batchPortions = 0,
                immediatePortions = 2,
                leftoverPortions = 2,
                cookInTwoBatches = false,
                tags = setOf(MealTag.PASTA),
                instructions = "Cook pasta",
                notes = "Tasty",
                cookedAt = 1800L,
                createdAt = 1000L,
                updatedAt = 1800L
            )
        )
        database.mealDao().insertMealIngredients(
            listOf(
                MealIngredientEntity(
                    id = "ing-100",
                    mealId = "meal-100",
                    canonicalIngredientId = "ING_OIL",
                    displayName = "Olive Oil",
                    plannedQuantity = 0.5,
                    plannedUnit = QuantityUnit.LITRE,
                    actualQuantity = 0.5,
                    actualUnit = QuantityUnit.LITRE,
                    optional = false,
                    substitutionAllowed = false,
                    substitutedCanonicalIngredientId = null,
                    substitutedDisplayName = null
                )
            )
        )
        database.leftoverDao().insertLeftover(
            LeftoverEntity(
                id = "left-100",
                sourceMealId = "meal-100",
                name = "Pasta Primavera Leftover",
                initialPortions = 2,
                portions = 2,
                storageLocation = StorageLocation.FRIDGE,
                status = LeftoverStatus.AVAILABLE,
                createdDate = "2026-10-13",
                consumeByDate = "2026-10-16",
                createdAt = 1800L,
                updatedAt = 1800L
            )
        )
        database.shoppingDao().insertShoppingList(
            ShoppingListEntity(
                id = "list-100",
                name = "Weekly Grocery List",
                startDate = "2026-10-13",
                endDate = "2026-10-20",
                status = ShoppingListStatus.ACTIVE,
                createdAt = 1000L,
                updatedAt = 1000L
            )
        )
        database.shoppingDao().insertShoppingListItem(
            ShoppingListItemEntity(
                id = "shop-item-100",
                shoppingListId = "list-100",
                canonicalIngredientId = "ING_OIL",
                displayName = "Olive Oil Top Up",
                requiredQuantity = 1.0,
                requiredUnit = QuantityUnit.LITRE,
                suggestedPurchaseQuantity = 1.0,
                suggestedPurchaseUnit = QuantityUnit.LITRE,
                packCount = 1,
                reason = "Staple reserve",
                priority = ShoppingPriority.ESSENTIAL,
                status = ShoppingItemStatus.NEEDED,
                optional = false,
                sourceType = ShoppingSourceType.STAPLE_RESTOCK,
                hasMealRequirement = false,
                hasStapleRestock = true,
                mealShortfallQuantity = null,
                stapleReserveTopUpQuantity = 1.0,
                expectedFinalInventory = 4.5,
                expectedFinalInventoryUnit = QuantityUnit.LITRE,
                quantityUncertain = false,
                uncertaintyReason = null,
                requirementConfidence = RequirementConfidence.EXACT,
                wasteRisk = WasteRisk.LOW,
                createdAt = 1000L,
                updatedAt = 1000L
            )
        )

        // 2. Build snapshot from REAL Room database
        val snapshotBuilder = MainBackupSnapshotBuilder(database, RoomTransactionRunner(database))
        val dto = snapshotBuilder.createMainBackupSnapshot(currentTimeMs = 2000000L)

        // 3. Assert full state, identity, sequence, and relationships
        assertNotNull(dto)
        assertEquals("pantry-household-backup-v1", dto.backupFormatVersion)
        assertEquals(16, dto.roomSchemaVersion)
        assertEquals("MAIN", dto.role)
        assertEquals("house-100", dto.householdId)
        assertEquals("Family Pantry", dto.householdName)
        assertEquals("main-dev-1", dto.mainDeviceId)
        assertEquals(15L, dto.nextCanonicalSequence)

        // Inventory checks
        assertEquals(1, dto.inventory.size)
        val inv = dto.inventory.first()
        assertEquals("item-100", inv.id)
        assertEquals("Extra Virgin Olive Oil", inv.displayName)
        assertEquals(3.5, inv.quantity, 0.001)
        assertEquals(5L, inv.revision)

        // Canonical operations checks
        assertEquals(1, dto.canonicalOperations.size)
        val op = dto.canonicalOperations.first()
        assertEquals("op-100", op.operationId)
        assertEquals(10L, op.canonicalSequence)
        assertEquals("SET_QUANTITY", op.operationType)

        // Meals checks
        assertEquals(1, dto.meals.size)
        val mealDto = dto.meals.first()
        assertEquals("meal-100", mealDto.id)
        assertEquals("Pasta Primavera", mealDto.title)
        assertEquals(1, mealDto.ingredients.size)
        assertEquals("ing-100", mealDto.ingredients.first().id)

        // Leftovers checks
        assertEquals(1, dto.leftovers.size)
        assertEquals("left-100", dto.leftovers.first().id)

        // Shopping lists checks
        assertEquals(1, dto.shoppingLists.size)
        assertEquals("list-100", dto.shoppingLists.first().id)
        assertEquals(1, dto.shoppingLists.first().items.size)

        // Read-only database assertion: database records MUST remain 100% unchanged
        val dbItem = database.inventoryDao().getItemById("item-100")
        assertNotNull("Database record MUST remain untouched", dbItem)
        assertEquals(3.5, dbItem!!.quantity!!, 0.001)
        assertEquals(5L, dbItem.revision)
    }
}
