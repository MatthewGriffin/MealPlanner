package com.example.chatgpt_mealplanner.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptQuantityMemoryEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration28To29Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-28-29-test"
    private var database: AppDatabase? = null

    @Before fun setUp() { context.deleteDatabase(name) }
    @After fun tearDown() { database?.close(); context.deleteDatabase(name) }

    @Test
    fun versionNumbers() {
        assertEquals(28, MIGRATION_28_29.startVersion)
        assertEquals(29, MIGRATION_28_29.endVersion)
    }

    @Test
    fun quantityMemoryTable_remembersAnAmountPerShopAndProduct() = runBlocking {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*ALL_MIGRATIONS).allowMainThreadQueries().build()
        database = db
        val dao = db.receiptSpendDao()
        assertNull(dao.getQuantityMemory("TESCO", "bread white"))
        dao.insertOrUpdateQuantityMemory(ReceiptQuantityMemoryEntity("1", "TESCO", "bread white", 800.0, "GRAM", 1L))
        dao.insertOrUpdateQuantityMemory(ReceiptQuantityMemoryEntity("2", "TESCO", "bread white", 750.0, "GRAM", 2L))   // same shop and product: replaced
        dao.insertOrUpdateQuantityMemory(ReceiptQuantityMemoryEntity("3", "ASDA", "bread white", 400.0, "GRAM", 3L))
        assertEquals(750.0, dao.getQuantityMemory("TESCO", "bread white")!!.quantity, 0.0)
        assertEquals(400.0, dao.getAnyQuantityMemory("bread white")!!.quantity, 0.0)   // newest anywhere
    }
}
