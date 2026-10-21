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
    fun migratesLegacyEncryptedValuesAndPreservesLegacyFile() {
        val name = "legacy_encrypted_test"
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            .putString("legacy_file_sentinel", "keep-me")
            .commit()
        val prefs = AndroidKeystoreSecurePreferences(
            context,
            name,
            mapOf("secret" to 'S', "port" to 'I'),
            FakeCipher(),
            legacyEncryptedReader = { mapOf("secret" to "S:old-secret", "port" to "I:8443") }
        )

        assertEquals("old-secret", prefs.getString("secret"))
        assertEquals(8443, prefs.getInt("port", 0))
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
    fun clearRemovesSecureValuesButRetainsMigrationMarker() {
        val name = "clear_test"
        val prefs = AndroidKeystoreSecurePreferences(context, name, emptyMap(), FakeCipher())
        assertTrue(prefs.putString("secret", "value"))
        assertTrue(prefs.clear())
        assertEquals(null, prefs.getString("secret"))
        assertTrue(context.getSharedPreferences("${name}_keystore_v2", Context.MODE_PRIVATE)
            .getBoolean("__secure_preferences_migration_v2", false))
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
