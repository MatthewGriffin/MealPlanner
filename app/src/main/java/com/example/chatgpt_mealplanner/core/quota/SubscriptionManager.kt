package com.example.chatgpt_mealplanner.core.quota

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The seam for Google Play Billing. Until the app is set up on Google Play there is no store to buy from,
 * so [storeAvailable] is false and [startPurchase] only explains that. When billing is added, check the
 * active subscription here and call [UsageQuota.setTier] with PAID or FREE.
 */
@Singleton
open class SubscriptionManager @Inject constructor(private val usageQuota: UsageQuota) {
    open val storeAvailable: Boolean = false

    /** Starts the purchase flow; returns a message to show the user. */
    open fun startPurchase(): String =
        "Subscriptions will be available once the app is on Google Play."

    open fun restorePurchases(): String =
        "There is nothing to restore until the app is on Google Play."
}
