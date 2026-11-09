package com.example.chatgpt_mealplanner.core.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatgpt_mealplanner.core.database.ALL_MIGRATIONS
import com.example.chatgpt_mealplanner.core.database.AppDatabase
import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemEntity
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.security.CredentialStore
import com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
import com.example.chatgpt_mealplanner.core.sync.security.PantryTlsKeyManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Saves full backups through the real ContentResolver and SQLite on a device. */
@RunWith(AndroidJUnit4::class)
class PantryPhase6Step6cAndroidSafIntegrationTest {

    private val databaseName = "test_full_backup_saf.db"
    private val passphrase = "AndroidMasterPassword#2026!".toCharArray()
    private val codec = MainBackupEncryptionCodec()
    private lateinit var targetContext: Context
    private lateinit var database: AppDatabase
    private lateinit var backupFile: File
    private lateinit var extractedDatabase: File

    private class FakeAppRoleResolver : AppRoleResolver(
        com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore(null)
    ) {
        override suspend fun isMainAsync(): Boolean = true
        override fun isMain(): Boolean = true
        override suspend fun isFollowerAsync(): Boolean = false
        override fun isFollower(): Boolean = false
    }

    private class FakeCredentialStore : CredentialStore {
        override fun hasKey(): Boolean = true
        override fun saveKey(key: String) {}
        override fun loadKey(): String = "claude-test-key"
        override fun deleteKey() {}
    }

    private class FakeTlsKeyManager : PantryTlsKeyManager(null) {
        override fun exportTlsIdentity() = "tls-bytes".toByteArray() to "tls-password"
    }

    @Before
    fun setUp() {
        targetContext = ApplicationProvider.getApplicationContext()
        targetContext.deleteDatabase(databaseName)
        // Room's default journal mode here is WAL, so recent writes are still in the -wal file during the backup.
        database = Room.databaseBuilder(targetContext, AppDatabase::class.java, databaseName)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        backupFile = File(targetContext.cacheDir, "test_full_backup_saf.pantryenc").apply { delete() }
        extractedDatabase = File(targetContext.cacheDir, "test_full_backup_extracted.db").apply { delete() }
    }

    @After
    fun tearDown() {
        database.close()
        targetContext.deleteDatabase(databaseName)
        backupFile.delete()
        extractedDatabase.delete()
    }

    private fun item(id: String, displayName: String) = InventoryItemEntity(
        id = id,
        barcode = "987654321",
        canonicalIngredientId = "ING_MILK",
        canonicalName = "whole milk",
        displayName = displayName,
        brand = "Arla",
        category = FoodCategory.FRIDGE,
        storageLocation = StorageLocation.FRIDGE,
        quantity = 2.0,
        quantityUnit = QuantityUnit.LITRE,
        packCount = 1,
        packSize = 2.0,
        packSizeUnit = QuantityUnit.LITRE,
        quantityConfidence = QuantityConfidence.EXACT,
        status = ItemStatus.AVAILABLE,
        opened = false,
        frozen = false,
        staple = true,
        purchaseDate = "2026-10-10",
        openedDate = null,
        useByDate = "2026-10-25",
        bestBeforeDate = null,
        notes = "Backup test milk",
        createdAt = 1000L,
        updatedAt = 2000L,
        revision = 1L
    )

    private fun manager(streamProvider: SafDocumentStreamProvider) =
        FullBackupManager(targetContext, database, FakeCredentialStore(), FakeTlsKeyManager(), codec, streamProvider)

    private suspend fun saveBackup(): SafBackupOutcome {
        val streamProvider = DefaultSafDocumentStreamProvider()
        val coordinator = MainEncryptedBackupSaveCoordinator(
            FakeAppRoleResolver(),
            MainEncryptedBackupSafWriter(streamProvider),
            manager(streamProvider)
        )
        return coordinator.executeMainBackupSave(targetContext, Uri.fromFile(backupFile), passphrase, currentTimeMs = 3000000L)
    }

    private fun readSavedContents(): FullBackupContents {
        val decrypted = codec.decodeFullBackup(backupFile.readBytes(), passphrase)
        assertTrue("Saved backup MUST decrypt", decrypted is DomainResult.Success)
        return FullBackupArchive.read((decrypted as DomainResult.Success).value)
    }

    private fun displayNamesIn(contents: FullBackupContents): Set<String> {
        extractedDatabase.writeBytes(contents.database)
        SQLiteDatabase.openDatabase(extractedDatabase.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT displayName FROM inventory_items", null).use { cursor ->
                return buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
        }
    }

    @Test
    fun realContentResolver_savesVerifiedBackupHoldingTheWholeDatabaseAndSecrets() = runBlocking {
        database.inventoryDao().insertItem(item("item-1", "Organic Whole Milk"))

        val outcome = saveBackup()

        assertEquals(SafBackupOutcome.VerifiedFullBackup, outcome)
        val contents = readSavedContents()
        assertEquals(FULL_BACKUP_FORMAT, contents.manifest.format)
        assertEquals(database.openHelper.readableDatabase.version, contents.manifest.roomSchemaVersion)
        assertEquals(3000000L, contents.manifest.createdAt)
        assertEquals("claude-test-key", contents.secrets.claudeApiKey)
        assertEquals(setOf("Organic Whole Milk"), displayNamesIn(contents))
    }

    @Test
    fun walMode_backupCapturesRecentlyCommittedWrites() = runBlocking {
        database.inventoryDao().insertItem(item("item-1", "First"))
        // No checkpoint, so this write is only in the WAL when the backup is taken.
        database.inventoryDao().insertItem(item("item-2", "Second"))

        assertEquals(SafBackupOutcome.VerifiedFullBackup, saveBackup())

        assertEquals(setOf("First", "Second"), displayNamesIn(readSavedContents()))
    }

    @Test
    fun savedBackup_passesTheChecksRestoreRuns() = runBlocking {
        database.inventoryDao().insertItem(item("item-1", "Organic Whole Milk"))
        assertEquals(SafBackupOutcome.VerifiedFullBackup, saveBackup())

        val bytes = backupFile.readBytes()

        assertTrue(manager(DefaultSafDocumentStreamProvider()).verifyEncryptedBackup(bytes, passphrase))
    }
}
