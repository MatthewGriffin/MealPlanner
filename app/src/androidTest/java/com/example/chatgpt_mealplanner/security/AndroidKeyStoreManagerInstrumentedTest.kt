package com.example.chatgpt_mealplanner.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatgpt_mealplanner.core.security.AndroidKeyStoreManager
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeyStoreManagerInstrumentedTest {

    @Test
    fun androidKeyStore_ensureKeyPairAndSigningOnDevice_succeeds() {
        val manager = AndroidKeyStoreManager()
        val alias = "pantry_device_identity_test_alias"

        val keyPair = manager.ensureKeyPair(alias)
        assertNotNull(keyPair.public)
        assertNotNull(keyPair.private)

        val pubKeyBase64 = manager.getPublicKeyBase64(alias)
        assertNotNull(pubKeyBase64)

        val challengeData = "instrumented_challenge_data_12345".toByteArray()
        val signatureBase64 = manager.signChallenge(alias, challengeData)
        assertNotNull(signatureBase64)

        val verified = manager.verifySignature(pubKeyBase64!!, challengeData, signatureBase64)
        assertTrue("Android KeyStore signature verification must succeed on real Android runtime", verified)
    }
}
