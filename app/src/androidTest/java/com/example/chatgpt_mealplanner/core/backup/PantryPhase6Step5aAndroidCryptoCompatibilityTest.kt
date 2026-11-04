package com.example.chatgpt_mealplanner.core.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatgpt_mealplanner.core.model.DomainResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PantryPhase6Step5aAndroidCryptoCompatibilityTest {

    private lateinit var codec: MainBackupEncryptionCodec

    @Before
    fun setUp() {
        codec = MainBackupEncryptionCodec()
    }

    private val sampleArchive = ByteArray(4096) { (it % 251).toByte() }

    @Test
    fun androidNativeJca_encryptsAndDecryptsFullBackupLosslessly() {
        val passphrase = "AndroidMasterPassword#2026!".toCharArray()

        val encryptedBytes = codec.encodeFullBackup(sampleArchive, passphrase)
        assertTrue("Android encrypted bytes MUST be non-empty", encryptedBytes.isNotEmpty())

        val result = codec.decodeFullBackup(encryptedBytes, passphrase)
        assertTrue("Android native JCA decryption MUST succeed", result is DomainResult.Success)
        assertArrayEquals(sampleArchive, (result as DomainResult.Success).value)
    }

    @Test
    fun androidNativeJca_unicodePassphrase_roundTripsLosslesslyOnAndroidOS() {
        val unicodePassphrase = "AndroidPassphraseWithUnicodeEmoji🔒&AccentsÁÉÍÓÚ!".toCharArray()

        val encryptedBytes = codec.encodeFullBackup(sampleArchive, unicodePassphrase)
        val result = codec.decodeFullBackup(encryptedBytes, unicodePassphrase)

        assertTrue("Android native JCA Unicode passphrase MUST decrypt successfully", result is DomainResult.Success)
        assertArrayEquals(sampleArchive, (result as DomainResult.Success).value)
    }

    @Test
    fun androidNativeJca_wrongPassphrase_rejectsOnAndroidOS() {
        val passphrase = "CorrectPassword123!".toCharArray()
        val wrongPassphrase = "WrongPassword456!".toCharArray()

        val encryptedBytes = codec.encodeFullBackup(sampleArchive, passphrase)
        val result = codec.decodeFullBackup(encryptedBytes, wrongPassphrase)

        assertTrue("Android native JCA wrong passphrase MUST fail decryption", result is DomainResult.Failure)
    }
}
