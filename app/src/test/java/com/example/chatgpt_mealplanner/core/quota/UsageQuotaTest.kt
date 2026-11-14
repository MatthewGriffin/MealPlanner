package com.example.chatgpt_mealplanner.core.quota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageQuotaTest {
    @Test
    fun limitsAreOrderedAndPaidIsNotUnlimited() {
        assertTrue(QuotaLimits.FREE.chatMessages < QuotaLimits.PAID.chatMessages)
        assertTrue(QuotaLimits.FREE.itemEntries < QuotaLimits.PAID.itemEntries)
        assertTrue(QuotaLimits.PAID.chatMessages < 1000 && QuotaLimits.PAID.itemEntries < 1000)
    }

    @Test
    fun withoutStorageNothingIsCountedSoNothingIsBlocked() {
        val q = UsageQuota(null)
        q.record(QuotaKind.CHAT_MESSAGE, 500)
        assertEquals(0, q.used(QuotaKind.CHAT_MESSAGE))
        assertTrue(q.canUse(QuotaKind.CHAT_MESSAGE))
    }

    @Test
    fun messageNamesTheLimitAndTheTier() {
        val free = object : UsageQuota(null) { override fun tier() = UsageTier.FREE }
        val paid = object : UsageQuota(null) { override fun tier() = UsageTier.PAID }
        assertTrue(free.limitMessage(QuotaKind.CHAT_MESSAGE).contains("${QuotaLimits.FREE.chatMessages} free"))
        assertFalse(paid.limitMessage(QuotaKind.ITEM_ENTRY).contains("free"))
        assertTrue(paid.limitMessage(QuotaKind.ITEM_ENTRY).contains("${QuotaLimits.PAID.itemEntries}"))
    }

    @Test
    fun bypassedLimitsAlwaysAllowAndEnforcedLimitsDoNot() {
        val bypassed = object : UsageQuota(null) { override fun limitsBypassed() = true }
        assertTrue(bypassed.canUse(QuotaKind.CHAT_MESSAGE, 100000))
        val enforced = object : UsageQuota(null) {
            override fun limitsBypassed() = false
            override fun tier() = UsageTier.FREE
        }
        assertTrue(enforced.canUse(QuotaKind.CHAT_MESSAGE, QuotaLimits.FREE.chatMessages))
        assertFalse(enforced.canUse(QuotaKind.CHAT_MESSAGE, QuotaLimits.FREE.chatMessages + 1))
    }

    @Test
    fun agreedLimits() {
        assertEquals(TierLimits(4, 5), QuotaLimits.FREE)
        assertEquals(TierLimits(20, 100), QuotaLimits.PAID)
    }

    @Test
    fun snapshotShowsWhatIsLeftAndUnlimitedWhenBypassed() {
        val enforced = object : UsageQuota(null) {
            override fun limitsBypassed() = false
            override fun tier() = UsageTier.FREE
        }
        enforced.refresh()
        val snap = enforced.snapshot.value
        assertEquals(4, snap.chatLeft); assertEquals(5, snap.itemsLeft); assertFalse(snap.unlimited)
        val bypassed = object : UsageQuota(null) { override fun limitsBypassed() = true }
        bypassed.refresh()
        val example = bypassed.snapshot.value
        assertTrue(example.example); assertFalse(example.unlimited)
        assertTrue(example.chatLeft in 1 until example.chatLimit)
        assertTrue(bypassed.canUse(QuotaKind.CHAT_MESSAGE, 1000))
    }
}
