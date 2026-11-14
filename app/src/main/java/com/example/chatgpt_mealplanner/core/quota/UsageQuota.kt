package com.example.chatgpt_mealplanner.core.quota

import com.example.chatgpt_mealplanner.core.ai.provider.ProxyAccount
import com.example.chatgpt_mealplanner.core.ai.provider.UsageReading
import com.example.chatgpt_mealplanner.core.security.CredentialStore
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class UsageTier { FREE, PRO, MAX }

/** How the plans compare, for the plans page. Keep in step with worker/src/quota.ts (the Worker enforces the real limits). */
object QuotaLimits {
    const val PRO_MULTIPLE = 9
    const val MAX_MULTIPLE_OF_PRO = 2
}

/**
 * What the screens show. [percentLeft] is 0..100. [resetsOn] is when the allowance refreshes, known once a period has started.
 * [example] is true in debug builds: made-up numbers. [unlimited] is true when the user brings their own Claude key,
 * so there is nothing to show.
 */
data class UsageSnapshot(
    val tier: UsageTier = UsageTier.FREE,
    val percentLeft: Int = 100,
    val resetsOn: LocalDate? = null,
    val example: Boolean = false,
    val unlimited: Boolean = false
)

/**
 * Tracks the AI allowance for the screens. The Worker is the one that counts and enforces; every reply carries
 * the current numbers and they are kept here. With the user's own Claude key there are no limits.
 * Only AI use counts; manual adds, edits and barcode scans never touch this.
 */
@Singleton
open class UsageQuota @Inject constructor(
    private val credentialStore: CredentialStore?,
    private val proxyAccount: ProxyAccount?
) {
    private val _snapshot = MutableStateFlow(UsageSnapshot())
    val snapshot: StateFlow<UsageSnapshot> get() { refresh(); return _snapshot.asStateFlow() }

    @Volatile private var reading: UsageReading? = null
    @Volatile private var debugTier: UsageTier? = null

    protected open fun today(): LocalDate = LocalDate.now()

    /** Debug builds (dev testing) never block locally and show example numbers. */
    protected open fun limitsBypassed(): Boolean = com.example.chatgpt_mealplanner.BuildConfig.DEBUG

    /** The user's own Claude key means their own bill, so no limits. */
    protected open fun ownKeyMode(): Boolean = runCatching { credentialStore?.hasKey() }.getOrNull() == true

    /** Called with what the Worker reported on a reply. */
    fun applyServer(tier: String?, percentLeft: Int?, resetsOn: String?) {
        if (tier == null || percentLeft == null) return
        reading = UsageReading(tier, percentLeft, resetsOn?.takeIf { it.isNotBlank() })
        refresh()
    }

    /** Asks the Worker how much is left, e.g. when the app opens. */
    suspend fun refreshFromServer() {
        if (ownKeyMode() || proxyAccount == null) { refresh(); return }
        withContext(Dispatchers.IO) { proxyAccount.fetchUsage() }?.let { reading = it }
        refresh()
    }

    private fun tierOf(name: String?): UsageTier =
        name?.let { n -> UsageTier.values().firstOrNull { it.name.equals(n, ignoreCase = true) } } ?: UsageTier.FREE

    private fun resetDate(): LocalDate? = reading?.resetsOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun refresh() {
        val r = reading
        _snapshot.value = when {
            limitsBypassed() -> UsageSnapshot(tier = debugTier ?: tierOf(r?.tier), percentLeft = 70, resetsOn = resetDate(), example = true)
            ownKeyMode() -> UsageSnapshot(unlimited = true)
            else -> UsageSnapshot(tier = tierOf(r?.tier), percentLeft = r?.percentLeft ?: 100, resetsOn = resetDate())
        }
    }

    /** Debug only: pretend to be on a plan to see how the app looks. The Worker still decides the real allowance. */
    fun setDebugTier(tier: UsageTier) { debugTier = tier; refresh() }

    /**
     * Quick check before sending. False only when the Worker last said the allowance is used up and its refresh date
     * has not come; the Worker has the final say either way.
     */
    open fun canUse(): Boolean {
        if (limitsBypassed() || ownKeyMode()) return true
        val r = reading ?: return true
        if (r.percentLeft > 0) return true
        val resets = resetDate() ?: return false
        return !today().isBefore(resets)
    }

    fun limitMessage(): String {
        val date = resetDate()?.let { formatDate(it) }
        return if (tierOf(reading?.tier) == UsageTier.FREE)
            "You have used this week's free AI allowance. It refreshes" + (date?.let { " on $it" } ?: "") +
                ", or upgrade for more. Everything you can do by hand still works."
        else
            "You have used today's AI allowance. It refreshes tomorrow. Everything you can do by hand still works."
    }

    companion object {
        fun formatDate(date: LocalDate): String =
            date.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.getDefault()))
    }
}
