package com.example.chatgpt_mealplanner.security

import com.example.chatgpt_mealplanner.core.security.AndroidKeyStoreManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

@RunWith(JUnit4::class)
class AndroidKeyStoreManagerTest {

    @Test
    fun ecdsaSignature_generationAndVerification_succeeds() {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(256)
        val keyPair = keyPairGenerator.generateKeyPair()

        val challengeData = "pantry_challenge_nonce_12345".toByteArray()

        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(challengeData)
        val signatureBytes = signer.sign()

        val signatureBase64 = Base64.getEncoder().encodeToString(signatureBytes)
        val publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)

        val manager = AndroidKeyStoreManager()
        val verified = manager.verifySignature(publicKeyBase64, challengeData, signatureBase64)

        assertTrue("EC P-256 SHA256withECDSA signature verification must succeed", verified)
    }

    @Test
    fun publicKeyEncoding_roundTripX509EncodedKeySpec_succeeds() {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(256)
        val originalKeyPair = keyPairGenerator.generateKeyPair()

        val encodedBase64 = Base64.getEncoder().encodeToString(originalKeyPair.public.encoded)
        val decodedBytes = Base64.getDecoder().decode(encodedBase64)

        val keyFactory = java.security.KeyFactory.getInstance("EC")
        val reconstructedKey = keyFactory.generatePublic(java.security.spec.X509EncodedKeySpec(decodedBytes))

        org.junit.Assert.assertEquals("Reconstructed public key must match original algorithm", originalKeyPair.public.algorithm, reconstructedKey.algorithm)
        org.junit.Assert.assertArrayEquals("Reconstructed public key bytes must match original encoded bytes", originalKeyPair.public.encoded, reconstructedKey.encoded)
    }
}
