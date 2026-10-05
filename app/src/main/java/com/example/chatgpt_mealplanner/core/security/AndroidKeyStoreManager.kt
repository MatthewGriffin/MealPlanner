package com.example.chatgpt_mealplanner.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.inject.Inject
import javax.inject.Singleton

object Base64Utils {
    fun encodeToString(bytes: ByteArray): String {
        return try {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (_: Throwable) {
            java.util.Base64.getEncoder().encodeToString(bytes)
        }
    }

    fun decode(base64Str: String): ByteArray {
        return try {
            android.util.Base64.decode(base64Str, android.util.Base64.NO_WRAP)
        } catch (_: Throwable) {
            java.util.Base64.getDecoder().decode(base64Str)
        }
    }
}

@Singleton
open class AndroidKeyStoreManager @Inject constructor() {

    private val keyStoreType = "AndroidKeyStore"
    private val ecAlgorithm = "SHA256withECDSA"

    protected open fun getKeyStore(): KeyStore {
        return KeyStore.getInstance(keyStoreType).apply { load(null) }
    }

    open fun ensureKeyPair(alias: String): KeyPair {
        val ks = getKeyStore()
        if (ks.containsAlias(alias)) {
            val privateKey = ks.getKey(alias, null) as PrivateKey
            val cert = ks.getCertificate(alias)
            val publicKey = cert.publicKey
            return KeyPair(publicKey, privateKey)
        }

        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, keyStoreType)
        val parameterSpec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()

        kpg.initialize(parameterSpec)
        return kpg.generateKeyPair()
    }

    open fun getPublicKey(alias: String): PublicKey? {
        val ks = getKeyStore()
        return runCatching { ks.getCertificate(alias)?.publicKey }.getOrNull()
    }

    open fun getPublicKeyBase64(alias: String): String? {
        val key = getPublicKey(alias) ?: return null
        return Base64Utils.encodeToString(key.encoded)
    }

    open fun signChallenge(alias: String, challengeData: ByteArray): String {
        val keyPair = ensureKeyPair(alias)
        val signature = Signature.getInstance(ecAlgorithm)
        signature.initSign(keyPair.private)
        signature.update(challengeData)
        val signatureBytes = signature.sign()
        return Base64Utils.encodeToString(signatureBytes)
    }

    open fun verifySignature(publicKeyBase64: String, challengeData: ByteArray, signatureBase64: String): Boolean {
        return try {
            val keyBytes = Base64Utils.decode(publicKeyBase64)
            val keyFactory = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC)
            val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(keyBytes))

            val signature = Signature.getInstance(ecAlgorithm)
            signature.initVerify(publicKey)
            signature.update(challengeData)
            val sigBytes = Base64Utils.decode(signatureBase64)
            signature.verify(sigBytes)
        } catch (_: Exception) {
            false
        }
    }

    open fun isValidP256PublicKey(publicKeyBase64: String?): Boolean {
        if (publicKeyBase64.isNullOrBlank()) return false
        return try {
            val keyBytes = Base64Utils.decode(publicKeyBase64)
            val keyFactory = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC)
            val pubKey = keyFactory.generatePublic(X509EncodedKeySpec(keyBytes)) as? java.security.interfaces.ECPublicKey
                ?: return false

            if (pubKey.algorithm != KeyProperties.KEY_ALGORITHM_EC) return false

            val params = pubKey.params ?: return false
            val fieldSize = params.curve.field.fieldSize
            val orderBitLength = params.order.bitLength()

            fieldSize == 256 && orderBitLength == 256
        } catch (_: Exception) {
            false
        }
    }
}
