package com.example.chatgpt_mealplanner.backend.retail

import com.example.chatgpt_mealplanner.retail.contract.Money
import com.example.chatgpt_mealplanner.retail.contract.RetailAvailability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IcelandProductParserTest {

    private val parser = IcelandProductParser()

    @Test
    fun parseBroccoliFixture_returnsValidProductAndOffer() {
        val html = loadFixture("iceland_broccoli.html")
        val records = parser.parseHtmlPage(html)

        assertEquals(1, records.size)
        val rec = records.first()
        val prod = rec.productWithOffer.product
        val offer = rec.productWithOffer.offer

        assertEquals("ice_broc_1kg", prod.retailerProductId)
        assertEquals("Iceland Frozen Broccoli Florets 1kg", prod.name)
        assertEquals(Money(180L), offer.price) // £1.80
        assertEquals(RetailAvailability.IN_STOCK, offer.availability)
        assertEquals(com.example.chatgpt_mealplanner.retail.contract.RetailPriceScope.ONLINE, offer.priceScope)
        assertEquals(null, offer.storeLocationId)
    }

    @Test
    fun parseBakedBeansFixture_extractsMultipackAndSalePromotion() {
        val html = loadFixture("iceland_baked_beans.html")
        val records = parser.parseHtmlPage(html)

        assertEquals(1, records.size)
        val prod = records.first().productWithOffer.product
        assertEquals(4, prod.packCount)
        assertEquals(415.0, prod.packSize)
        assertEquals("G", prod.packSizeUnit)
    }

    @Test
    fun parseOutOfStockFixture_mapsAvailabilityToOutOfStock() {
        val html = loadFixture("iceland_out_of_stock.html")
        val records = parser.parseHtmlPage(html)

        assertEquals(1, records.size)
        val offer = records.first().productWithOffer.offer
        assertEquals(RetailAvailability.OUT_OF_STOCK, offer.availability)
    }

    @Test
    fun parsePriceConflictFixture_emitsWarning_andUsesJsonLdPrice() {
        val html = loadFixture("iceland_conflict_price.html")
        val records = parser.parseHtmlPage(html)

        assertEquals(1, records.size)
        val rec = records.first()
        assertTrue(rec.warnings.any { it.contains("Price conflict detected") })
        assertEquals(Money(600L), rec.productWithOffer.offer.price) // £6.00 JSON-LD price
    }

    @Test
    fun genericBasketButtonDoesNotProveEveryProductIsInStock() {
        val html = """
            <html>
              <script type="application/ld+json">
                {
                  "@context": "https://schema.org/",
                  "@type": "Product",
                  "name": "Iceland Product With Unknown Stock",
                  "sku": "unknown-stock-1",
                  "offers": {
                    "@type": "Offer",
                    "priceCurrency": "GBP",
                    "price": "1.25"
                  }
                }
              </script>
              <body><button>Add to Basket</button></body>
            </html>
        """.trimIndent()

        val records = parser.parseHtmlPage(html)

        assertEquals(1, records.size)
        assertEquals(RetailAvailability.UNKNOWN, records.single().productWithOffer.offer.availability)
        assertEquals(
            com.example.chatgpt_mealplanner.retail.contract.RetailPriceScope.ONLINE,
            records.single().productWithOffer.offer.priceScope
        )
        assertEquals(null, records.single().productWithOffer.offer.storeLocationId)
    }

    @Test
    fun parseJsonLdSupportsArraysGraphsAndWrappedItemLists() {
        val html = """
            <html><head>
              <script type="application/ld+json">
                [
                  {
                    "@type": "BreadcrumbList",
                    "itemListElement": []
                  },
                  {
                    "@type": "ItemList",
                    "itemListElement": [
                      {
                        "@type": "ListItem",
                        "item": {
                          "@type": "Product",
                          "name": "Milk in array list",
                          "sku": "milk-array",
                          "offers": {"@type": "Offer", "price": "1.99", "priceCurrency": "GBP"}
                        }
                      }
                    ]
                  }
                ]
              </script>
              <script type="application/ld+json">
                {
                  "@context": "https://schema.org",
                  "@graph": [
                    {
                      "@type": ["Product"],
                      "name": "Bread in graph",
                      "sku": "bread-graph",
                      "offers": {"@type": "Offer", "price": "1.25", "priceCurrency": "GBP"}
                    },
                    {
                      "@type": "Product",
                      "name": "Duplicate milk card",
                      "sku": "milk-array",
                      "offers": {"@type": "Offer", "price": "1.99", "priceCurrency": "GBP"}
                    }
                  ]
                }
              </script>
            </head><body></body></html>
        """.trimIndent()

        val records = parser.parseHtmlPage(html)

        assertEquals(2, records.size)
        assertEquals(setOf("milk-array", "bread-graph"), records.map { it.productWithOffer.product.retailerProductId }.toSet())
        assertEquals(setOf(Money(199L), Money(125L)), records.map { it.productWithOffer.offer.price }.toSet())
    }

    @Test
    fun parseAlgoliaResponseRequiresProductIdNameAndPrice() {
        val response = """
            {
              "hits": [
                {
                  "objectID": "iceland-123",
                  "name": "Iceland Frozen Peas 900g",
                  "brand": "Iceland",
                  "price": "1.50",
                  "availability": "InStock"
                },
                {
                  "objectID": "missing-price",
                  "name": "Product without a price"
                },
                {
                  "name": "Product without a stable ID",
                  "price": "2.00"
                }
              ]
            }
        """.trimIndent()

        val records = parser.parseAlgoliaResponse(response)

        assertEquals(1, records.size)
        val product = records.single().productWithOffer.product
        val offer = records.single().productWithOffer.offer
        assertEquals("iceland-123", product.retailerProductId)
        assertEquals("Iceland Frozen Peas 900g", product.name)
        assertEquals(Money(150L), offer.price)
        assertEquals(RetailAvailability.IN_STOCK, offer.availability)
        assertEquals(com.example.chatgpt_mealplanner.retail.contract.RetailPriceScope.ONLINE, offer.priceScope)
        assertEquals(null, offer.storeLocationId)
    }

    @Test
    fun parsePriceString_handlesPoundsAndPenceCorrectly() {
        assertEquals(Money(100L), parser.parsePriceString("£1.00"))
        assertEquals(Money(125L), parser.parsePriceString("£1.25"))
        assertEquals(Money(199L), parser.parsePriceString("£1.99"))
        assertEquals(Money(10L), parser.parsePriceString("£0.10"))
        assertEquals(Money(69L), parser.parsePriceString("69p"))
        assertEquals(Money(25L), parser.parsePriceString("25p"))
    }

    @Test
    fun parsePriceString_rejectsInvalidNegativeAndOverflowValues() {
        assertEquals(null, parser.parsePriceString(""))
        assertEquals(null, parser.parsePriceString("not a price"))
        assertEquals(null, parser.parsePriceString("£-1.00"))
        assertEquals(null, parser.parsePriceString("999999999999999999999999999999999.99"))
    }

    private fun loadFixture(filename: String): String {
        val inputStream = javaClass.classLoader.getResourceAsStream("fixtures/iceland/$filename")
            ?: throw IllegalArgumentException("Fixture $filename not found in test resources.")
        return inputStream.bufferedReader().use { it.readText() }
    }
}
