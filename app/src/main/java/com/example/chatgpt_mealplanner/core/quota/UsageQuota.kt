package com.example.chatgpt_mealplanner.core.quota

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class UsageTier { FREE, PAID }

/** Daily AI allowance per tier, in millionths of a dollar of Claude cost. Paid is bigger but not unlimited. */
object QuotaLimits {
    const val FREE_MICRO_DOLLARS = 20_000L   // 2 cents a day
    const val PAID_MICRO_DOLLARS = 100_000L  // 10 cents a day
    fun forTier(tier: UsageTier) = if (tier == UsageTier.FREE) FREE_MICRO_DOLLARS else PAID_MICRO_DOLLARS

    /** How many times bigger the paid allowance is, for the comparison page. */
    val PAID_MULTIPLE: Int get() = (PAID_MICRO_DOLLARS / FREE_MICRO_DOLLARS).toInt()
}

/** What the screens show. [percentLeft] is 0..100. [example] is true in debug builds: made-up numbers. */
data class UsageSnapshot(
    val tier: UsageTier = UsageTier.FREE,
    val percentLeft: Int = 100,
    val example: Boolean = false,
    /** True when usage is not tracked at all (no quota wired in): the screens show nothing. */
    val unlimited: Boolean = false
)

/**
 * Adds up what today's AI use cost (Claude token prices, see [ClaudePricing]) and says whether more is allowed.
 * Only AI use counts; manual adds, edits and barcode scans never touch this.
 * A request that starts under the limit is allowed to finish, so a day can end slightly over.
 */
@Singleton
open class UsageQuota @Inject constructor(
    @param:ApplicationContext private val context: Context?
) {
    private val prefs by lazy {
        context?.let { runCatching { it.getSharedPreferences("usage_quota_v2", Context.MODE_PRIVATE) }.getOrNull() }
    }

    private val _snapshot = MutableStateFlow(UsageSnapshot())
    val snapshot: StateFlow<UsageSnapshot> get() { refresh(); return _snapshot.asStateFlow() }

    fun refresh() {
        if (limitsBypassed()) {
            // Debug builds have no real limits, so show an example to see how the screens look.
            _snapshot.value = UsageSnapshot(tier = tier(), percentLeft = 70, example = true)
            return
        }
        val limit = QuotaLimits.forTier(tier())
        val left = ((limit - spentMicros()).coerceAtLeast(0) * 100 / limit).toInt()
        _snapshot.value = UsageSnapshot(tier = tier(), percentLeft = left)
    }

    /** Replace when billing arrives (read the active subscription here). */
    open fun tier(): UsageTier {
        val saved = runCatching { prefs?.getString("tier", null) }.getOrNull()
        return saved?.let { runCatching { UsageTier.valueOf(it) }.getOrNull() } ?: UsageTier.FREE
    }

    open fun setTier(tier: UsageTier) {
        runCatching { prefs?.edit()?.putString("tier", tier.name)?.apply() }
        refresh()
    }

    protected open fun today(): LocalDate = LocalDate.now()

    /** Debug builds (dev testing) ignore the limits completely. Tests can override this to check the limits. */
    protected open fun limitsBypassed(): Boolean = com.example.chatgpt_mealplanner.BuildConfig.DEBUG

    open fun spentMicros(): Long {
        val p = prefs ?: return 0L
        return runCatching {
            if (p.getString("day", null) != today().toString()) 0L else p.getLong("spent", 0L)
        }.getOrDefault(0L)
    }

    /** True while today's allowance is not used up. */
    open fun canUse(): Boolean = limitsBypassed() || spentMicros() < QuotaLimits.forTier(tier())

    /** Adds the cost of one Claude reply (millionths of a dollar). */
    @Synchronized
    open fun recordSpend(micros: Long) {
        val p = prefs ?: return
        if (micros <= 0) return
        runCatching {
            val day = today().toString()
            val before = if (p.getString("day", null) == day) p.getLong("spent", 0L) else 0L
            p.edit().putString("day", day).putLong("spent", before + micros).apply()
        }
        refresh()
    }

    fun limitMessage(): String =
        if (tier() == UsageTier.FREE)
            "You have used today's free AI allowance. It resets tomorrow, or upgrade for more."
        else
            "You have used today's AI allowance. It resets tomorrow."
}
