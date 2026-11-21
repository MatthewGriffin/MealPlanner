package com.example.chatgpt_mealplanner.core.ai.validation

/** Replaces a reply that claims the meal plan was changed when no planner change was made. */
object PlannerClaimGuard {
    const val NOT_SAVED = "No meal-plan changes were saved in this turn. Please retry or use Save meal on a recipe card when ready."

    fun sanitize(text: String): String {
        // Model prose is not evidence of a database write. Cover contractions,
        // plural meals and passive completion claims as well as "I have added".
        val normalized = text.replace("’", "'").replace(Regex("(?i)\\bI've\\b"), "I have")
        val completion = Regex("(?i)(?:I (?:have )?(?:now )?(?:added|scheduled|put|placed|saved)[^.!\n]*(?:meal|plan|dinner|week|calendar|lunch|breakfast|menu)|(?:meals?|dinners?|plan)[^.!\n]*(?:have been|has been|were|was|are now|is now) (?:added|saved|scheduled)|(?:added|saved|scheduled)[^.!\n]*(?:meal plan|planner|calendar)|(?:meal plan|planner) (?:is|has been) (?:updated|saved))")
        // Saving a kit's instructions or a saved recipe is a real save the assistant can report ("saved them so I can use them
        // in your meal plan"), and says nothing about the planner, so those sentences are not treated as a planner claim.
        val aboutReferenceData = Regex("(?i)\\b(?:instructions?|packets?|kit|saved recipes?|recipe book)\\b")
        val claimsPlannerChange = normalized.split(Regex("(?<=[.!?\\n])\\s+")).any { sentence ->
            completion.containsMatchIn(sentence) && !aboutReferenceData.containsMatchIn(sentence)
        }
        return if (claimsPlannerChange) {
            NOT_SAVED
        } else text
    }
}
