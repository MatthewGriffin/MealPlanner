package com.example.chatgpt_mealplanner.core.quota

import android.app.Activity
import com.example.chatgpt_mealplanner.core.ai.provider.ProxyAccount
import com.example.chatgpt_mealplanner.core.ai.provider.PurchaseResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionManagerTest {
    private val events = mutableListOf<String>()

    private fun proxy(result: PurchaseResult?) = object : ProxyAccount(null) {
        override fun reportPurchase(purchaseToken: String): PurchaseResult? { events += "report:$purchaseToken"; return result }
    }

    private val store = object : StoreClient {
        override var onPurchasesChanged: (List<StorePurchase>) -> Unit = {}
        override suspend fun connect() = true
        override suspend fun products() = listOf(StoreProduct(UsageTier.PRO, "£4.99"), StoreProduct(UsageTier.MAX, "£11.99"))
        override suspend fun purchases() = emptyList<StorePurchase>()
        override suspend fun acknowledge(token: String): Boolean { events += "ack:$token"; return true }
        override fun launch(activity: Activity, tier: UsageTier): String? = null
    }

    private fun manager(result: PurchaseResult?) = SubscriptionManager(UsageQuota(null, null), proxy(result), store)

    private fun purchase(
        token: String = "t1", product: String = SubscriptionProducts.PRO,
        purchased: Boolean = true, pending: Boolean = false, acknowledged: Boolean = false
    ) = StorePurchase(token, listOf(product), purchased, pending, acknowledged)

    @Test
    fun acknowledgesOnlyAfterTheWorkerSaysEntitled() = runTest {
        val m = manager(PurchaseResult(entitled = true))
        m.handle(listOf(purchase()))
        assertEquals(listOf("report:t1", "ack:t1"), events)
        assertNotNull(m.message.value)
    }

    @Test
    fun doesNotAcknowledgeWhenTheWorkerSaysNotEntitled() = runTest {
        manager(PurchaseResult(entitled = false)).handle(listOf(purchase()))
        assertEquals(listOf("report:t1"), events)
    }

    @Test
    fun doesNotAcknowledgeWhenTheCheckCouldNotBeMade_andTellsTheUser() = runTest {
        val m = manager(null)
        m.handle(listOf(purchase()))
        assertEquals(listOf("report:t1"), events)
        assertTrue(m.message.value!!.contains("again"))
    }

    @Test
    fun pendingPurchaseIsNeitherReportedNorAcknowledged() = runTest {
        val m = manager(PurchaseResult(true))
        m.handle(listOf(purchase(purchased = false, pending = true)))
        assertTrue(events.isEmpty())
        assertTrue(m.message.value!!.contains("pending"))
    }

    @Test
    fun alreadyAcknowledgedPurchaseIsReportedButNotAcknowledgedAgain() = runTest {
        manager(PurchaseResult(true)).handle(listOf(purchase(acknowledged = true)))
        assertEquals(listOf("report:t1"), events)
    }

    @Test
    fun unknownProductsAreIgnored() = runTest {
        val m = manager(PurchaseResult(true))
        m.handle(listOf(purchase(product = "something_else")))
        assertTrue(events.isEmpty())
        assertNull(m.message.value)
    }

    @Test
    fun refreshLoadsPrices() = runTest {
        val m = manager(PurchaseResult(true))
        m.refresh()
        assertEquals("£4.99", m.prices.value[UsageTier.PRO])
        assertTrue(m.storeAvailable.value)
    }
}
