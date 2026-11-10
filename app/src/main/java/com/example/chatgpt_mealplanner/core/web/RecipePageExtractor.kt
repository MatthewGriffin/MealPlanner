package com.example.chatgpt_mealplanner.core.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Most recipe sites describe the recipe in a machine-readable block (schema.org Recipe in JSON-LD), which is
 * exact and much shorter than the page. Use that when it is there, and fall back to the page's text.
 */
object RecipePageExtractor {

    private val ldJson = Regex("""<script[^>]+type\s*=\s*["']application/ld\+json["'][^>]*>(.*?)</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    const val MAX_TEXT_CHARS = 14_000

    fun extract(html: String): String {
        val recipe = ldJson.findAll(html).mapNotNull { runCatching { json.parseToJsonElement(it.groupValues[1].trim()) }.getOrNull() }
            .mapNotNull { findRecipe(it) }.firstOrNull()
        if (recipe != null) return "Recipe data from the page (schema.org):\n" + readable(recipe).take(MAX_TEXT_CHARS)
        return "Page text:\n" + visibleText(html).take(MAX_TEXT_CHARS)
    }

    private fun findRecipe(element: JsonElement): JsonObject? = when (element) {
        is JsonArray -> element.firstNotNullOfOrNull { findRecipe(it) }
        is JsonObject -> {
            val type = element["@type"]
            val isRecipe = when (type) {
                is JsonPrimitive -> type.content.equals("Recipe", ignoreCase = true)
                is JsonArray -> type.any { (it as? JsonPrimitive)?.content.equals("Recipe", ignoreCase = true) }
                else -> false
            }
            if (isRecipe) element else element["@graph"]?.let { findRecipe(it) }
        }
        else -> null
    }

    private val keep = listOf("name", "recipeYield", "prepTime", "cookTime", "totalTime", "recipeIngredient", "recipeInstructions", "description")

    private fun readable(recipe: JsonObject): String = buildString {
        for (key in keep) {
            val value = recipe[key] ?: continue
            append(key).append(": ").append(flatten(value)).append('\n')
        }
    }

    private fun flatten(element: JsonElement): String = when (element) {
        is JsonPrimitive -> element.content
        is JsonArray -> element.joinToString("\n") { "- " + flatten(it) }
        is JsonObject -> (element["text"] ?: element["name"] ?: element["itemListElement"])?.let { flatten(it) } ?: element.toString()
        else -> element.toString()
    }

    private fun visibleText(html: String): String = html
        .replace(Regex("""<(script|style|noscript|svg|nav|footer|header)[^>]*>.*?</\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        .replace(Regex("""<(br|/p|/div|/li|/h\d|/tr)[^>]*>""", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("""<[^>]+>"""), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").replace("&frac12;", "1/2")
        .lines().map { it.replace(Regex("""\s+"""), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
}
