package com.example.chatgpt_mealplanner.core.backup

import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.DomainResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class MainBackupEncryptionCodec @Inject constructor(
    private val containerEncoderDecoder: MainBackupContainerEncoderDecoder
) {
    private val secureRandom = SecureRandom()

    open fun encodeEncryptedBackup(
        snapshot: MainHouseholdBackupDto,
        passphrase: CharArray,
        currentTimeMs: Long = System.currentTimeMillis(),
        iterations: Int = DEFAULT_KDF_ITERATIONS
    ): ByteArray {
        require(passphrase.isNotEmpty()) { "Backup passphrase MUST NOT be empty." }
        require(iterations in MIN_KDF_ITERATIONS..MAX_KDF_ITERATIONS) {
            "KDF iterations $iterations outside permitted range [$MIN_KDF_ITERATIONS, $MAX_KDF_ITERATIONS]."
        }

        // Encode snapshot DTO into plaintext .pantrybak container
        val plaintextContainerBytes = containerEncoderDecoder.encodeContainer(snapshot, currentTimeMs)
        return encryptPayload(plaintextContainerBytes, passphrase, iterations, ENCRYPTED_WIRE_VERSION)
    }

    open fun encodeFullBackup(
        archive: ByteArray,
        passphrase: CharArray,
        iterations: Int = DEFAULT_KDF_ITERATIONS
    ): ByteArray {
        require(passphrase.isNotEmpty()) { "Backup passphrase MUST NOT be empty." }
        require(iterations in MIN_KDF_ITERATIONS..MAX_KDF_ITERATIONS) {
            "KDF iterations $iterations outside permitted range [$MIN_KDF_ITERATIONS, $MAX_KDF_ITERATIONS]."
        }
        require(archive.size <= MAX_CONTAINER_BODY_SIZE_BYTES) { "Household data is too large to back up." }
        return encryptPayload(archive, passphrase, iterations, FULL_BACKUP_WIRE_VERSION)
    }

    private fun encryptPayload(
        plaintextContainerBytes: ByteArray,
        passphrase: CharArray,
        iterations: Int,
        wireVersion: Int
    ): ByteArray {
        // 2. Generate random salt and nonce
        val salt = ByteArray(KDF_SALT_SIZE_BYTES)
        val nonce = ByteArray(AEAD_NONCE_SIZE_BYTES)
        secureRandom.nextBytes(salt)
        secureRandom.nextBytes(nonce)

        // 3. Derive 256-bit AES key via PBKDF2WithHmacSHA256
        val secretKey = deriveAesKey(passphrase, salt, iterations)

        // 4. Encrypt plaintext container using AES-256-GCM
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(AEAD_GCM_TAG_SIZE_BITS, nonce)

        // Calculate expected ciphertext length: plaintext length + 16B GCM tag
        val ciphertextLength = plaintextContainerBytes.size + AEAD_GCM_TAG_SIZE_BYTES

        // 5. Construct 59-byte raw header
        val headerBuffer = ByteBuffer.allocate(ENCRYPTED_HEADER_SIZE_BYTES).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(ENCRYPTED_CONTAINER_MAGIC)
            putInt(wireVersion)
            putShort(KDF_ID_PBKDF2_HMAC_SHA256)
            putShort(AEAD_ID_AES_256_GCM)
            put(salt)
            put(nonce)
            putInt(iterations)
            putInt(ciphertextLength)
        }
        val headerBytes = headerBuffer.array()

        // 6. Initialize Cipher and pass headerBytes as Authenticated Associated Data (AAD)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
        cipher.updateAAD(headerBytes)

        val ciphertextWithTag = cipher.doFinal(plaintextContainerBytes)

        // 7. Output complete encrypted container: [Header (59B)][Ciphertext + GCM Tag]
        val outputBuffer = ByteBuffer.allocate(headerBytes.size + ciphertextWithTag.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(headerBytes)
            put(ciphertextWithTag)
        }

        return outputBuffer.array()
    }

    open fun decodeAndValidateEncryptedBackup(
        encryptedBytes: ByteArray,
        passphrase: CharArray
    ): DomainResult<MainHouseholdBackupDto> {
        return when (val decrypted = decryptPayload(encryptedBytes, passphrase, ENCRYPTED_WIRE_VERSION)) {
            is DomainResult.Failure -> DomainResult.Failure(decrypted.error)
            // Pass Decrypted Plaintext to PANTRYBK Container Decoder
            is DomainResult.Success -> containerEncoderDecoder.decodeAndValidateContainer(decrypted.value)
        }
    }

    open fun decodeFullBackup(encryptedBytes: ByteArray, passphrase: CharArray): DomainResult<ByteArray> {
        if (encryptedBytes.size >= ENCRYPTED_HEADER_SIZE_BYTES) {
            val wireVersion = ByteBuffer.wrap(encryptedBytes, ENCRYPTED_CONTAINER_MAGIC.size, 4)
                .order(ByteOrder.BIG_ENDIAN).int
            if (wireVersion == ENCRYPTED_WIRE_VERSION) {
                return DomainResult.Failure(
                    DomainError.TransactionFailed(
                        "This backup was made by an older version of the app and can't be restored. Save a new backup to replace it."
                    )
                )
            }
        }
        return decryptPayload(encryptedBytes, passphrase, FULL_BACKUP_WIRE_VERSION)
    }

    private fun decryptPayload(
        encryptedBytes: ByteArray,
        passphrase: CharArray,
        expectedWireVersion: Int
    ): DomainResult<ByteArray> {
        if (passphrase.isEmpty()) {
            return DomainResult.Failure(DomainError.TransactionFailed("Backup passphrase MUST NOT be empty."))
        }

        if (encryptedBytes.isEmpty()) {
            return DomainResult.Failure(DomainError.TransactionFailed("Encrypted backup container file is empty."))
        }

        if (encryptedBytes.size < MIN_ENCRYPTED_CONTAINER_SIZE_BYTES) {
            return DomainResult.Failure(
                DomainError.TransactionFailed("Truncated encrypted header: size ${encryptedBytes.size} bytes is less than minimum framing header ${MIN_ENCRYPTED_CONTAINER_SIZE_BYTES} bytes.")
            )
        }

        val totalSize = encryptedBytes.size
        if (totalSize > MAX_ENCRYPTED_CONTAINER_SIZE_BYTES) {
            return DomainResult.Failure(
                DomainError.TransactionFailed("Encrypted backup container exceeds maximum size limit of ${MAX_CONTAINER_BODY_SIZE_BYTES / (1024 * 1024)} MB.")
            )
        }

        val headerBuffer = ByteBuffer.wrap(encryptedBytes, 0, ENCRYPTED_HEADER_SIZE_BYTES).apply {
            order(ByteOrder.BIG_ENDIAN)
        }

        // 1. Verify Magic "PANTRYENC" (9 bytes)
        val magicBytes = ByteArray(9)
        headerBuffer.get(magicBytes)
        if (!magicBytes.contentEquals(ENCRYPTED_CONTAINER_MAGIC)) {
            return DomainResult.Failure(
                DomainError.TransactionFailed("Invalid encrypted container magic identifier. Expected PANTRYENC.")
            )
        }

        // 2. Verify Wire Version (4 bytes)
        val wireVersion = headerBuffer.int
        if (wireVersion != expectedWireVersion) {
            return DomainResult.Failure(
                DomainError.TransactionFailed("Unsupported encrypted wire version: $wireVersion. Expected $expectedWireVersion.")
            )
        }

        // 3. Verify KDF & AEAD Algorithm IDs (2 + 2 bytes)
        val kdfId = headerBuffer.short
        if (kdfId != KDF_ID_PBKDF2_HMAC_SHA256) {
            return DomainResult.Failure(
                DomainError.TransactionFailed("Unsupported KDF algorithm ID: $kdfId. Expected PBKDF2-HMAC-SHA256 ($KDF_ID_PBKDF2_HMAC_SHA256).")
            )
        }

        val aeadId = headerBuffer.short
        if (aeadId != AEAD_ID_AES_256_GCM) {
            return DomainResult.Failure(
                DomainError.TransactionFailed("Unsupported AEAD algorithm ID: $aeadId. Expected AES-256-GCM ($AEAD_ID_AES_256_GCM).")
            )
        }

        // 4. Extract Salt (32B) & Nonce (12B)
        val salt = ByteArray(KDF_SALT_SIZE_BYTES)
        val nonce = ByteArray(AEAD_NONCE_SIZE_BYTES)
        headerBuffer.get(salt)
        headerBuffer.get(nonce)

        // 5. Verify Iterations (4 bytes)
        val iterations = headerBuffer.int
        if (iterations !in MIN_KDF_ITERATIONS..MAX_KDF_ITERATIONS) {
            return DomainResult.Failure(
                DomainError.TransactionFailed("KDF iterations $iterations outside permitted range [$MIN_KDF_ITERATIONS, $MAX_KDF_ITERATIONS].")
            )
        }

        // 6. Verify Ciphertext Length (4 bytes)
        val ciphertextLength = headerBuffer.int
        if (ciphertextLength <= AEAD_GCM_TAG_SIZE_BYTES) {
            return DomainResult.Failure(
                DomainError.TransactionFailed("Invalid ciphertext length $ciphertextLength bytes.")
            )
        }

        val expectedTotalSize = ENCRYPTED_HEADER_SIZE_BYTES + ciphertextLength
        if (totalSize != expectedTotalSize) {
            return DomainResult.Failure(
                DomainError.TransactionFailed("Encrypted container length mismatch: expected $expectedTotalSize bytes based on header length, but file size is $totalSize bytes.")
            )
        }

        // 7. Extract Raw Header Bytes for AAD & Ciphertext Bytes
        val headerBytes = ByteArray(ENCRYPTED_HEADER_SIZE_BYTES)
        System.arraycopy(encryptedBytes, 0, headerBytes, 0, ENCRYPTED_HEADER_SIZE_BYTES)

        val ciphertextBytes = ByteArray(ciphertextLength)
        System.arraycopy(encryptedBytes, ENCRYPTED_HEADER_SIZE_BYTES, ciphertextBytes, 0, ciphertextLength)

        // 8. Derive Key & Decrypt with AAD Authentication
        val secretKey = deriveAesKey(passphrase, salt, iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(AEAD_GCM_TAG_SIZE_BITS, nonce)

        val decryptedPlaintextBytes = runCatching {
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
            cipher.updateAAD(headerBytes)
            cipher.doFinal(ciphertextBytes)
        }.getOrElse {
            return DomainResult.Failure(
                DomainError.TransactionFailed("Invalid backup passphrase or corrupted/tampered encrypted backup.")
            )
        }

        return DomainResult.Success(decryptedPlaintextBytes)
    }

    private fun deriveAesKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }
}
