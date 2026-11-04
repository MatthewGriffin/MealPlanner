package com.example.chatgpt_mealplanner.core.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.chatgpt_mealplanner.core.database.ALL_MIGRATIONS
import com.example.chatgpt_mealplanner.core.database.AppDatabase
import com.example.chatgpt_mealplanner.core.database.MAIN_DATABASE_NAME
import com.example.chatgpt_mealplanner.core.database.entity.PairedDeviceEntity
import com.example.chatgpt_mealplanner.core.database.entity.PurchasePriceObservationEntity
import com.example.chatgpt_mealplanner.core.database.entity.UserStoreEntity
import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.security.FakeCredentialStore
import com.example.chatgpt_mealplanner.core.sync.security.PantryTlsKeyManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class FullBackupRestoreTest {

    private val passphrase = "correct horse".toCharArray()
    private val uri: Uri = Uri.parse("content://test/backup")
    private val codec = MainBackupEncryptionCodec(MainBackupContainerEncoderDecoder())
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var manager: FullBackupManager
    private var fileToRestore = ByteArray(0)

    private val streamProvider = object : SafDocumentStreamProvider {
        override fun openOutputStream(appContext: Context?, uri: Uri?): OutputStream? = null
        override fun openInputStream(appContext: Context?, uri: Uri?): InputStream =
            ByteArrayInputStream(fileToRestore)
    }

    private val tlsKeyManager = object : PantryTlsKeyManager(null) {
        override fun exportTlsIdentity() = "tls-bytes".toByteArray() to "tls-password"
    }

    private fun openDatabase(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, MAIN_DATABASE_NAME)
            .addMigrations(*ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    private fun price(id: String, storeId: String) = PurchasePriceObservationEntity(
        id = id,
        storeId = storeId,
        barcode = null,
        productName = "Cheese",
        brand = null,
        packQuantity = 500.0,
        packUnit = "GRAM",
        packCount = null,
        priceMinorUnits = 500L,
        canonicalName = "cheese"
    )

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(MAIN_DATABASE_NAME)
        FullBackupRestoreInstaller.stagingFolder(context).deleteRecursively()
        database = openDatabase()
        database.purchasePriceDao().insertStore(UserStoreEntity(id = "iceland", name = "Iceland"))
        database.purchasePriceDao().insertPriceObservation(price("original_price", "iceland"))
        database.pairedDeviceDao().insertOrUpdateDevice(
            PairedDeviceEntity("follower", "Phone", "public-key", "ACTIVE", 1L, 1L)
        )
        manager = FullBackupManager(context, database, FakeCredentialStore("gemini-key"), tlsKeyManager, codec, streamProvider)
    }

    @After
    fun tearDown() {
        runCatching { database.close() }
        context.deleteDatabase(MAIN_DATABASE_NAME)
    }

    /** Makes a backup, then changes the data so a restore has something visible to undo. */
    private suspend fun backupThenChangeData() {
        fileToRestore = manager.createEncryptedBackup(passphrase)
        database.purchasePriceDao().insertStore(UserStoreEntity(id = "asda", name = "Asda"))
        database.purchasePriceDao().insertPriceObservation(price("newer_price", "asda"))
    }

    private suspend fun storedStoreIds(): Set<String> {
        val reopened = openDatabase()
        return try {
            reopened.purchasePriceDao().getAllStores().map { it.id }.toSet()
        } finally {
            reopened.close()
        }
    }

    private fun failureMessage(result: DomainResult<Unit>): String =
        ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause

    private fun stagingFolder() = FullBackupRestoreInstaller.stagingFolder(context)

    @Test
    fun restore_bringsBackEverything_andRevokesJoinedPhones() = runBlocking {
        val prefs = context.getSharedPreferences("barcode_concept_verification_v1", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("verified", true).commit()
        backupThenChangeData()
        File(context.applicationInfo.dataDir, "shared_prefs/barcode_concept_verification_v1.xml").delete()

        assertTrue(manager.stageRestore(uri, passphrase) is DomainResult.Success)
        database.close()
        var restoredSecrets: FullBackupSecrets? = null
        FullBackupRestoreInstaller.applyPendingRestore(context, applySecrets = { restoredSecrets = it; true })

        val reopened = openDatabase()
        try {
            val dao = reopened.purchasePriceDao()
            assertEquals(setOf("iceland"), dao.getAllStores().map { it.id }.toSet())
            assertNotNull(dao.getObservation("original_price"))
            assertNull(dao.getObservation("newer_price"))
            assertEquals("REVOKED", reopened.pairedDeviceDao().getDeviceById("follower")?.status)
        } finally {
            reopened.close()
        }
        assertEquals("gemini-key", restoredSecrets?.geminiApiKey)
        assertEquals("tls-password", restoredSecrets?.tlsKeystorePassword)
        assertTrue(File(context.applicationInfo.dataDir, "shared_prefs/barcode_concept_verification_v1.xml").exists())
        assertTrue(FullBackupRestoreInstaller.consumeResult(context)!!.startsWith("Backup restored"))
        assertFalse("Decrypted data must not stay on disk", stagingFolder().exists())
    }

    @Test
    fun wrongPassphrase_changesNothing() = runBlocking {
        backupThenChangeData()

        val result = manager.stageRestore(uri, "wrong passphrase".toCharArray())

        assertTrue(failureMessage(result).contains("passphrase"))
        assertFalse(stagingFolder().exists())
        database.close()
        FullBackupRestoreInstaller.applyPendingRestore(context)
        assertEquals(setOf("iceland", "asda"), storedStoreIds())
    }

    @Test
    fun olderBackupFormat_isExplainedAndRejected() = runBlocking {
        fileToRestore = codec.encodeEncryptedBackup(MainHouseholdBackupDto(exportedAt = 1L), passphrase)

        assertTrue(failureMessage(manager.stageRestore(uri, passphrase)).contains("older version"))
    }

    @Test
    fun backupFromNewerAppVersion_isRejected() = runBlocking {
        val contents = manager.createContents(1L)
        val newer = FullBackupContents(
            contents.manifest.copy(roomSchemaVersion = contents.manifest.roomSchemaVersion + 1),
            contents.database, contents.secrets, contents.preferenceFiles
        )
        fileToRestore = codec.encodeFullBackup(FullBackupArchive.write(newer), passphrase)

        assertTrue(failureMessage(manager.stageRestore(uri, passphrase)).contains("newer version"))
        assertFalse(stagingFolder().exists())
    }

    @Test
    fun corruptDatabase_isRejectedBeforeAnythingIsReplaced() = runBlocking {
        val contents = manager.createContents(1L)
        val corrupt = contents.database.copyOf().also { bytes ->
            for (i in 100 until bytes.size) bytes[i] = 0x5A
        }
        val damaged = FullBackupContents(contents.manifest, corrupt, contents.secrets, contents.preferenceFiles)
        fileToRestore = codec.encodeFullBackup(FullBackupArchive.write(damaged), passphrase)

        assertTrue(manager.stageRestore(uri, passphrase) is DomainResult.Failure)
        assertFalse(stagingFolder().exists())
        database.close()
        assertEquals(setOf("iceland"), storedStoreIds())
    }

    @Test
    fun databaseThatWontOpen_rollsBackToExistingData() = runBlocking {
        backupThenChangeData()
        assertTrue(manager.stageRestore(uri, passphrase) is DomainResult.Success)
        database.close()

        var secretsApplied = false
        FullBackupRestoreInstaller.applyPendingRestore(
            context,
            applySecrets = { secretsApplied = true; true },
            openDatabaseCheck = { throw IllegalStateException("migration failed") }
        )

        assertEquals(setOf("iceland", "asda"), storedStoreIds())
        assertFalse("Secrets must not change when the restore failed", secretsApplied)
        assertTrue(FullBackupRestoreInstaller.consumeResult(context)!!.contains("existing data was kept"))
        assertFalse(stagingFolder().exists())
    }

    @Test
    fun interruptedInstall_rollsBackOnNextStart() = runBlocking {
        backupThenChangeData()
        assertTrue(manager.stageRestore(uri, passphrase) is DomainResult.Success)
        database.close()

        // Reproduces the state on disk if the app dies right after the databases were swapped.
        val staging = stagingFolder()
        File(staging, FullBackupRestoreInstaller.READY_MARKER)
            .renameTo(File(staging, FullBackupRestoreInstaller.INSTALLING_MARKER))
        val live = context.getDatabasePath(MAIN_DATABASE_NAME)
        val safety = File(context.filesDir, "restore_safety").apply { mkdirs() }
        listOf("", "-wal", "-shm").forEach { suffix ->
            File(live.path + suffix).takeIf { it.exists() }?.renameTo(File(safety, MAIN_DATABASE_NAME + suffix))
        }
        File(staging, FullBackupRestoreInstaller.STAGED_DATABASE).renameTo(live)

        FullBackupRestoreInstaller.applyPendingRestore(context)

        assertEquals(setOf("iceland", "asda"), storedStoreIds())
        assertTrue(FullBackupRestoreInstaller.consumeResult(context)!!.contains("interrupted"))
    }

    @Test
    fun restoreWorksOnAPhoneWithNoExistingDatabase() = runBlocking {
        backupThenChangeData()
        assertTrue(manager.stageRestore(uri, passphrase) is DomainResult.Success)
        database.close()
        context.deleteDatabase(MAIN_DATABASE_NAME)

        FullBackupRestoreInstaller.applyPendingRestore(context, applySecrets = { true })

        assertEquals(setOf("iceland"), storedStoreIds())
    }

    @Test
    fun credentialFailure_keepsTheRestoredData_andWarns() = runBlocking {
        backupThenChangeData()
        assertTrue(manager.stageRestore(uri, passphrase) is DomainResult.Success)
        database.close()

        FullBackupRestoreInstaller.applyPendingRestore(context, applySecrets = { false })

        assertEquals(setOf("iceland"), storedStoreIds())
        assertTrue(FullBackupRestoreInstaller.consumeResult(context)!!.contains("credentials"))
    }

    @Test
    fun nothingPending_changesNothing() = runBlocking {
        database.close()

        FullBackupRestoreInstaller.applyPendingRestore(context)

        assertEquals(setOf("iceland"), storedStoreIds())
        assertNull(FullBackupRestoreInstaller.consumeResult(context))
    }

    @Test
    fun savedBackupVerification_rejectsWrongPassphraseAndDamage() {
        val backup = manager.createEncryptedBackup(passphrase)

        assertTrue(manager.verifyEncryptedBackup(backup, passphrase))
        assertFalse(manager.verifyEncryptedBackup(backup, "wrong passphrase".toCharArray()))
        val damaged = backup.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFalse(manager.verifyEncryptedBackup(damaged, passphrase))
    }

    @Test
    fun keystoreProtectedSettings_areNotCopied() {
        assertTrue(FullBackupManager.isPortablePreferenceFile("barcode_concept_verification_v1.xml"))
        listOf(
            "gemini_secure_prefs_keystore_v2.xml",
            "pantry_tls_secure_prefs.xml",
            "pantry_follower_pairing_prefs_keystore_v2.xml",
            "androidx.work.util.preferences.xml"
        ).forEach { assertFalse(it, FullBackupManager.isPortablePreferenceFile(it)) }
    }
}
