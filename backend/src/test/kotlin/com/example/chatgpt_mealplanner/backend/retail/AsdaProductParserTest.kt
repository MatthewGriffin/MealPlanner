package com.example.chatgpt_mealplanner.backend.retail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AsdaProductParserTest {

    private val parser = AsdaProductParser()

    @Test
    fun parsePriceToMinorPence_convertsCorrectly() {
        assertEquals(200L, parser.parsePriceToMinorPence(kotlinx.serialization.json.JsonPrimitive("2.00")))
        assertEquals(317L, parser.parsePriceToMinorPence(kotlinx.serialization.json.JsonPrimitive("3.17")))
        assertEquals(454L, parser.parsePriceToMinorPence(kotlinx.serialization.json.JsonPrimitive("4.54")))
    }

    @Test
    fun parseAlgoliaResponse_validJson_returnsProductsWithOffers() {
        val json = """
            {
              "results": [
                {
                  "hits": [
                    {
                      "CIN": "4642107",
                      "NAME": "Grated Mature British Cheddar 250g",
                      "BRAND": "ASDA",
                      "PACK_SIZE": "250G",
                      "PRICES": {
                        "EN": {
                          "PRICE": "2.00",
                          "WASPRICE": "2.45"
                        }
                      }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val result = parser.parseAlgoliaResponse(json)
        assertEquals(1, result.productsWithOffers.size)
        val item = result.productsWithOffers.first()
        assertEquals("4642107", item.product.retailerProductId)
        assertEquals("Grated Mature British Cheddar 250g", item.product.name)
        assertEquals("ASDA", item.product.brand)
        assertEquals(200L, item.offer.price.amountMinor)
        assertNotNull(item.offer.promotion)
    }

    @Test
    fun parseAlgoliaResponse_malformedHit_skippedGracefully() {
        val json = """
            {
              "results": [
                {
                  "hits": [
                    { "INVALID": true },
                    {
                      "CIN": "6793874",
                      "NAME": "Valid Item",
                      "PRICES": { "EN": { "PRICE": "1.50" } }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val result = parser.parseAlgoliaResponse(json)
        assertEquals(1, result.productsWithOffers.size)
        assertEquals(2, result.hitsReceived)
        assertEquals(1, result.hitsAccepted)
        assertEquals(1, result.hitsRejected)
    }

    @Test
    fun parseAlgoliaResponse_objectIdFallback_whenCinMissing() {
        val json = """
            {
              "results": [
                {
                  "hits": [
                    {
                      "objectID": "9988776",
                      "NAME": "Fallback Item",
                      "PRICES": { "EN": { "PRICE": "3.00" } }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val result = parser.parseAlgoliaResponse(json)
        assertEquals(1, result.productsWithOffers.size)
        assertEquals("9988776", result.productsWithOffers.first().product.retailerProductId)
    }

    @Test
    fun parseAlgoliaResponse_multibuyPromotion_parsedCorrectly() {
        val json = """
            {
              "results": [
                {
                  "hits": [
                    {
                      "CIN": "112233",
                      "NAME": "Promo Item",
                      "PRICES": { "EN": { "PRICE": "3.00" } },
                      "PROMOS": { "EN": ["Any 2 for £5"] }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val result = parser.parseAlgoliaResponse(json)
        assertEquals(1, result.productsWithOffers.size)
        val offer = result.productsWithOffers.first().offer
        assertNotNull(offer.promotion)
        assertTrue(offer.promotion is com.example.chatgpt_mealplanner.retail.contract.RetailPromotion.Multibuy)
    }
}
