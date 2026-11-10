package com.example.chatgpt_mealplanner.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A recipe the household gave the assistant, from a web page or a photo of a book, kept so it can be used
 * in plans without fetching or scanning it again.
 */
@Entity(tableName = "saved_recipes")
data class SavedRecipeEntity(
    /** The title, lower case with single spaces, so saving the same recipe again replaces it. */
    @PrimaryKey val normalizedTitle: String,
    val title: String,
    /** How many people the recipe serves as written, or null if it doesn't say. */
    val servings: Int?,
    /** The whole recipe as text: the ingredients with amounts, then the method with times. */
    val recipeText: String,
    /** URL, PHOTO or USER. */
    val source: String,
    /** The page address for URL; the book or page for a photo, if known. */
    val sourceNote: String?,
    val createdAt: Long,
    val updatedAt: Long
)
