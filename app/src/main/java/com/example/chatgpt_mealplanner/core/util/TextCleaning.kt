package com.example.chatgpt_mealplanner.core.util

private val PICTOGRAPHS = Regex("[\\p{So}\\p{Cs}\\uFE0F\\u200D\\u20E3]")
private val EXTRA_SPACES = Regex("\\s{2,}")

/**
 * The text without emoji and other pictographs, for names that end up in the inventory. Product
 * databases are written by the public, and one entry is called "Broccoli 🥦 and Stilton soup".
 * Letters in any language, digits and ordinary punctuation are kept.
 */
fun String.withoutPictographs(): String = replace(PICTOGRAPHS, " ").replace(EXTRA_SPACES, " ").trim()

fun String?.withoutPictographsOrNull(): String? = this?.withoutPictographs()?.takeIf { it.isNotEmpty() }
