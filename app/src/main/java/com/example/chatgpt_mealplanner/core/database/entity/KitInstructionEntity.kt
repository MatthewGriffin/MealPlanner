package com.example.chatgpt_mealplanner.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * What the packet of a meal kit or spice kit says to do with it. Claude can't know this from the
 * product name, so it is read from a photo of the packet or found on the web once, saved here, and
 * reused in later recipes.
 */
@Entity(tableName = "kit_instructions")
data class KitInstructionEntity(
    /** The kit's name, lower case with single spaces, so "Old El Paso Fajita Kit" matches however it is typed. */
    @PrimaryKey val normalizedName: String,
    val displayName: String,
    /** What is in the kit, what else the packet says to add, and the steps, as the packet gives them. */
    val instructions: String,
    /** PHOTO_TEXT, PHOTO_IMAGE, WEB or USER. */
    val source: String,
    /** Where it came from, such as the page URL; null for a photo or something the user typed. */
    val sourceNote: String?,
    val createdAt: Long,
    val updatedAt: Long
)

fun normalizeKitName(name: String?): String = name.orEmpty().trim().replace(Regex("\\s+"), " ").lowercase()

/** Whether a saved instruction matches an owned kit: equal names, or one name contained in the other. */
fun savedKitMatches(ownedNormalizedNames: List<String>, savedNormalizedName: String): Boolean =
    ownedNormalizedNames.any { it.isNotEmpty() && (it == savedNormalizedName || it.contains(savedNormalizedName) || savedNormalizedName.contains(it)) }
