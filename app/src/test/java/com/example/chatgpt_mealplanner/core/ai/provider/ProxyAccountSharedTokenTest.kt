package com.example.chatgpt_mealplanner.core.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyAccountSharedTokenTest {
    @Test
    fun aFollowerUsesTheHouseholdKeyInsteadOfItsOwnAccount() {
        val account = ProxyAccount(null)
        assertFalse(account.usingSharedToken())
        account.setSharedToken("household-key")
        assertTrue(account.usingSharedToken())
        assertEquals("household-key", account.token())
        assertTrue(account.isToken("household-key"))
    }

    @Test
    fun aKeyTheWorkerRefusesIsDroppedSoTheFollowerFallsBackToItsOwnAccount() {
        val account = ProxyAccount(null)
        account.setSharedToken("household-key")
        account.forget()
        assertFalse(account.usingSharedToken())
        assertFalse(account.isToken("household-key"))
    }

    @Test
    fun aFollowerCannotMakeKeysForOthers() {
        val account = ProxyAccount(null)
        account.setSharedToken("household-key")
        assertNull(account.createFollowerKey())
    }
}
