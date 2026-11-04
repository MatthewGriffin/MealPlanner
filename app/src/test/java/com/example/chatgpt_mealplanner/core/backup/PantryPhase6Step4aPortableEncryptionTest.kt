package com.example.chatgpt_mealplanner.core.backup

import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.DomainResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class PantryPhase6Step4aPortableEncryptionTest {

    private lateinit var codec: MainBackupEncryptionCodec

    @Before
    fun setUp() {
        codec = MainBackupEncryptionCodec()
    }

    private val sampleArchive = ByteArray(4096) { (it % 251).toByte() }

    @Test
    fun encodeAndDecode_validPassphrase_encryptsAndDecryptsLosslessly() = runTest {
        val passphrase = "PantryMasterPassword#2026!".toCharArray()

        val encryptedBytes = codec.encodeFullBackup(sampleArchive, passphrase)
        assertTrue("Encrypted bytes length MUST exceed header size", encryptedBytes.size > ENCRYPTED_HEADER_SIZE_BYTES)

        val result = codec.decodeFullBackup(encryptedBytes, passphrase)
        assertTrue("Decryption with valid passphrase MUST succeed", result is DomainResult.Success)
        assertArrayEquals(sampleArchive, (result as DomainResult.Success).value)
    }

    @Test
    fun encodeFullBackup_sameArchive_producesDifferentSaltAndNonce() = runTest {
        val passphrase = "SecretPassword123!".toCharArray()

        val bytes1 = codec.encodeFullBackup(sampleArchive, passphrase)
        val bytes2 = codec.encodeFullBackup(sampleArchive, passphrase)

        // Random salt and nonce MUST produce different byte outputs
        assertFalse("Two encryptions MUST produce different ciphertexts due to random salt/nonce", bytes1.contentEquals(bytes2))

        // Both MUST decrypt successfully to the same archive
        val res1 = codec.decodeFullBackup(bytes1, passphrase)
        val res2 = codec.decodeFullBackup(bytes2, passphrase)

        assertTrue(res1 is DomainResult.Success)
        assertTrue(res2 is DomainResult.Success)
        assertArrayEquals((res1 as DomainResult.Success).value, (res2 as DomainResult.Success).value)
    }

    @Test
    fun decodeFullBackup_wrongPassphrase_rejectsWithPassphraseError() = runTest {
        val passphrase = "CorrectPassword123!".toCharArray()
        val wrongPassphrase = "WrongPassword456!".toCharArray()

        val encryptedBytes = codec.encodeFullBackup(sampleArchive, passphrase)

        val result = codec.decodeFullBackup(encryptedBytes, wrongPassphrase)
        assertTrue("Decryption with wrong passphrase MUST fail", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention invalid passphrase", errorMsg.contains("passphrase"))
    }

    @Test
    fun decodeFullBackup_tamperedHeaderMagic_rejectsWithMagicError() = runTest {
        val passphrase = "Password123!".toCharArray()
        val bytes = codec.encodeFullBackup(sampleArchive, passphrase)

        // Mutate first magic byte 'P' -> 'X'
        val mutatedBytes = bytes.clone()
        mutatedBytes[0] = 'X'.code.toByte()

        val result = codec.decodeFullBackup(mutatedBytes, passphrase)
        assertTrue("Mutated magic byte MUST fail validation", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention magic identifier", errorMsg.contains("magic"))
    }

    @Test
    fun decodeFullBackup_tamperedHeaderWireVersion_rejectsWithVersionError() = runTest {
        val passphrase = "Password123!".toCharArray()
        val bytes = codec.encodeFullBackup(sampleArchive, passphrase)

        // Mutate wire version at offset 12 (Int 1 -> 99)
        val mutatedBytes = bytes.clone()
        mutatedBytes[12] = 99.toByte()

        val result = codec.decodeFullBackup(mutatedBytes, passphrase)
        assertTrue("Mutated wire version MUST fail validation", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention wire version", errorMsg.contains("wire version"))
    }

    @Test
    fun decodeFullBackup_tamperedCiphertext_rejectsWithTagAuthenticationError() = runTest {
        val passphrase = "Password123!".toCharArray()
        val bytes = codec.encodeFullBackup(sampleArchive, passphrase)

        // Mutate 1 byte in ciphertext portion
        val mutatedBytes = bytes.clone()
        mutatedBytes[ENCRYPTED_HEADER_SIZE_BYTES + 5] = (mutatedBytes[ENCRYPTED_HEADER_SIZE_BYTES + 5] + 1).toByte()

        val result = codec.decodeFullBackup(mutatedBytes, passphrase)
        assertTrue("Mutated ciphertext MUST fail GCM tag authentication", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention invalid passphrase or tampered backup", errorMsg.contains("passphrase") || errorMsg.contains("tampered"))
    }

    @Test
    fun decodeFullBackup_appendedTrailingBytes_rejectsWithLengthMismatch() = runTest {
        val passphrase = "Password123!".toCharArray()
        val bytes = codec.encodeFullBackup(sampleArchive, passphrase)

        val trailingBytes = bytes + byteArrayOf(0x01, 0x02, 0x03, 0x04)

        val result = codec.decodeFullBackup(trailingBytes, passphrase)
        assertTrue("Appended trailing bytes MUST fail length check", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention length mismatch", errorMsg.contains("length mismatch"))
    }

    @Test
    fun decodeFullBackup_unicodePassphrase_roundTripsLosslessly() = runTest {
        val unicodePassphrase = "PassphraseWithUnicodeEmoji🔒&AccentsÁÉÍÓÚ!".toCharArray()

        val bytes = codec.encodeFullBackup(sampleArchive, unicodePassphrase)
        val result = codec.decodeFullBackup(bytes, unicodePassphrase)

        assertTrue("Unicode passphrase MUST decrypt successfully", result is DomainResult.Success)
        assertArrayEquals(sampleArchive, (result as DomainResult.Success).value)
    }

    @Test
    fun decodeFullBackup_excessiveIterations_rejectsIterationBounds() = runTest {
        val passphrase = "Password123!".toCharArray()
        val bytes = codec.encodeFullBackup(sampleArchive, passphrase, iterations = DEFAULT_KDF_ITERATIONS)

        // Mutate iterations field in header (offset 61) to 5,000,000 > 1,000,000 max
        val mutatedBytes = bytes.clone()
        val buf = java.nio.ByteBuffer.wrap(mutatedBytes).apply {
            order(java.nio.ByteOrder.BIG_ENDIAN)
            position(61)
            putInt(5_000_000)
        }

        val result = codec.decodeFullBackup(buf.array(), passphrase)
        assertTrue("Excessive iterations MUST fail validation before KDF work", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention iterations outside permitted range", errorMsg.contains("iterations"))
    }

    @Test
    fun maxEncryptedContainerSize_constantCalculation_matchesFullPlaintextAndHeaderOverhead() = runTest {
        val expectedMaxEncryptedSize = MAX_CONTAINER_BODY_SIZE_BYTES + ENCRYPTED_HEADER_SIZE_BYTES + AEAD_GCM_TAG_SIZE_BYTES
        assertEquals("MAX_ENCRYPTED_CONTAINER_SIZE_BYTES MUST account for the header and tag", expectedMaxEncryptedSize, MAX_ENCRYPTED_CONTAINER_SIZE_BYTES)
        assertEquals(52428885, MAX_ENCRYPTED_CONTAINER_SIZE_BYTES)
    }

    @Test
    fun decodeFullBackup_oneByteOverMaxEncryptedBoundary_rejectsWithSizeError() = runTest {
        val passphrase = "Password123!".toCharArray()
        val oversizedBytes = ByteArray(MAX_ENCRYPTED_CONTAINER_SIZE_BYTES + 1)

        val result = codec.decodeFullBackup(oversizedBytes, passphrase)
        assertTrue("Input 1 byte over MAX_ENCRYPTED_CONTAINER_SIZE_BYTES MUST fail validation", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention size limit", errorMsg.contains("size limit"))
    }
}
