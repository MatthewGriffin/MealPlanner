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
    private val subscriptions: SubscriptionManager
) : ViewModel() {
    val usage: StateFlow<UsageSnapshot> = usageQuota.snapshot

    init {
        // Ask the Worker how much is left whenever the app starts (this screen's model lives with the top bar).
        viewModelScope.launch { usageQuota.refreshFromServer() }
    }
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    val isDebugBuild: Boolean = com.example.chatgpt_mealplanner.BuildConfig.DEBUG

    fun subscribe(tier: UsageTier) { _message.value = subscriptions.startPurchase(tier) }
    fun manage() { _message.value = subscriptions.manageSubscription() }
    fun restore() { _message.value = subscriptions.restorePurchases() }
    fun clearMessage() { _message.value = null }

    /** Debug builds only: pretend to be on a plan to see how the app looks. Limits stay bypassed in debug. */
    fun debugSetTier(tier: UsageTier) { if (isDebugBuild) usageQuota.setDebugTier(tier) }
}
