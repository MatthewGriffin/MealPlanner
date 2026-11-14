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
}
