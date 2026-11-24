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

class IngredientKitMatchTest {
    @Test fun kitNameInRecipeMatchesStoredKit() {
        assertTrue(IngredientNameMatcher.matchesKit("Chicken risotto", "Chicken risotto seasoning kit (Schwartz sachet)"))
        assertTrue(IngredientNameMatcher.matchesKit("Tandoori wrap kit", "Tandoori wrap kit"))
    }
    @Test fun differentKitsStayApart() {
        assertFalse(IngredientNameMatcher.matchesKit("Risotto", "Chicken risotto seasoning kit"))
        assertFalse(IngredientNameMatcher.matchesKit("Chicken risotto", "Mushroom risotto kit"))
    }

    @Test fun trimmedCutsMatchThePackName() {
        assertTrue(IngredientNameMatcher.matches("boneless skinless chicken breast, diced", "diced chicken breast"))
        assertTrue(IngredientNameMatcher.matches("Boneless, skinless chicken breasts", "Chicken breast"))
        assertTrue(IngredientNameMatcher.matches("lean beef mince", "beef mince"))
        assertTrue(IngredientNameMatcher.matches("thinly sliced onions", "onion"))
    }

    @Test fun skinlessDicedChickenMatchesDicedChicken() {
        assertTrue(IngredientNameMatcher.matches("skinless diced chicken", "diced chicken"))
        assertTrue(IngredientNameMatcher.matches("diced chicken", "skinless diced chicken"))
        assertTrue(IngredientNameMatcher.matches("Skinless, boneless chicken", "chicken"))
    }

    @Test fun differentCutsOrFormsStayApart() {
        assertFalse(IngredientNameMatcher.matches("chicken breast", "chicken thigh"))
        assertFalse(IngredientNameMatcher.matches("boneless chicken breast", "chicken stock"))
    }

    @Test fun aCombinedPackIsEachOfItsPieces() {
        assertTrue(IngredientNameMatcher.matches("Chicken drumsticks and thighs", "chicken thighs"))
        assertTrue(IngredientNameMatcher.matches("Chicken drumsticks and thighs", "chicken drumsticks"))
        assertTrue(IngredientNameMatcher.matches("chicken thighs, skin on", "Chicken drumsticks and thighs"))
        assertTrue(IngredientNameMatcher.matches("Chicken drumsticks & thighs", "bone-in chicken drumsticks and thighs"))
    }

    @Test fun andInOtherNamesDoesNotSplitThem() {
        assertFalse(IngredientNameMatcher.matches("salt and pepper", "pepper"))
        assertFalse(IngredientNameMatcher.matches("fish and chips", "chips"))
        assertFalse(IngredientNameMatcher.matches("Chicken drumsticks and thighs", "chicken breast"))
    }

    @Test fun accentsDoNotSplitWords() {
        assertTrue(IngredientNameMatcher.matches("Sliced green jalapeños", "sliced green jalapenos"))
        assertTrue(IngredientNameMatcher.matches("jalapeño", "Jalapeno"))
    }

    @Test fun tasteAndTextureWordsDoNotStopAMatch() {
        assertTrue(IngredientNameMatcher.matches("Cheddar cheese", "Mild Cheddar cheese"))
        assertTrue(IngredientNameMatcher.matches("Sweet potatoes", "Creamy, fluffy sweet potatoes"))
        assertTrue(IngredientNameMatcher.matches("Butter", "Free range butter"))
    }

    @Test fun wordsThatNameADifferentProductStillStopAMatch() {
        assertFalse(IngredientNameMatcher.matches("Cheese", "Soft cheese"))
        assertFalse(IngredientNameMatcher.matches("Flour", "Strong flour"))
        assertFalse(IngredientNameMatcher.matches("Syrup", "Golden syrup"))
        assertFalse(IngredientNameMatcher.matches("Potatoes", "Sweet potatoes"))
        assertFalse(IngredientNameMatcher.matches("Chicken", "Chicken stock"))
    }

    @Test fun aBurgerBunIsTheBunThatWasBought() {
        assertTrue(IngredientNameMatcher.matches("Briosh buns", "Briosh burger buns"))
        assertTrue(IngredientNameMatcher.matches("Rolls", "Hot dog rolls"))
        // Only for breads: a burger is not the beef it is made of.
        assertFalse(IngredientNameMatcher.matches("Beef", "Beef burger"))
    }
}
