package com.example.chatgpt_mealplanner.core.shopping

import org.junit.Assert.assertEquals
import org.junit.Test

class StoreRouteTest {
    private val delta = 1e-9

    @Test fun walkingNorthAndEastMovesTheRightWay() {
        val d = DeadReckoning(strideMetres = 1.0)
        d.onStep()                         // no heading yet: ignored
        d.onHeading(0.0)                   // north
        repeat(10) { d.onStep() }
        d.onHeading(Math.PI / 2)           // east
        repeat(5) { d.onStep() }
        assertEquals(5.0, d.position().x, 1e-6)
        assertEquals(10.0, d.position().y, 1e-6)
        d.reset()
        assertEquals(ShopPoint(0.0, 0.0), d.position())
    }

    @Test fun mergingAveragesPositions() {
        val p = StoreRoute.mergePoint(ShopPoint(10.0, 0.0), 1, ShopPoint(20.0, 10.0))
        assertEquals(15.0, p.x, delta); assertEquals(5.0, p.y, delta)
        assertEquals(ShopPoint(1.0, 2.0), StoreRoute.mergePoint(null, 0, ShopPoint(1.0, 2.0)))
    }

    @Test fun theRouteGoesToTheNearestItemNext() {
        val ranks = StoreRoute.rankByRoute(mapOf(
            "far" to ShopPoint(0.0, 40.0), "near" to ShopPoint(0.0, 5.0), "middle" to ShopPoint(0.0, 20.0)
        ))
        assertEquals(mapOf("near" to 0.0, "middle" to 0.5, "far" to 1.0), ranks)
    }

    @Test fun oneItemIsInTheMiddle_andNoneIsEmpty() {
        assertEquals(mapOf("a" to 0.5), StoreRoute.rankByRoute(mapOf("a" to ShopPoint(3.0, 4.0))))
        assertEquals(emptyMap<String, Double>(), StoreRoute.rankByRoute(emptyMap()))
    }
}
