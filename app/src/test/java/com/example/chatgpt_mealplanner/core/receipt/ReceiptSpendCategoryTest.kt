package com.example.chatgpt_mealplanner.core.receipt

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiptSpendCategoryTest {
    private fun cat(text: String) = ReceiptSpendCategory.classifyDescription(text)

    @Test
    fun commonBrandsAreRecognised() {
        assertEquals(ReceiptSpendCategory.FOOD, cat("HOVIS SORT WHIT"))
        assertEquals(ReceiptSpendCategory.FOOD, cat("WARBURTONS TOASTIE"))
        assertEquals(ReceiptSpendCategory.DRINKS, cat("FOUR LOKO PINK"))
        assertEquals(ReceiptSpendCategory.DRINKS, cat("LUCOZADE ALERT"))
        assertEquals(ReceiptSpendCategory.DRINKS, cat("RED BULL 250ML"))
    }

    @Test
    fun wordsAreMatchedFromTheStartOfAWord() {
        // "ham" is inside SHAMPOO, "roll" style words are inside others: none of these are food.
        assertEquals(ReceiptSpendCategory.PERSONAL_CARE, cat("DOVE SHAMPOO"))
        assertEquals(ReceiptSpendCategory.PERSONAL_CARE, cat("SHAMPOO 400ML"))
        assertEquals(ReceiptSpendCategory.HOUSEHOLD_CLEANING, cat("TOILET ROLL 9 PACK"))
        assertEquals(ReceiptSpendCategory.FOOD, cat("ASDA HAM"))
        assertEquals(ReceiptSpendCategory.FOOD, cat("HAMBURGER BUNS"))
    }

    @Test
    fun foodWithADrinkWordStaysFood_andUnknownStaysUncategorised() {
        assertEquals(ReceiptSpendCategory.FOOD, cat("CHOCOLATE MILK"))
        assertEquals(ReceiptSpendCategory.UNCATEGORISED, cat("ZZZ WIDGET"))
        assertEquals(ReceiptSpendCategory.DRINKS, cat("GORDONS GIN 70CL"))
        assertEquals(ReceiptSpendCategory.UNCATEGORISED, cat("ALERT PLUG"))
        assertEquals(ReceiptSpendCategory.UNCATEGORISED, cat("GINGER ROOT"))
    }
}
