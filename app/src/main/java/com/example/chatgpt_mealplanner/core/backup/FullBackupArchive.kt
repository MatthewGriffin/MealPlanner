package com.example.chatgpt_mealplanner.core.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

const val FULL_BACKUP_FORMAT = "pantry-full-backup-v2"

@Serializable
data class FullBackupManifest(
    val format: String = FULL_BACKUP_FORMAT,
    val roomSchemaVersion: Int,
    val createdAt: Long,
    val appVersionName: String
)

/** Credentials can't move with the database because their encryption keys never leave the device. */
@Serializable
data class FullBackupSecrets(
    val claudeApiKey: String? = null,
    /** Written by versions that used Gemini. Still read so those backups restore, but no longer used. */
    val geminiApiKey: String? = null,
    val tlsKeystoreBase64: String? = null,
    val tlsKeystorePassword: String? = null
)

class FullBackupContents(
    val manifest: FullBackupManifest,
    val database: ByteArray,
    val secrets: FullBackupSecrets,
    val preferenceFiles: Map<String, ByteArray>
)

class InvalidFullBackupException(message: String) : Exception(message)

/** The zip inside the encrypted container. It holds the whole database, so new tables are included automatically. */
object FullBackupArchive {
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val DATABASE_ENTRY = "database.sqlite"
    private const val SECRETS_ENTRY = "secrets.json"
    private const val PREFERENCES_PREFIX = "shared_prefs/"
    private val PREFERENCE_FILE_NAME = Regex("^[A-Za-z0-9._-]+\\.xml$")
    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun write(contents: FullBackupContents): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            entry(MANIFEST_ENTRY, json.encodeToString(contents.manifest).toByteArray())
            entry(DATABASE_ENTRY, contents.database)
            entry(SECRETS_ENTRY, json.encodeToString(contents.secrets).toByteArray())
            contents.preferenceFiles.forEach { (name, bytes) -> entry(PREFERENCES_PREFIX + name, bytes) }
        }
        return output.toByteArray()
    }

    fun read(archive: ByteArray, maxEntryBytes: Long = MAX_ENTRY_BYTES): FullBackupContents {
        var manifest: FullBackupManifest? = null
        var database: ByteArray? = null
        var secrets = FullBackupSecrets()
        val preferences = mutableMapOf<String, ByteArray>()

        try {
            ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val bytes = readBounded(zip, maxEntryBytes)
                    when {
                        entry.name == MANIFEST_ENTRY ->
                            manifest = json.decodeFromString<FullBackupManifest>(bytes.decodeToString())
                        entry.name == DATABASE_ENTRY -> database = bytes
                        entry.name == SECRETS_ENTRY ->
                            secrets = json.decodeFromString<FullBackupSecrets>(bytes.decodeToString())
                        entry.name.startsWith(PREFERENCES_PREFIX) -> {
                            // Names become file paths on restore, so reject anything that could escape the folder.
                            val name = entry.name.removePrefix(PREFERENCES_PREFIX)
                            if (!PREFERENCE_FILE_NAME.matches(name) || name.startsWith("..")) {
                                throw InvalidFullBackupException("Backup contains an invalid settings file.")
                            }
                            preferences[name] = bytes
                        }
                    }
                }
            }
        } catch (e: InvalidFullBackupException) {
            throw e
        } catch (e: Exception) {
            throw InvalidFullBackupException("Backup archive is damaged or unreadable.")
        }

        val validManifest = manifest ?: throw InvalidFullBackupException("Backup is missing its manifest.")
        if (validManifest.format != FULL_BACKUP_FORMAT) {
            throw InvalidFullBackupException("Unsupported backup format: ${validManifest.format}.")
        }
        val validDatabase = database ?: throw InvalidFullBackupException("Backup is missing its database.")
        if (validDatabase.size < SQLITE_HEADER.size ||
            !validDatabase.copyOf(SQLITE_HEADER.size).contentEquals(SQLITE_HEADER)
        ) {
            throw InvalidFullBackupException("Backup database is not valid.")
        }
        return FullBackupContents(validManifest, validDatabase, secrets, preferences)
    }

    private fun readBounded(zip: ZipInputStream, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = zip.read(buffer)
            if (read == -1) break
            total += read
            if (total > maxBytes) throw InvalidFullBackupException("Backup archive is too large to restore.")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private const val MAX_ENTRY_BYTES = 512L * 1024L * 1024L
}
