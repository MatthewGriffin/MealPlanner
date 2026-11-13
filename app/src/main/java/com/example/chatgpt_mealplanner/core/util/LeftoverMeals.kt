package com.example.chatgpt_mealplanner.core.util

/** Marks planned "Leftovers: X" meals and pantry items with the meal they came from, without a schema change. */
object LeftoverMeals {
    private const val PREFIX = "leftover-of:"

    fun noteFor(sourceMealId: String) = PREFIX + sourceMealId

    /** The source meal's id when [notes] marks a leftovers entry or pantry item. */
    fun sourceMealId(notes: String?): String? =
        notes?.trim()?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.trim()?.takeIf { it.isNotEmpty() }
}
