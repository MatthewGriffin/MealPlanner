package com.example.chatgpt_mealplanner.core.ai.context

import com.example.chatgpt_mealplanner.contract.ActionAuthorizationContext
import com.example.chatgpt_mealplanner.contract.AuthorizedMutationScope
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserMutationIntentResolver @Inject constructor() {

    fun resolveAuthorizationContext(
        messageId: String,
        userMessageText: String,
        pendingMutation: com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation? = null
    ): ActionAuthorizationContext {
        val lowerText = userMessageText.lowercase().trim()
        val scopes = mutableListOf<AuthorizedMutationScope>()

        val intentClass = UserMessageIntentClassifier.classify(userMessageText, pendingMutation)

        if (intentClass == UserMessageIntentClass.CONFIRM_PENDING_MUTATION ||
            intentClass == UserMessageIntentClass.ANSWER_TO_PENDING_CLARIFICATION) {
            when (pendingMutation) {
                is com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.AddPlannedMeal -> {
                    scopes.add(AuthorizedMutationScope.AddPlannedMealScope(sourceUserMessageId = messageId))
                }
                is com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.DeleteInventoryItem -> {
                    scopes.add(
                        AuthorizedMutationScope.DeleteInventoryScope(
                            sourceUserMessageId = messageId,
                            permittedItemId = pendingMutation.itemId,
                            targetQuery = pendingMutation.targetName
                        )
                    )
                }
                is com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.DeleteAllInventory -> {
                    scopes.add(AuthorizedMutationScope.DeleteAllInventoryScope(sourceUserMessageId = messageId))
                }
                is com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.PrepareMarkMealCooked -> {}
                null -> {}
            }
        }

        if (lowerText.contains("no ") && (lowerText.contains("left") || lowerText.contains("out of"))) {
            val itemTerm = extractItemTerm(lowerText)
            scopes.add(
                AuthorizedMutationScope.InventoryOutScope(
                    sourceUserMessageId = messageId,
                    permittedCanonicalIngredientIds = if (itemTerm != null) setOf(itemTerm.uppercase()) else emptySet()
                )
            )
        }

        if (lowerText.contains("staple") || lowerText.contains("staples")) {
            val isUnset = lowerText.contains("not a staple") ||
                    lowerText.contains("isn't a staple") ||
                    lowerText.contains("is not a staple") ||
                    lowerText.contains("remove") ||
                    lowerText.contains("unset") ||
                    lowerText.contains("unmark")
            val itemTerm = extractStapleItemTerm(lowerText)
            scopes.add(
                AuthorizedMutationScope.SetStapleScope(
                    sourceUserMessageId = messageId,
                    searchScope = itemTerm,
                    isStaple = !isUnset
                )
            )
        }

        if (lowerText.contains("regenerate") && lowerText.contains("shopping")) {
            scopes.add(AuthorizedMutationScope.RegenerateShoppingScope(sourceUserMessageId = messageId))
        }

        if ((lowerText.contains("add") || lowerText.contains("put") || lowerText.contains("buy") || lowerText.contains("need") || lowerText.contains("get") || lowerText.contains("find")) &&
            (lowerText.contains("shopping") || lowerText.contains("list") || lowerText.contains("tablets") || lowerText.contains("roll") || lowerText.contains("liquid") || lowerText.contains("soap") || lowerText.contains("paper") || lowerText.contains("item"))
        ) {
            scopes.add(AuthorizedMutationScope.AddManualShoppingScope(sourceUserMessageId = messageId))
        }

        val hasAddVerb = lowerText.contains("add") || lowerText.contains("put") || lowerText.contains("save") || lowerText.contains("plan") || lowerText.contains("schedule")

        if (hasAddVerb && (
            lowerText.contains("planner") || lowerText.contains("meal") || lowerText.contains("plan") || lowerText.contains("calendar") || lowerText.contains("shopping") || lowerText.contains("list") || lowerText.contains("it")
        )) {
            scopes.add(AuthorizedMutationScope.AddPlannedMealScope(sourceUserMessageId = messageId))
        }

        return ActionAuthorizationContext(
            sourceUserMessageId = messageId,
            userMessageText = userMessageText,
            scopes = scopes
        )
    }

    private fun isCancelRequest(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower == "cancel" || lower == "no" || lower == "never mind" || lower == "stop" || lower == "don't" || lower == "dont"
    }

    private fun extractDeleteItemTerm(text: String): String? {
        val clean = text
            .replace("delete", "")
            .replace("remove", "")
            .replace("from my inventory", "")
            .replace("from inventory", "")
            .replace("the", "")
            .trim()
        return clean.ifBlank { null }
    }

    private fun extractItemTerm(text: String): String? {
        val clean = text.replace("no ", "").replace("left", "").replace("out of", "").trim()
        return clean.ifBlank { null }
    }

    private fun extractStapleItemTerm(text: String): String? {
        val clean = text
            .replace("mark", "")
            .replace("make", "")
            .replace("add", "")
            .replace("remove", "")
            .replace("unset", "")
            .replace("unmark", "")
            .replace("as a staple", "")
            .replace("a staple", "")
            .replace("to my staples", "")
            .replace("from my staples", "")
            .replace("isn't a staple anymore", "")
            .replace("is not a staple anymore", "")
            .replace("isn't a staple", "")
            .replace("is not a staple", "")
            .replace("staples", "")
            .replace("staple", "")
            .trim()
        return clean.ifBlank { null }
    }
}
