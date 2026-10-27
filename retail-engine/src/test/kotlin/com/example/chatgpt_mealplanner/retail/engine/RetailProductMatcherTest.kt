package com.example.chatgpt_mealplanner.retail.engine

import com.example.chatgpt_mealplanner.retail.contract.MatchConfidence
import com.example.chatgpt_mealplanner.retail.contract.MatchType
import com.example.chatgpt_mealplanner.retail.contract.RetailProduct
import com.example.chatgpt_mealplanner.retail.contract.RetailProductMappingDecision
import com.example.chatgpt_mealplanner.retail.contract.RetailStorageClass
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetailProductMatcherTest {

    @Test
    fun matcher_distinguishesBeansWithSausages_fromBakedBeans() {
        val matcher = RetailProductMatcher()

        val product = RetailProduct(
            retailerId = RetailerId.ICELAND,
            retailerProductId = "p1",
            name = "Iceland Baked Beans with Pork Sausages",
            canonicalIngredientId = "BAKED_BEANS_SAUSAGES",
            storageClass = RetailStorageClass.AMBIENT
        )

        val candidate = matcher.evaluateMatch(
            requirementCanonicalId = "BAKED_BEANS",
            requirementDisplayName = "Baked Beans",
            candidateProduct = product
        )

        assertEquals(MatchType.INCOMPATIBLE, candidate.matchType)
        assertFalse(candidate.isAutoBasketEligible)
    }

    @Test
    fun matcher_acceptsDescriptiveRetailerTitleForGenericEggsRequirement() {
        val matcher = RetailProductMatcher()
        val product = RetailProduct(
            retailerId = RetailerId.ICELAND,
            retailerProductId = "iceland_eggs_6",
            name = "British Lion Free Range Eggs 6 Pack",
            storageClass = RetailStorageClass.FRESH
        )

        val match = matcher.evaluateMatch(null, "Eggs", product)

        assertTrue(match.isAutoBasketEligible, "Descriptive packaging text should not hide an ordinary eggs match")
    }

    @Test
    fun matcher_mincedBeef_matchesRetailerBeefMinceNames_butRejectsPreparedDishes() {
        val matcher = RetailProductMatcher()
        listOf("Beef Mince 500g", "Lean Beef Mince 5% Fat", "Minced Beef 500g").forEachIndexed { index, name ->
            val product = RetailProduct(RetailerId.ASDA, "beef_mince_$index", name, storageClass = RetailStorageClass.FRESH)
            assertTrue(matcher.evaluateMatch(null, "Minced Beef", product).isAutoBasketEligible, "Expected '$name' to match Minced Beef")
        }
        listOf("Beef Burgers", "Beef Meatballs", "Cottage Pie with Beef Mince", "Plant Based Mince").forEachIndexed { index, name ->
            val product = RetailProduct(RetailerId.ASDA, "beef_mince_invalid_$index", name, storageClass = RetailStorageClass.FRESH)
            assertFalse(matcher.evaluateMatch(null, "Minced Beef", product).isAutoBasketEligible, "Expected '$name' not to auto-match Minced Beef")
        }
    }

    @Test
    fun matcher_singleCarrots_requirementRejectsMixedVegetablePack() {
        val matcher = RetailProductMatcher()
        val product = RetailProduct(
            retailerId = RetailerId.ICELAND,
            retailerProductId = "mixed_veg_1",
            name = "Carrots, Sugar Snaps and Baby Corn",
            storageClass = RetailStorageClass.FROZEN
        )

        val match = matcher.evaluateMatch(null, "Carrots", product)

        assertEquals(MatchType.INCOMPATIBLE, match.matchType)
        assertFalse(match.isAutoBasketEligible)
    }

    @Test
    fun matcher_heavyCreamWithChoicePhrase_matchesUKDoubleCream() {
        val matcher = RetailProductMatcher()
        val product = RetailProduct(
            retailerId = RetailerId.ICELAND,
            retailerProductId = "double_cream_300",
            name = "Fresh Double Cream 300ml",
            storageClass = RetailStorageClass.FRESH
        )

        val match = matcher.evaluateMatch(null, "Heavy cream, toppings of choice", product)

        assertTrue(match.isAutoBasketEligible, "UK double cream should satisfy a heavy cream requirement")
    }

    @Test
    fun matcher_heavyCream_acceptsRetailerPunctuationAndWordOrderVariations() {
        val matcher = RetailProductMatcher()
        listOf("Cream, Double 300ml", "Double-Cream 300ml", "Whipping Cream 300ml").forEachIndexed { index, name ->
            val product = RetailProduct(
                retailerId = RetailerId.ASDA,
                retailerProductId = "cream_variant_$index",
                name = name,
                storageClass = RetailStorageClass.FRESH
            )
            assertTrue(
                matcher.evaluateMatch(null, "Heavy cream", product).isAutoBasketEligible,
                "Expected '$name' to satisfy a heavy cream requirement"
            )
        }
    }

    @Test
    fun matcher_roastingPotatoes_acceptsOrdinaryPotatoVarieties_butRejectsPreparedProducts() {
        val matcher = RetailProductMatcher()
        listOf("Maris Piper Potatoes 2kg", "White Potatoes 2kg", "King Edward Potatoes").forEachIndexed { index, name ->
            val product = RetailProduct(RetailerId.ASDA, "roast_potato_$index", name, storageClass = RetailStorageClass.FRESH)
            assertTrue(
                matcher.evaluateMatch(null, "Roasting potatoes", product).isAutoBasketEligible,
                "Expected '$name' to be suitable for roasting"
            )
        }
        listOf("Ready to Roast Potato Wedges", "Potato Crisps", "Potato Salad").forEachIndexed { index, name ->
            val product = RetailProduct(RetailerId.ASDA, "prepared_potato_$index", name, storageClass = RetailStorageClass.AMBIENT)
            assertFalse(
                matcher.evaluateMatch(null, "Roasting potatoes", product).isAutoBasketEligible,
                "Expected prepared product '$name' not to match raw roasting potatoes"
            )
        }
    }

    @Test
    fun matcher_userRejectedMapping_overridesAutomatedMatch() {
        val rejectedKey = UserProductMappingKey(
            retailerId = RetailerId.ASDA.name,
            retailerProductId = "p2",
            canonicalIngredientId = "CHICKEN"
        )
        val userMappings = mapOf(rejectedKey to RetailProductMappingDecision.REJECTED_MATCH)
        val matcher = RetailProductMatcher(userMappings)

        val product = RetailProduct(
            retailerId = RetailerId.ASDA,
            retailerProductId = "p2",
            name = "Asda Fresh Chicken Breast",
            canonicalIngredientId = "CHICKEN",
            storageClass = RetailStorageClass.FRESH
        )

        val candidate = matcher.evaluateMatch(
            requirementCanonicalId = "CHICKEN",
            requirementDisplayName = "Chicken Breast",
            candidateProduct = product
        )

        assertEquals(MatchType.INCOMPATIBLE, candidate.matchType)
        assertFalse(candidate.isAutoBasketEligible)
    }

    @Test
    fun matcher_tinnedTomatoesAndCannedTomatoes_shareSameCanonicalConceptAndAcceptValidProducts() {
        val matcher = RetailProductMatcher()

        val candidateNames = listOf(
            "Chopped Tomatoes 400g",
            "Chopped Tomatoes in Tomato Juice 400g",
            "Peeled Plum Tomatoes 400g",
            "Whole Peeled Tomatoes",
            "Plum Tomatoes"
        )

        for (req in listOf("Tinned Tomatoes", "Canned Tomatoes")) {
            for (name in candidateNames) {
                val product = RetailProduct(
                    retailerId = RetailerId.ASDA,
                    retailerProductId = "t_accept",
                    name = name,
                    storageClass = RetailStorageClass.AMBIENT
                )
                val match = matcher.evaluateMatch(null, req, product)
                assertTrue(match.isAutoBasketEligible, "Expected '$name' to be accepted for requirement '$req'")
            }
        }
    }

    @Test
    fun matcher_tinnedTomatoes_rejectsIncompatibleProducts() {
        val matcher = RetailProductMatcher()

        val rejectedNames = listOf(
            "Passata",
            "Sieved Tomatoes",
            "Tomato Purée",
            "Tomato Paste",
            "Tomato Ketchup",
            "Tomato Soup",
            "Classic Pizza Sauce 400g",
            "Pasta Sauce",
            "Fresh Tomatoes 500g",
            "Cherry Tomatoes"
        )

        for (name in rejectedNames) {
            val product = RetailProduct(
                retailerId = RetailerId.ASDA,
                retailerProductId = "t_reject",
                name = name,
                storageClass = RetailStorageClass.AMBIENT
            )
            val match = matcher.evaluateMatch(null, "Tinned Tomatoes", product)
            assertFalse(match.isAutoBasketEligible, "Expected '$name' to be rejected for Tinned Tomatoes")
        }
    }

    @Test
    fun matcher_tomatoSauce_passesGenuineSauces_andRejectsCompositeProducts() {
        val matcher = RetailProductMatcher()

        val passingNames = listOf(
            "Tomato Sauce",
            "Tomato & Basil Sauce",
            "Tomato Pasta Sauce"
        )
        for (name in passingNames) {
            val product = RetailProduct(
                retailerId = RetailerId.ASDA,
                retailerProductId = "ts_pass",
                name = name,
                storageClass = RetailStorageClass.AMBIENT
            )
            val match = matcher.evaluateMatch(null, "Tomato sauce", product)
            assertTrue(match.isAutoBasketEligible, "Expected '$name' to be accepted for 'Tomato sauce'")
        }

        val rejectingNames = listOf(
            "Stuffed Cabbage in Tomato Sauce",
            "Meatballs in Tomato Sauce",
            "Sardines in Tomato Sauce",
            "Ravioli in Tomato Sauce"
        )
        for (name in rejectingNames) {
            val product = RetailProduct(
                retailerId = RetailerId.ASDA,
                retailerProductId = "ts_reject",
                name = name,
                storageClass = RetailStorageClass.AMBIENT
            )
            val match = matcher.evaluateMatch(null, "Tomato sauce", product)
            assertFalse(match.isAutoBasketEligible, "Expected '$name' to be rejected for 'Tomato sauce'")
            assertEquals(MatchType.INCOMPATIBLE, match.matchType)
        }
    }

    @Test
    fun matcher_cabbage_passesCabbages_rejectsComposites_unlessExplicitlyRequested() {
        val matcher = RetailProductMatcher()

        val passingNames = listOf(
            "Cabbage",
            "White Cabbage",
            "Red Cabbage",
            "Savoy Cabbage"
        )
        for (name in passingNames) {
            val product = RetailProduct(
                retailerId = RetailerId.ASDA,
                retailerProductId = "cab_pass",
                name = name,
                storageClass = RetailStorageClass.FRESH
            )
            val match = matcher.evaluateMatch(null, "Cabbage", product)
            assertTrue(match.isAutoBasketEligible, "Expected '$name' to be accepted for 'Cabbage'")
        }

        val rejectingNames = listOf(
            "Creamed Cabbage",
            "Stuffed Cabbage",
            "Cabbage Soup",
            "Coleslaw"
        )
        for (name in rejectingNames) {
            val product = RetailProduct(
                retailerId = RetailerId.ASDA,
                retailerProductId = "cab_reject",
                name = name,
                storageClass = RetailStorageClass.FRESH
            )
            val match = matcher.evaluateMatch(null, "Cabbage", product)
            assertFalse(match.isAutoBasketEligible, "Expected '$name' to be rejected for generic 'Cabbage'")
            assertEquals(MatchType.INCOMPATIBLE, match.matchType)
        }

        // Explicit modifier tests
        val creamedProduct = RetailProduct(
            retailerId = RetailerId.ASDA,
            retailerProductId = "cab_creamed",
            name = "Creamed Cabbage",
            storageClass = RetailStorageClass.FRESH
        )
        val creamedMatch = matcher.evaluateMatch(null, "creamed cabbage", creamedProduct)
        assertTrue(creamedMatch.isAutoBasketEligible, "Expected explicitly requested 'creamed cabbage' to match Creamed Cabbage")

        val stuffedProduct = RetailProduct(
            retailerId = RetailerId.ASDA,
            retailerProductId = "cab_stuffed",
            name = "Stuffed Cabbage",
            storageClass = RetailStorageClass.FRESH
        )
        val stuffedMatch = matcher.evaluateMatch(null, "stuffed cabbage", stuffedProduct)
        assertTrue(stuffedMatch.isAutoBasketEligible, "Expected explicitly requested 'stuffed cabbage' to match Stuffed Cabbage")
    }

    @Test
    fun matcher_packWeightNotRequiredForIdentity() {
        val matcher = RetailProductMatcher()
        val product = RetailProduct(
            retailerId = RetailerId.ASDA,
            retailerProductId = "t_noweight",
            name = "Peeled Plum Tomatoes",
            storageClass = RetailStorageClass.AMBIENT
        )
        val match = matcher.evaluateMatch(null, "Tinned Tomatoes", product)
        assertTrue(match.isAutoBasketEligible)
    }

    @Test
    fun matcher_allPurposeFlour_matchesUKPlainFlour_butRejectsOtherFlourTypes() {
        val matcher = RetailProductMatcher()
        listOf("Plain Flour 1.5kg", "ASDA Plain Flour 1.5kg", "All Purpose Flour 1kg", "All-Purpose Flour 1kg").forEachIndexed { index, name ->
            val product = RetailProduct(RetailerId.ASDA, "plain_flour_$index", name, storageClass = RetailStorageClass.AMBIENT)
            listOf("All purpose flour", "All-purpose flour").forEach { requirement ->
                val match = matcher.evaluateMatch(null, requirement, product)
                assertTrue(match.isAutoBasketEligible, "Expected '$name' to satisfy '$requirement'")
            }
        }
        listOf(
            "Self Raising Flour 1.5kg",
            "Strong White Bread Flour 1.5kg",
            "Gluten Free Plain Flour",
            "Wholemeal Flour"
        ).forEachIndexed { index, name ->
            val product = RetailProduct(RetailerId.ASDA, "other_flour_$index", name, storageClass = RetailStorageClass.AMBIENT)
            val match = matcher.evaluateMatch(null, "All purpose flour", product)
            assertFalse(match.isAutoBasketEligible, "Expected '$name' not to auto-match all-purpose flour")
        }
    }

    @Test
    fun matcher_dicedChicken_rejectsSeasonedOrCoatedPreparedChicken() {
        val matcher = RetailProductMatcher()
        val preparedNames = listOf(
            "American Inspired Southern Fried Chicken",
            "Southern Fried Chicken Breast Strips",
            "Crispy Breaded Chicken Pieces",
            "BBQ Chicken Chunks"
        )
        preparedNames.forEachIndexed { index, name ->
            val product = RetailProduct(RetailerId.ASDA, "prepared_chicken_$index", name, canonicalIngredientId = "CHICKEN", storageClass = RetailStorageClass.FROZEN)
            val match = matcher.evaluateMatch("CHICKEN", "Diced Chicken", product)
            assertFalse(match.isAutoBasketEligible, "Expected '$name' not to match plain diced chicken")
            assertEquals(MatchType.INCOMPATIBLE, match.matchType)
        }
    }

    @Test
    fun matcher_rejectsIncompatibleWholeOrBoneInPhysicalForms_acrossIngredients() {
        val matcher = RetailProductMatcher()
        listOf(
            Triple("CHICKEN", "Diced Chicken", "Whole Chicken 1.7kg"),
            Triple("CHICKEN", "Diced Chicken", "Fresh Whole Roasting Chicken"),
            Triple("CHICKEN", "Diced Chicken", "Chicken Drumsticks 1kg"),
            Triple("CHICKEN", "Diced Chicken", "Bone-In Chicken Thighs 800g"),
            Triple("CHICKEN", "Diced Chicken", "Chicken Wings 1kg"),
            Triple("PORK", "Diced Pork", "Whole Pork Shoulder 1.7kg"),
            Triple("PORK", "Diced Pork", "Bone-In Pork Chops 800g"),
            Triple("CHICKEN", "Diced Chicken", "Succulent Large Chicken")
        ).forEachIndexed { index, (canonicalId, requirement, name) ->
            val product = RetailProduct(
                RetailerId.ASDA,
                "physical_form_$index",
                name,
                canonicalIngredientId = canonicalId,
                storageClass = RetailStorageClass.FRESH
            )
            val match = matcher.evaluateMatch(canonicalId, requirement, product)
            assertFalse(match.isAutoBasketEligible, "Expected '$name' not to satisfy '$requirement'")
            assertEquals(MatchType.INCOMPATIBLE, match.matchType)
        }
    }

    @Test
    fun matcher_dicedChicken_acceptsDicedProducts_butRejectsUnpreparedBreastCuts() {
        val matcher = RetailProductMatcher()
        val diced = RetailProduct(
            RetailerId.ASDA, "diced_chicken", "Diced Chicken Breast 500g",
            canonicalIngredientId = "CHICKEN", storageClass = RetailStorageClass.FRESH
        )
        assertTrue(matcher.evaluateMatch("CHICKEN", "Diced Chicken", diced).isAutoBasketEligible)

        listOf("Chicken Breast Fillets 600g", "Fresh Chicken Breast").forEachIndexed { index, name ->
            val product = RetailProduct(RetailerId.ASDA, "unprepared_chicken_$index", name, canonicalIngredientId = "CHICKEN", storageClass = RetailStorageClass.FRESH)
            val match = matcher.evaluateMatch("CHICKEN", "Diced Chicken", product)
            assertFalse(match.isAutoBasketEligible, "Expected '$name' not to satisfy diced chicken")
            assertEquals(MatchType.INCOMPATIBLE, match.matchType)
        }
    }

    @Test
    fun matcher_prefersDicedChicken_overUnpreparedBreastFillets() {
        val matcher = RetailProductMatcher()
        val diced = RetailProduct(
            retailerId = RetailerId.ASDA,
            retailerProductId = "diced_chicken",
            name = "ASDA Diced Chicken Breast 500g",
            canonicalIngredientId = "CHICKEN",
            storageClass = RetailStorageClass.FRESH
        )
        val fillets = RetailProduct(
            retailerId = RetailerId.ASDA,
            retailerProductId = "chicken_breast_fillets",
            name = "Tender British Chicken Breast Fillets 600g",
            canonicalIngredientId = "CHICKEN",
            storageClass = RetailStorageClass.FRESH
        )

        val dicedMatch = matcher.evaluateMatch("CHICKEN", "Diced Chicken", diced)
        val filletMatch = matcher.evaluateMatch("CHICKEN", "Diced Chicken", fillets)

        assertTrue(dicedMatch.isAutoBasketEligible)
        assertFalse(filletMatch.isAutoBasketEligible)
        assertEquals(MatchConfidence.EXACT, dicedMatch.confidence)
        assertEquals(MatchType.INCOMPATIBLE, filletMatch.matchType)
    }

    @Test
    fun matcher_normalizesUnnecessaryDescriptors_forCommonStaples() {
        val matcher = RetailProductMatcher()
        val cases = listOf(
            Triple("Coarsley ground black pepper", "ASDA Black Pepper 50g", "BLACK_PEPPER"),
            Triple("Mature White Cheddar", "Mature Cheddar 400g", "CHEDDAR"),
            Triple("Sweetcorn kernals", "Sweetcorn 340g", "SWEETCORN")
        )

        cases.forEachIndexed { index, (requirement, productName, canonicalId) ->
            val product = RetailProduct(
                retailerId = RetailerId.ASDA,
                retailerProductId = "normalized_staple_$index",
                name = productName,
                canonicalIngredientId = null,
                storageClass = RetailStorageClass.AMBIENT
            )
            assertTrue(
                matcher.evaluateMatch(null, requirement, product).isAutoBasketEligible,
                "Expected '$productName' to match '$requirement'"
            )
        }
    }

}
