package com.example.chatgpt_mealplanner.feature.subscription

import androidx.lifecycle.ViewModel
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
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    val isDebugBuild: Boolean = com.example.chatgpt_mealplanner.BuildConfig.DEBUG

    fun subscribe() { _message.value = subscriptions.startPurchase() }
    fun restore() { _message.value = subscriptions.restorePurchases() }
    fun clearMessage() { _message.value = null }

    /** Debug builds only: pretend to be on a plan to see how the app looks. Limits stay bypassed in debug. */
    fun debugSetTier(tier: UsageTier) { if (isDebugBuild) usageQuota.setTier(tier) }
}
