package com.example.chatgpt_mealplanner.core.backup

import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.DomainResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class PantryPhase6Step4aPortableEncryptionTest {

    private lateinit var encoderDecoder: MainBackupContainerEncoderDecoder
    private lateinit var codec: MainBackupEncryptionCodec

    @Before
    fun setUp() {
        encoderDecoder = MainBackupContainerEncoderDecoder()
        codec = MainBackupEncryptionCodec(encoderDecoder)
    }

    private fun createSampleSnapshot(): MainHouseholdBackupDto = MainHouseholdBackupDto(
        exportedAt = 1791890000000L,
        householdId = "house-enc-100",
        householdName = "Encrypted Pantry Test",
        mainDeviceId = "main-dev-1",
        nextCanonicalSequence = 42L,
        inventory = listOf(
            InventoryItemExportDto(
                id = "item-enc-1",
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
                notes = "Encrypted test",
                createdAt = 1000L,
                updatedAt = 2000L,
                revision = 5L
            )
        ),
        canonicalOperations = listOf(
            CanonicalSyncOperationExportDto(
                operationId = "op-enc-10",
                deviceId = "follower-dev-1",
                deviceSequence = 1L,
                protocolVersion = 1,
                operationType = "SET_QUANTITY",
                targetItemId = "item-enc-1",
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
    fun encodeAndDecode_validPassphrase_encryptsAndDecryptsLosslessly() = runTest {
        val originalSnapshot = createSampleSnapshot()
        val passphrase = "PantryMasterPassword#2026!".toCharArray()

        val encryptedBytes = codec.encodeEncryptedBackup(originalSnapshot, passphrase, currentTimeMs = 2000000L)
        assertTrue("Encrypted bytes MUST be non-empty", encryptedBytes.isNotEmpty())
        assertTrue("Encrypted bytes length MUST exceed header size", encryptedBytes.size > ENCRYPTED_HEADER_SIZE_BYTES)

        val result = codec.decodeAndValidateEncryptedBackup(encryptedBytes, passphrase)
        assertTrue("Decryption with valid passphrase MUST succeed", result is DomainResult.Success)

        val decryptedSnapshot = (result as DomainResult.Success).value
        assertEquals("pantry-household-backup-v1", decryptedSnapshot.backupFormatVersion)
        assertEquals(16, decryptedSnapshot.roomSchemaVersion)
        assertEquals("MAIN", decryptedSnapshot.role)
        assertEquals("house-enc-100", decryptedSnapshot.householdId)
        assertEquals(42L, decryptedSnapshot.nextCanonicalSequence)

        assertEquals(1, decryptedSnapshot.inventory.size)
        val inv = decryptedSnapshot.inventory.first()
        assertEquals("item-enc-1", inv.id)
        assertEquals("Extra Virgin Olive Oil", inv.displayName)
        assertEquals(3.5, inv.quantity, 0.001)
        assertEquals(5L, inv.revision)
    }

    @Test
    fun encodeEncryptedBackup_sameSnapshot_producesDifferentSaltAndNonce() = runTest {
        val snapshot = createSampleSnapshot()
        val passphrase = "SecretPassword123!".toCharArray()

        val bytes1 = codec.encodeEncryptedBackup(snapshot, passphrase, currentTimeMs = 2000000L)
        val bytes2 = codec.encodeEncryptedBackup(snapshot, passphrase, currentTimeMs = 2000000L)

        // Random salt and nonce MUST produce different byte outputs
        assertFalse("Two encryptions MUST produce different ciphertexts due to random salt/nonce", bytes1.contentEquals(bytes2))

        // Both MUST decrypt successfully to the same snapshot
        val res1 = codec.decodeAndValidateEncryptedBackup(bytes1, passphrase)
        val res2 = codec.decodeAndValidateEncryptedBackup(bytes2, passphrase)

        assertTrue(res1 is DomainResult.Success)
        assertTrue(res2 is DomainResult.Success)
        assertEquals((res1 as DomainResult.Success).value.householdId, (res2 as DomainResult.Success).value.householdId)
    }

    @Test
    fun decodeAndValidate_wrongPassphrase_rejectsWithPassphraseError() = runTest {
        val snapshot = createSampleSnapshot()
        val passphrase = "CorrectPassword123!".toCharArray()
        val wrongPassphrase = "WrongPassword456!".toCharArray()

        val encryptedBytes = codec.encodeEncryptedBackup(snapshot, passphrase, currentTimeMs = 2000000L)

        val result = codec.decodeAndValidateEncryptedBackup(encryptedBytes, wrongPassphrase)
        assertTrue("Decryption with wrong passphrase MUST fail", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention invalid passphrase", errorMsg.contains("passphrase"))
    }

    @Test
    fun decodeAndValidate_tamperedHeaderMagic_rejectsWithMagicError() = runTest {
        val snapshot = createSampleSnapshot()
        val passphrase = "Password123!".toCharArray()
        val bytes = codec.encodeEncryptedBackup(snapshot, passphrase, currentTimeMs = 2000000L)

        // Mutate first magic byte 'P' -> 'X'
        val mutatedBytes = bytes.clone()
        mutatedBytes[0] = 'X'.code.toByte()

        val result = codec.decodeAndValidateEncryptedBackup(mutatedBytes, passphrase)
        assertTrue("Mutated magic byte MUST fail validation", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention magic identifier", errorMsg.contains("magic"))
    }

    @Test
    fun decodeAndValidate_tamperedHeaderWireVersion_rejectsWithVersionError() = runTest {
        val snapshot = createSampleSnapshot()
        val passphrase = "Password123!".toCharArray()
        val bytes = codec.encodeEncryptedBackup(snapshot, passphrase, currentTimeMs = 2000000L)

        // Mutate wire version at offset 12 (Int 1 -> 99)
        val mutatedBytes = bytes.clone()
        mutatedBytes[12] = 99.toByte()

        val result = codec.decodeAndValidateEncryptedBackup(mutatedBytes, passphrase)
        assertTrue("Mutated wire version MUST fail validation", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention wire version", errorMsg.contains("wire version"))
    }

    @Test
    fun decodeAndValidate_tamperedCiphertext_rejectsWithTagAuthenticationError() = runTest {
        val snapshot = createSampleSnapshot()
        val passphrase = "Password123!".toCharArray()
        val bytes = codec.encodeEncryptedBackup(snapshot, passphrase, currentTimeMs = 2000000L)

        // Mutate 1 byte in ciphertext portion
        val mutatedBytes = bytes.clone()
        mutatedBytes[ENCRYPTED_HEADER_SIZE_BYTES + 5] = (mutatedBytes[ENCRYPTED_HEADER_SIZE_BYTES + 5] + 1).toByte()

        val result = codec.decodeAndValidateEncryptedBackup(mutatedBytes, passphrase)
        assertTrue("Mutated ciphertext MUST fail GCM tag authentication", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention invalid passphrase or tampered backup", errorMsg.contains("passphrase") || errorMsg.contains("tampered"))
    }

    @Test
    fun decodeAndValidate_appendedTrailingBytes_rejectsWithLengthMismatch() = runTest {
        val snapshot = createSampleSnapshot()
        val passphrase = "Password123!".toCharArray()
        val bytes = codec.encodeEncryptedBackup(snapshot, passphrase, currentTimeMs = 2000000L)

        val trailingBytes = bytes + byteArrayOf(0x01, 0x02, 0x03, 0x04)

        val result = codec.decodeAndValidateEncryptedBackup(trailingBytes, passphrase)
        assertTrue("Appended trailing bytes MUST fail length check", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention length mismatch", errorMsg.contains("length mismatch"))
    }

    @Test
    fun decodeAndValidate_unicodePassphrase_roundTripsLosslessly() = runTest {
        val snapshot = createSampleSnapshot()
        val unicodePassphrase = "PassphraseWithUnicodeEmoji🔒&AccentsÁÉÍÓÚ!".toCharArray()

        val bytes = codec.encodeEncryptedBackup(snapshot, unicodePassphrase, currentTimeMs = 2000000L)
        val result = codec.decodeAndValidateEncryptedBackup(bytes, unicodePassphrase)

        assertTrue("Unicode passphrase MUST decrypt successfully", result is DomainResult.Success)
        assertEquals("house-enc-100", (result as DomainResult.Success).value.householdId)
    }

    @Test
    fun decodeAndValidate_excessiveIterations_rejectsIterationBounds() = runTest {
        val snapshot = createSampleSnapshot()
        val passphrase = "Password123!".toCharArray()
        val bytes = codec.encodeEncryptedBackup(snapshot, passphrase, currentTimeMs = 2000000L, iterations = DEFAULT_KDF_ITERATIONS)

        // Mutate iterations field in header (offset 61) to 5,000,000 > 1,000,000 max
        val mutatedBytes = bytes.clone()
        val buf = java.nio.ByteBuffer.wrap(mutatedBytes).apply {
            order(java.nio.ByteOrder.BIG_ENDIAN)
            position(61)
            putInt(5_000_000)
        }

        val result = codec.decodeAndValidateEncryptedBackup(buf.array(), passphrase)
        assertTrue("Excessive iterations MUST fail validation before KDF work", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention iterations outside permitted range", errorMsg.contains("iterations"))
    }

    @Test
    fun maxEncryptedContainerSize_constantCalculation_matchesFullPlaintextAndHeaderOverhead() = runTest {
        val expectedMaxEncryptedSize = MAX_CONTAINER_BODY_SIZE_BYTES + CONTAINER_OVERHEAD_SIZE + ENCRYPTED_HEADER_SIZE_BYTES + AEAD_GCM_TAG_SIZE_BYTES
        assertEquals("MAX_ENCRYPTED_CONTAINER_SIZE_BYTES MUST account for full plaintext framing overhead", expectedMaxEncryptedSize, MAX_ENCRYPTED_CONTAINER_SIZE_BYTES)
        assertEquals(52428933, MAX_ENCRYPTED_CONTAINER_SIZE_BYTES)
    }

    @Test
    fun decodeAndValidate_oneByteOverMaxEncryptedBoundary_rejectsWithSizeError() = runTest {
        val passphrase = "Password123!".toCharArray()
        val oversizedBytes = ByteArray(MAX_ENCRYPTED_CONTAINER_SIZE_BYTES + 1)

        val result = codec.decodeAndValidateEncryptedBackup(oversizedBytes, passphrase)
        assertTrue("Input 1 byte over MAX_ENCRYPTED_CONTAINER_SIZE_BYTES MUST fail validation", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention size limit", errorMsg.contains("size limit"))
    }
}
