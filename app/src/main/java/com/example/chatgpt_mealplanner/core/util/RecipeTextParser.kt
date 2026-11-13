package com.example.chatgpt_mealplanner.core.util

/** A planned meal's saved method text, split into the parts the recipe page lays out separately. */
data class ParsedRecipeText(
    val prepMinutes: Int?,
    val cookMinutes: Int?,
    val equipment: List<String>,
    val steps: List<RecipeStep>,
    val swaps: List<String>,
    val worthAdding: List<String>,
    val basedOn: String?,
    /** Anything that is none of the above, shown as plain text. */
    val other: List<String>
)

data class RecipeStep(val number: Int, val text: String, val minutes: Int?)

object RecipeTextParser {
    private val STEP = Regex("^(\\d+)[.)]\\s+(.*)$")
    private val MINUTES = Regex("\\s*\\((\\d+)\\s*min\\)\\s*$")

    fun parse(text: String): ParsedRecipeText {
        var prep: Int? = null
        var cook: Int? = null
        var equipment = emptyList<String>()
        val steps = mutableListOf<RecipeStep>()
        val swaps = mutableListOf<String>()
        val extras = mutableListOf<String>()
        var basedOn: String? = null
        val other = mutableListOf<String>()

        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val step = STEP.matchEntire(line)
            when {
                step != null -> {
                    val body = step.groupValues[2]
                    val mins = MINUTES.find(body)
                    steps += RecipeStep(
                        number = step.groupValues[1].toInt(),
                        text = if (mins != null) body.removeRange(mins.range).trim() else body,
                        minutes = mins?.groupValues?.get(1)?.toIntOrNull()
                    )
                }
                line.startsWith("Worth adding:") -> extras += line.removePrefix("Worth adding:").trim()
                line.startsWith("Swap ") -> swaps += line
                line.startsWith("Based on:") -> basedOn = line.removePrefix("Based on:").trim()
                line.startsWith("Prep ") || line.startsWith("Cook ") || line.startsWith("Equipment:") -> {
                    line.split("•").map { it.trim() }.forEach { part ->
                        when {
                            part.startsWith("Prep ") -> prep = Regex("\\d+").find(part)?.value?.toIntOrNull()
                            part.startsWith("Cook ") -> cook = Regex("\\d+").find(part)?.value?.toIntOrNull()
                            part.startsWith("Equipment:") ->
                                equipment = part.removePrefix("Equipment:").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            else -> other += part
                        }
                    }
                }
                else -> other += line
            }
        }
        return ParsedRecipeText(prep, cook, equipment, steps, swaps, extras, basedOn, other)
    }
}
