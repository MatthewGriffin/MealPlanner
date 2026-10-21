package com.example.chatgpt_mealplanner.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keystore-backed typed preferences. The old preference file is never deleted during migration. */
internal interface SecureValueCipher {
    fun encrypt(plainText: String, associatedData: ByteArray, allowCreateKey: Boolean): String
    fun decrypt(envelope: String, associatedData: ByteArray): String
    fun keyExists(): Boolean
}

internal class AndroidKeystoreValueCipher(private val keyAlias: String) : SecureValueCipher {
    override fun encrypt(plainText: String, associatedData: ByteArray, allowCreateKey: Boolean): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey(allowCreateKey))
        cipher.updateAAD(associatedData)
        val ciphertext = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        return "v1:${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(ciphertext, Base64.NO_WRAP)}"
    }

    override fun decrypt(envelope: String, associatedData: ByteArray): String {
        val parts = envelope.split(':', limit = 3)
        if (parts.size != 3 || parts[0] != "v1") throw GeneralSecurityException("Unsupported or corrupt secure preference value")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getKey(false),
            GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP))
        )
        cipher.updateAAD(associatedData)
        return String(cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)), StandardCharsets.UTF_8)
    }

    override fun keyExists(): Boolean {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        return store.containsAlias(keyAlias)
    }

    private fun getKey(allowCreate: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        if (!allowCreate) throw GeneralSecurityException("Secure preference key '$keyAlias' is missing; refusing to replace it")
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}

internal class AndroidKeystoreSecurePreferences(
    context: Context,
    private val prefsName: String,
    private val legacyTypes: Map<String, Char>,
    private val cipher: SecureValueCipher = AndroidKeystoreValueCipher(keyAliasFor(prefsName)),
    private val legacyEncryptedReader: (() -> Map<String, String>)? = null
) {
    private val appContext = context.applicationContext
    private val backing = appContext.getSharedPreferences("${prefsName}_keystore_v2", Context.MODE_PRIVATE)

    @Synchronized fun getString(key: String, defaultValue: String? = null): String? =
        read(key)?.let { decode(it, 'S') } ?: defaultValue

    @Synchronized fun getInt(key: String, defaultValue: Int): Int =
        read(key)?.let { decode(it, 'I').toInt() } ?: defaultValue

    @Synchronized fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        read(key)?.let { decode(it, 'B').toBooleanStrict() } ?: defaultValue

    @Synchronized fun putString(key: String, value: String?): Boolean =
        writeValues(mapOf(key to value?.let { "S:$it" }))

    @Synchronized fun putInt(key: String, value: Int): Boolean =
        writeValues(mapOf(key to "I:$value"))

    @Synchronized fun putBoolean(key: String, value: Boolean): Boolean =
        writeValues(mapOf(key to "B:$value"))

    /** Commits a related set of values together; legacy types preserve int/boolean representation. */
    @Synchronized fun putValues(values: Map<String, String?>): Boolean =
        writeValues(values.mapValues { (key, value) -> value?.let { "${legacyTypes[key] ?: 'S'}:$it" } })

    @Synchronized fun remove(key: String): Boolean {
        ensureMigrated()
        return backing.edit().remove(key).commit()
    }

    @Synchronized fun clear(): Boolean {
        ensureMigrated()
        return backing.edit().clear().putBoolean(MIGRATION_MARKER, true).commit()
    }

    private fun read(key: String): String? {
        ensureMigrated()
        val encrypted = backing.getString(key, null) ?: return null
        return cipher.decrypt(encrypted, associatedData(key))
    }

    private fun writeValues(values: Map<String, String?>): Boolean {
        ensureMigrated()
        val editor = backing.edit()
        var canCreateKey = backing.all.keys.none { it != MIGRATION_MARKER }
        for ((key, value) in values) {
            if (value == null) {
                editor.remove(key)
            } else {
                editor.putString(key, cipher.encrypt(value, associatedData(key), canCreateKey))
                canCreateKey = false
            }
        }
        return editor.commit()
    }

    private fun ensureMigrated() {
        if (backing.getBoolean(MIGRATION_MARKER, false)) {
            if (backing.all.keys.any { it != MIGRATION_MARKER } && !cipher.keyExists()) {
                throw GeneralSecurityException("Secure preference key is missing for '$prefsName'")
            }
            return
        }

        val plainLegacy = appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val plainValues = linkedMapOf<String, String>()
        for ((key, type) in legacyTypes) {
            if (plainLegacy.contains(key)) {
                val value = when (type) {
                    'S' -> plainLegacy.getString(key, null)
                    'I' -> plainLegacy.getInt(key, 0).toString()
                    'B' -> plainLegacy.getBoolean(key, false).toString()
                    else -> throw IllegalArgumentException("Unsupported legacy preference type: $type")
                }
                if (value != null) plainValues[key] = "$type:$value"
            }
        }

        val legacyValues = when {
            plainValues.isNotEmpty() -> plainValues
            legacyEncryptedReader != null -> legacyEncryptedReader.invoke()
            legacyFileExists() -> readLegacyEncryptedPreferences()
            else -> emptyMap()
        }

        if (legacyValues.isNotEmpty()) {
            val editor = backing.edit()
            var canCreateKey = backing.all.keys.none { it != MIGRATION_MARKER }
            for ((key, value) in legacyValues) {
                editor.putString(key, cipher.encrypt(value, associatedData(key), canCreateKey))
                canCreateKey = false
            }
            if (!editor.commit()) throw GeneralSecurityException("Could not persist migrated secure preferences")
            for ((key, value) in legacyValues) {
                val encrypted = backing.getString(key, null) ?: throw GeneralSecurityException("Migrated value missing")
                if (cipher.decrypt(encrypted, associatedData(key)) != value) {
                    throw GeneralSecurityException("Secure preference migration verification failed")
                }
            }
        } else if (backing.all.keys.any { it != MIGRATION_MARKER } && !cipher.keyExists()) {
            throw GeneralSecurityException("Secure preference key is missing for '$prefsName'")
        }

        if (!backing.edit().putBoolean(MIGRATION_MARKER, true).commit()) {
            throw GeneralSecurityException("Could not record secure preference migration")
        }
    }

    @Suppress("DEPRECATION")
    private fun readLegacyEncryptedPreferences(): Map<String, String> {
        // Compatibility-only reader: this deprecated format is the only way to decrypt data written
        // by older app versions. New reads and writes exclusively use Android Keystore AES-GCM.
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val legacy = EncryptedSharedPreferences.create(
            appContext,
            prefsName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        val values = linkedMapOf<String, String>()
        for ((key, type) in legacyTypes) {
            if (legacy.contains(key)) {
                val value = when (type) {
                    'S' -> legacy.getString(key, null)
                    'I' -> legacy.getInt(key, 0).toString()
                    'B' -> legacy.getBoolean(key, false).toString()
                    else -> throw IllegalArgumentException("Unsupported legacy preference type: $type")
                }
                if (value != null) values[key] = "$type:$value"
            }
        }
        return values
    }

    private fun legacyFileExists(): Boolean =
        java.io.File(appContext.applicationInfo.dataDir, "shared_prefs/${prefsName}.xml").exists()

    private fun associatedData(key: String): ByteArray =
        "${prefsName}\u0000$key".toByteArray(StandardCharsets.UTF_8)

    private fun decode(value: String, expectedType: Char): String {
        val prefix = "$expectedType:"
        if (!value.startsWith(prefix)) throw GeneralSecurityException("Secure preference type mismatch for '$prefsName'")
        return value.substring(prefix.length)
    }

    companion object {
        private const val MIGRATION_MARKER = "__secure_preferences_migration_v2"
        private fun keyAliasFor(name: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(name.toByteArray(StandardCharsets.UTF_8))
            return "pantry_secure_prefs_" + digest.take(16).joinToString("") { "%02x".format(it) }
        }
    }
}
