package com.example.chatgpt_mealplanner.core.quota

import com.example.chatgpt_mealplanner.core.ai.provider.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageQuotaTest {
    @Test
    fun agreedLimits() {
        assertEquals(20_000L, QuotaLimits.FREE_MICRO_DOLLARS)   // 2 cents
        assertEquals(80_000L, QuotaLimits.PAID_MICRO_DOLLARS)  // 8 cents
        assertEquals(4, QuotaLimits.PAID_MULTIPLE)
    }

    @Test
    fun costOfAHaikuReply() {
        // 10k input at $0.10/M + 1k output at $0.50/M = $0.001 + $0.0005 = 1,500 micro-dollars.
        assertEquals(1_500L, ClaudePricing.microDollars("claude-haiku-5-5", TokenUsage(10_000, 1_000, 0, 0)))
        // A web search costs a cent.
        assertEquals(10_000L, ClaudePricing.microDollars("claude-haiku-5-5", TokenUsage(0, 0, 0, 0, webSearches = 1)))
        // An unknown model is charged at the dearest price.
        assertEquals(2_000_000L, ClaudePricing.microDollars("mystery", TokenUsage(1_000_000, 0, 0, 0)))
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
    fun enforcedSnapshotStartsFullAndMessageNamesTheTier() {
        val free = object : UsageQuota(null) {
            override fun limitsBypassed() = false
            override fun tier() = UsageTier.FREE
        }
        free.refresh()
        assertEquals(100, free.snapshot.value.percentLeft)
        assertTrue(free.limitMessage().contains("free"))
        val paid = object : UsageQuota(null) { override fun tier() = UsageTier.PAID }
        assertFalse(paid.limitMessage().contains("free"))
    }
}
