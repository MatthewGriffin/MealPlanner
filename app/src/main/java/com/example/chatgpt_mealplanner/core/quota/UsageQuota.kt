package com.example.chatgpt_mealplanner.core.quota

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

enum class UsageTier { FREE, PAID }

enum class QuotaKind { CHAT_MESSAGE, ITEM_ENTRY }

/** Daily allowance per tier. Paid is bigger but deliberately not unlimited. Tune these numbers freely. */
data class TierLimits(val chatMessages: Int, val itemEntries: Int) {
    fun limitFor(kind: QuotaKind) = if (kind == QuotaKind.CHAT_MESSAGE) chatMessages else itemEntries
}

object QuotaLimits {
    val FREE = TierLimits(chatMessages = 8, itemEntries = 20)
    val PAID = TierLimits(chatMessages = 100, itemEntries = 300)
    fun forTier(tier: UsageTier) = if (tier == UsageTier.FREE) FREE else PAID
}

/**
 * Counts AI use per calendar day (device local time) and says whether more is allowed.
 * The tier comes from [tierSource]; until Play billing exists, release builds are FREE; debug builds bypass the limits.
 */
@Singleton
open class UsageQuota @Inject constructor(
    @param:ApplicationContext private val context: Context?
) {
    private val prefs by lazy {
        context?.let { runCatching { it.getSharedPreferences("usage_quota_v1", Context.MODE_PRIVATE) }.getOrNull() }
    }

    /** Replace when billing arrives (read the active subscription here). */
    open fun tier(): UsageTier {
        val saved = runCatching { prefs?.getString("tier", null) }.getOrNull()
        if (saved != null) return runCatching { UsageTier.valueOf(saved) }.getOrDefault(UsageTier.FREE)
        return UsageTier.FREE
    }

    open fun setTier(tier: UsageTier) {
        runCatching { prefs?.edit()?.putString("tier", tier.name)?.apply() }
    }

    protected open fun today(): LocalDate = LocalDate.now()

    open fun used(kind: QuotaKind): Int {
        val p = prefs ?: return 0
        return runCatching {
            if (p.getString("day", null) != today().toString()) 0 else p.getInt(kind.name, 0)
        }.getOrDefault(0)
    }

    /** Debug builds (dev testing) ignore the limits completely. Tests can override this to check the limits. */
    protected open fun limitsBypassed(): Boolean = com.example.chatgpt_mealplanner.BuildConfig.DEBUG

    open fun remaining(kind: QuotaKind): Int =
        if (limitsBypassed()) Int.MAX_VALUE else (QuotaLimits.forTier(tier()).limitFor(kind) - used(kind)).coerceAtLeast(0)

    /** True when [amount] more fits today. Does not count anything. */
    open fun canUse(kind: QuotaKind, amount: Int = 1): Boolean = remaining(kind) >= amount

    open fun record(kind: QuotaKind, amount: Int = 1) {
        val p = prefs ?: return
        runCatching {
            val day = today().toString()
            val edit = p.edit()
            if (p.getString("day", null) != day) {
                edit.putString("day", day)
                QuotaKind.values().forEach { edit.putInt(it.name, 0) }
                edit.putInt(kind.name, amount)
            } else {
                edit.putInt(kind.name, p.getInt(kind.name, 0) + amount)
            }
            edit.apply()
        }
    }

    fun limitMessage(kind: QuotaKind): String {
        val tier = tier()
        val what = if (kind == QuotaKind.CHAT_MESSAGE) "assistant messages" else "item entries"
        val limit = QuotaLimits.forTier(tier).limitFor(kind)
        return if (tier == UsageTier.FREE)
            "You have used today's $limit free $what. The limit resets tomorrow, or upgrade for a higher daily limit."
        else
            "You have reached today's limit of $limit $what. It resets tomorrow."
    }
}
