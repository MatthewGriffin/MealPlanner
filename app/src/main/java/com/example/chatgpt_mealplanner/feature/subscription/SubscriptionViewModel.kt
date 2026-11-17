package com.example.chatgpt_mealplanner.feature.subscription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import com.example.chatgpt_mealplanner.core.quota.SubscriptionManager
import com.example.chatgpt_mealplanner.core.quota.UsageQuota
import com.example.chatgpt_mealplanner.core.quota.UsageSnapshot
import com.example.chatgpt_mealplanner.core.quota.UsageTier
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class SubscriptionViewModel @Inject constructor(
    private val usageQuota: UsageQuota,
    private val subscriptions: SubscriptionManager,
    private val appRoleResolver: com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
) : ViewModel() {
    /** A follower phone uses the household's plan and cannot buy or change one. */
    val isFollower: Boolean = runCatching { appRoleResolver.isFollower() }.getOrDefault(false)

    val usage: StateFlow<UsageSnapshot> = usageQuota.snapshot

    init {
        // Ask the Worker how much is left whenever the app starts (this screen's model lives with the top bar).
        viewModelScope.launch { usageQuota.refreshFromServer() }
        // Connect to Google Play, show real prices, and pick up any purchase (restore, renewal, retry).
        viewModelScope.launch { subscriptions.refresh(); usageQuota.refreshFromServer() }
        viewModelScope.launch { subscriptions.message.collect { if (it != null) _message.value = it } }
    }
    val prices: StateFlow<Map<UsageTier, String>> = subscriptions.prices
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    val isDebugBuild: Boolean = com.example.chatgpt_mealplanner.BuildConfig.DEBUG

    fun subscribe(activity: android.app.Activity, tier: UsageTier) { subscriptions.startPurchase(activity, tier)?.let { _message.value = it } }
    fun manage() { subscriptions.manageSubscription() }
    fun restore() { viewModelScope.launch { _message.value = subscriptions.restorePurchases(); usageQuota.refreshFromServer() } }
    fun clearMessage() { _message.value = null }

    /** Debug builds only: pretend to be on a plan to see how the app looks. Limits stay bypassed in debug. */
    fun debugSetTier(tier: UsageTier) { if (isDebugBuild) usageQuota.setDebugTier(tier) }
}
