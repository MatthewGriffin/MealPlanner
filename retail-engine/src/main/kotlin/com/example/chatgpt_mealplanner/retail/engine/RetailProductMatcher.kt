package com.example.chatgpt_mealplanner.retail.engine

import com.example.chatgpt_mealplanner.retail.contract.MatchConfidence
import com.example.chatgpt_mealplanner.retail.contract.MatchType
import com.example.chatgpt_mealplanner.retail.contract.RetailProduct
import com.example.chatgpt_mealplanner.retail.contract.RetailProductMappingDecision

data class RetailMatchCandidate(
    val product: RetailProduct,
    val matchType: MatchType,
    val confidence: MatchConfidence,
    val isAutoBasketEligible: Boolean,
    val reasoning: String
)

data class UserProductMappingKey(
    val retailerId: String,
    val retailerProductId: String,
    val canonicalIngredientId: String
)

class RetailProductMatcher(
    private val userMappings: Map<UserProductMappingKey, RetailProductMappingDecision> = emptyMap()
) {

    fun evaluateMatch(
        requirementCanonicalId: String?,
        requirementDisplayName: String,
        candidateProduct: RetailProduct
    ): RetailMatchCandidate {
        safeLog("[RETAIL_MATCHER_ENTRY] buildAudit=TOMATO_MATCH_AUDIT_20261001_01 requirement=\"$requirementDisplayName\" candidate=\"${candidateProduct.name}\"")

        // 1. Check user-confirmed mapping decision
        if (requirementCanonicalId != null) {
            val key = UserProductMappingKey(
                retailerId = candidateProduct.retailerId.name,
                retailerProductId = candidateProduct.retailerProductId,
                canonicalIngredientId = requirementCanonicalId
            )
            val userDecision = userMappings[key]
            if (userDecision == RetailProductMappingDecision.REJECTED_MATCH) {
                return returnMatch(
                    requirementDisplayName,
                    candidateProduct,
                    RetailMatchCandidate(
                        product = candidateProduct,
                        matchType = MatchType.INCOMPATIBLE,
                        confidence = MatchConfidence.LOW,
                        isAutoBasketEligible = false,
                        reasoning = "User explicitly rejected this product mapping."
                    )
                )
            } else if (userDecision == RetailProductMappingDecision.CONFIRMED_MATCH) {
                return returnMatch(
                    requirementDisplayName,
                    candidateProduct,
                    RetailMatchCandidate(
                        product = candidateProduct,
                        matchType = MatchType.EXACT_CANONICAL,
                        confidence = MatchConfidence.EXACT,
                        isAutoBasketEligible = true,
                        reasoning = "User confirmed this product mapping."
                    )
                )
            }
        }

        val nameLower = candidateProduct.name.lowercase()
        val normalizedProductName = nameLower.replace(Regex("[^a-z0-9]+"), " ").trim()
        // Recipe-generated shopping labels sometimes append a vague optional-choice
        // phrase to a real ingredient (for example, "Heavy cream, toppings of choice").
        // Match the actual ingredient identity without treating the choice phrase as food.
        val reqLower = requirementDisplayName.lowercase()
            .replace(Regex("[,;]?\\s*(?:optional )?(?:toppings?|garnishes?) of choice.*$"), "")
            .replace(Regex("\\s*\\(optional\\)\\s*$"), "")
            .trim()

        // Plain ingredient requirements must not silently accept seasoned, coated,
        // breaded or otherwise prepared variants just because the core word appears.
        // Keep this ahead of canonical-ID matching: retailer feeds may assign the
        // broad CHICKEN identity to prepared chicken products too.
        val normalizedRequirementName = reqLower.replace(Regex("[^a-z0-9]+"), " ").trim()
        val normalizedCandidateName = normalizedProductName
        val wantsChicken = normalizedRequirementName.split(" ").any { it in setOf("chicken", "chickens") } &&
            listOf("fried", "southern", "american", "inspired", "breaded", "battered", "coated", "crispy", "nugget", "nuggets", "goujon", "goujons", "kiev", "schnitzel", "seasoned", "marinated", "flavoured", "flavored", "roast", "roasted").none { normalizedRequirementName.contains(it) }
        val chickenPreparationTerms = listOf("southern fried", "american inspired", "breaded", "battered", "coated", "crispy", "nugget", "goujon", "kiev", "schnitzel", "seasoned", "marinated", "flavoured", "flavored", "bbq", "barbecue", "peri peri", "tikka", "fajita")
        if (wantsChicken && normalizedCandidateName.split(" ").any { it == "chicken" } &&
            chickenPreparationTerms.any { normalizedCandidateName.contains(it) }) {
            return returnMatch(requirementDisplayName, candidateProduct, RetailMatchCandidate(
                candidateProduct, MatchType.INCOMPATIBLE, MatchConfidence.LOW, false,
                "Prepared, coated or seasoned chicken is not a match for a plain chicken requirement."
            ))
        }

        // In UK supermarket terminology, US "all-purpose flour" normally maps to
        // plain flour. Do not substitute self-raising, bread or specialist flour.
        val wantsAllPurposeFlour = normalizedRequirementName in setOf("all purpose flour", "all purpose wheat flour")
        if (wantsAllPurposeFlour) {
            val candidateHasFlour = normalizedCandidateName.split(" ").contains("flour")
            val excludedFlourType = listOf(
                "self raising", "self-raising", "raising", "bread flour", "strong flour",
                "gluten free", "gluten-free", "wholemeal", "whole wheat", "rye flour",
                "cornflour", "corn flour", "almond flour", "coconut flour", "rice flour",
                "spelt flour", "chapati flour", "00 flour", "pizza flour"
            ).any { normalizedCandidateName.contains(it.replace(Regex("[^a-z0-9]+"), " ").trim()) }
            val isPlainFlour = candidateHasFlour && !excludedFlourType &&
                (normalizedCandidateName.contains("plain flour") || normalizedCandidateName == "flour" ||
                    normalizedCandidateName.startsWith("plain flour "))
            if (isPlainFlour) {
                return returnMatch(requirementDisplayName, candidateProduct, RetailMatchCandidate(
                    candidateProduct, MatchType.CATEGORY_MATCH, MatchConfidence.HIGH, true,
                    "All-purpose flour matched to UK plain flour; specialist and self-raising flours excluded."
                ))
            }
            if (candidateHasFlour) {
                return returnMatch(requirementDisplayName, candidateProduct, RetailMatchCandidate(
                    candidateProduct, MatchType.INCOMPATIBLE, MatchConfidence.LOW, false,
                    "Flour type is not a safe equivalent for all-purpose flour."
                ))
            }
        }

        // Distinct food guard: "Beans with Sausages" vs "Baked Beans"
        if (reqLower == "baked beans" || reqLower.contains("beans")) {
            if (nameLower.contains("sausage") || nameLower.contains("pork") || nameLower.contains("chilli") || nameLower.contains("bbq")) {
                if (!reqLower.contains("sausage") && !reqLower.contains("pork")) {
                    return returnMatch(
                        requirementDisplayName,
                        candidateProduct,
                        RetailMatchCandidate(
                            product = candidateProduct,
                            matchType = MatchType.INCOMPATIBLE,
                            confidence = MatchConfidence.LOW,
                            isAutoBasketEligible = false,
                            reasoning = "Product contains sausages or distinct flavor not requested by ingredient requirement."
                        )
                    )
                }
            }
        }

        // Semantic Ingredient Aliasing: Tinned / Canned Tomatoes (UK Localisation: "Tinned Tomatoes")
        if (reqLower == "canned tomatoes" || reqLower == "tinned tomatoes" || reqLower == "chopped tomatoes" || reqLower == "plum tomatoes" || reqLower == "tinned tomato" || reqLower == "canned tomato" || reqLower.contains("tinned tomatoes") || reqLower.contains("canned tomatoes")) {
            val isTomato = nameLower.contains("tomato") || nameLower.contains("tomatoes")
            val isExcluded = nameLower.contains("ketchup") || nameLower.contains("puree") || nameLower.contains("purée") ||
                    nameLower.contains("paste") || nameLower.contains("sauce") || nameLower.contains("soup") ||
                    nameLower.contains("fresh") || nameLower.contains("vine") || nameLower.contains("salad") ||
                    nameLower.contains("passata") || nameLower.contains("sieved") || nameLower.contains("cherry")

            if (isTomato && !isExcluded) {
                // Check modifier compatibility (chilli, garlic, herbs, etc.)
                val hasChilli = nameLower.contains("chilli") || nameLower.contains("chili") || nameLower.contains("pepper") || nameLower.contains("peppers")
                val hasGarlic = nameLower.contains("garlic")
                val hasHerbs = nameLower.contains("herb") || nameLower.contains("herbs") || nameLower.contains("basil") || nameLower.contains("oregano")

                val reqWantsChilli = reqLower.contains("chilli") || reqLower.contains("chili") || reqLower.contains("pepper")
                val reqWantsGarlic = reqLower.contains("garlic")
                val reqWantsHerbs = reqLower.contains("herb") || reqLower.contains("herbs")

                val hasUnrequestedModifier = (hasChilli && !reqWantsChilli) || (hasGarlic && !reqWantsGarlic) || (hasHerbs && !reqWantsHerbs)

                if (hasUnrequestedModifier) {
                    val match = RetailMatchCandidate(
                        product = candidateProduct,
                        matchType = MatchType.INCOMPATIBLE,
                        confidence = MatchConfidence.LOW,
                        isAutoBasketEligible = false,
                        reasoning = "Product contains unrequested flavor modifier (chilli/garlic/herbs)."
                    )
                    safeLog("[RETAIL_MATCH] requirement=\"$requirementDisplayName\" candidate=\"${candidateProduct.name}\" categoryMatch=true modifierCompatible=false autoEligible=false reason=UNREQUESTED_FLAVOUR_MODIFIER")
                    return returnMatch(requirementDisplayName, candidateProduct, match)
                }

                val isCannedStyle = nameLower.contains("chopped") || nameLower.contains("plum") || nameLower.contains("peeled") ||
                        nameLower.contains("juice") || nameLower.contains("whole") || nameLower.contains("crushed") ||
                        nameLower.contains("tin") || nameLower.contains("can") || nameLower.contains("tinned") ||
                        nameLower.contains("in a rich") || nameLower.contains("in tomato juice") || nameLower == "tomatoes"

                val isFreshOrProduce = nameLower.contains("fresh") || nameLower.contains("loose") || nameLower.contains("salad") || nameLower.contains("truss")

                if (isCannedStyle && !isFreshOrProduce) {
                    val match = RetailMatchCandidate(
                        product = candidateProduct,
                        matchType = MatchType.CATEGORY_MATCH,
                        confidence = MatchConfidence.HIGH,
                        isAutoBasketEligible = true,
                        reasoning = "Semantic tinned/canned tomato alias match with compatible modifiers."
                    )
                    safeLog("[RETAIL_MATCH] requirement=\"$requirementDisplayName\" candidate=\"${candidateProduct.name}\" categoryMatch=true modifierCompatible=true autoEligible=true reason=ACCEPTED")
                    return returnMatch(requirementDisplayName, candidateProduct, match)
                }
            } else {
                val match = RetailMatchCandidate(
                    product = candidateProduct,
                    matchType = MatchType.INCOMPATIBLE,
                    confidence = MatchConfidence.LOW,
                    isAutoBasketEligible = false,
                    reasoning = "Product is passata, purée, fresh, sauce, soup, or condiment, not tinned tomatoes."
                )
                safeLog("[RETAIL_MATCH] requirement=\"$requirementDisplayName\" candidate=\"${candidateProduct.name}\" categoryMatch=false modifierCompatible=false autoEligible=false reason=EXCLUDED_PRODUCT_TYPE")
                return returnMatch(requirementDisplayName, candidateProduct, match)
            }
        }

        // UK double cream is the closest standard retail equivalent to US-style heavy cream.
        // Normalize punctuation/order variations in retailer catalogue names (e.g. "Cream, Double").
        val wantsHeavyCream = reqLower.contains("heavy cream") ||
            reqLower.contains("heavy whipping cream") ||
            reqLower in setOf("double cream", "whipping cream", "heavy whipping cream")
        if (wantsHeavyCream) {
            val isCream = normalizedProductName.split(" ").contains("cream")
            val isEquivalentCream = Regex("\\b(double|whipping|heavy)\\b.*\\bcream\\b|\\bcream\\b.*\\b(double|whipping|heavy)\\b")
                .containsMatchIn(normalizedProductName)
            val isPreparedOrFlavoured = listOf("ice cream", "sour cream", "clotted cream", "cream cheese", "custard", "spray cream").any { normalizedProductName.contains(it) }
            if (isCream && isEquivalentCream && !isPreparedOrFlavoured) {
                return returnMatch(requirementDisplayName, candidateProduct, RetailMatchCandidate(
                    candidateProduct, MatchType.CATEGORY_MATCH, MatchConfidence.HIGH, true,
                    "Heavy cream matched to UK double/whipping cream equivalent."
                ))
            }
        }

        // Any ordinary potato suitable for roasting is a valid substitute for a
        // generic "roasting potatoes" requirement; exclude prepared potato products.
        val wantsRoastingPotatoes = reqLower in setOf("roasting potatoes", "potatoes for roasting", "roast potatoes")
        if (wantsRoastingPotatoes) {
            val isPotato = Regex("\\\\bpotatoes?\\\\b").containsMatchIn(nameLower) ||
                listOf("maris piper", "king edward", "desiree", "rooster potatoes").any { nameLower.contains(it) }
            val isPrepared = listOf("crisps", "chips", "wedges", "mash", "mashed", "potato salad", "croquette", "waffles", "ready meal", "roast potatoes ready").any { nameLower.contains(it) }
            if (isPotato && !isPrepared) {
                return returnMatch(requirementDisplayName, candidateProduct, RetailMatchCandidate(
                    candidateProduct, MatchType.CATEGORY_MATCH, MatchConfidence.HIGH, true,
                    "Ordinary potatoes are a suitable substitute for roasting potatoes."
                ))
            }
        }

        // UK retailers commonly use "beef mince" where the list says "minced beef".
        // Accept this ingredient alias while excluding prepared dishes and meat alternatives.
        val normalizedRequirement = reqLower.trim().replace(Regex("\\s+"), " ")
        val wantsMincedBeef = normalizedRequirement in setOf("minced beef", "beef mince", "lean minced beef", "lean beef mince")
        if (wantsMincedBeef) {
            val hasBeef = Regex("\\bbeef\\b").containsMatchIn(nameLower)
            val hasMince = Regex("\\bmince(?:d)?\\b").containsMatchIn(nameLower)
            val excluded = listOf("burger", "meatball", "cottage pie", "shepherds pie", "shepherd's pie", "bolognese", "lasagne", "sausage", "dog food", "plant based", "meat free", "vegetarian", "vegan").any { nameLower.contains(it) }
            if (hasBeef && hasMince && !excluded) {
                return returnMatch(requirementDisplayName, candidateProduct, RetailMatchCandidate(candidateProduct, MatchType.CATEGORY_MATCH, MatchConfidence.HIGH, true, "UK minced-beef/beef-mince naming alias with prepared dishes and meat alternatives excluded."))
            }
        }

        // Primary Product Identity & Composite Product Guard
        val isCompositeCandidate = nameLower.contains(" in $reqLower") ||
                nameLower.contains(" with $reqLower") ||
                nameLower.startsWith("stuffed ") ||
                nameLower.startsWith("creamed ") ||
                nameLower.contains(" soup") ||
                nameLower.contains(" slaw") ||
                nameLower.contains("coleslaw") ||
                nameLower.contains(" ready meal") ||
                nameLower.contains(" meals")

        val reqHasModifier = reqLower.contains("stuffed") || reqLower.contains("creamed") || reqLower.contains("soup") || reqLower.contains("slaw")

        if (isCompositeCandidate && !reqHasModifier) {
            val match = RetailMatchCandidate(
                product = candidateProduct,
                matchType = MatchType.INCOMPATIBLE,
                confidence = MatchConfidence.LOW,
                isAutoBasketEligible = false,
                reasoning = "Product is a composite or prepared dish containing ingredient as a component, not primary product identity."
            )
            safeLog("[RETAIL_MATCH_REJECT] requirement=\"$requirementDisplayName\" candidate=\"${candidateProduct.name}\" reason=COMPOSITE_PRODUCT_IDENTITY_MISMATCH")
            return returnMatch(requirementDisplayName, candidateProduct, match)
        }


        // Do not treat a mixed-ingredient product as a substitute for one ingredient.
        // For example, "Carrots, Sugar Snaps and Baby Corn" is not plain carrots.
        if (nameLower.contains(",") && nameLower.contains(" and ") && !reqLower.contains(" and ")) {
            val match = RetailMatchCandidate(
                product = candidateProduct,
                matchType = MatchType.INCOMPATIBLE,
                confidence = MatchConfidence.LOW,
                isAutoBasketEligible = false,
                reasoning = "Product combines multiple ingredients but the shopping requirement requests a single ingredient."
            )
            safeLog("[RETAIL_MATCH_REJECT] requirement=\"$requirementDisplayName\" candidate=\"${candidateProduct.name}\" reason=MULTI_INGREDIENT_PRODUCT")
            return returnMatch(requirementDisplayName, candidateProduct, match)
        }

        // 2. Exact canonical match
        if (requirementCanonicalId != null && candidateProduct.canonicalIngredientId == requirementCanonicalId) {
            return returnMatch(
                requirementDisplayName,
                candidateProduct,
                RetailMatchCandidate(
                    product = candidateProduct,
                    matchType = MatchType.EXACT_CANONICAL,
                    confidence = MatchConfidence.EXACT,
                    isAutoBasketEligible = true,
                    reasoning = "Exact canonical ingredient ID match."
                )
            )
        }

        // 3. Exact display name match
        if (nameLower == reqLower) {
            return returnMatch(
                requirementDisplayName,
                candidateProduct,
                RetailMatchCandidate(
                    product = candidateProduct,
                    matchType = MatchType.EXACT_CANONICAL,
                    confidence = MatchConfidence.HIGH,
                    isAutoBasketEligible = true,
                    reasoning = "Exact product name match."
                )
            )
        }

        // 4. Category / Substring / Normalized Keyword match
        val normalizedReq = reqLower
            .replace("shredded ", "")
            .replace("grated ", "")
            .replace("diced ", "")
            .replace("sliced ", "")
            .replace("chopped ", "")
            .replace("baby ", "")
            .replace("fresh ", "")
            .trim()

        val reqKeywords = normalizedReq.split(" ").filter { it.length > 2 }

        val matchesKeywords = reqKeywords.isNotEmpty() && reqKeywords.all { keyword ->
            nameLower.contains(keyword) || (candidateProduct.canonicalIngredientId?.lowercase()?.contains(keyword) == true)
        }

        if (nameLower.contains(reqLower) || reqLower.contains(nameLower) || nameLower.contains(normalizedReq) || matchesKeywords) {
            return returnMatch(
                requirementDisplayName,
                candidateProduct,
                RetailMatchCandidate(
                    product = candidateProduct,
                    matchType = MatchType.CATEGORY_MATCH,
                    confidence = MatchConfidence.MEDIUM,
                    isAutoBasketEligible = true,
                    reasoning = "Category / normalized keyword match."
                )
            )
        }

        // 5. Text match requiring confirmation
        return returnMatch(
            requirementDisplayName,
            candidateProduct,
            RetailMatchCandidate(
                product = candidateProduct,
                matchType = MatchType.TEXT_MATCH_REQUIRES_CONFIRMATION,
                confidence = MatchConfidence.REQUIRES_CONFIRMATION,
                isAutoBasketEligible = false,
                reasoning = "Fuzzy text match requires user confirmation before automated basket entry."
            )
        )
    }

    private fun returnMatch(
        requirementDisplayName: String,
        candidateProduct: RetailProduct,
        match: RetailMatchCandidate
    ): RetailMatchCandidate {
        safeLog("[RETAIL_MATCH_EVALUATION] requirement=\"$requirementDisplayName\" product=\"${candidateProduct.name}\" retailer=${candidateProduct.retailerId} matchType=${match.matchType} autoEligible=${match.isAutoBasketEligible} reason=\"${match.reasoning}\"")
        return match
    }

    private fun safeLog(msg: String) {
        try {
            println("[AI_MEALPLANNER] $msg")
        } catch (_: Throwable) {}
    }
}
