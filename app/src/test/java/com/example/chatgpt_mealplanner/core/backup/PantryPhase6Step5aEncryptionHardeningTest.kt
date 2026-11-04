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

@RunWith(JUnit4::class)
class PantryPhase6Step5aEncryptionHardeningTest {

    private lateinit var codec: MainBackupEncryptionCodec

    @Before
    fun setUp() {
        codec = MainBackupEncryptionCodec()
    }

    private val sampleArchive = ByteArray(4096) { (it % 251).toByte() }

    @Test
    fun decodeFullBackup_tamperedTag_rejectsTagAuthenticationError() = runTest {
        val passphrase = "Password123!".toCharArray()
        val bytes = codec.encodeFullBackup(sampleArchive, passphrase)

        // Mutate the last byte of the 16-byte GCM authentication tag
        val mutatedBytes = bytes.clone()
        mutatedBytes[mutatedBytes.size - 1] = (mutatedBytes[mutatedBytes.size - 1] + 1).toByte()

        val result = codec.decodeFullBackup(mutatedBytes, passphrase)
        assertTrue("Mutated GCM tag MUST fail tag authentication", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention invalid passphrase or tampered backup", errorMsg.contains("passphrase") || errorMsg.contains("tampered"))
    }

    @Test
    fun decodeFullBackup_tamperedSalt_rejectsTagAuthenticationError() = runTest {
        val passphrase = "Password123!".toCharArray()
        val bytes = codec.encodeFullBackup(sampleArchive, passphrase)

        // Mutate salt at offset 17
        val mutatedBytes = bytes.clone()
        mutatedBytes[17] = (mutatedBytes[17] + 1).toByte()

        val result = codec.decodeFullBackup(mutatedBytes, passphrase)
        assertTrue("Mutated salt MUST fail GCM tag authentication", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention invalid passphrase or tampered backup", errorMsg.contains("passphrase") || errorMsg.contains("tampered"))
    }

    @Test
    fun decodeFullBackup_tamperedNonce_rejectsTagAuthenticationError() = runTest {
        val passphrase = "Password123!".toCharArray()
        val bytes = codec.encodeFullBackup(sampleArchive, passphrase)

        // Mutate nonce at offset 49
        val mutatedBytes = bytes.clone()
        mutatedBytes[49] = (mutatedBytes[49] + 1).toByte()

        val result = codec.decodeFullBackup(mutatedBytes, passphrase)
        assertTrue("Mutated nonce MUST fail GCM tag authentication", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention invalid passphrase or tampered backup", errorMsg.contains("passphrase") || errorMsg.contains("tampered"))
    }

    @Test
    fun decodeFullBackup_tamperedIterationsWithinBounds_rejectsTagAuthenticationError() = runTest {
        val passphrase = "Password123!".toCharArray()
        val bytes = codec.encodeFullBackup(sampleArchive, passphrase, iterations = 250_000)

        // Mutate iterations field (offset 61) to another valid bound value 200,000
        val mutatedBytes = bytes.clone()
        ByteBuffer.wrap(mutatedBytes).apply {
            order(ByteOrder.BIG_ENDIAN)
            position(61)
            putInt(200_000)
        }

        val result = codec.decodeFullBackup(mutatedBytes, passphrase)
        assertTrue("Mutated iterations MUST fail GCM tag authentication", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention invalid passphrase or tampered backup", errorMsg.contains("passphrase") || errorMsg.contains("tampered"))
    }

    @Test
    fun decodeFullBackup_truncatedHeader_rejectsTruncatedHeaderError() = runTest {
        val truncatedHeader = ByteArray(30) // 30 bytes < 69 bytes header

        val result = codec.decodeFullBackup(truncatedHeader, "Password123!".toCharArray())
        assertTrue("Truncated header MUST fail validation", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention truncated header", errorMsg.contains("Truncated"))
    }

    @Test
    fun decodeFullBackup_truncatedCiphertext_rejectsLengthMismatchError() = runTest {
        val passphrase = "Password123!".toCharArray()
        val fullBytes = codec.encodeFullBackup(sampleArchive, passphrase)

        // Truncate 20 bytes from ciphertext
        val truncatedBytes = fullBytes.copyOf(fullBytes.size - 20)

        val result = codec.decodeFullBackup(truncatedBytes, passphrase)
        assertTrue("Truncated ciphertext MUST fail length check", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention length mismatch", errorMsg.contains("length mismatch"))
    }

    @Test
    fun decodeFullBackup_unsupportedKdfId_rejectsKdfIdError() = runTest {
        val passphrase = "Password123!".toCharArray()
        val bytes = codec.encodeFullBackup(sampleArchive, passphrase)

        // Mutate KDF ID at offset 13 (Short 1 -> Short 99)
        val mutatedBytes = bytes.clone()
        ByteBuffer.wrap(mutatedBytes).apply {
            order(ByteOrder.BIG_ENDIAN)
            position(13)
            putShort(99.toShort())
        }

        val result = codec.decodeFullBackup(mutatedBytes, passphrase)
        assertTrue("Unsupported KDF ID MUST fail validation before key derivation", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention unsupported KDF algorithm ID", errorMsg.contains("KDF algorithm ID"))
    }

    @Test
    fun decodeFullBackup_unsupportedAeadId_rejectsAeadIdError() = runTest {
        val passphrase = "Password123!".toCharArray()
        val bytes = codec.encodeFullBackup(sampleArchive, passphrase)

        // Mutate AEAD ID at offset 15 (Short 1 -> Short 99)
        val mutatedBytes = bytes.clone()
        ByteBuffer.wrap(mutatedBytes).apply {
            order(ByteOrder.BIG_ENDIAN)
            position(15)
            putShort(99.toShort())
        }

        val result = codec.decodeFullBackup(mutatedBytes, passphrase)
        assertTrue("Unsupported AEAD ID MUST fail validation before decryption", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention unsupported AEAD algorithm ID", errorMsg.contains("AEAD algorithm ID"))
    }

    @Test
    fun decodeFullBackup_belowMinIterations_rejectsIterationBoundsError() = runTest {
        val passphrase = "Password123!".toCharArray()
        val bytes = codec.encodeFullBackup(sampleArchive, passphrase)

        // Mutate iterations at offset 61 to 1,000 < 10,000 min
        val mutatedBytes = bytes.clone()
        ByteBuffer.wrap(mutatedBytes).apply {
            order(ByteOrder.BIG_ENDIAN)
            position(61)
            putInt(1_000)
        }

        val result = codec.decodeFullBackup(mutatedBytes, passphrase)
        assertTrue("Iterations below minimum MUST fail validation", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention iterations outside permitted range", errorMsg.contains("iterations"))
    }

    @Test
    fun decodeFullBackup_unencryptedArchiveSupplied_rejectsMagicErrorNoFallback() = runTest {
        val result = codec.decodeFullBackup(sampleArchive, "Password123!".toCharArray())
        assertTrue("Unencrypted archive supplied to the decoder MUST fail magic check", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention invalid magic identifier", errorMsg.contains("magic"))
    }

}
