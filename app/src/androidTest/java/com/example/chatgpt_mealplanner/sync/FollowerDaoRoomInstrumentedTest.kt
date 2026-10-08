package com.example.chatgpt_mealplanner.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FollowerDaoRoomInstrumentedTest {

    private lateinit var database: FollowerDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(
            context,
            FollowerDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testFollowerDao_roomReactiveFlows_emitDatabaseUpdates() = runBlocking {
        val dao = database.followerDao()

        val initialItems = dao.observeAllItems().first()
        assertTrue("Initial database should be empty", initialItems.isEmpty())

        val testItem = FollowerInventoryItemEntity(
            itemId = "7b197c46-780c-424c-8b45-1a9368f94d68",
            barcode = null,
            canonicalName = "fusilli pasta",
            confirmedDisplayName = "fusilli pasta",
            confirmedBrand = null,
            confirmedCategory = "CUPBOARD",
            confirmedStorageLocation = "CUPBOARD",
            confirmedQuantity = 3.0,
            confirmedQuantityUnit = "KILOGRAM",
            confirmedNotes = null,
            confirmedStatus = "AVAILABLE",
            mainConfirmedRevision = 1L,
            displayName = "fusilli pasta",
            brand = null,
            category = "CUPBOARD",
            storageLocation = "CUPBOARD",
            quantity = 3.0,
            quantityUnit = "KILOGRAM",
            notes = null,
            status = "AVAILABLE",
            optimisticRevision = 1L,
            isPendingSync = false,
            updatedAt = System.currentTimeMillis()
        )
        dao.insertOrUpdateItem(testItem)

        val observedAll = dao.observeAllItems().first()
        assertEquals(1, observedAll.size)
        assertEquals("fusilli pasta", observedAll[0].displayName)

        val observedUsable = dao.observeUsableItems().first()
        assertEquals(1, observedUsable.size)

        val observedByStatus = dao.observeItemsByStatus("AVAILABLE").first()
        assertEquals(1, observedByStatus.size)

        val updatedItem = testItem.copy(quantity = 4.5)
        dao.insertOrUpdateItem(updatedItem)

        val updatedObserved = dao.observeAllItems().first()
        assertEquals(1, updatedObserved.size)
        assertEquals(4.5, updatedObserved[0].quantity!!, 0.001)
    }
}
