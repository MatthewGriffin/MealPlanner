package com.example.chatgpt_mealplanner.core.quota

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.chatgpt_mealplanner.core.ai.provider.ProxyAccount
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Buying and keeping a plan through Google Play. The plan itself lives on the Worker: this tells the Worker about each
 * purchase, the Worker checks it with Google, and only after it says the purchase is good do we acknowledge it to Google
 * (an unacknowledged purchase is refunded automatically after about three days, so a failed check fails safe).
 */
@Singleton
open class SubscriptionManager(
    private val usageQuota: UsageQuota,
    private val proxyAccount: ProxyAccount?,
    private val store: StoreClient?,
    private val openUrl: (String) -> Unit = {}
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        usageQuota: UsageQuota,
        proxyAccount: ProxyAccount
    ) : this(usageQuota, proxyAccount, PlayStoreClient(context), { url -> openInBrowser(context, url) })

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    private val _available = MutableStateFlow(false)
    /** True once Google Play Billing is reachable on this device. */
    val storeAvailable: StateFlow<Boolean> = _available.asStateFlow()

    private val _prices = MutableStateFlow<Map<UsageTier, String>>(emptyMap())
    /** The real prices from Google Play, in the user's currency. Empty until known. */
    val prices: StateFlow<Map<UsageTier, String>> = _prices.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        store?.onPurchasesChanged = { purchases -> scope.launch { handle(purchases) } }
    }

    /** Connects, loads prices, and passes on any purchases (this is also how a renewal, a restore or a retry is picked up). */
    open suspend fun refresh() {
        val s = store ?: return
        if (!s.connect()) { _available.value = false; return }
        _available.value = true
        _prices.value = s.products().associate { it.tier to it.price }
        handle(s.purchases())
    }

    /** Tells the Worker about each good purchase and acknowledges it only once the Worker says it is entitled. */
    suspend fun handle(purchases: List<StorePurchase>) = lock.withLock {
        val s = store ?: return@withLock
        val account = proxyAccount ?: return@withLock
        for (p in purchases) {
            if (p.productIds.none { SubscriptionProducts.tierOf(it) != null }) continue
            if (p.pending) { _message.value = "Your purchase is pending. The plan switches on once Google Play confirms the payment."; continue }
            if (!p.purchased) continue
            val result = withContext(Dispatchers.IO) { account.reportPurchase(p.token) }
            if (result == null) { _message.value = "Couldn't confirm your purchase just now. It will be checked again next time the app opens."; continue }
            if (!result.entitled) continue
            if (!p.acknowledged) s.acknowledge(p.token)
            usageQuota.refreshFromServer()
            _message.value = "Your plan is active. Thank you!"
        }
    }

    /** Opens the purchase dialog. Returns a message to show if it could not be opened. */
    open fun startPurchase(activity: Activity, tier: UsageTier): String? {
        val s = store ?: return "Subscriptions are not available on this device."
        if (!_available.value) return "Subscriptions are not available right now. Check your connection and that Google Play is up to date."
        return s.launch(activity, tier)
    }

    /** Opens Google Play's page to change or cancel the subscription. */
    open fun manageSubscription() = openUrl("https://play.google.com/store/account/subscriptions")

    open suspend fun restorePurchases(): String {
        refresh()
        return if (_available.value) "Checked Google Play for your purchases." else "Subscriptions are not available right now."
    }
}

private fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}
