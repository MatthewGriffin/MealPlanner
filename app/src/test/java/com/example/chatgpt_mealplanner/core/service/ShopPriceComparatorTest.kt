package com.example.chatgpt_mealplanner.core.service

import com.example.chatgpt_mealplanner.core.database.entity.PurchasePriceObservationEntity
import com.example.chatgpt_mealplanner.core.database.entity.PurchasePriceWithStore
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShopPriceComparatorTest {

    private fun price(
        store: String,
        priceMinorUnits: Long,
        packQuantity: Double? = null,
        packUnit: String? = "GRAM",
        packCount: Int? = null,
        purchasedAt: Long = 1_000L,
        productName: String = "Cheese",
        canonicalName: String? = null
    ) = PurchasePriceWithStore(
        observation = PurchasePriceObservationEntity(
            id = "$store-$purchasedAt-$priceMinorUnits",
            storeId = store,
            barcode = null,
            productName = productName,
            brand = null,
            packQuantity = packQuantity,
            packUnit = packUnit,
            packCount = packCount,
            priceMinorUnits = priceMinorUnits,
            purchasedAt = purchasedAt,
            canonicalName = canonicalName
        ),
        storeName = store
    )

    private val iceland = price("Iceland", 500, packQuantity = 500.0)
    private val asda = price("Asda", 450, packQuantity = 450.0)

    private fun best(neededGrams: Double, vararg prices: PurchasePriceWithStore): ShopPriceOption =
        ShopPriceComparator.compare(neededGrams, QuantityUnit.GRAM, prices.toList()).first { it.isBest }

    @Test
    fun smallerPackWins_whenOnePackOfEachCoversTheNeed() {
        val result = best(400.0, iceland, asda)

        assertEquals("Asda", result.price.storeName)
        assertEquals(1, result.packsToBuy)
        assertEquals(450L, result.totalCostMinorUnits)
    }

    @Test
    fun largerPackWins_whenSmallerPackWouldNeedTwo() {
        val result = best(480.0, iceland, asda)

        assertEquals("Iceland", result.price.storeName)
        assertEquals(500L, result.totalCostMinorUnits)
    }

    @Test
    fun wholePacksAreCounted_whenBothShopsNeedTwo() {
        val result = best(900.0, iceland, asda)

        assertEquals("Asda", result.price.storeName)
        assertEquals(2, result.packsToBuy)
        assertEquals(900L, result.totalCostMinorUnits)
        assertEquals(900.0, result.purchasedQuantity!!, 0.001)
    }

    @Test
    fun lowestSpendBeatsBetterValue_whenBothCoverTheNeed() {
        val small = price("Small", 300, packQuantity = 250.0)
        val bigValue = price("Big", 400, packQuantity = 500.0)

        assertEquals("Small", best(100.0, small, bigValue).price.storeName)
    }

    @Test
    fun betterValueBreaksTie_whenTotalSpendIsEqual() {
        val small = price("Small", 400, packQuantity = 250.0)
        val large = price("Large", 400, packQuantity = 500.0)

        assertEquals("Large", best(100.0, small, large).price.storeName)
    }

    @Test
    fun kilogramsAndGramsAreCompared() {
        val kilo = price("Kilo", 800, packQuantity = 1.0, packUnit = "KILOGRAM")
        val needInKilos = ShopPriceComparator.compare(0.8, QuantityUnit.KILOGRAM, listOf(kilo, iceland))

        assertEquals("Kilo", needInKilos.first().price.storeName)
        assertEquals(1, needInKilos.first().packsToBuy)
    }

    @Test
    fun multipackContentsCountTowardsTheNeed() {
        val multipack = price("Multi", 600, packQuantity = 250.0, packCount = 4)

        val result = best(900.0, multipack)

        assertEquals(1, result.packsToBuy)
        assertEquals(1000.0, result.purchasedQuantity!!, 0.001)
    }

    @Test
    fun exactFitNeedsOnePack_despiteFloatingPointArithmetic() {
        val pack = price("Exact", 100, packQuantity = 0.1, packUnit = "KILOGRAM")

        assertEquals(1, ShopPriceComparator.compare(100.0, QuantityUnit.GRAM, listOf(pack)).first().packsToBuy)
    }

    @Test
    fun unknownPackSize_isListedButNeverBest() {
        val unknown = price("Unknown", 1, packQuantity = null, packUnit = null)

        val result = ShopPriceComparator.compare(400.0, QuantityUnit.GRAM, listOf(unknown, asda))

        assertEquals(listOf("Asda", "Unknown"), result.map { it.price.storeName })
        assertTrue(result[0].isBest)
        assertFalse(result[1].isBest)
        assertNull(result[1].totalCostMinorUnits)
    }

    @Test
    fun differentKindsOfUnit_areNotCompared() {
        val tin = price("Tin", 100, packQuantity = 1.0, packUnit = "TIN")

        val result = ShopPriceComparator.compare(400.0, QuantityUnit.GRAM, listOf(tin))

        assertFalse(result.single().isBest)
        assertNull(result.single().packsToBuy)
    }

    @Test
    fun noKnownAmount_marksNothingBest() {
        val result = ShopPriceComparator.compare(null, null, listOf(iceland, asda))

        assertEquals(2, result.size)
        assertTrue(result.none { it.isBest })
    }

    @Test
    fun newerPriceReplacesOlderPriceForTheSameProduct() {
        val old = price("Asda", 100, packQuantity = 450.0, purchasedAt = 1L)
        val current = price("Asda", 600, packQuantity = 450.0, purchasedAt = 2L)

        val result = ShopPriceComparator.compare(400.0, QuantityUnit.GRAM, listOf(old, current, iceland))

        assertEquals("Iceland", result.first { it.isBest }.price.storeName)
        assertEquals(600L, result.single { it.price.storeName == "Asda" }.totalCostMinorUnits)
    }

    @Test
    fun eachShopAppearsOnce_withItsCheapestProduct() {
        val pricey = price("Asda", 900, packQuantity = 500.0, productName = "Premium")
        val cheap = price("Asda", 450, packQuantity = 500.0, productName = "Value")

        val result = ShopPriceComparator.compare(400.0, QuantityUnit.GRAM, listOf(pricey, cheap))

        assertEquals(450L, result.single().totalCostMinorUnits)
    }

    @Test
    fun matching_usesTheProductConcept_acrossDifferentProductNames() {
        val cheddar = price("Asda", 450, productName = "Mature Cheddar 450g", canonicalName = "cheese").observation

        assertTrue(ShopPriceComparator.matchesItem("CHEESE", "Grated cheese", cheddar))
        assertTrue(ShopPriceComparator.matchesItem(null, "Mature cheddar 450G", cheddar))
    }

    @Test
    fun matching_rejectsPartialNames() {
        val chocolate = price("Asda", 150, productName = "Milk Chocolate").observation

        assertFalse(ShopPriceComparator.matchesItem("milk", "Milk", chocolate))
    }
}
