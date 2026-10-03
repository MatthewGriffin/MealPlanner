package com.example.chatgpt_mealplanner.backend.retail

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Test

class IcelandLiveDiagnosticTest {

    @Test
    fun testAllPhysicalItemsWithCategoryMapping() = runBlocking {
        val httpClient = HttpClient(OkHttp)
        val client = IcelandSourceClient(
            httpClient = httpClient,
            config = IcelandClientConfig(userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
        )
        val adapter = IcelandRetailerAdapter(client)

        val items = listOf("Eggs", "Baby spinach", "Mushrooms", "Shredded cheddar cheese")

        for (q in items) {
            val res = adapter.searchProducts(q)
            println("==================================================")
            println("QUERY: '$q'")
            when (res) {
                is com.example.chatgpt_mealplanner.retail.contract.RetailProductSearchResult.Success -> {
                    println("SUCCESS! (${res.productsWithOffers.size} items)")
                    res.productsWithOffers.take(3).forEach {
                        println("  - ${it.product.name} @ ${it.offer.price.toFormattedString()} [${it.offer.availability}]")
                    }
                }
                is com.example.chatgpt_mealplanner.retail.contract.RetailProductSearchResult.PartialSuccess -> {
                    println("PARTIAL SUCCESS! (${res.productsWithOffers.size} items)")
                    res.productsWithOffers.take(3).forEach {
                        println("  - ${it.product.name} @ ${it.offer.price.toFormattedString()} [${it.offer.availability}]")
                    }
                }
                is com.example.chatgpt_mealplanner.retail.contract.RetailProductSearchResult.Unavailable -> {
                    println("UNAVAILABLE: ${res.reason}")
                }
            }
        }
    }
}
