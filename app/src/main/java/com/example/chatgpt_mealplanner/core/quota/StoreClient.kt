package com.example.chatgpt_mealplanner.core.quota

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** The Google Play subscription product IDs. They match worker/src/play.ts and can never change once created in Play Console. */
object SubscriptionProducts {
    const val PRO = "pantrypal_pro"
    const val MAX = "pantrypal_max"
    fun idFor(tier: UsageTier): String? = when (tier) { UsageTier.PRO -> PRO; UsageTier.MAX -> MAX; UsageTier.FREE -> null }
    fun tierOf(productId: String): UsageTier? = when (productId) { PRO -> UsageTier.PRO; MAX -> UsageTier.MAX; else -> null }
}

data class StoreProduct(val tier: UsageTier, val price: String)

data class StorePurchase(val token: String, val productIds: List<String>, val purchased: Boolean, val pending: Boolean, val acknowledged: Boolean)

/** The store, behind an interface so the buying logic can be tested without Google Play. */
interface StoreClient {
    /** Called when a purchase changes outside our own query (for example finishing the purchase dialog). */
    var onPurchasesChanged: (List<StorePurchase>) -> Unit
    suspend fun connect(): Boolean
    suspend fun products(): List<StoreProduct>
    suspend fun purchases(): List<StorePurchase>
    suspend fun acknowledge(token: String): Boolean
    /** Opens the purchase dialog. Returns a message if it could not be opened, else null. */
    fun launch(activity: Activity, tier: UsageTier): String?
}

/** Google Play Billing. */
class PlayStoreClient(context: Context) : StoreClient {
    override var onPurchasesChanged: (List<StorePurchase>) -> Unit = {}
    private val details = mutableMapOf<String, com.android.billingclient.api.ProductDetails>()

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(PurchasesUpdatedListener { _: BillingResult, list: MutableList<Purchase>? ->
            list?.let { onPurchasesChanged(it.map(::toStorePurchase)) }
        })
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private fun toStorePurchase(p: Purchase) = StorePurchase(
        token = p.purchaseToken,
        productIds = p.products,
        purchased = p.purchaseState == Purchase.PurchaseState.PURCHASED,
        pending = p.purchaseState == Purchase.PurchaseState.PENDING,
        acknowledged = p.isAcknowledged
    )

    override suspend fun connect(): Boolean {
        if (client.isReady) return true
        return suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }
                override fun onBillingServiceDisconnected() {}
            })
        }
    }

    override suspend fun products(): List<StoreProduct> {
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            listOf(SubscriptionProducts.PRO, SubscriptionProducts.MAX).map {
                QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.SUBS).build()
            }
        ).build()
        val result = client.queryProductDetails(params)
        val list = result.productDetailsList ?: return emptyList()
        list.forEach { details[it.productId] = it }
        return list.mapNotNull { d ->
            val tier = SubscriptionProducts.tierOf(d.productId) ?: return@mapNotNull null
            val price = d.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice
            price?.let { StoreProduct(tier, it) }
        }
    }

    override suspend fun purchases(): List<StorePurchase> {
        val result = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build())
        return result.purchasesList.map(::toStorePurchase)
    }

    override suspend fun acknowledge(token: String): Boolean =
        client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()).responseCode ==
            BillingClient.BillingResponseCode.OK

    override fun launch(activity: Activity, tier: UsageTier): String? {
        val id = SubscriptionProducts.idFor(tier) ?: return "That plan cannot be bought."
        val d = details[id] ?: return "That plan is not available to buy right now."
        val offer = d.subscriptionOfferDetails?.firstOrNull() ?: return "That plan is not available to buy right now."
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(
            listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).setOfferToken(offer.offerToken).build())
        ).build()
        val result = client.launchBillingFlow(activity, params)
        return if (result.responseCode == BillingClient.BillingResponseCode.OK) null else "The purchase could not be started (${result.debugMessage.ifBlank { "try again later" }})."
    }
}
