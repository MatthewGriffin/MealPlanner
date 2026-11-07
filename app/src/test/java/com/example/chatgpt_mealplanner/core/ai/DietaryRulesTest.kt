package com.example.chatgpt_mealplanner.core.ai

import com.example.chatgpt_mealplanner.contract.UserPreferencesSummaryDto
import com.example.chatgpt_mealplanner.core.ai.validation.DietaryRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DietaryRulesTest {

    private fun allergy(name: String) = UserPreferencesSummaryDto(allergies = listOf(name))
    private fun diet(name: String) = UserPreferencesSummaryDto(diets = listOf(name))
    private fun hits(ingredient: String, preferences: UserPreferencesSummaryDto) =
        DietaryRules.conflicts(ingredient, preferences).isNotEmpty()

    private fun assertCaught(preferences: UserPreferencesSummaryDto, vararg ingredients: String) =
        ingredients.forEach { assertTrue("$it should conflict", hits(it, preferences)) }

    private fun assertAllowed(preferences: UserPreferencesSummaryDto, vararg ingredients: String) =
        ingredients.forEach { assertFalse("$it should not conflict", hits(it, preferences)) }

    private fun recipe(title: String = "Baked Rice", servings: Int = 2, vararg ingredients: Triple<String, Double, String>) =
        com.example.chatgpt_mealplanner.contract.MealProposal(
            title = title,
            servings = servings,
            ingredients = ingredients.map {
                com.example.chatgpt_mealplanner.contract.ProposedIngredient(displayName = it.first, quantity = it.second, unit = it.third)
            },
            reasoningSummary = ""
        )

    private val rice = Triple("Basmati rice", 150.0, "GRAM")

    private fun queries(method: String, recipe: com.example.chatgpt_mealplanner.contract.MealProposal = recipe(ingredients = arrayOf(rice))) =
        com.example.chatgpt_mealplanner.core.ai.validation.RecipeOfferChecks.queries(recipe, method).joinToString(" ")

    @Test
    fun realMistakesFromGemini_areQueried() {
        assertTrue(queries("Boil the rice over medium heat (160°C).").contains("hob heat"))
        assertTrue(queries("Boil the rice over a medium-high heat 180°C.").contains("hob heat"))
        assertTrue(queries("Boil the rice with 4g ground cumin.").contains("spoons"))
        assertTrue(queries("Boil the rice in salted water.").contains("uses salt"))
        assertTrue(queries("Boil the rice, then add a pinch of pepper.").contains("uses pepper"))
        assertTrue(queries("Boil the rice.", recipe("Rice or Noodles", ingredients = arrayOf(rice))).contains("Name one dish"))
        assertTrue(queries("Roast the chicken.", recipe("Roast Chicken", ingredients = arrayOf(Triple("Chicken thighs", 600.0, "GRAM")))).contains("a lot for 2"))
        assertTrue(queries("Fry the chicken.", recipe("Chicken", ingredients = arrayOf(Triple("Chicken thighs", 50.0, "GRAM")))).contains("very little for 2"))
        assertTrue(queries("Melt 10 gram butter over the rice.").contains("spoons"))
        assertTrue(queries("Boil the rice with 2 grams ground cumin.").contains("spoons"))
        assertTrue(queries("Crack 4 unit eggs over the rice.").contains("'4 eggs'"))
    }

    @Test
    fun otherRecipeFieldsSentAsIngredients_areRefused() {
        val step = "Pat the chicken dry."
        fun problems(vararg names: String) = com.example.chatgpt_mealplanner.core.ai.validation.RecipeOfferChecks.problems(
            com.example.chatgpt_mealplanner.contract.MealProposal(
                title = "Rice",
                mealType = "DINNER",
                steps = listOf(com.example.chatgpt_mealplanner.contract.RecipeStep(order = 1, instruction = step)),
                ingredients = names.map { com.example.chatgpt_mealplanner.contract.ProposedIngredient(displayName = it, quantity = 1.0, unit = "UNIT") },
                reasoningSummary = "Uses up your rice."
            )
        )

        assertEquals(3, problems("DINNER", step, "Uses up your rice.").count { it.contains("isn't an ingredient") })
        assertEquals(emptyList<String>(), problems("Rice", "Dinner rolls", "Chicken"))
    }

    @Test
    fun amountsThatArentMainPortions_areNotCalledTooSmall() {
        fun smallQuery(ingredient: Triple<String, Double, String>) = queries("Fry the ${ingredient.first}.", recipe("Dish", ingredients = arrayOf(ingredient)))
        assertFalse(smallQuery(Triple("Pancetta", 100.0, "GRAM")).contains("very little"))
        assertFalse(smallQuery(Triple("Whole chicken", 1.0, "UNIT")).contains("very little"))
        assertFalse(smallQuery(Triple("Chicken stock", 30.0, "MILLILITRE")).contains("very little"))
    }

    @Test
    fun goodRecipeWording_isNotQueried() {
        assertEquals("", queries("Preheat the oven to 200°C (180°C fan). Bake the rice for 20 minutes over a medium heat until tender."))
        assertEquals("", queries("Bake the rice with 150g stock until a thermometer reads 75°C in the centre."))
        assertEquals(
            "",
            queries(
                "Roast the chicken.",
                recipe("Roast Chicken", ingredients = arrayOf(Triple("Chicken thighs", 400.0, "GRAM")))
            )
        )
        assertEquals(
            "",
            queries(
                "Boil the rice in salted water.",
                recipe(ingredients = arrayOf(rice, Triple("Fine sea salt", 3.0, "GRAM")))
            )
        )
    }

    @Test
    fun milkAllergy() {
        assertCaught(allergy("Milk"), "Double cream", "mature cheddar", "Butter", "Greek yoghurt", "whole milk", "Ghee")
        assertAllowed(allergy("Milk"), "peanut butter", "butter beans", "coconut milk", "oat milk", "cream of tartar", "vegan butter", "butternut squash", "dairy-free spread")
    }

    @Test
    fun nutAllergies_areSeparate_andSimilarNamesAreNotCaught() {
        assertCaught(allergy("Tree nuts"), "Flaked almonds", "walnuts", "mixed nuts", "pesto", "pine nuts")
        assertAllowed(allergy("Tree nuts"), "nutmeg", "butternut squash", "coconut milk", "peanut butter")
        assertCaught(allergy("Peanuts"), "peanut butter", "Roasted peanuts", "satay sauce")
        assertAllowed(allergy("Peanuts"), "almonds", "nutmeg")
    }

    @Test
    fun glutenAllergy() {
        assertCaught(allergy("Gluten"), "plain flour", "spaghetti", "soy sauce", "breadcrumbs", "egg noodles", "porridge oats")
        assertAllowed(allergy("Gluten"), "cornflour", "rice noodles", "gluten-free pasta", "gram flour", "buckwheat", "tamari", "potatoes")
    }

    @Test
    fun otherListedAllergies() {
        assertCaught(allergy("Eggs"), "free-range eggs", "mayonnaise")
        assertAllowed(allergy("Eggs"), "eggplant", "vegan mayonnaise")
        assertCaught(allergy("Fish"), "smoked salmon", "anchovies", "Worcestershire sauce", "tuna steak")
        assertCaught(allergy("Crustaceans"), "king prawns")
        assertCaught(allergy("Molluscs"), "mussels", "oyster sauce")
        assertCaught(allergy("Sesame"), "tahini", "hummus", "sesame oil")
        assertCaught(allergy("Soya"), "tofu", "soy sauce", "soya milk")
        assertCaught(allergy("Mustard"), "Dijon mustard")
        assertCaught(allergy("Celery"), "celery sticks", "celeriac")
        assertCaught(allergy("Sulphites"), "white wine")
    }

    @Test
    fun allergyListCoversEveryRule() {
        assertEquals(14, DietaryRules.ALLERGIES.size)
        val examples = listOf("celery", "prawn", "egg", "cod", "flour", "lupin", "milk", "mussel", "mustard", "peanut", "sesame", "tofu", "wine", "almond")
        DietaryRules.ALLERGIES.forEach { name ->
            assertTrue(name, examples.any { hits(it, allergy(name)) })
            assertFalse(name, hits("water", allergy(name)))
        }
    }

    @Test
    fun typedAllergy_matchesAsAWord() {
        assertCaught(allergy("kiwi"), "kiwi fruit", "Kiwis")
        assertAllowed(allergy("kiwi"), "apples")
        assertAllowed(allergy("  "), "apples")
    }

    @Test
    fun vegetarianAndVegan() {
        assertCaught(diet("Vegetarian"), "chicken breast", "beef mince", "bacon", "prawns", "salmon", "gelatine")
        assertAllowed(diet("Vegetarian"), "cheddar", "eggs", "Quorn mince", "vegan sausages", "meat-free mince", "vegetable stock", "hamburger buns")
        assertCaught(diet("Vegan"), "milk", "eggs", "honey", "chicken stock", "butter")
        assertAllowed(diet("Vegan"), "oat milk", "tofu", "vegan cheese", "chickpeas")
    }

    @Test
    fun otherDiets() {
        assertCaught(diet("Pescatarian"), "lamb chops")
        assertAllowed(diet("Pescatarian"), "cod fillet", "prawns")
        assertCaught(diet("Gluten-free"), "wholemeal bread")
        assertCaught(diet("Dairy-free"), "ricotta")
        assertCaught(diet("Halal"), "smoked bacon", "red wine")
        assertAllowed(diet("Halal"), "lamb", "white rice")
        assertCaught(diet("Kosher"), "gammon", "crab")
    }

    @Test
    fun everyDietInTheListHasRules() {
        DietaryRules.DIETS.forEach { name ->
            assertTrue(name, listOf("bacon", "milk", "flour").any { hits(it, diet(name)) })
        }
    }

    @Test
    fun conflictMessageNamesTheIngredientAndTheRule() {
        assertEquals(
            listOf("'Double cream' conflicts with the Milk allergy.", "'Double cream' conflicts with the Vegan diet."),
            DietaryRules.conflicts("Double cream", UserPreferencesSummaryDto(allergies = listOf("Milk"), diets = listOf("Vegan")))
        )
    }

    @Test
    fun noPreferences_meansNoConflicts() {
        assertAllowed(UserPreferencesSummaryDto(), "peanuts", "bacon", "double cream")
    }

    @Test
    fun dislikes_matchAsWords() {
        val preferences = UserPreferencesSummaryDto(dislikes = listOf("coriander"))
        assertTrue(DietaryRules.isDisliked("Fresh coriander", preferences))
        assertFalse(DietaryRules.isDisliked("cumin", preferences))
    }
}
