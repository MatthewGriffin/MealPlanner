package com.example.chatgpt_mealplanner.ui.text

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkedTextTest {

    private fun links(text: String) = webAddressRanges(text).map { text.substring(it) }

    @Test
    fun aRecipeSource_hasItsUrlFound() {
        assertEquals(
            listOf("https://thehappyfoodie.co.uk/recipes/chicken-pot-pie/"),
            links("Based on: The Happy Foodie: https://thehappyfoodie.co.uk/recipes/chicken-pot-pie/")
        )
    }

    @Test
    fun punctuationAroundAUrl_isLeftOut_andPlainTextHasNoLinks() {
        assertEquals(listOf("https://example.com/a"), links("See (https://example.com/a)."))
        assertEquals(listOf("http://a.co/x?y=1", "https://b.co"), links("http://a.co/x?y=1, then https://b.co!"))
        assertEquals(emptyList<String>(), links("1. Boil the rice for 10 min."))
    }
}
