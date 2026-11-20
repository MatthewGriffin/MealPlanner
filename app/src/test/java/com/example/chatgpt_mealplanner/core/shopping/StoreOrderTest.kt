package com.example.chatgpt_mealplanner.core.shopping

import org.junit.Assert.assertEquals
import org.junit.Test

class StoreOrderTest {
    @Test fun ranksRunFromZeroToOne_andALoneItemIsInTheMiddle() {
        assertEquals(mapOf("a" to 0.0, "b" to 0.5, "c" to 1.0), StoreOrder.ranksOf(listOf("a", "b", "c")))
        assertEquals(mapOf("a" to 0.5), StoreOrder.ranksOf(listOf("a")))
        assertEquals(emptyMap<String, Double>(), StoreOrder.ranksOf(emptyList()))
    }

    @Test fun anItemTickedTwiceCountsWhereItWasFirstTicked() {
        assertEquals(mapOf("a" to 0.0, "b" to 1.0), StoreOrder.ranksOf(listOf("a", "b", "a")))
    }

    @Test fun mergeAveragesTheTrips() = assertEquals(0.25, StoreOrder.mergeRank(0.5, 1, 0.0), 1e-9)

    @Test fun learnedItemsComeFirstInShopOrder_andUnknownOnesFollowInTheirOwnOrder() {
        val order = mapOf(StoreOrder.keyOf("Milk") to 0.9, StoreOrder.keyOf("Bread") to 0.1)
        val sorted = StoreOrder.sort(listOf("Cheese", "Milk", "Eggs", "Bread"), { it }, order)
        assertEquals(listOf("Bread", "Milk", "Cheese", "Eggs"), sorted)
    }

    @Test fun nothingLearnedLeavesTheListAlone() {
        assertEquals(listOf("b", "a"), StoreOrder.sort(listOf("b", "a"), { it }, emptyMap()))
    }

    @Test fun differentWordingsOfOneItemShareAKey() {
        assertEquals(StoreOrder.keyOf("Diced chicken breast"), StoreOrder.keyOf("chicken breast, diced"))
    }
}
