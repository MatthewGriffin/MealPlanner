package com.example.chatgpt_mealplanner.retail.contract

import kotlin.test.Test
import kotlin.test.assertEquals

class MoneyTest {

    @Test
    fun moneyArithmetic_exactMinorUnits_noFloatingPointErrors() {
        val tenPence = Money(10L)
        val twentyPence = Money(20L)
        val thirtyPence = tenPence + twentyPence

        assertEquals(30L, thirtyPence.amountMinor)
        assertEquals("£0.30", thirtyPence.toFormattedString())
    }

    @Test
    fun moneyFormatting_handlesPoundsAndPenceCorrectly() {
        val money = Money(138L) // £1.38
        assertEquals("£1.38", money.toFormattedString())

        val zero = Money.zero()
        assertEquals("£0.00", zero.toFormattedString())
    }
}
