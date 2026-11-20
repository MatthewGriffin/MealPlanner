package com.example.chatgpt_mealplanner.core.util

import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StockRemainderTest {
    @Test fun aGramOfTenIsAScrap() = assertTrue(StockRemainder.isSmallRemainder(10.0, 9.0, QuantityUnit.GRAM))
    @Test fun fiftyGramsOfEightFiftyIsAScrap() = assertTrue(StockRemainder.isSmallRemainder(850.0, 800.0, QuantityUnit.GRAM))
    @Test fun aFifthIsWorthKeeping() = assertFalse(StockRemainder.isSmallRemainder(100.0, 80.0, QuantityUnit.GRAM))
    @Test fun countedThingsAreNeverRoundedUp() = assertFalse(StockRemainder.isSmallRemainder(10.0, 9.0, QuantityUnit.UNIT))
    @Test fun usingEverythingOrNothingIsNotARemainder() {
        assertFalse(StockRemainder.isSmallRemainder(10.0, 10.0, QuantityUnit.GRAM))
        assertFalse(StockRemainder.isSmallRemainder(10.0, 0.0, QuantityUnit.GRAM))
    }
    @Test fun volumesCountToo() = assertTrue(StockRemainder.isSmallRemainder(500.0, 460.0, QuantityUnit.MILLILITRE))
}
