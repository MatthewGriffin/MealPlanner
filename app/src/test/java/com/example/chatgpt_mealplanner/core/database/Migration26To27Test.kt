package com.example.chatgpt_mealplanner.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.chatgpt_mealplanner.core.database.entity.SavedRecipeEntity
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
class Migration26To27Test {

    private val name = "migration_26_27.db"
    private lateinit var context: Context
    private var database: AppDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(name)
        val schema = File("schemas/com.example.chatgpt_mealplanner.core.database.AppDatabase/26.json")
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
            db.version = 26
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
    fun migrationAddsTheSavedRecipesTable_andItWorks() = runBlocking {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(MIGRATION_26_27).allowMainThreadQueries().build()
        database = db

        assertNull(db.savedRecipeDao().getByTitle("shakshuka"))
        db.savedRecipeDao().upsert(SavedRecipeEntity("shakshuka", "Shakshuka", 2, "Ingredients: eggs. Method: poach.", "URL", "https://x", 1L, 1L))
        db.savedRecipeDao().upsert(SavedRecipeEntity("shakshuka", "Shakshuka", 4, "Updated.", "PHOTO", null, 1L, 2L))

        assertEquals(1, db.savedRecipeDao().getAll().size)
        assertEquals("Updated.", db.savedRecipeDao().getByTitle("shakshuka")!!.recipeText)
    }
}
