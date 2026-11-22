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

    @Test fun withNothingLearnedDepartmentsFollowATypicalWalk() {
        val plan = StoreOrder.plan(listOf("Milk", "Bananas", "Chicken breast", "Bread"), { it }, emptyMap())
        assertEquals(
            listOf(Department.PRODUCE, Department.BAKERY, Department.MEAT_FISH, Department.DAIRY_EGGS),
            plan.map { it.department }
        )
    }

    @Test fun aDepartmentSitsWhereItsLearnedItemsAverage_andNewItemsJoinIt() {
        // This shop puts dairy first: milk was ticked at the very start.
        val order = mapOf(StoreOrder.keyOf("Milk") to 0.0, StoreOrder.keyOf("Bananas") to 0.8)
        val plan = StoreOrder.plan(listOf("Bananas", "Cheddar cheese", "Milk", "Apples"), { it }, order)
        assertEquals(listOf(Department.DAIRY_EGGS, Department.PRODUCE), plan.map { it.department })
        // Cheddar was never ticked here, but it is placed with the dairy; within produce, learned bananas come before apples.
        assertEquals(listOf("Milk", "Cheddar cheese"), plan[0].items)
        assertEquals(listOf("Bananas", "Apples"), plan[1].items)
    }

    @Test
    fun aDepartmentFixedByHandWinsOverTheAutomaticGuess() {
        val items = listOf("Cornitos nachos", "Milk")
        val auto = StoreOrder.plan(items, { it }, emptyMap())
        assertEquals(Department.SNACKS, auto.first { "Cornitos nachos" in it.items }.department)

        val fixed = StoreOrder.plan(items, { it }, emptyMap(), mapOf(StoreOrder.keyOf("Cornitos nachos") to Department.CUPBOARD))
        assertEquals(Department.CUPBOARD, fixed.first { "Cornitos nachos" in it.items }.department)
        assertEquals(Department.DAIRY_EGGS, fixed.first { "Milk" in it.items }.department)
    }
}
