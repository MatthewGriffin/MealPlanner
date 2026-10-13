package com.example.chatgpt_mealplanner.core.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatgpt_mealplanner.core.model.DomainResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PantryPhase6Step5aAndroidCryptoCompatibilityTest {

    private lateinit var encoderDecoder: MainBackupContainerEncoderDecoder
    private lateinit var codec: MainBackupEncryptionCodec

    @Before
    fun setUp() {
        encoderDecoder = MainBackupContainerEncoderDecoder()
        codec = MainBackupEncryptionCodec(encoderDecoder)
    }

    private fun createSampleSnapshot(): MainHouseholdBackupDto = MainHouseholdBackupDto(
        exportedAt = 1791890000000L,
        householdId = "house-android-100",
        householdName = "Android Crypto Compatibility Test",
        mainDeviceId = "main-dev-1",
        nextCanonicalSequence = 42L,
        inventory = listOf(
            InventoryItemExportDto(
                id = "item-android-1",
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
                notes = "Android test",
                createdAt = 1000L,
                updatedAt = 2000L,
                revision = 5L
            )
        ),
        canonicalOperations = listOf(
            CanonicalSyncOperationExportDto(
                operationId = "op-android-10",
                deviceId = "follower-dev-1",
                deviceSequence = 1L,
                protocolVersion = 1,
                operationType = "SET_QUANTITY",
                targetItemId = "item-android-1",
                baseRevision = 4L,
                payloadJson = "{}",
                createdAt = 1500L,
                canonicalSequence = 10L,
                receivedAt = 1600L,
                status = "APPLIED",
                resultCode = "OK",
                resultPayloadJson = null
            )
        )
    )

    @Test
    fun androidNativeJca_encryptsAndDecryptsMainBackupLosslessly() {
        val originalSnapshot = createSampleSnapshot()
        val passphrase = "AndroidMasterPassword#2026!".toCharArray()

        val encryptedBytes = codec.encodeEncryptedBackup(originalSnapshot, passphrase, currentTimeMs = 2000000L)
        assertTrue("Android encrypted bytes MUST be non-empty", encryptedBytes.isNotEmpty())

        val result = codec.decodeAndValidateEncryptedBackup(encryptedBytes, passphrase)
        assertTrue("Android native JCA decryption MUST succeed", result is DomainResult.Success)

        val decryptedSnapshot = (result as DomainResult.Success).value
        assertEquals("pantry-household-backup-v1", decryptedSnapshot.backupFormatVersion)
        assertEquals(16, decryptedSnapshot.roomSchemaVersion)
        assertEquals("MAIN", decryptedSnapshot.role)
        assertEquals("house-android-100", decryptedSnapshot.householdId)
        assertEquals(42L, decryptedSnapshot.nextCanonicalSequence)

        assertEquals(1, decryptedSnapshot.inventory.size)
        val inv = decryptedSnapshot.inventory.first()
        assertEquals("item-android-1", inv.id)
        assertEquals(3.5, inv.quantity, 0.001)
        assertEquals(5L, inv.revision)
    }

    @Test
    fun androidNativeJca_unicodePassphrase_roundTripsLosslesslyOnAndroidOS() {
        val snapshot = createSampleSnapshot()
        val unicodePassphrase = "AndroidPassphraseWithUnicodeEmoji🔒&AccentsÁÉÍÓÚ!".toCharArray()

        val encryptedBytes = codec.encodeEncryptedBackup(snapshot, unicodePassphrase, currentTimeMs = 2000000L)
        val result = codec.decodeAndValidateEncryptedBackup(encryptedBytes, unicodePassphrase)

        assertTrue("Android native JCA Unicode passphrase MUST decrypt successfully", result is DomainResult.Success)
        assertEquals("house-android-100", (result as DomainResult.Success).value.householdId)
    }

    @Test
    fun androidNativeJca_wrongPassphrase_rejectsOnAndroidOS() {
        val snapshot = createSampleSnapshot()
        val passphrase = "CorrectPassword123!".toCharArray()
        val wrongPassphrase = "WrongPassword456!".toCharArray()

        val encryptedBytes = codec.encodeEncryptedBackup(snapshot, passphrase, currentTimeMs = 2000000L)
        val result = codec.decodeAndValidateEncryptedBackup(encryptedBytes, wrongPassphrase)

        assertTrue("Android native JCA wrong passphrase MUST fail decryption", result is DomainResult.Failure)
    }
}
