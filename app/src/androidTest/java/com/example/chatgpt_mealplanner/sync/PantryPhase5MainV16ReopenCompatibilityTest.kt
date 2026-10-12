package com.example.chatgpt_mealplanner.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatgpt_mealplanner.core.database.AppDatabase
import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemEntity
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PantryPhase5MainV16ReopenCompatibilityTest {

    private lateinit var context: Context
    private val dbName = "test_main_v16_reopen.db"

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
    fun realRoom_mainDatabase_preexistingV16_reopensWithoutSchemaError() = runBlocking {
        // 1. Create a fresh v16 database using production Room AppDatabase v16
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .build()

        val sampleItem = InventoryItemEntity(
            id = "item-v16-reopen-1",
            barcode = null,
            canonicalIngredientId = null,
            canonicalName = "apples",
            displayName = "Red Gala Apples",
            brand = null,
            category = FoodCategory.FRESH_PANTRY,
            storageLocation = StorageLocation.FRIDGE,
            quantity = 6.0,
            quantityUnit = QuantityUnit.UNIT,
            packCount = null,
            packSize = null,
            packSizeUnit = null,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE,
            opened = false,
            frozen = false,
            staple = false,
            purchaseDate = null,
            openedDate = null,
            useByDate = null,
            bestBeforeDate = null,
            notes = "Preexisting v16 test",
            createdAt = 1000L,
            updatedAt = 1000L,
            revision = 1L
        )
        db.inventoryDao().insertItem(sampleItem)
        db.close()

        // 2. Reopen the existing v16 database and verify TableInfo and identityHash validation pass 100%
        val reopenedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .build()

        val retrievedItem = reopenedDb.inventoryDao().getItemById("item-v16-reopen-1")
        assertNotNull("Item in preexisting v16 database MUST be readable upon reopen", retrievedItem)
        assertEquals("Red Gala Apples", retrievedItem!!.displayName)
        assertEquals(6.0, retrievedItem.quantity!!, 0.001)
        assertEquals(1L, retrievedItem.revision)

        reopenedDb.close()
    }
}
