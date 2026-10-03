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
    fun parsePriceString_handlesPoundsAndPenceCorrectly() {
        assertEquals(Money(100L), parser.parsePriceString("£1.00"))
        assertEquals(Money(125L), parser.parsePriceString("£1.25"))
        assertEquals(Money(69L), parser.parsePriceString("69p"))
        assertEquals(Money(25L), parser.parsePriceString("25p"))
    }

    private fun loadFixture(filename: String): String {
        val inputStream = javaClass.classLoader.getResourceAsStream("fixtures/iceland/$filename")
            ?: throw IllegalArgumentException("Fixture $filename not found in test resources.")
        return inputStream.bufferedReader().use { it.readText() }
    }
}
