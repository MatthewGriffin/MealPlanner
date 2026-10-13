package com.example.chatgpt_mealplanner.core.backup

import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.DomainResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

@RunWith(JUnit4::class)
class PantryPhase6Step5aEncryptionHardeningTest {

    private lateinit var encoderDecoder: MainBackupContainerEncoderDecoder
    private lateinit var codec: MainBackupEncryptionCodec

    @Before
    fun setUp() {
        encoderDecoder = MainBackupContainerEncoderDecoder()
        codec = MainBackupEncryptionCodec(encoderDecoder)
    }

    private fun createSampleSnapshot(): MainHouseholdBackupDto = MainHouseholdBackupDto(
        exportedAt = 1791890000000L,
        householdId = "house-hard-100",
        householdName = "Hardening Test Household",
        mainDeviceId = "main-dev-1",
        nextCanonicalSequence = 42L,
        inventory = listOf(
            InventoryItemExportDto(
                id = "item-hard-1",
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
                notes = "Hardening test",
                createdAt = 1000L,
                updatedAt = 2000L,
                revision = 5L
            )
        ),
        canonicalOperations = listOf(
            CanonicalSyncOperationExportDto(
                operationId = "op-hard-10",
                deviceId = "follower-dev-1",
                deviceSequence = 1L,
                protocolVersion = 1,
                operationType = "SET_QUANTITY",
                targetItemId = "item-hard-1",
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
    fun decodeAndValidate_tamperedTag_rejectsTagAuthenticationError() = runTest {
        val snapshot = createSampleSnapshot()
        val passphrase = "Password123!".toCharArray()
        val bytes = codec.encodeEncryptedBackup(snapshot, passphrase, currentTimeMs = 2000000L)

        // Mutate the last byte of the 16-byte GCM authentication tag
        val mutatedBytes = bytes.clone()
        mutatedBytes[mutatedBytes.size - 1] = (mutatedBytes[mutatedBytes.size - 1] + 1).toByte()

        val result = codec.decodeAndValidateEncryptedBackup(mutatedBytes, passphrase)
        assertTrue("Mutated GCM tag MUST fail tag authentication", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention invalid passphrase or tampered backup", errorMsg.contains("passphrase") || errorMsg.contains("tampered"))
    }

    @Test
    fun decodeAndValidate_tamperedSalt_rejectsTagAuthenticationError() = runTest {
        val snapshot = createSampleSnapshot()
        val passphrase = "Password123!".toCharArray()
        val bytes = codec.encodeEncryptedBackup(snapshot, passphrase, currentTimeMs = 2000000L)

        // Mutate salt at offset 17
        val mutatedBytes = bytes.clone()
        mutatedBytes[17] = (mutatedBytes[17] + 1).toByte()

        val result = codec.decodeAndValidateEncryptedBackup(mutatedBytes, passphrase)
        assertTrue("Mutated salt MUST fail GCM tag authentication", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention invalid passphrase or tampered backup", errorMsg.contains("passphrase") || errorMsg.contains("tampered"))
    }

    @Test
    fun decodeAndValidate_tamperedNonce_rejectsTagAuthenticationError() = runTest {
        val snapshot = createSampleSnapshot()
        val passphrase = "Password123!".toCharArray()
        val bytes = codec.encodeEncryptedBackup(snapshot, passphrase, currentTimeMs = 2000000L)

        // Mutate nonce at offset 49
        val mutatedBytes = bytes.clone()
        mutatedBytes[49] = (mutatedBytes[49] + 1).toByte()

        val result = codec.decodeAndValidateEncryptedBackup(mutatedBytes, passphrase)
        assertTrue("Mutated nonce MUST fail GCM tag authentication", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention invalid passphrase or tampered backup", errorMsg.contains("passphrase") || errorMsg.contains("tampered"))
    }

    @Test
    fun decodeAndValidate_tamperedIterationsWithinBounds_rejectsTagAuthenticationError() = runTest {
        val snapshot = createSampleSnapshot()
        val passphrase = "Password123!".toCharArray()
        val bytes = codec.encodeEncryptedBackup(snapshot, passphrase, currentTimeMs = 2000000L, iterations = 250_000)

        // Mutate iterations field (offset 61) to another valid bound value 200,000
        val mutatedBytes = bytes.clone()
        ByteBuffer.wrap(mutatedBytes).apply {
            order(ByteOrder.BIG_ENDIAN)
            position(61)
            putInt(200_000)
        }

        val result = codec.decodeAndValidateEncryptedBackup(mutatedBytes, passphrase)
        assertTrue("Mutated iterations MUST fail GCM tag authentication", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention invalid passphrase or tampered backup", errorMsg.contains("passphrase") || errorMsg.contains("tampered"))
    }

    @Test
    fun decodeAndValidate_truncatedHeader_rejectsTruncatedHeaderError() = runTest {
        val truncatedHeader = ByteArray(30) // 30 bytes < 69 bytes header

        val result = codec.decodeAndValidateEncryptedBackup(truncatedHeader, "Password123!".toCharArray())
        assertTrue("Truncated header MUST fail validation", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention truncated header", errorMsg.contains("Truncated"))
    }

    @Test
    fun decodeAndValidate_truncatedCiphertext_rejectsLengthMismatchError() = runTest {
        val snapshot = createSampleSnapshot()
        val passphrase = "Password123!".toCharArray()
        val fullBytes = codec.encodeEncryptedBackup(snapshot, passphrase, currentTimeMs = 2000000L)

        // Truncate 20 bytes from ciphertext
        val truncatedBytes = fullBytes.copyOf(fullBytes.size - 20)

        val result = codec.decodeAndValidateEncryptedBackup(truncatedBytes, passphrase)
        assertTrue("Truncated ciphertext MUST fail length check", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention length mismatch", errorMsg.contains("length mismatch"))
    }

    @Test
    fun decodeAndValidate_unsupportedKdfId_rejectsKdfIdError() = runTest {
        val snapshot = createSampleSnapshot()
        val passphrase = "Password123!".toCharArray()
        val bytes = codec.encodeEncryptedBackup(snapshot, passphrase, currentTimeMs = 2000000L)

        // Mutate KDF ID at offset 13 (Short 1 -> Short 99)
        val mutatedBytes = bytes.clone()
        ByteBuffer.wrap(mutatedBytes).apply {
            order(ByteOrder.BIG_ENDIAN)
            position(13)
            putShort(99.toShort())
        }

        val result = codec.decodeAndValidateEncryptedBackup(mutatedBytes, passphrase)
        assertTrue("Unsupported KDF ID MUST fail validation before key derivation", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention unsupported KDF algorithm ID", errorMsg.contains("KDF algorithm ID"))
    }

    @Test
    fun decodeAndValidate_unsupportedAeadId_rejectsAeadIdError() = runTest {
        val snapshot = createSampleSnapshot()
        val passphrase = "Password123!".toCharArray()
        val bytes = codec.encodeEncryptedBackup(snapshot, passphrase, currentTimeMs = 2000000L)

        // Mutate AEAD ID at offset 15 (Short 1 -> Short 99)
        val mutatedBytes = bytes.clone()
        ByteBuffer.wrap(mutatedBytes).apply {
            order(ByteOrder.BIG_ENDIAN)
            position(15)
            putShort(99.toShort())
        }

        val result = codec.decodeAndValidateEncryptedBackup(mutatedBytes, passphrase)
        assertTrue("Unsupported AEAD ID MUST fail validation before decryption", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention unsupported AEAD algorithm ID", errorMsg.contains("AEAD algorithm ID"))
    }

    @Test
    fun decodeAndValidate_belowMinIterations_rejectsIterationBoundsError() = runTest {
        val snapshot = createSampleSnapshot()
        val passphrase = "Password123!".toCharArray()
        val bytes = codec.encodeEncryptedBackup(snapshot, passphrase, currentTimeMs = 2000000L)

        // Mutate iterations at offset 61 to 1,000 < 10,000 min
        val mutatedBytes = bytes.clone()
        ByteBuffer.wrap(mutatedBytes).apply {
            order(ByteOrder.BIG_ENDIAN)
            position(61)
            putInt(1_000)
        }

        val result = codec.decodeAndValidateEncryptedBackup(mutatedBytes, passphrase)
        assertTrue("Iterations below minimum MUST fail validation", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention iterations outside permitted range", errorMsg.contains("iterations"))
    }

    @Test
    fun decodeAndValidate_plaintextContainerSupplied_rejectsMagicErrorNoFallback() = runTest {
        val snapshot = createSampleSnapshot()
        val plaintextBytes = encoderDecoder.encodeContainer(snapshot, currentTimeMs = 2000000L)

        val result = codec.decodeAndValidateEncryptedBackup(plaintextBytes, "Password123!".toCharArray())
        assertTrue("Plaintext container supplied to encrypted decoder MUST fail magic check", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention invalid magic identifier", errorMsg.contains("magic"))
    }

    @Test
    fun decodeAndValidate_authenticatedInnerInvalidContainer_rejectsInnerContainerError() = runTest {
        val passphrase = "Password123!".toCharArray()

        // Construct a validly framed PANTRYBK container containing malformed inner JSON and valid SHA-256
        val malformedJsonBytes = "{ invalid inner json }".toByteArray(Charsets.UTF_8)
        val bodyLength = malformedJsonBytes.size

        val innerFramedBuffer = ByteBuffer.allocate(CONTAINER_HEADER_SIZE + bodyLength).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(CONTAINER_MAGIC)
            putInt(CONTAINER_WIRE_VERSION)
            putInt(bodyLength)
            put(malformedJsonBytes)
        }
        val innerFramedBytes = innerFramedBuffer.array()
        val innerSha256 = MessageDigest.getInstance("SHA-256").digest(innerFramedBytes)
        val malformedPlaintextBytes = innerFramedBytes + innerSha256

        // Encrypt malformed plaintext bytes using test cipher setup
        val salt = ByteArray(KDF_SALT_SIZE_BYTES)
        val nonce = ByteArray(AEAD_NONCE_SIZE_BYTES)
        val iterations = DEFAULT_KDF_ITERATIONS

        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = javax.crypto.spec.PBEKeySpec(passphrase, salt, iterations, 256)
        val key = javax.crypto.spec.SecretKeySpec(factory.generateSecret(spec).encoded, "AES")

        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = javax.crypto.spec.GCMParameterSpec(AEAD_GCM_TAG_SIZE_BITS, nonce)

        val ciphertextLength = malformedPlaintextBytes.size + AEAD_GCM_TAG_SIZE_BYTES
        val headerBuffer = ByteBuffer.allocate(ENCRYPTED_HEADER_SIZE_BYTES).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(ENCRYPTED_CONTAINER_MAGIC)
            putInt(ENCRYPTED_WIRE_VERSION)
            putShort(KDF_ID_PBKDF2_HMAC_SHA256)
            putShort(AEAD_ID_AES_256_GCM)
            put(salt)
            put(nonce)
            putInt(iterations)
            putInt(ciphertextLength)
        }
        val headerBytes = headerBuffer.array()

        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, gcmSpec)
        cipher.updateAAD(headerBytes)
        val ciphertextWithTag = cipher.doFinal(malformedPlaintextBytes)

        val encryptedContainerBytes = headerBytes + ciphertextWithTag

        val result = codec.decodeAndValidateEncryptedBackup(encryptedContainerBytes, passphrase)
        assertTrue("Authenticated envelope containing invalid inner container MUST fail inner validation", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention malformed JSON or inner validation error", errorMsg.contains("Malformed") || errorMsg.contains("JSON"))
    }
}
