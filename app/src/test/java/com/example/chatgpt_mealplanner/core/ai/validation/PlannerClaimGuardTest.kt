package com.example.chatgpt_mealplanner.core.ai.validation

import org.junit.Assert.assertEquals
import org.junit.Test

class PlannerClaimGuardTest {
    @Test fun aClaimThatAMealWasAddedIsReplaced() {
        assertEquals(PlannerClaimGuard.NOT_SAVED, PlannerClaimGuard.sanitize("I've added the lasagne to your meal plan for Friday."))
        assertEquals(PlannerClaimGuard.NOT_SAVED, PlannerClaimGuard.sanitize("Done! The dinners have been saved to your planner."))
    }

    @Test fun savingAKitsInstructionsIsNotAPlannerClaim() {
        val reply = "I've saved the packet instructions for your fajita kit so I can use them in your meal plan."
        assertEquals(reply, PlannerClaimGuard.sanitize(reply))
        val saved = "I have saved your recipe to the recipe book. Want me to plan it for this week?"
        assertEquals(saved, PlannerClaimGuard.sanitize(saved))
    }

    @Test fun aKitSentenceDoesNotHideARealPlannerClaimInAnother() {
        val reply = "The kit instructions are saved. I've added the fajitas to your meal plan."
        assertEquals(PlannerClaimGuard.NOT_SAVED, PlannerClaimGuard.sanitize(reply))
    }

    @Test fun ordinaryRepliesAreLeftAlone() {
        val reply = "Here is a recipe idea you could add to your plan if you like it."
        assertEquals(reply, PlannerClaimGuard.sanitize(reply))
    }
}
