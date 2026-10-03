package com.example.chatgpt_mealplanner.core.ai.context

import com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation

enum class UserMessageIntentClass {
    CONFIRM_PENDING_MUTATION,
    ANSWER_TO_PENDING_CLARIFICATION,
    FOLLOW_UP_TO_ACTIVE_PROPOSAL,
    NEW_READ_OR_PROPOSAL_REQUEST,
    NEW_MUTATION_REQUEST,
    CANCEL_PENDING,
    AMBIGUOUS
}

object UserMessageIntentClassifier {

    fun classify(
        userMessageText: String,
        pendingMutation: PendingAiMutation?,
        hasActiveProposal: Boolean = false
    ): UserMessageIntentClass {
        val lower = userMessageText.lowercase().trim()

        if (isNegativeConfirmation(lower)) {
            return UserMessageIntentClass.CANCEL_PENDING
        }

        if (pendingMutation != null && isAffirmativeConfirmation(lower)) {
            return UserMessageIntentClass.CONFIRM_PENDING_MUTATION
        }

        val isNewProposalRequest = lower.contains("new recipe") ||
                lower.contains("another recipe") ||
                lower.contains("different recipe") ||
                lower.contains("something different") ||
                lower.contains("another option") ||
                lower.contains("different option") ||
                lower.contains("suggest something else") ||
                lower.contains("something else") ||
                lower.contains("give me another") ||
                lower.contains("suggest another") ||
                lower.contains("new dish") ||
                lower.contains("give me a new") ||
                lower.contains("suggest a new")

        if (isNewProposalRequest) {
            return UserMessageIntentClass.NEW_READ_OR_PROPOSAL_REQUEST
        }

        val isFollowUpRead = (pendingMutation != null || hasActiveProposal) && isFollowUpQuestion(lower)
        if (isFollowUpRead) {
            return UserMessageIntentClass.FOLLOW_UP_TO_ACTIVE_PROPOSAL
        }

        val isProposalOrReadRequest = lower.contains("suggest") || lower.contains("recommend") || lower.contains("recipe") ||
                lower.contains("give me") || lower.contains("what can i cook") || lower.contains("what can i make") ||
                lower.contains("idea") || lower.contains("option") || lower.contains("haven't") || lower.contains("what's in my") ||
                lower.contains("what is in my") || lower.contains("show me") || lower.contains("what am i having") ||
                lower.contains("what dinner") || lower.contains("what lunch") || lower.contains("what breakfast")

        if (isProposalOrReadRequest) {
            return UserMessageIntentClass.NEW_READ_OR_PROPOSAL_REQUEST
        }

        val hasMutationVerb = lower.contains("add") || lower.contains("put") || lower.contains("save") ||
                lower.contains("schedule") || lower.contains("mark") || lower.contains("delete") ||
                lower.contains("remove") || lower.contains("clear") || lower.contains("regenerate")

        if (hasMutationVerb) {
            return UserMessageIntentClass.NEW_MUTATION_REQUEST
        }

        if (pendingMutation != null) {
            val isSlotAnswer = lower.contains("lunch") || lower.contains("dinner") || lower.contains("breakfast") ||
                    lower.contains("snack") || lower.contains("today") || lower.contains("tomorrow") ||
                    lower.contains("monday") || lower.contains("tuesday") || lower.contains("wednesday") ||
                    lower.contains("thursday") || lower.contains("friday") || lower.contains("saturday") || lower.contains("sunday") ||
                    runCatching { java.time.LocalDate.parse(lower) }.isSuccess

            if (isSlotAnswer) {
                return UserMessageIntentClass.ANSWER_TO_PENDING_CLARIFICATION
            }
        }

        return UserMessageIntentClass.AMBIGUOUS
    }

    private fun isFollowUpQuestion(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower.contains("how do i cook") || lower.contains("how do i make") ||
                lower.contains("how to cook") || lower.contains("how to make") || lower.contains("elaborate") ||
                lower.contains("tell me more") || lower.contains("explain") || lower.contains("can i use") ||
                lower.contains("is it") || lower.contains("make that") || lower.contains("change to") ||
                lower.contains("instead of") || lower.contains("serve ") || lower.contains("servings") ||
                lower.contains("substitute") || lower.contains("what about")
    }

    fun isAffirmativeConfirmation(text: String): Boolean {
        val lower = text.lowercase().trim()
        if (isNegativeConfirmation(lower)) return false
        return lower == "yes" || lower == "yes please" || lower == "yeah" || lower == "yep" ||
                lower == "sure" || lower == "okay" || lower == "ok" || lower == "go ahead" ||
                lower == "do it" || lower == "please do" || lower == "add it" || lower == "add that" ||
                lower == "delete it" || lower == "delete" || lower == "confirm" || lower == "cook it" ||
                lower == "yes delete" || lower == "yes delete it" || lower == "yes do it" ||
                lower.startsWith("yes ") || lower.startsWith("yeah ") || lower.startsWith("yep ") ||
                lower.contains("confirm") || lower.contains("delete it")
    }

    fun isNegativeConfirmation(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower == "no" || lower == "no thanks" || lower.startsWith("no ") ||
                lower == "don't" || lower == "dont" || lower.contains("don't") || lower.contains("dont") ||
                lower == "do not" || lower.contains("do not") || lower == "cancel" ||
                lower == "never mind" || lower == "stop"
    }
}
