package com.example.chatgpt_mealplanner.core.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IngredientNameMatcherTest {
    @Test fun driedMatchesPlain() = assertTrue(IngredientNameMatcher.matches("Dried oregano", "oregano"))
    @Test fun pluralsMatch() = assertTrue(IngredientNameMatcher.matches("Tomatoes", "tomato"))
    @Test fun wordOrderAndPrepMatch() = assertTrue(IngredientNameMatcher.matches("Onion, finely chopped", "onion"))
    @Test fun stockIsNotChicken() = assertFalse(IngredientNameMatcher.matches("chicken stock", "chicken"))
    @Test fun differentItems() = assertFalse(IngredientNameMatcher.matches("garlic powder", "garlic"))
}

class IngredientNameSynonymTest {
    @Test fun ukUs() {
        assertTrue(IngredientNameMatcher.matches("fresh cilantro", "Coriander"))
        assertTrue(IngredientNameMatcher.matches("zucchini", "courgettes"))
        assertTrue(IngredientNameMatcher.matches("ground beef", "Beef mince"))
        assertTrue(IngredientNameMatcher.matches("green onions", "spring onion"))
        assertTrue(IngredientNameMatcher.matches("ground cumin", "cumin"))
    }
    @Test fun stillDistinct() = assertFalse(IngredientNameMatcher.matches("beef stock", "beef mince"))
}
