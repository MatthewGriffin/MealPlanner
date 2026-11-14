package com.example.chatgpt_mealplanner.core.quota

import com.example.chatgpt_mealplanner.core.ai.provider.TokenUsage
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageQuotaTest {
    private class MemoryStore : QuotaStore {
        val strings = mutableMapOf<String, String>()
        val longs = mutableMapOf<String, Long>()
        override fun getString(key: String) = strings[key]
        override fun getLong(key: String) = longs[key] ?: 0L
        override fun put(strings: Map<String, String>, longs: Map<String, Long>) { this.strings += strings; this.longs += longs }
    }

    private class TestQuota(var day: LocalDate = LocalDate.of(2026, 11, 16), val memory: MemoryStore = MemoryStore()) : UsageQuota(null) {
        override fun store(): QuotaStore = memory
        override fun today(): LocalDate = day
        override fun limitsBypassed() = false
    }

    @Test
    fun agreedLimits() {
        assertEquals(70_000L, QuotaLimits.FREE_MICRO_DOLLARS)  // 1 cent a day bundled into a week
        assertEquals(7L, QuotaLimits.FREE_PERIOD_DAYS)
        assertEquals(80_000L, QuotaLimits.PAID_MICRO_DOLLARS)  // 8 cents a day
        assertEquals(8, QuotaLimits.PAID_MULTIPLE)
    }

    @Test
    fun costOfAHaikuReply() {
        assertEquals(1_500L, ClaudePricing.microDollars("claude-haiku-5-5", TokenUsage(10_000, 1_000, 0, 0)))
        assertEquals(10_000L, ClaudePricing.microDollars("claude-haiku-5-5", TokenUsage(0, 0, 0, 0, webSearches = 1)))
        assertEquals(2_000_000L, ClaudePricing.microDollars("mystery", TokenUsage(1_000_000, 0, 0, 0)))
    }

    @Test
    fun freeWeekCanBeBurnedAtOnceThenWaitsUntilTheWeekEnds() {
        val q = TestQuota()
        repeat(15) { assertTrue(q.canUse()); q.recordSpend(4_500) } // 15 requests = 67,500 micro-dollars
        assertTrue(q.canUse())          // 2,500 left: one more may start
        q.recordSpend(4_500)            // finishes slightly over
        assertFalse(q.canUse())
        q.day = q.day.plusDays(6)
        assertFalse(q.canUse())         // still the same week
        assertEquals(LocalDate.of(2026, 11, 23), q.resetsOn())
        q.day = q.day.plusDays(1)
        assertTrue(q.canUse())          // a week after the first use
        assertEquals(0L, q.spentMicros())
    }

    @Test
    fun theWeekStartsAtTheFirstUseNotBefore() {
        val q = TestQuota()
        q.refresh()
        assertNull(q.snapshot.value.resetsOn)
        q.day = q.day.plusDays(3)
        q.recordSpend(4_500)
        assertEquals(q.day.plusDays(7), q.snapshot.value.resetsOn)
    }

    @Test
    fun proIsADailyAllowance() {
        val q = TestQuota()
        q.setTier(UsageTier.PAID)
        q.recordSpend(80_000)
        assertFalse(q.canUse())
        q.day = q.day.plusDays(1)
        assertTrue(q.canUse())
    }

    @Test
    fun changingPlanStartsAFreshAllowance() {
        val q = TestQuota()
        q.recordSpend(70_000)
        assertFalse(q.canUse())
        q.setTier(UsageTier.PAID)
        assertTrue(q.canUse())
    }

    @Test
    fun withoutStorageNothingIsCountedSoNothingIsBlocked() {
        val q = UsageQuota(null)
        q.recordSpend(10_000_000)
        assertEquals(0L, q.spentMicros())
        assertTrue(q.canUse())
    }

    @Test
    fun bypassedLimitsAlwaysAllowAndShowAnExample() {
        val bypassed = object : UsageQuota(null) { override fun limitsBypassed() = true }
        assertTrue(bypassed.canUse())
        bypassed.refresh()
        val example = bypassed.snapshot.value
        assertTrue(example.example); assertFalse(example.unlimited)
        assertTrue(example.percentLeft in 1..99)
    }

    @Test
    fun limitMessageNamesTheRefreshDateAndSaysManualStillWorks() {
        val q = TestQuota()
        q.recordSpend(70_000)
        val msg = q.limitMessage()
        assertTrue(msg.contains(UsageQuota.formatDate(LocalDate.of(2026, 11, 23))))
        assertTrue(msg.contains("by hand"))
    }
}
