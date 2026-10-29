package com.example.chatgpt_mealplanner.core.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration20To21Test {

    private lateinit var context: Context
    private val dbName = "test_migration_20_21.db"
    private lateinit var openHelper: SupportSQLiteOpenHelper

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
        openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(20) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE receipt_spend (id TEXT NOT NULL PRIMARY KEY)")
                        db.execSQL("CREATE TABLE inventory_items (id TEXT NOT NULL PRIMARY KEY)")
                        db.execSQL("CREATE TABLE shopping_list_items (id TEXT NOT NULL PRIMARY KEY)")
                        db.execSQL(
                            """
                            CREATE TABLE receipt_spend_items (
                                id TEXT NOT NULL PRIMARY KEY,
                                receiptSpendId TEXT NOT NULL,
                                rawDescription TEXT NOT NULL,
                                normalizedDescription TEXT NOT NULL,
                                lineAmountMinorPence INTEGER NOT NULL,
                                quantity INTEGER NOT NULL,
                                unitPriceMinorPence INTEGER,
                                category TEXT NOT NULL,
                                isDiscount INTEGER NOT NULL,
                                createdAt INTEGER NOT NULL,
                                FOREIGN KEY(receiptSpendId) REFERENCES receipt_spend(id)
                                    ON UPDATE NO ACTION ON DELETE CASCADE
                            )
                            """.trimIndent()
                        )
                        db.execSQL("CREATE INDEX index_receipt_spend_items_receiptSpendId ON receipt_spend_items (receiptSpendId)")
                        db.execSQL("INSERT INTO receipt_spend (id) VALUES ('receipt-1')")
                        db.execSQL("INSERT INTO inventory_items (id) VALUES ('inventory-1')")
                        db.execSQL("INSERT INTO shopping_list_items (id) VALUES ('shopping-1')")
                        db.execSQL(
                            """
                            INSERT INTO receipt_spend_items (
                                id, receiptSpendId, rawDescription, normalizedDescription,
                                lineAmountMinorPence, quantity, unitPriceMinorPence,
                                category, isDiscount, createdAt
                            ) VALUES (
                                'line-1', 'receipt-1', 'Bread', 'bread',
                                120, 1, 120, 'UNCATEGORISED', 0, 1000
                            )
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
    }

    @After
    fun tearDown() {
        openHelper.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration20To21_preservesExistingLinesAndSetsLinksNullOnParentDeletion() {
        val db = openHelper.writableDatabase
        db.execSQL("PRAGMA foreign_keys = ON")

        MIGRATION_20_21.migrate(db)

        db.execSQL(
            "UPDATE receipt_spend_items SET linkedInventoryItemId = 'inventory-1', linkedShoppingListItemId = 'shopping-1' WHERE id = 'line-1'"
        )
        db.execSQL("DELETE FROM inventory_items WHERE id = 'inventory-1'")
        db.execSQL("DELETE FROM shopping_list_items WHERE id = 'shopping-1'")

        db.query(
            """
            SELECT rawDescription, lineAmountMinorPence, linkedInventoryItemId, linkedShoppingListItemId
            FROM receipt_spend_items WHERE id = 'line-1'
            """.trimIndent()
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("Bread", cursor.getString(0))
            assertEquals(120L, cursor.getLong(1))
            assertNull(cursor.getString(2))
            assertNull(cursor.getString(3))
        }

        db.query("PRAGMA index_list('receipt_spend_items')").use { cursor ->
            val indexNames = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
            assertEquals(true, "index_receipt_spend_items_linkedInventoryItemId" in indexNames)
            assertEquals(true, "index_receipt_spend_items_linkedShoppingListItemId" in indexNames)
        }
    }
}
