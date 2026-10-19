package com.example.chatgpt_mealplanner.sync

import com.example.chatgpt_mealplanner.core.database.dao.HouseholdIdentityDao
import com.example.chatgpt_mealplanner.core.database.entity.HouseholdIdentityEntity
import com.example.chatgpt_mealplanner.core.sync.AppRole
import com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock

class AppRoleResolverTest {

    private class TestPairingStore(private val paired: Boolean) : FollowerPairingStore(null) {
        override fun isPaired(): Boolean = paired
    }

    @Test
    fun selfReferencingPairingDoesNotDemoteMain() = runTest {
        val identity = HouseholdIdentityEntity("home", "Main", "main-device", 1L)
        val dao: HouseholdIdentityDao = mock {
            onBlocking { getHouseholdIdentity() } doAnswer { identity }
            on { getHouseholdIdentitySync() } doAnswer { identity }
        }
        val store = object : FollowerPairingStore(null) {
            override fun isPaired() = true
            override fun getPairingConfig() = com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
                "localhost", pinnedCertFingerprint = "pin", deviceId = "main-device", householdId = "home", mainDeviceId = "main-device")
        }
        val resolver = AppRoleResolver(store, dao)
        assertEquals(AppRole.MAIN, resolver.getAppRoleAsync())
        assertEquals(AppRole.MAIN, resolver.getAppRole())
    }

    @Test
    fun testIsMainAsync_existingMainIdentityWithPairingPresent_returnsTrueAndMainRole() = runTest {
        val identityDao: HouseholdIdentityDao = mock {
            onBlocking { getHouseholdIdentity() } doAnswer {
                HouseholdIdentityEntity("house-1", "Main Household", "main-dev-1", 1000L)
            }
        }
        val pairingStore = TestPairingStore(paired = true)
        val resolver = AppRoleResolver(pairingStore, identityDao)

        val role = resolver.getAppRoleAsync()
        val isMain = resolver.isMainAsync()
        val isFollower = resolver.isFollowerAsync()

        assertEquals(AppRole.MAIN, role)
        assertTrue(isMain)
        assertFalse(isFollower)
    }

    @Test
    fun testIsMainAsync_existingMainIdentityWithoutPairing_returnsTrueAndMainRole() = runTest {
        val identityDao: HouseholdIdentityDao = mock {
            onBlocking { getHouseholdIdentity() } doAnswer {
                HouseholdIdentityEntity("house-1", "Main Household", "main-dev-1", 1000L)
            }
        }
        val pairingStore = TestPairingStore(paired = false)
        val resolver = AppRoleResolver(pairingStore, identityDao)

        val role = resolver.getAppRoleAsync()
        val isMain = resolver.isMainAsync()

        assertEquals(AppRole.MAIN, role)
        assertTrue(isMain)
    }

    @Test
    fun testIsMainAsync_noIdentityWithPairing_returnsFalseAndFollowerRole() = runTest {
        val identityDao: HouseholdIdentityDao = mock {
            onBlocking { getHouseholdIdentity() } doAnswer { null }
        }
        val pairingStore = TestPairingStore(paired = true)
        val resolver = AppRoleResolver(pairingStore, identityDao)

        val role = resolver.getAppRoleAsync()
        val isMain = resolver.isMainAsync()
        val isFollower = resolver.isFollowerAsync()

        assertEquals(AppRole.FOLLOWER, role)
        assertFalse(isMain)
        assertTrue(isFollower)
    }

    @Test
    fun testIsMainAsync_noIdentityWithoutPairing_returnsTrueAndMainRole() = runTest {
        val identityDao: HouseholdIdentityDao = mock {
            onBlocking { getHouseholdIdentity() } doAnswer { null }
        }
        val pairingStore = TestPairingStore(paired = false)
        val resolver = AppRoleResolver(pairingStore, identityDao)

        val role = resolver.getAppRoleAsync()
        val isMain = resolver.isMainAsync()

        assertEquals(AppRole.MAIN, role)
        assertTrue(isMain)
    }

    @Test
    fun testIsMainAsync_daoLookupThrowsException_propagatesException() = runTest {
        val identityDao: HouseholdIdentityDao = mock {
            onBlocking { getHouseholdIdentity() } doThrow IllegalStateException("Database disk I/O error")
        }
        val pairingStore = TestPairingStore(paired = true)
        val resolver = AppRoleResolver(pairingStore, identityDao)

        try {
            resolver.getAppRoleAsync()
            fail("Expected IllegalStateException to propagate from DAO lookup")
        } catch (e: IllegalStateException) {
            assertEquals("Database disk I/O error", e.message)
        }
    }

    @Test
    fun testIsMainAsync_coroutineCancellation_propagatesCancellation() = runTest {
        val identityDao: HouseholdIdentityDao = mock {
            onBlocking { getHouseholdIdentity() } doThrow CancellationException("Test coroutine cancellation")
        }
        val pairingStore = TestPairingStore(paired = false)
        val resolver = AppRoleResolver(pairingStore, identityDao)

        try {
            resolver.isMainAsync()
            fail("Expected CancellationException to propagate")
        } catch (e: CancellationException) {
            assertEquals("Test coroutine cancellation", e.message)
        }
    }
}
