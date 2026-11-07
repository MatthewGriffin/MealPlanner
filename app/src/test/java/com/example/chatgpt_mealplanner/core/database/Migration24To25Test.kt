package com.example.chatgpt_mealplanner.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.chatgpt_mealplanner.core.model.MealRating
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
class Migration24To25Test {

    private val name = "migration_24_25.db"
    private lateinit var context: Context
    private var database: AppDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(name)
        val schema = File("schemas/com.example.chatgpt_mealplanner.core.database.AppDatabase/24.json")
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
            db.execSQL(
                """
                INSERT INTO meals (id, date, mealType, title, servings, status, batchPortions, immediatePortions,
                    leftoverPortions, cookInTwoBatches, tags, createdAt, updatedAt)
                VALUES ('m1', '2026-11-07', 'DINNER', 'Stew', 2, 'PLANNED', 2, 2, 0, 0, '', 1, 1)
                """.trimIndent()
            )
            db.version = 24
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
    fun upgrade_keepsMealsAndStartsThemUnrated() = runBlocking {
        val meal = upgraded().mealDao().getMealWithIngredients("m1")!!.meal

        assertEquals("Stew", meal.title)
        assertNull(meal.rating)
        assertNull(meal.ratedAt)
    }

    @Test
    fun rating_savesAndClears_withoutMarkingTheMealUpdated() = runBlocking {
        val dao = upgraded().mealDao()

        dao.setRating("m1", MealRating.DOWN, 5)
        val rated = dao.getMealWithIngredients("m1")!!.meal
        assertEquals(MealRating.DOWN, rated.rating)
        assertEquals(1L, rated.updatedAt)
        assertEquals("Stew", dao.getRatedMealTitles(10).single().title)

        dao.setRating("m1", null, null)
        assertEquals(emptyList<Any>(), dao.getRatedMealTitles(10))
    }
}
