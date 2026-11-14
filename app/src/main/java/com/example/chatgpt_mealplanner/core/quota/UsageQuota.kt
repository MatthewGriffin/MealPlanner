package com.example.chatgpt_mealplanner.core.quota

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class UsageTier { FREE, PRO, MAX }

/**
 * AI allowance per tier, in millionths of a dollar of Claude cost.
 * Free is a weekly bundle (use it all at once if you like, then wait for the week to end);
 * Pro is a bigger daily allowance. Neither is unlimited.
 */
object QuotaLimits {
    const val FREE_MICRO_DOLLARS = 70_000L   // 7 cents a week (1 cent a day, bundled)
    const val FREE_PERIOD_DAYS = 7L
    const val PRO_MICRO_DOLLARS = 90_000L    // 9 cents a day, about 20 meal requests
    const val MAX_MICRO_DOLLARS = 180_000L   // double Pro: 18 cents a day, about 40 requests
    const val PAID_PERIOD_DAYS = 1L

    fun limitFor(tier: UsageTier) = when (tier) {
        UsageTier.FREE -> FREE_MICRO_DOLLARS
        UsageTier.PRO -> PRO_MICRO_DOLLARS
        UsageTier.MAX -> MAX_MICRO_DOLLARS
    }
    fun periodDays(tier: UsageTier) = if (tier == UsageTier.FREE) FREE_PERIOD_DAYS else PAID_PERIOD_DAYS

    /** How many times more AI Pro gets than Free over the same time (Pro's daily allowance x7 against Free's week). */
    val PRO_MULTIPLE: Int get() = ((PRO_MICRO_DOLLARS * FREE_PERIOD_DAYS) / FREE_MICRO_DOLLARS).toInt()

    /** How many times Pro's allowance Max gets. */
    val MAX_MULTIPLE_OF_PRO: Int get() = (MAX_MICRO_DOLLARS / PRO_MICRO_DOLLARS).toInt()
}

/**
 * What the screens show. [percentLeft] is 0..100. [resetsOn] is set once the current period has started
 * (the first AI use of a week for Free). [example] is true in debug builds: made-up numbers.
 */
data class UsageSnapshot(
    val tier: UsageTier = UsageTier.FREE,
    val percentLeft: Int = 100,
    val resetsOn: LocalDate? = null,
    val example: Boolean = false,
    /** True when usage is not tracked at all (no quota wired in): the screens show nothing. */
    val unlimited: Boolean = false,
    /** For the debug section of the plans page: real cost so far this period and of the latest reply, in millionths of a dollar. */
    val spentMicros: Long = 0,
    val lastMicros: Long = 0
)

/** Where the quota keeps its few numbers. Phones use SharedPreferences; tests use memory. */
interface QuotaStore {
    fun getString(key: String): String?
    fun getLong(key: String): Long
    fun put(strings: Map<String, String>, longs: Map<String, Long>)
}

private class PrefsQuotaStore(private val prefs: android.content.SharedPreferences) : QuotaStore {
    override fun getString(key: String) = prefs.getString(key, null)
    override fun getLong(key: String) = prefs.getLong(key, 0L)
    override fun put(strings: Map<String, String>, longs: Map<String, Long>) {
        val edit = prefs.edit()
        strings.forEach { (k, v) -> edit.putString(k, v) }
        longs.forEach { (k, v) -> edit.putLong(k, v) }
        edit.apply()
    }
}

/**
 * Adds up what AI use cost (Claude token prices, see [ClaudePricing]) and says whether more is allowed.
 * Only AI use counts; manual adds, edits and barcode scans never touch this.
 * A request that starts under the limit is allowed to finish, so a period can end slightly over.
 */
@Singleton
open class UsageQuota @Inject constructor(
    @param:ApplicationContext private val context: Context?
) {
    private val prefsStore: QuotaStore? by lazy {
        context?.let { runCatching { PrefsQuotaStore(it.getSharedPreferences("usage_quota_v3", Context.MODE_PRIVATE)) }.getOrNull() }
    }

    protected open fun store(): QuotaStore? = prefsStore

    private val _snapshot = MutableStateFlow(UsageSnapshot())
    val snapshot: StateFlow<UsageSnapshot> get() { refresh(); return _snapshot.asStateFlow() }

    fun refresh() {
        val tier = tier()
        val limit = QuotaLimits.limitFor(tier)
        val spent = spentMicros()
        val left = ((limit - spent).coerceAtLeast(0) * 100 / limit).toInt()
        _snapshot.value = UsageSnapshot(
            tier = tier,
            // Debug builds have no real limits, so show an example to see how the screens look.
            percentLeft = if (limitsBypassed()) 70 else left,
            resetsOn = if (spent > 0 || limitsBypassed()) resetsOn() else null,
            example = limitsBypassed(),
            spentMicros = spent,
            lastMicros = lastMicros()
        )
    }

    /** Replace when billing arrives (read the active subscription here). */
    open fun tier(): UsageTier {
        val saved = runCatching { store()?.getString("tier") }.getOrNull()
        return saved?.let { runCatching { UsageTier.valueOf(it) }.getOrNull() } ?: UsageTier.FREE
    }

    open fun setTier(tier: UsageTier) {
        runCatching { store()?.put(mapOf("tier" to tier.name), emptyMap()) }
        refresh()
    }

    protected open fun today(): LocalDate = LocalDate.now()

    /** Debug builds (dev testing) ignore the limits completely. Tests can override this to check the limits. */
    protected open fun limitsBypassed(): Boolean = com.example.chatgpt_mealplanner.BuildConfig.DEBUG

    /** The day the current period started, or null if it has ended or never started (or the tier changed since). */
    private fun activePeriodStart(): LocalDate? {
        val s = store() ?: return null
        return runCatching {
            if (s.getString("periodTier") != tier().name) return null
            val start = LocalDate.parse(s.getString("periodStart") ?: return null)
            if (!today().isBefore(start.plusDays(QuotaLimits.periodDays(tier())))) null else start
        }.getOrNull()
    }

    /** The day the allowance refreshes, for the current period. */
    open fun resetsOn(): LocalDate =
        (activePeriodStart() ?: today()).plusDays(QuotaLimits.periodDays(tier()))

    open fun spentMicros(): Long {
        val s = store() ?: return 0L
        return if (activePeriodStart() == null) 0L else runCatching { s.getLong("spent") }.getOrDefault(0L)
    }

    open fun lastMicros(): Long = runCatching { store()?.getLong("last") }.getOrNull() ?: 0L

    /** True while the allowance for this period is not used up. */
    open fun canUse(): Boolean = limitsBypassed() || spentMicros() < QuotaLimits.limitFor(tier())

    /** Adds the cost of one Claude reply (millionths of a dollar). The first use of a period starts it. */
    @Synchronized
    open fun recordSpend(micros: Long) {
        val s = store() ?: return
        if (micros <= 0) return
        runCatching {
            val start = activePeriodStart() ?: today()
            val before = if (activePeriodStart() == null) 0L else s.getLong("spent")
            s.put(
                mapOf("periodStart" to start.toString(), "periodTier" to tier().name),
                mapOf("spent" to before + micros, "last" to micros)
            )
        }
        refresh()
    }

    fun limitMessage(): String {
        val date = formatDate(resetsOn())
        return if (tier() == UsageTier.FREE)
            "You have used this week's free AI allowance. It refreshes on $date, or upgrade for more. Everything you can do by hand still works."
        else
            "You have used today's AI allowance. It refreshes tomorrow. Everything you can do by hand still works."
    }

    companion object {
        fun formatDate(date: LocalDate): String =
            date.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.getDefault()))
    }
}
