package com.example.chatgpt_mealplanner.core.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FullBackupArchiveTest {

    private val database = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(32) { 1 }
    private val manifest = FullBackupManifest(roomSchemaVersion = 22, createdAt = 5L, appVersionName = "1.0")
    private val manifestJson = """{"format":"$FULL_BACKUP_FORMAT","roomSchemaVersion":22,"createdAt":5,"appVersionName":"1.0"}"""

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    @Test
    fun everythingSurvivesARoundTrip() {
        val secrets = FullBackupSecrets("gemini", "dGxz", "password")
        val preferences = mapOf("settings.xml" to "<map/>".toByteArray())

        val read = FullBackupArchive.read(
            FullBackupArchive.write(FullBackupContents(manifest, database, secrets, preferences))
        )

        assertEquals(manifest, read.manifest)
        assertArrayEquals(database, read.database)
        assertEquals(secrets, read.secrets)
        assertArrayEquals(preferences["settings.xml"], read.preferenceFiles["settings.xml"])
    }

    @Test
    fun settingsFileCannotEscapeItsFolder() {
        val archive = zipOf(
            "manifest.json" to manifestJson.toByteArray(),
            "database.sqlite" to database,
            "shared_prefs/../../databases/pantry_planner.xml" to ByteArray(1)
        )

        assertThrows(InvalidFullBackupException::class.java) { FullBackupArchive.read(archive) }
    }

    @Test
    fun missingOrInvalidPartsAreRejected() {
        val missingDatabase = zipOf("manifest.json" to manifestJson.toByteArray())
        val missingManifest = zipOf("database.sqlite" to database)
        val notSqlite = zipOf("manifest.json" to manifestJson.toByteArray(), "database.sqlite" to ByteArray(64))
        val wrongFormat = zipOf(
            "manifest.json" to manifestJson.replace(FULL_BACKUP_FORMAT, "other").toByteArray(),
            "database.sqlite" to database
        )

        listOf(missingDatabase, missingManifest, notSqlite, wrongFormat, "not a zip".toByteArray()).forEach {
            assertThrows(InvalidFullBackupException::class.java) { FullBackupArchive.read(it) }
        }
    }

    @Test
    fun oversizedEntryIsRejected() {
        val archive = zipOf("database.sqlite" to ByteArray(1024))

        assertThrows(InvalidFullBackupException::class.java) { FullBackupArchive.read(archive, maxEntryBytes = 100) }
    }
}
