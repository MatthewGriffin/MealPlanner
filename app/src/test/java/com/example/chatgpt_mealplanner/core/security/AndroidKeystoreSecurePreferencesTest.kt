package com.example.chatgpt_mealplanner.core.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.GeneralSecurityException

@RunWith(RobolectricTestRunner::class)
class AndroidKeystoreSecurePreferencesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun migratesLegacyPlaintextValuesAndKeepsOriginals() {
        val name = "legacy_plain_test"
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            .putString("secret", "token-value")
            .putInt("port", 9443)
            .commit()
        val prefs = AndroidKeystoreSecurePreferences(
            context, name, mapOf("secret" to 'S', "port" to 'I'), FakeCipher()
        )

        assertEquals("token-value", prefs.getString("secret"))
        assertEquals(9443, prefs.getInt("port", 0))
        assertEquals("token-value", context.getSharedPreferences(name, Context.MODE_PRIVATE).getString("secret", null))
        assertTrue(context.getSharedPreferences("${name}_keystore_v2", Context.MODE_PRIVATE).contains("secret"))
    }

    @Test
    fun migratesCredentialPairingIdentityAndTlsPasswordWithoutDeletingLegacyData() {
        val name = "legacy_encrypted_test"
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            .putString("legacy_file_sentinel", "keep-me")
            .commit()
        val prefs = AndroidKeystoreSecurePreferences(
            context,
            name,
            mapOf(
                "KEY_GEMINI_API_KEY" to 'S',
                "KEY_HOST_IP" to 'S',
                "KEY_PORT" to 'I',
                "KEY_FOLLOWER_DEVICE_ID" to 'S',
                "KEY_TLS_P12_PASSWORD" to 'S'
            ),
            FakeCipher(),
            legacyEncryptedReader = {
                mapOf(
                    "KEY_GEMINI_API_KEY" to "S:old-api-key",
                    "KEY_HOST_IP" to "S:192.0.2.10",
                    "KEY_PORT" to "I:8443",
                    "KEY_FOLLOWER_DEVICE_ID" to "S:stable-device-id",
                    "KEY_TLS_P12_PASSWORD" to "S:existing-p12-password"
                )
            }
        )

        assertEquals("old-api-key", prefs.getString("KEY_GEMINI_API_KEY"))
        assertEquals("192.0.2.10", prefs.getString("KEY_HOST_IP"))
        assertEquals(8443, prefs.getInt("KEY_PORT", 0))
        assertEquals("stable-device-id", prefs.getString("KEY_FOLLOWER_DEVICE_ID"))
        assertEquals("existing-p12-password", prefs.getString("KEY_TLS_P12_PASSWORD"))
        assertEquals(
            "keep-me",
            context.getSharedPreferences(name, Context.MODE_PRIVATE).getString("legacy_file_sentinel", null)
        )
    }

    @Test
    fun rejectsCiphertextWhenKeyIsMissingInsteadOfCreatingReplacement() {
        val name = "lost_key_test"
        context.getSharedPreferences("${name}_keystore_v2", Context.MODE_PRIVATE).edit()
            .putString("secret", "v1:encrypted")
            .putBoolean("__secure_preferences_migration_v2", true)
            .commit()
        val prefs = AndroidKeystoreSecurePreferences(
            context, name, mapOf("secret" to 'S'), FakeCipher(keyAvailable = false)
        )

        var failedClosed = false
        try {
            prefs.getString("secret")
        } catch (_: GeneralSecurityException) {
            failedClosed = true
        }
        assertTrue("missing key must not be replaced", failedClosed)
    }

    @Test
    fun recoversMissingV2KeyFromRetainedLegacyEncryptedPreferences() {
        val name = "recover_lost_key_test"
        val backing = context.getSharedPreferences("${name}_keystore_v2", Context.MODE_PRIVATE)
        backing.edit()
            .putString("secret", "v1:unrecoverable-old-ciphertext")
            .putBoolean("__secure_preferences_migration_v2", true)
            .commit()

        val cipher = RecoveringCipher()
        val prefs = AndroidKeystoreSecurePreferences(
            context,
            name,
            mapOf("secret" to 'S'),
            cipher,
            legacyEncryptedReader = { mapOf("secret" to "S:original-api-key") }
        )

        assertEquals("original-api-key", prefs.getString("secret"))
        assertTrue(cipher.keyExists())
        assertEquals("S:original-api-key", cipher.decrypt(backing.getString("secret", null)!!, byteArrayOf()))
    }

    @Test
    fun clearRemovesSecureValuesButRetainsMigrationMarker() {
        val name = "clear_test"
        val prefs = AndroidKeystoreSecurePreferences(context, name, emptyMap(), FakeCipher())
        assertTrue(prefs.putString("secret", "value"))
        assertTrue(prefs.clear())
        assertEquals(null, prefs.getString("secret"))
        assertTrue(context.getSharedPreferences("${name}_keystore_v2", Context.MODE_PRIVATE)
            .getBoolean("__secure_preferences_migration_v2", false))
    }

    private class RecoveringCipher : SecureValueCipher {
        private var available = false

        override fun encrypt(plainText: String, associatedData: ByteArray, allowCreateKey: Boolean): String {
            if (!available && !allowCreateKey) throw GeneralSecurityException("key missing")
            if (allowCreateKey) available = true
            return "v1:" + plainText.reversed()
        }

        override fun decrypt(envelope: String, associatedData: ByteArray): String {
            if (!available) throw GeneralSecurityException("key missing")
            if (!envelope.startsWith("v1:")) throw GeneralSecurityException("corrupt")
            return envelope.removePrefix("v1:").reversed()
        }

        override fun keyExists(): Boolean = available
    }

    private class FakeCipher(private val keyAvailable: Boolean = true) : SecureValueCipher {
        override fun encrypt(plainText: String, associatedData: ByteArray, allowCreateKey: Boolean): String {
            if (!keyAvailable && !allowCreateKey) throw GeneralSecurityException("key missing")
            return "v1:" + plainText.reversed()
        }
        override fun decrypt(envelope: String, associatedData: ByteArray): String {
            if (!keyAvailable) throw GeneralSecurityException("key missing")
            if (!envelope.startsWith("v1:")) throw GeneralSecurityException("corrupt")
            return envelope.removePrefix("v1:").reversed()
        }
        override fun keyExists(): Boolean = keyAvailable
    }
}
