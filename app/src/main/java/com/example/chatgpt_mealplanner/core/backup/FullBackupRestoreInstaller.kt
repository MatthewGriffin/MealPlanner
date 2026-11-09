package com.example.chatgpt_mealplanner.core.backup

import android.content.Context
import androidx.room.Room
import com.example.chatgpt_mealplanner.core.database.ALL_MIGRATIONS
import com.example.chatgpt_mealplanner.core.database.AppDatabase
import com.example.chatgpt_mealplanner.core.database.MAIN_DATABASE_NAME
import com.example.chatgpt_mealplanner.core.security.ClaudeCredentialStore
import com.example.chatgpt_mealplanner.core.sync.security.PantryTlsKeyManager
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64

/**
 * Applies a staged restore at app start, before anything can open the database. The current
 * database is kept until the restored one has opened and migrated, and is put back otherwise.
 * An interrupted install is rolled back on the next start.
 */
object FullBackupRestoreInstaller {
    internal const val STAGED_DATABASE = "database.sqlite"
    internal const val STAGED_SECRETS = "secrets.json"
    internal const val STAGED_PREFERENCES = "shared_prefs"
    internal const val READY_MARKER = "ready"
    internal const val INSTALLING_MARKER = "installing"
    private const val RESULT_FILE = "restore_result.txt"
    private val DATABASE_SUFFIXES = listOf("", "-wal", "-shm", "-journal")

    internal fun stagingFolder(context: Context) = File(context.filesDir, "pending_restore")
    private fun safetyFolder(context: Context) = File(context.filesDir, "restore_safety")
    private fun resultFile(context: Context) = File(context.filesDir, RESULT_FILE)

    fun consumeResult(context: Context): String? {
        val file = resultFile(context)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull().also { file.delete() }
    }

    /** Must never throw, because it runs during app start. */
    fun applyPendingRestore(
        context: Context,
        applySecrets: (FullBackupSecrets) -> Boolean = { applySecretsToDevice(context, it) },
        openDatabaseCheck: () -> Unit = { checkDatabaseOpens(context) }
    ) {
        try {
            val staging = stagingFolder(context)
            when {
                File(staging, INSTALLING_MARKER).exists() -> {
                    rollBack(context)
                    finish(context, "The last restore was interrupted, so your existing data was kept.")
                }
                File(staging, READY_MARKER).exists() -> install(context, applySecrets, openDatabaseCheck)
            }
        } catch (_: Throwable) {
            runCatching { rollBack(context) }
            finish(context, "Restore failed, so your existing data was kept.")
        }
    }

    private fun install(context: Context, applySecrets: (FullBackupSecrets) -> Boolean, openDatabaseCheck: () -> Unit) {
        val staging = stagingFolder(context)
        val safety = safetyFolder(context)
        val stagedDatabase = File(staging, STAGED_DATABASE)

        safety.deleteRecursively()
        safety.mkdirs()
        File(staging, READY_MARKER).renameTo(File(staging, INSTALLING_MARKER))

        val liveDatabase = context.getDatabasePath(MAIN_DATABASE_NAME)
        liveDatabase.parentFile?.mkdirs()
        DATABASE_SUFFIXES.forEach { suffix ->
            val file = File(liveDatabase.path + suffix)
            if (file.exists()) moveFile(file, File(safety, MAIN_DATABASE_NAME + suffix))
        }
        moveFile(stagedDatabase, liveDatabase)

        try {
            openDatabaseCheck()
        } catch (_: Throwable) {
            rollBack(context)
            finish(context, "The backup couldn't be opened by this version of the app, so your existing data was kept.")
            return
        }

        // The data is safely in place from here, so a credential failure only needs a warning.
        val secretsApplied = runCatching {
            val secrets = Json { ignoreUnknownKeys = true }
                .decodeFromString<FullBackupSecrets>(File(staging, STAGED_SECRETS).readText())
            applySecrets(secrets)
        }.getOrDefault(false)
        restorePreferences(context, File(staging, STAGED_PREFERENCES))

        finish(
            context,
            if (secretsApplied) {
                "Backup restored. Re-pair any joined phones from Household settings so they can resync."
            } else {
                "Backup restored, but saved credentials couldn't be restored. Check your Claude key, and re-pair any joined phones."
            }
        )
    }

    private fun rollBack(context: Context) {
        val staging = stagingFolder(context)
        val safety = safetyFolder(context)
        val liveDatabase = context.getDatabasePath(MAIN_DATABASE_NAME)
        // While the staged database is still in staging, the live files were never replaced.
        if (!File(staging, STAGED_DATABASE).exists()) {
            DATABASE_SUFFIXES.forEach { File(liveDatabase.path + it).delete() }
        }
        DATABASE_SUFFIXES.forEach { suffix ->
            val saved = File(safety, MAIN_DATABASE_NAME + suffix)
            if (saved.exists()) moveFile(saved, File(liveDatabase.path + suffix))
        }
    }

    private fun restorePreferences(context: Context, folder: File) {
        val target = File(context.applicationInfo.dataDir, "shared_prefs")
        folder.listFiles().orEmpty().forEach { file ->
            if (FullBackupManager.isPortablePreferenceFile(file.name)) {
                runCatching {
                    target.mkdirs()
                    file.copyTo(File(target, file.name), overwrite = true)
                }
            }
        }
    }

    private fun finish(context: Context, message: String) {
        runCatching { resultFile(context).writeText(message) }
        // These hold decrypted data, so they never outlive the restore.
        runCatching { stagingFolder(context).deleteRecursively() }
        runCatching { safetyFolder(context).deleteRecursively() }
    }

    private fun moveFile(from: File, to: File) {
        if (!from.renameTo(to)) {
            from.copyTo(to, overwrite = true)
            from.delete()
        }
    }

    private fun checkDatabaseOpens(context: Context) {
        val database = Room.databaseBuilder(context, AppDatabase::class.java, MAIN_DATABASE_NAME)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        try {
            // Opening runs any migrations and checks the result against the schema Room expects.
            database.openHelper.writableDatabase
        } finally {
            database.close()
        }
    }

    private fun applySecretsToDevice(context: Context, secrets: FullBackupSecrets): Boolean {
        var succeeded = true
        // A Gemini key from an older backup is useless to Claude, so it's left out.
        secrets.claudeApiKey?.takeIf { it.isNotBlank() }?.let { key ->
            succeeded = runCatching { ClaudeCredentialStore(context).saveKey(key) }.isSuccess && succeeded
        }
        val keystore = secrets.tlsKeystoreBase64
        val password = secrets.tlsKeystorePassword
        if (keystore != null && password != null) {
            succeeded = runCatching {
                PantryTlsKeyManager(context).restoreTlsIdentity(Base64.getDecoder().decode(keystore), password)
            }.isSuccess && succeeded
        }
        return succeeded
    }
}
