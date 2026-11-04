package com.example.chatgpt_mealplanner.sync

import com.example.chatgpt_mealplanner.core.security.AndroidKeyStoreManager
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class PantrySyncClientPinningTest {

    private val client = PantrySyncTestClient(AndroidKeyStoreManager())
    private val validFingerprint = "SHA256:" + List(32) { "AB" }.joinToString(":")

    @Test
    fun missingOrMalformedPin_isRejected() {
        listOf(null, "", "   ", "SHA256:AB", "not-a-fingerprint").forEach { pin ->
            assertThrows(IllegalArgumentException::class.java) { client.createPinnedOkHttpClient(pin) }
        }
    }

    @Test
    fun validPin_isAccepted() {
        assertNotNull(client.createPinnedOkHttpClient(validFingerprint))
    }
}
