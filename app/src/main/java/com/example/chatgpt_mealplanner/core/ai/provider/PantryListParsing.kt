package com.example.chatgpt_mealplanner.core.ai.provider

import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.model.quantityUnitOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** One line of a household's typed or dictated list, as Claude read it. The user reviews it before it is saved. */
data class ParsedPantryItem(
    val displayName: String,
    val canonicalName: String,
    val brand: String?,
    val category: FoodCategory,
    val storageLocation: StorageLocation,
    val quantity: Double,
    val unit: QuantityUnit,
    /** True when the list gave no amount and Claude assumed a typical pack. */
    val quantityGuessed: Boolean
)

sealed interface PantryListResult {
    data class Items(val items: List<ParsedPantryItem>) : PantryListResult
    data class Failed(val message: String) : PantryListResult
}

internal object PantryListParsing {

    const val TOOL_NAME = "record_pantry_items"

    /** Longest list sent in one request, which is far more than a household owns. */
    const val MAX_LIST_CHARS = 12_000

    val SYSTEM_PROMPT = """
        You turn a household's own list of the food and drink they have at home into stock records. The list is typed or dictated, so it may be messy: run-together items, no punctuation, shop brand names, spelling mistakes and speech-recognition slips.

        Call $TOOL_NAME once with every distinct item in the list, in the order given. Do not skip items and do not invent items that are not in the list. Ignore anything that is not food or drink the household keeps.
        1. displayName: the clear product name a person would write, such as "Basmati rice" or "Chopped tomatoes". Keep a brand in brand, not in the name.
        2. canonicalName: the plain generic ingredient in lower case, such as "basmati rice", "chopped tomatoes" or "ground cumin". It is how recipes and stock are matched, so use the most common grocery name.
        3. quantity and unit: use what the list says ("2 kg flour" is 2 KILOGRAM, "3 tins of chickpeas" is 3 TIN, "a dozen eggs" is 12 UNIT). When the list gives no amount, set quantityGuessed to true and assume one typical full pack in GRAM, MILLILITRE or the pack unit (rice 1000 GRAM, olive oil 500 MILLILITRE, a jar of cumin 40 GRAM, a loaf 1 UNIT). Never leave the quantity out.
        4. category and storageLocation: choose where it is normally kept and what kind of food it is. Spices, oils, rice, pasta and tins go in the CUPBOARD, dairy and fresh meat in the FRIDGE, frozen food in the FREEZER, and potatoes, onions and similar in the PANTRY. If the list says an item is frozen or kept in the fridge, follow it.
        5. If the same item appears twice, merge it into one entry and add the amounts when the units match.
    """.trimIndent()

    /** The tool's input schema, with the app's own choices listed so the answer can be read back. */
    fun itemsSchema(): JsonObject = buildJsonObject {
        putJsonObject("items") {
            put("type", "array")
            put("description", "Every distinct item in the list")
            putJsonObject("items") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("displayName") { put("type", "string") }
                    putJsonObject("canonicalName") { put("type", "string") }
                    putJsonObject("brand") { put("type", "string"); put("description", "Only if the list names a brand") }
                    choice("category", FoodCategory.entries.map { it.name })
                    choice("storageLocation", StorageLocation.entries.map { it.name })
                    putJsonObject("quantity") { put("type", "number") }
                    choice("unit", QuantityUnit.entries.map { it.name })
                    putJsonObject("quantityGuessed") {
                        put("type", "boolean")
                        put("description", "True if the list gave no amount and you assumed a typical pack")
                    }
                }
                putJsonArray("required") {
                    listOf("displayName", "canonicalName", "category", "storageLocation", "quantity", "unit", "quantityGuessed")
                        .forEach { add(JsonPrimitive(it)) }
                }
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.choice(name: String, values: List<String>) {
        putJsonObject(name) {
            put("type", "string")
            putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
        }
    }

    /**
     * Reads the tool call's JSON. A line with no name is dropped, and a value that isn't one of the
     * app's choices falls back to a safe default, since the user reviews every line anyway.
     */
    fun parse(argsJson: String): List<ParsedPantryItem> {
        val root = try {
            Json.parseToJsonElement(argsJson).jsonObject
        } catch (_: Exception) {
            return emptyList()
        }
        val array = (root["items"] as? JsonArray) ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj.string("displayName")?.trim().orEmpty().ifBlank { obj.string("canonicalName")?.trim().orEmpty() }
            if (name.isBlank()) return@mapNotNull null
            val unit = quantityUnitOrNull(obj.string("unit")) ?: QuantityUnit.UNIT
            val quantity = (obj["quantity"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0.0 && it.isFinite() }
            ParsedPantryItem(
                displayName = name,
                canonicalName = obj.string("canonicalName")?.trim()?.lowercase().orEmpty().ifBlank { name.lowercase() },
                brand = obj.string("brand")?.trim()?.takeIf { it.isNotEmpty() },
                category = FoodCategory.entries.firstOrNull { it.name == obj.string("category") } ?: FoodCategory.OTHER,
                storageLocation = StorageLocation.entries.firstOrNull { it.name == obj.string("storageLocation") } ?: StorageLocation.CUPBOARD,
                quantity = quantity ?: 1.0,
                unit = if (quantity == null) QuantityUnit.UNIT else unit,
                quantityGuessed = quantity == null || (obj["quantityGuessed"] as? JsonPrimitive)?.booleanOrNull == true
            )
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
