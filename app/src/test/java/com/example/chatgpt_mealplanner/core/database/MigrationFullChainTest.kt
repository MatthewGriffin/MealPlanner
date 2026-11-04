package com.example.chatgpt_mealplanner.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Builds a database from each exported schema and upgrades it with the app's migration list.
 * Opening through Room fails unless the result exactly matches the schema the app expects.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MigrationFullChainTest {

    private val schemaFolder = File("schemas/com.example.chatgpt_mealplanner.core.database.AppDatabase")
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val exportedVersions: List<Int> =
        schemaFolder.listFiles().orEmpty().map { it.nameWithoutExtension.toInt() }.sorted()

    private fun createDatabaseAt(version: Int, name: String) {
        val definition = Json.parseToJsonElement(File(schemaFolder, "$version.json").readText())
            .jsonObject["database"]!!.jsonObject
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            definition["entities"]!!.jsonArray.map { it.jsonObject }.forEach { entity ->
                val table = entity["tableName"]!!.jsonPrimitive.content
                db.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach { index ->
                    db.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            db.version = version
        }
    }

    private fun upgradeFailure(version: Int): String? {
        val name = "migration_chain_$version.db"
        createDatabaseAt(version, name)
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        return try {
            val opened = database.openHelper.writableDatabase
            val brokenReferences = opened.query("PRAGMA foreign_key_check").use { it.count }
            when {
                opened.version != exportedVersions.last() -> "ended on version ${opened.version}"
                brokenReferences > 0 -> "$brokenReferences broken foreign keys"
                else -> null
            }
        } catch (e: Exception) {
            e.message ?: e.toString()
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun exportedSchemas_coverEveryVersion() {
        assertEquals((1..exportedVersions.last()).toList(), exportedVersions)
        assertTrue("Migration list must reach the newest schema", ALL_MIGRATIONS.any { it.endVersion == exportedVersions.last() })
    }

    @Test
    fun everyExportedVersion_upgradesToTheCurrentSchema() {
        val failures = exportedVersions.filter { it >= OLDEST_UPGRADABLE_VERSION }.dropLast(1).mapNotNull { version ->
            upgradeFailure(version)?.let { "v$version: $it" }
        }

        assertTrue(failures.joinToString("\n\n"), failures.isEmpty())
    }

    private companion object {
        /**
         * The exports for v1 to v6 don't match what their migrations expect, because those schemas
         * changed during early development without a version bump. They predate this repository's
         * history and were never released, so no real database can be on them.
         */
        const val OLDEST_UPGRADABLE_VERSION = 7
    }
}
