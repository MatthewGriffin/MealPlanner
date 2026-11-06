package com.example.chatgpt_mealplanner.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration22To23Test {

    private val name = "migration_22_23.db"
    private val droppedTables = setOf(
        "basket_lines",
        "basket_plans",
        "basket_comparison_cache",
        "retail_product_mappings",
        "retail_price_snapshots",
        "retail_products"
    )
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(name)
        val schema = File("schemas/com.example.chatgpt_mealplanner.core.database.AppDatabase/22.json")
        val entities = Json.parseToJsonElement(schema.readText()).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            entities.map { it.jsonObject }.forEach { entity ->
                val table = entity["tableName"]!!.jsonPrimitive.content
                db.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach { index ->
                    db.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            db.execSQL("INSERT INTO user_stores (id, name, isArchived, createdAt, updatedAt) VALUES ('store', 'Asda', 0, 1, 1)")
            db.execSQL(
                """
                INSERT INTO purchase_price_observations
                (id, storeId, barcode, productName, priceMinorUnits, currencyCode, purchasedAt, createdAt, source)
                VALUES ('price', 'store', NULL, 'Cheese', 250, 'GBP', 1, 1, 'SCANNER')
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO retail_products (id, retailerId, retailerProductId, name, storageClass, createdAt, updatedAt)
                VALUES ('product', 'ASDA', 'p1', 'Cheese', 'UNKNOWN', 1, 1)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO basket_plans
                (planId, storesCsv, feasibilityTier, basketSubtotalMinorPence, estimatedTripPenaltyMinorPence,
                 estimatedWastePenaltyScore, unmatchedRequirementsCsv, uncertainItemsCsv, explanationText, generatedAt)
                VALUES ('plan', 'ASDA', 'COMPLETE', 1, 0, 0, '', '', '', 1)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO basket_lines
                (id, basketPlanId, shoppingRequirementId, requirementDisplayName, retailerId, retailerProductId,
                 productName, packCountToBuy, lineTotalCostMinorPence, requirementCovered, expectedRemainder, matchConfidence)
                VALUES ('line', 'plan', 'r', 'Cheese', 'ASDA', 'p1', 'Cheese', 1, 1, 1, 0, 'EXACT')
                """.trimIndent()
            )
            db.version = 22
        }
    }

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun upgrade_dropsRetailTables_andKeepsRecordedPrices() = runBlocking {
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val opened = database.openHelper.writableDatabase
            assertEquals(ALL_MIGRATIONS.last().endVersion, opened.version)

            val tables = opened.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            assertEquals(emptySet<String>(), tables intersect droppedTables)

            assertEquals(setOf("store"), database.purchasePriceDao().getAllStores().map { it.id }.toSet())
            assertEquals(250L, database.purchasePriceDao().getObservation("price")?.priceMinorUnits)
        } finally {
            database.close()
        }
    }
}
