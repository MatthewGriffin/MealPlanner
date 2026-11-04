package com.example.chatgpt_mealplanner.core.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration21To22Test {

    private lateinit var openHelper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    private fun schemaEntity(version: Int, table: String): JsonObject {
        val file = File("schemas/com.example.chatgpt_mealplanner.core.database.AppDatabase/$version.json")
        return Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
            .map { it.jsonObject }
            .single { it["tableName"]!!.jsonPrimitive.content == table }
    }

    private fun createSql(version: Int, table: String): String =
        schemaEntity(version, table)["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(21) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(createSql(21, "user_stores"))
                        db.execSQL(createSql(21, "product_barcode_mappings"))
                        db.execSQL(createSql(21, "purchase_price_observations"))
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        db = openHelper.writableDatabase
        db.execSQL("INSERT INTO user_stores (id, name, isArchived, createdAt, updatedAt) VALUES ('s1', 'Asda', 0, 1, 1)")
        db.execSQL(
            """
            INSERT INTO product_barcode_mappings
            (barcode, displayName, canonicalIngredientId, canonicalName, source, confidence, lastVerifiedAt, createdAt, updatedAt)
            VALUES
            ('with_id', 'Cheddar', 'cheese', 'Cheddar', 'TEST', 'HIGH', 1, 1, 1),
            ('name_only', 'Whole Milk', NULL, 'Milk', 'TEST', 'HIGH', 1, 1, 1)
            """.trimIndent()
        )
        insertObservation("a", "'with_id'")
        insertObservation("b", "'name_only'")
        insertObservation("c", "'unknown_barcode'")
        insertObservation("d", "NULL")
    }

    private fun insertObservation(id: String, barcodeSql: String) {
        db.execSQL(
            """
            INSERT INTO purchase_price_observations
            (id, storeId, barcode, productName, priceMinorUnits, currencyCode, purchasedAt, createdAt, source)
            VALUES ('$id', 's1', $barcodeSql, 'Product', 100, 'GBP', 1, 1, 'SCANNER')
            """.trimIndent()
        )
    }

    @After
    fun tearDown() {
        openHelper.close()
    }

    private fun canonicalNameOf(id: String): String? =
        db.query("SELECT canonicalName FROM purchase_price_observations WHERE id = '$id'").use { cursor ->
            cursor.moveToFirst()
            if (cursor.isNull(0)) null else cursor.getString(0)
        }

    @Test
    fun migration_backfillsKnownBarcodes_andKeepsEveryPrice() {
        MIGRATION_21_22.migrate(db)

        assertEquals("cheese", canonicalNameOf("a"))
        assertEquals("Milk", canonicalNameOf("b"))
        assertNull(canonicalNameOf("c"))
        assertNull(canonicalNameOf("d"))
        db.query("SELECT COUNT(*) FROM purchase_price_observations").use { cursor ->
            cursor.moveToFirst()
            assertEquals(4, cursor.getInt(0))
        }
    }

    @Test
    fun migration_matchesExportedSchemaColumns() {
        MIGRATION_21_22.migrate(db)

        val expected = schemaEntity(22, "purchase_price_observations")["fields"]!!.jsonArray
            .map { it.jsonObject["columnName"]!!.jsonPrimitive.content }
            .toSet()
        val actual = mutableSetOf<String>()
        db.query("PRAGMA table_info(purchase_price_observations)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) actual += cursor.getString(nameColumn)
        }

        assertEquals(expected, actual)
    }
}
