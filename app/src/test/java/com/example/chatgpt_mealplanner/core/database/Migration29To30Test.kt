package com.example.chatgpt_mealplanner.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.chatgpt_mealplanner.core.shopping.ShoppingSessionManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration29To30Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-29-30-test"
    private var database: AppDatabase? = null

    @Before fun setUp() { context.deleteDatabase(name) }
    @After fun tearDown() { database?.close(); context.deleteDatabase(name) }

    @Test
    fun versionNumbers() {
        assertEquals(29, MIGRATION_29_30.startVersion)
        assertEquals(30, MIGRATION_29_30.endVersion)
    }

    private fun open(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*ALL_MIGRATIONS).allowMainThreadQueries().build().also { database = it }

    @Test
    fun aTripLearnsTheOrderItemsWereTickedIn_andLaterTripsAverageIt() = runBlocking {
        val manager = ShoppingSessionManager(open().storeOrderDao())

        manager.start("tesco", now = 1_000L)
        listOf("Milk", "Bread", "Chicken breast, diced").forEachIndexed { i, n -> manager.recordTick(n, now = 2_000L + i) }
        manager.finishOpenTrip(now = 3_000L)
        assertNull(database!!.storeOrderDao().getOpenSession())

        val first = manager.observeOrder("tesco").first().associate { it.itemKey to it.avgRank }
        assertEquals(0.0, first.getValue("milk"), 1e-9)
        assertEquals(0.5, first.getValue("bread"), 1e-9)
        assertEquals(1.0, first.getValue("breast chicken"), 1e-9)

        // Next trip, bread comes first: its average moves from 0.5 to 0.25.
        manager.start("tesco", now = 4_000L)
        listOf("Bread", "Milk").forEachIndexed { i, n -> manager.recordTick(n, now = 5_000L + i) }
        manager.finishOpenTrip(now = 6_000L)
        val second = manager.observeOrder("tesco").first().associate { it.itemKey to it.avgRank }
        assertEquals(0.25, second.getValue("bread"), 1e-9)
        assertEquals(0.5, second.getValue("milk"), 1e-9)
        assertTrue(manager.observeOrder("asda").first().isEmpty())
    }

    @Test
    fun startingAgainFinishesAnOpenTripFirst_andTicksOutsideATripAreIgnored() = runBlocking {
        val manager = ShoppingSessionManager(open().storeOrderDao())
        manager.recordTick("Milk")                       // no trip open: nothing recorded
        manager.start("tesco", now = 1L)
        manager.recordTick("Milk", now = 2L)
        manager.start("asda", now = 3L)                  // finishes the tesco trip
        assertEquals("asda", database!!.storeOrderDao().getOpenSession()!!.storeId)
        assertNotNull(manager.observeOrder("tesco").first().firstOrNull { it.itemKey == "milk" })
    }
}
