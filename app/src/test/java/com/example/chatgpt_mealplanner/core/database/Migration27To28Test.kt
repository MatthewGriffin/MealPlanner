package com.example.chatgpt_mealplanner.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptSpendEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration27To28Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-27-28-test"
    private var database: AppDatabase? = null

    @Before fun setUp() { context.deleteDatabase(name) }
    @After fun tearDown() { database?.close(); context.deleteDatabase(name) }

    @Test
    fun versionNumbers() {
        assertEquals(27, MIGRATION_27_28.startVersion)
        assertEquals(28, MIGRATION_27_28.endVersion)
    }

    @Test
    fun receiptsKeepTheirCurrency_andOlderOnesAreGbp() = runBlocking {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*ALL_MIGRATIONS).allowMainThreadQueries().build()
        database = db
        val dao = db.receiptSpendDao()
        dao.insertReceiptSpend(ReceiptSpendEntity("a", "ALDI", "2026-11-17", 1250L, 1250L, 0L, 3, "fp-a", 1L, currencyCode = "EUR"))
        dao.insertReceiptSpend(ReceiptSpendEntity("b", "ASDA", "2026-11-17", 999L, 999L, 0L, 2, "fp-b", 2L))
        val all = dao.observeAllReceiptSpends().first()
        assertEquals("EUR", all.first { it.id == "a" }.currencyCode)
        assertEquals("GBP", all.first { it.id == "b" }.currencyCode)
    }

    @Test
    fun theMigrationSqlGivesExistingReceiptsGbp() {
        val callback = object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE receipt_spend (id TEXT NOT NULL PRIMARY KEY, retailerId TEXT NOT NULL, purchasedAt TEXT NOT NULL, totalMinorPence INTEGER NOT NULL, subtotalMinorPence INTEGER, discountMinorPence INTEGER NOT NULL, itemCount INTEGER NOT NULL, fingerprint TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("INSERT INTO receipt_spend VALUES ('old', 'ASDA', '2026-10-01', 3888, 4248, 360, 12, 'fp', 1)")
            }
            override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context).name(null).callback(callback).build()
        )
        val db = helper.writableDatabase
        MIGRATION_27_28.migrate(db)
        db.query("SELECT currencyCode FROM receipt_spend WHERE id = 'old'").use {
            it.moveToFirst()
            assertEquals("GBP", it.getString(0))
        }
        helper.close()
    }
}
