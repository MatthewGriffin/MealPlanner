package com.example.chatgpt_mealplanner.core.ai.packet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketTextFilterTest {
    private val packet = """
        Fajita Kit
        Nutrition
        Typical values per 100g
        Energy 1200kJ / 285kcal
        Fat 4.1g
        of which saturates 0.8g
        Salt 2.1g
        Ingredients: wheat flour, water, spices, salt
        Allergy advice: contains wheat
        Instructions
        1. Heat 1 tbsp oil in a pan and fry 400g chicken for 5 minutes.
        2. Add the spice mix and 3 tbsp water, stir and simmer for 3 minutes.
        Warm the tortillas and serve.
        Store in a cool dry place
        5000123456789
        Packed by Foods Ltd, 1 High Street, London SW1A 1AA
        www.example.co.uk
        Please recycle
    """.trimIndent()

    @Test
    fun keepsTheCookingStepsAndDropsTheRest() {
        val out = PacketTextFilter.keepInstructions(packet)
        assertTrue(out.contains("Heat 1 tbsp oil"))
        assertTrue(out.contains("simmer for 3 minutes"))
        assertTrue(out.contains("Warm the tortillas"))
        for (noise in listOf("kcal", "saturates", "wheat flour", "Allergy", "Store in", "5000123456789", "SW1A", "www.", "recycle")) {
            assertFalse("should drop $noise", out.contains(noise, ignoreCase = true))
        }
    }

    @Test
    fun keepsOriginalWhenNothingWouldRemain() {
        assertTrue(PacketTextFilter.keepInstructions("Energy 100kcal").contains("100kcal"))
    }
}
