package com.example.chatgpt_mealplanner.sync

import com.example.chatgpt_mealplanner.core.security.AndroidKeyStoreManager
import com.example.chatgpt_mealplanner.core.sync.server.SyncSessionManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class SyncSessionManagerLimitsTest {

    private val sessionManager = SyncSessionManager(AndroidKeyStoreManager(), FakeSyncPairedDeviceDao())

    @Test
    fun challengeFlood_staysWithinCapAndEvictsOldest() = runTest {
        val first = sessionManager.createChallenge("HH", "dev_0")

        repeat(SyncSessionManager.MAX_PENDING_CHALLENGES * 4) {
            sessionManager.createChallenge("HH", "unknown_device_$it")
        }

        assertEquals(SyncSessionManager.MAX_PENDING_CHALLENGES, sessionManager.pendingChallengeCount)
        assertNull(sessionManager.verifyChallengeAndCreateSession(first.challengeId, "dev_0", "c2ln"))
    }

    @Test
    fun challengeIsSingleUse_evenWhenFirstAttemptFails() = runTest {
        val challenge = sessionManager.createChallenge("HH", "dev_0")

        assertNull(sessionManager.verifyChallengeAndCreateSession(challenge.challengeId, "dev_0", "c2ln"))
        assertEquals(0, sessionManager.pendingChallengeCount)
        assertNull(sessionManager.verifyChallengeAndCreateSession(challenge.challengeId, "dev_0", "c2ln"))
    }
}
