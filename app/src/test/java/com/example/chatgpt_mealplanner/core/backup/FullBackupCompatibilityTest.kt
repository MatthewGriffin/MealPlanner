package com.example.chatgpt_mealplanner.core.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.chatgpt_mealplanner.core.database.ALL_MIGRATIONS
import com.example.chatgpt_mealplanner.core.database.AppDatabase
import com.example.chatgpt_mealplanner.core.database.MAIN_DATABASE_NAME
import com.example.chatgpt_mealplanner.core.database.entity.UserStoreEntity
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.security.FakeCredentialStore
import com.example.chatgpt_mealplanner.core.sync.security.PantryTlsKeyManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

/**
 * Backups must stay restorable by every later version of the app, because updating is one of the
 * main reasons to make one. See "Compatibility" in docs/BACKUP_EXPORT.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class FullBackupCompatibilityTest {

    private val uri: Uri = Uri.parse("content://test/backup")
    private val codec = MainBackupEncryptionCodec()
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
        override fun exportTlsIdentity() = "fixture-tls".toByteArray() to "fixture-tls-password"
    }

    private fun openDatabase(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, MAIN_DATABASE_NAME)
            .addMigrations(*ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(MAIN_DATABASE_NAME)
        FullBackupRestoreInstaller.stagingFolder(context).deleteRecursively()
        database = openDatabase()
        // Data the restore has to replace.
        database.purchasePriceDao().insertStore(UserStoreEntity(id = "not_in_backup", name = "Not In Backup"))
        manager = FullBackupManager(context, database, FakeCredentialStore("current-key"), tlsKeyManager, codec, streamProvider)
    }

    @After
    fun tearDown() {
        runCatching { database.close() }
        context.deleteDatabase(MAIN_DATABASE_NAME)
    }

    /** Stages and installs [fileToRestore] the way the app does, with the real open and migrate check. */
    private suspend fun restore(passphrase: CharArray): FullBackupSecrets? {
        val staged = manager.stageRestore(uri, passphrase)
        assertTrue("Backup must pass the restore checks: $staged", staged is DomainResult.Success)
        database.close()
        var secrets: FullBackupSecrets? = null
        FullBackupRestoreInstaller.applyPendingRestore(context, applySecrets = { secrets = it; true })
        val message = FullBackupRestoreInstaller.consumeResult(context)
        assertTrue("Restore must finish: $message", message!!.startsWith("Backup restored"))
        return secrets
    }

    private fun databaseAtSchema(version: Int): ByteArray {
        val schema = File("schemas/com.example.chatgpt_mealplanner.core.database.AppDatabase/$version.json")
        val definition = Json.parseToJsonElement(schema.readText()).jsonObject["database"]!!.jsonObject
        val file = File(context.cacheDir, "older_schema_$version.db").apply { delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            definition["entities"]!!.jsonArray.map { it.jsonObject }.forEach { entity ->
                val table = entity["tableName"]!!.jsonPrimitive.content
                db.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach { index ->
                    db.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            db.execSQL("INSERT INTO user_stores (id, name, isArchived, createdAt, updatedAt) VALUES ('older_store', 'Older Store', 0, 1, 1)")
            db.execSQL(
                """
                INSERT INTO product_barcode_mappings
                (barcode, displayName, canonicalIngredientId, canonicalName, source, confidence, lastVerifiedAt, createdAt, updatedAt)
                VALUES ('older_barcode', 'Cheddar', 'cheese', 'Cheddar', 'TEST', 'HIGH', 1, 1, 1)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO purchase_price_observations
                (id, storeId, barcode, productName, priceMinorUnits, currencyCode, purchasedAt, createdAt, source)
                VALUES ('older_price', 'older_store', 'older_barcode', 'Cheddar', 250, 'GBP', 1, 1, 'SCANNER')
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO paired_devices (deviceId, deviceName, devicePublicKey, status, pairedAt, lastSyncTimestamp)
                VALUES ('older_follower', 'Phone', 'public-key', 'ACTIVE', 1, 1)
                """.trimIndent()
            )
            db.version = version
        }
        return file.readBytes().also { file.delete() }
    }

    @Test
    fun backupWithAnOlderDatabase_isMigratedOnRestore() = runBlocking {
        val passphrase = "older schema".toCharArray()
        val olderVersion = 21
        val contents = FullBackupContents(
            manifest = FullBackupManifest(roomSchemaVersion = olderVersion, createdAt = 1L, appVersionName = "older"),
            database = databaseAtSchema(olderVersion),
            secrets = FullBackupSecrets(geminiApiKey = "older-key"),
            preferenceFiles = emptyMap()
        )
        fileToRestore = codec.encodeFullBackup(FullBackupArchive.write(contents), passphrase)

        val secrets = restore(passphrase)

        val reopened = openDatabase()
        try {
            assertTrue(reopened.openHelper.readableDatabase.version > olderVersion)
            val prices = reopened.purchasePriceDao()
            assertEquals(setOf("older_store"), prices.getAllStores().map { it.id }.toSet())
            assertEquals("cheese", prices.getObservation("older_price")?.canonicalName)
            assertEquals("REVOKED", reopened.pairedDeviceDao().getDeviceById("older_follower")?.status)
        } finally {
            reopened.close()
        }
        assertEquals("older-key", secrets?.geminiApiKey)
    }

    /**
     * A real backup file saved by the first version of this format. Never regenerate it: if this
     * test fails, the change being tested would strand users' existing backups.
     */
    @Test
    fun firstFullBackupFormat_canStillBeRestored() = runBlocking {
        val passphrase = "compatibility fixture".toCharArray()
        fileToRestore = requireNotNull(javaClass.getResourceAsStream("/backup/full-backup-wire2-schema22.pantryenc")).use { it.readBytes() }

        val secrets = restore(passphrase)

        val reopened = openDatabase()
        try {
            val prices = reopened.purchasePriceDao()
            assertEquals(setOf("fixture_store"), prices.getAllStores().map { it.id }.toSet())
            val price = prices.getObservation("fixture_price")
            assertNotNull(price)
            assertEquals(500L, price!!.priceMinorUnits)
            assertEquals("REVOKED", reopened.pairedDeviceDao().getDeviceById("fixture_follower")?.status)
        } finally {
            reopened.close()
        }
        assertEquals("fixture-gemini-key", secrets?.geminiApiKey)
        assertEquals("fixture-tls-password", secrets?.tlsKeystorePassword)
        assertFalse("Decrypted data must not stay on disk", FullBackupRestoreInstaller.stagingFolder(context).exists())
    }
}
