package com.example.chatgpt_mealplanner.core.quota

import com.example.chatgpt_mealplanner.core.security.FakeCredentialStore
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageQuotaTest {
    private class TestQuota(
        var day: LocalDate = LocalDate.of(2026, 11, 16),
        private val ownKey: Boolean = false,
        private val bypass: Boolean = false
    ) : UsageQuota(FakeCredentialStore(null), null) {
        override fun today(): LocalDate = day
        override fun limitsBypassed() = bypass
        override fun ownKeyMode() = ownKey
    }

    @Test
    fun beforeTheServerHasSpokenEverythingIsAllowedAndFull() {
        val q = TestQuota()
        q.refresh()
        assertTrue(q.canUse())
        assertEquals(100, q.snapshot.value.percentLeft)
        assertEquals(UsageTier.FREE, q.snapshot.value.tier)
    }

    @Test
    fun theServersNumbersAreShown() {
        val q = TestQuota()
        q.applyServer("pro", 42, "2026-11-17")
        val s = q.snapshot.value
        assertEquals(UsageTier.PRO, s.tier)
        assertEquals(42, s.percentLeft)
        assertEquals(LocalDate.of(2026, 11, 17), s.resetsOn)
    }

    @Test
    fun usedUpAllowanceBlocksUntilTheRefreshDate() {
        val q = TestQuota()
        q.applyServer("free", 0, "2026-11-23")
        assertFalse(q.canUse())
        q.day = LocalDate.of(2026, 11, 22)
        assertFalse(q.canUse())
        q.day = LocalDate.of(2026, 11, 23)
        assertTrue(q.canUse()) // the Worker has the final say, so try again
    }

    @Test
    fun partialAllowanceNeverBlocks() {
        val q = TestQuota()
        q.applyServer("free", 1, "2026-11-23")
        assertTrue(q.canUse())
    }

    @Test
    fun ownKeyMeansNoLimitsAndNothingToShow() {
        val q = TestQuota(ownKey = true)
        q.applyServer("free", 0, "2026-11-23")
        assertTrue(q.canUse())
        assertTrue(q.snapshot.value.unlimited)
    }

    @Test
    fun debugBuildsShowAnExampleAndNeverBlock() {
        val q = TestQuota(bypass = true)
        q.applyServer("free", 0, null)
        assertTrue(q.canUse())
        val s = q.snapshot.value
        assertTrue(s.example); assertFalse(s.unlimited)
        assertEquals(70, s.percentLeft)
        q.setDebugTier(UsageTier.MAX)
        assertEquals(UsageTier.MAX, q.snapshot.value.tier)
    }

    @Test
    fun incompleteServerNumbersAreIgnored() {
        val q = TestQuota()
        q.applyServer(null, 5, null)
        q.applyServer("free", null, null)
        assertEquals(100, q.snapshot.value.percentLeft)
        assertNull(q.snapshot.value.resetsOn)
    }

    @Test
    fun limitMessageNamesTheRefreshDateAndSaysManualStillWorks() {
        val q = TestQuota()
        q.applyServer("free", 0, "2026-11-23")
        val msg = q.limitMessage()
        assertTrue(msg.contains(UsageQuota.formatDate(LocalDate.of(2026, 11, 23))))
        assertTrue(msg.contains("by hand"))
        q.applyServer("pro", 0, "2026-11-17")
        assertFalse(q.limitMessage().contains("free"))
    }
}
