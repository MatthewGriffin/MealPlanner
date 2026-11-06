package com.example.chatgpt_mealplanner.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.chatgpt_mealplanner.contract.UserPreferencesSummaryDto
import com.example.chatgpt_mealplanner.core.repository.CookingPreferencesStore
import kotlinx.coroutines.flow.first
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
class Migration23To24Test {

    private val name = "migration_23_24.db"
    private lateinit var context: Context
    private var database: AppDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(name)
        val schema = File("schemas/com.example.chatgpt_mealplanner.core.database.AppDatabase/23.json")
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
            db.execSQL("INSERT INTO user_stores (id, name, isArchived, createdAt, updatedAt) VALUES ('s1', 'Asda', 0, 1, 1)")
            db.version = 23
        }
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(name)
    }

    private fun upgraded(): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .addMigrations(*ALL_MIGRATIONS)
        .allowMainThreadQueries()
        .build()
        .also { database = it }

    @Test
    fun upgrade_keepsExistingDataAndStartsWithNoRestrictions() = runBlocking {
        val db = upgraded()

        assertEquals(1, db.purchasePriceDao().getAllStores().size)
        assertEquals(UserPreferencesSummaryDto(), CookingPreferencesStore(db.cookingPreferencesDao()).current())
    }

    @Test
    fun preferences_saveAndLoad_withOnlyOneRow() = runBlocking {
        val store = CookingPreferencesStore(upgraded().cookingPreferencesDao())
        val first = UserPreferencesSummaryDto(householdSize = 4, allergies = listOf("Peanuts"), diets = listOf("Vegetarian"))
        val second = first.copy(
            allergies = listOf("Milk", "Sesame"),
            dislikes = listOf("coriander"),
            equipment = listOf("air fryer"),
            skillLevel = "Beginner"
        )

        store.save(first)
        store.save(second)

        assertEquals(second, store.current())
        assertEquals(second, store.observe().first())
        val rows = database!!.openHelper.readableDatabase.query("SELECT COUNT(*) FROM cooking_preferences")
        rows.use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
    }
}
