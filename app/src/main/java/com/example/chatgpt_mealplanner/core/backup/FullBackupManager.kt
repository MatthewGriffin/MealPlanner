package com.example.chatgpt_mealplanner.core.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.example.chatgpt_mealplanner.BuildConfig
import com.example.chatgpt_mealplanner.core.database.AppDatabase
import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.security.CredentialStore
import com.example.chatgpt_mealplanner.core.sync.security.PantryTlsKeyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Creates full backups and prepares them to be installed on the next app start. */
@Singleton
open class FullBackupManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val credentialStore: CredentialStore,
    private val tlsKeyManager: PantryTlsKeyManager,
    private val encryptionCodec: MainBackupEncryptionCodec,
    private val streamProvider: SafDocumentStreamProvider
) {
    private val json = Json { encodeDefaults = true }

    open fun createEncryptedBackup(passphrase: CharArray, currentTimeMs: Long = System.currentTimeMillis()): ByteArray =
        encryptionCodec.encodeFullBackup(FullBackupArchive.write(createContents(currentTimeMs)), passphrase)

    /** Confirms a saved backup can be decrypted and read, so the user isn't left with a useless file. */
    open fun verifyEncryptedBackup(encryptedBytes: ByteArray, passphrase: CharArray): Boolean {
        val decrypted = encryptionCodec.decodeFullBackup(encryptedBytes, passphrase)
        if (decrypted !is DomainResult.Success) return false
        return runCatching { FullBackupArchive.read(decrypted.value) }.isSuccess
    }

    internal fun createContents(currentTimeMs: Long): FullBackupContents {
        val snapshotFile = File(context.cacheDir, "backup_snapshot_${UUID.randomUUID()}.db")
        try {
            val db = database.openHelper.writableDatabase
            // VACUUM INTO produces one consistent file, including anything still in the WAL.
            db.execSQL("VACUUM INTO ?", arrayOf(snapshotFile.absolutePath))
            return FullBackupContents(
                manifest = FullBackupManifest(
                    roomSchemaVersion = db.version,
                    createdAt = currentTimeMs,
                    appVersionName = BuildConfig.VERSION_NAME
                ),
                database = snapshotFile.readBytes(),
                secrets = readSecrets(),
                preferenceFiles = readPreferenceFiles()
            )
        } finally {
            snapshotFile.delete()
        }
    }

    /** Secrets the last backup had to leave out because this phone could not read them (see [readSecrets]). */
    @Volatile
    open var lastSkippedSecrets: List<String> = emptyList()
        protected set

    /**
     * A secret that the phone's secure storage can no longer decrypt (its key was lost or replaced) must not stop
     * the backup of the user's data. It is left out and named, so the user knows what to set up again.
     */
    private fun readSecrets(): FullBackupSecrets {
        val skipped = mutableListOf<String>()
        val claudeKey = runCatching { credentialStore.loadKey() }.getOrElse { skipped += "your saved Claude key"; null }
        val tlsIdentity = runCatching { tlsKeyManager.exportTlsIdentity() }.getOrElse { skipped += "the household sync identity"; null }
        lastSkippedSecrets = skipped
        return FullBackupSecrets(
            claudeApiKey = claudeKey,
            tlsKeystoreBase64 = tlsIdentity?.first?.let { Base64.getEncoder().encodeToString(it) },
            tlsKeystorePassword = tlsIdentity?.second
        )
    }

    private fun readPreferenceFiles(): Map<String, ByteArray> {
        val folder = File(context.applicationInfo.dataDir, "shared_prefs")
        return folder.listFiles { file -> file.isFile && isPortablePreferenceFile(file.name) }
            .orEmpty()
            .associate { it.name to it.readBytes() }
    }

    /**
     * Decrypts and checks a backup, then leaves it ready to install on the next app start.
     * Nothing on this phone changes unless every check passes.
     */
    open suspend fun stageRestore(uri: Uri, passphrase: CharArray): DomainResult<Unit> {
        val encrypted = try {
            val input = streamProvider.openInputStream(context, uri)
                ?: return failure("Unable to open the selected backup file.")
            input.use { readBounded(it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return failure(e.message ?: "Unable to read the selected backup file.")
        }

        val decrypted = encryptionCodec.decodeFullBackup(encrypted, passphrase)
        if (decrypted is DomainResult.Failure) return DomainResult.Failure(decrypted.error)

        val contents = try {
            FullBackupArchive.read((decrypted as DomainResult.Success).value)
        } catch (e: InvalidFullBackupException) {
            return failure(e.message ?: "Backup file is damaged.")
        }

        val currentVersion = database.openHelper.readableDatabase.version
        if (contents.manifest.roomSchemaVersion > currentVersion) {
            return failure("This backup came from a newer version of the app. Update the app, then restore it.")
        }

        val stagingFolder = FullBackupRestoreInstaller.stagingFolder(context)
        return try {
            stagingFolder.deleteRecursively()
            stagingFolder.mkdirs()
            val stagedDatabase = File(stagingFolder, FullBackupRestoreInstaller.STAGED_DATABASE)
            stagedDatabase.writeBytes(contents.database)
            prepareStagedDatabase(stagedDatabase, currentVersion)

            File(stagingFolder, FullBackupRestoreInstaller.STAGED_SECRETS)
                .writeText(json.encodeToString(contents.secrets))
            val preferencesFolder = File(stagingFolder, FullBackupRestoreInstaller.STAGED_PREFERENCES).apply { mkdirs() }
            contents.preferenceFiles.forEach { (name, bytes) -> File(preferencesFolder, name).writeBytes(bytes) }

            // Written last, so a half-staged restore is never installed.
            File(stagingFolder, FullBackupRestoreInstaller.READY_MARKER).writeText(contents.manifest.createdAt.toString())
            DomainResult.Success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            stagingFolder.deleteRecursively()
            throw e
        } catch (e: Exception) {
            stagingFolder.deleteRecursively()
            failure(e.message ?: "Backup database failed its checks.")
        }
    }

    open fun consumeLastRestoreResult(): String? = FullBackupRestoreInstaller.consumeResult(context)

    private fun prepareStagedDatabase(file: File, maxVersion: Int) {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getString(0) != "ok") {
                    throw InvalidFullBackupException("Backup database failed its integrity check.")
                }
            }
            if (db.version <= 0 || db.version > maxVersion) {
                throw InvalidFullBackupException("Backup database has an unsupported version.")
            }
            // A restore rolls Main back in time, so joined phones must re-pair and resync from scratch.
            db.execSQL("UPDATE paired_devices SET status = 'REVOKED' WHERE status = 'ACTIVE'")
        }
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > MAX_ENCRYPTED_CONTAINER_SIZE_BYTES) {
                throw java.io.IOException("This file is too large to be a household backup.")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun failure(message: String): DomainResult<Unit> =
        DomainResult.Failure(DomainError.TransactionFailed(message))

    internal companion object {
        /** Keystore-protected preferences can't be read on another phone, and secrets are backed up separately. */
        fun isPortablePreferenceFile(name: String): Boolean {
            val lower = name.lowercase()
            return lower.endsWith(".xml") &&
                !lower.startsWith("androidx.") &&
                listOf("secure", "keystore", "follower", "webview").none { it in lower }
        }
    }
}
