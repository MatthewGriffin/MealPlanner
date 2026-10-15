package com.example.chatgpt_mealplanner.core.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatgpt_mealplanner.core.database.DatabaseTransactionRunner
import com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PantryPhase6Step6cAndroidSafIntegrationTest {

    private lateinit var targetContext: Context
    private lateinit var encoderDecoder: MainBackupContainerEncoderDecoder
    private lateinit var codec: MainBackupEncryptionCodec

    @Before
    fun setUp() {
        targetContext = ApplicationProvider.getApplicationContext()
        encoderDecoder = MainBackupContainerEncoderDecoder()
        codec = MainBackupEncryptionCodec(encoderDecoder)
    }

    private class FakeAppRoleResolver(private val isMainRole: Boolean) : AppRoleResolver(
        com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore(null)
    ) {
        override suspend fun isMainAsync(): Boolean = isMainRole
        override fun isMain(): Boolean = isMainRole
        override suspend fun isFollowerAsync(): Boolean = !isMainRole
        override fun isFollower(): Boolean = !isMainRole
    }

    private fun createSampleSnapshot(): MainHouseholdBackupDto = MainHouseholdBackupDto(
        exportedAt = 1791890000000L,
        householdId = "house-saf-android-100",
        householdName = "Android SAF Integration Household",
        mainDeviceId = "main-dev-1",
        nextCanonicalSequence = 42L,
        inventory = listOf(
            InventoryItemExportDto(
                id = "item-saf-android-1",
                barcode = "123456789",
                canonicalIngredientId = "ING_OIL",
                canonicalName = "olive oil",
                displayName = "Extra Virgin Olive Oil",
                brand = "Asda",
                category = "CUPBOARD",
                storageLocation = "CUPBOARD",
                quantity = 3.5,
                quantityUnit = "LITRE",
                packCount = 1,
                packSize = 1.0,
                packSizeUnit = "LITRE",
                quantityConfidence = "EXACT",
                status = "AVAILABLE",
                opened = true,
                frozen = false,
                staple = true,
                purchaseDate = "2026-10-01",
                openedDate = "2026-10-02",
                useByDate = "2027-10-01",
                bestBeforeDate = null,
                notes = "Android SAF test",
                createdAt = 1000L,
                updatedAt = 2000L,
                revision = 5L
            )
        )
    )

    @Test
    fun realContentResolverFileStream_writesAndVerifiesEncryptedBackupOnAndroid() = runBlocking {
        val noOpRunner = object : DatabaseTransactionRunner {
            override suspend fun <T> runInTransaction(block: suspend () -> T): T = block()
        }
        val snapshotBuilder = object : MainBackupSnapshotBuilder(null, noOpRunner) {
            override suspend fun createMainBackupSnapshot(currentTimeMs: Long): MainHouseholdBackupDto {
                return createSampleSnapshot().copy(exportedAt = currentTimeMs)
            }
        }
        val roleResolver = FakeAppRoleResolver(isMainRole = true)
        val defaultStreamProvider = DefaultSafDocumentStreamProvider()
        val safWriter = MainEncryptedBackupSafWriter(defaultStreamProvider)
        val coordinator = MainEncryptedBackupSaveCoordinator(roleResolver, snapshotBuilder, codec, safWriter)

        val testFile = File(targetContext.cacheDir, "test_pantry_saf_backup.pantrybak")
        if (testFile.exists()) testFile.delete()

        try {
            val fileUri = Uri.fromFile(testFile)
            val passphrase = "AndroidMasterPassword#2026!".toCharArray()

            val outcome = coordinator.executeMainBackupSave(
                appContext = targetContext,
                destinationUri = fileUri,
                passphrase = passphrase,
                currentTimeMs = 2000000L
            )

            if (outcome is SafBackupOutcome.NoDocumentIoAttempted) {
                org.junit.Assert.fail("Failed pre-I/O: ${outcome.cause.cause}")
            } else if (outcome is SafBackupOutcome.DocumentIoAttemptedUnverified) {
                org.junit.Assert.fail("Failed post-I/O: ${outcome.cause.cause}")
            }

            assertTrue("Real ContentResolver file stream SAF backup save MUST succeed", outcome is SafBackupOutcome.VerifiedSuccess)
            val snapshot = (outcome as SafBackupOutcome.VerifiedSuccess).snapshot
            assertEquals("pantry-household-backup-v1", snapshot.backupFormatVersion)
            assertEquals("house-saf-android-100", snapshot.householdId)
            assertTrue("Saved file MUST exist on disk", testFile.exists())
            assertTrue("Saved file size MUST be non-empty", testFile.length() > 0)
        } finally {
            if (testFile.exists()) testFile.delete()
        }
    }
}
