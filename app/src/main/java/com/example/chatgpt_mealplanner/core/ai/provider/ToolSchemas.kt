package com.example.chatgpt_mealplanner.core.ai.provider

import com.example.chatgpt_mealplanner.contract.AiToolId
import com.example.chatgpt_mealplanner.core.model.MealType
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The JSON Schema of each tool's input, as the model sees it. Units and meal types are listed as
 * choices, and the app's own checks catch anything else.
 *
 * Tools aren't strict. Tried live on 2026-11-09 with Haiku 5.5: strict recipe tools came back with
 * empty or "placeholder" steps and "150 GRAM" in the method, and the API also caps strict tools
 * at 20 and 24 optional fields in total.
 */
internal object ToolSchemas {

    /** The tool's input properties. */
    fun properties(tool: AiToolId): JsonObject = buildJsonObject {
        when (tool) {
            AiToolId.ADD_PLANNED_MEAL,
            AiToolId.OFFER_ADD_PLANNED_MEAL -> {
                string("title", "Meal title (e.g. Shakshuka)")
                string("date", "Date in YYYY-MM-DD format (e.g. 2026-09-30)")
                choice("mealType", "Meal type", MealType.entries.map { it.name })
                number("servings", "Number of servings (default 2)")
                if (tool == AiToolId.OFFER_ADD_PLANNED_MEAL) {
                    number("prepMinutes", "Honest preparation time in minutes")
                    number("cookMinutes", "Honest cooking time in minutes")
                    string("whyThisSuits", "One short, specific reason this recipe suits this user today")
                    putJsonObject("worthAdding") {
                        put("type", "array")
                        put("description", "1 to 3 optional extras the household doesn't have that would clearly lift this dish, each as the item and what it adds, such as \"Fresh mint: brightens the yoghurt sauce\". Not part of the recipe, and not added to the shopping list")
                        putJsonObject("items") { put("type", "string") }
                    }
                    string("basedOn", "Only if the recipe is adapted from a web page you searched: the site name and the page URL, such as \"BBC Good Food: https://...\"")
                    putJsonObject("equipment") {
                        put("type", "array")
                        put("description", "Pans, trays and tools needed")
                        putJsonObject("items") { put("type", "string") }
                    }
                    putJsonObject("steps") {
                        put("type", "array")
                        put("description", "Every method stage, in the order it happens")
                        put("minItems", 1)
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                string("instruction", "One stage, with heat or oven temperature, time and how to tell it is ready")
                                number("durationMinutes", "Minutes this stage takes")
                            }
                            putJsonArray("required") { add("instruction") }
                        }
                    }
                } else {
                    string("instructions", "Complete step-by-step cooking instructions for the recipe")
                }
                putJsonObject("ingredients") {
                    put("type", "array")
                    put("description", "List of required ingredients for the meal")
                    put("minItems", 1)
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            string("displayName", "Ingredient display name")
                            number("quantity", "Quantity needed")
                            choice("unit", "Use GRAM or MILLILITRE for small amounts, not spoons, and UNIT only for whole items that are counted", QuantityUnit.entries.map { it.name })
                            string("measure", "Kitchen measure for small amounts, such as 1 tsp, matching the method")
                            boolean("optional", "True if the dish works without it")
                            string("substitute", "A good swap if there is one")
                            string("canonicalIngredientId", "Optional canonical ingredient ID")
                        }
                        putJsonArray("required") {
                            add("displayName")
                            add("quantity")
                            add("unit")
                        }
                    }
                }
            }
            AiToolId.GET_INVENTORY_FOR_INGREDIENT -> {
                string("canonicalIngredientId", "Canonical ingredient ID (e.g. ING_MILK)")
                string("searchQuery", "Ingredient search query term")
            }
            AiToolId.GET_MEAL_DETAILS,
            AiToolId.CANCEL_PLANNED_MEAL,
            AiToolId.RESTORE_MEAL_TO_PLANNED,
            AiToolId.PREPARE_MARK_MEAL_COOKED -> string("mealId", "Target meal ID")
            AiToolId.GET_RETAIL_ITEM_OPTIONS -> string("requirementId", "Shopping requirement ID")
            AiToolId.SEARCH_RETAIL_PRODUCTS -> {
                string("query", "Product term to search for, without retailer name (e.g. milk)")
                string("retailerFilter", "Optional retailer ID filter: ASDA, ICELAND, ALDI, TESCO, MORRISONS, SAINSBURYS, or LIDL")
            }
            AiToolId.MOVE_PLANNED_MEAL -> {
                string("mealId", "Target meal ID")
                string("newDate", "New date in YYYY-MM-DD format")
            }
            AiToolId.MARK_INVENTORY_LOT_OUT -> string("itemId", "Target inventory lot item ID")
            AiToolId.MARK_ALL_INVENTORY_FOR_CANONICAL_INGREDIENT_OUT -> string("canonicalIngredientId", "Canonical ingredient ID (e.g. ING_MILK)")
            AiToolId.ADJUST_INVENTORY_QUANTITY,
            AiToolId.RESOLVE_INVENTORY_LOT_UNCERTAINTY -> {
                string("itemId", "Target inventory lot item ID")
                number("newQuantity", "New physical quantity")
            }
            AiToolId.ADD_MANUAL_SHOPPING_ITEM -> {
                string("displayName", "Display name of manual item")
                number("quantity", "Optional required quantity")
                choice("unit", "Optional unit", QuantityUnit.entries.map { it.name })
            }
            AiToolId.SET_STAPLE_STATUS -> {
                string("itemId", "Optional specific inventory item ID")
                string("searchQuery", "Optional item search query or display name")
                boolean("isStaple", "True to mark as staple, false to remove staple status")
            }
            AiToolId.DELETE_INVENTORY_ITEM -> {
                string("itemId", "Optional specific inventory item ID")
                string("searchQuery", "Optional item search query")
            }
            AiToolId.DELETE_ALL_INVENTORY -> boolean("confirmBulkDelete", "Explicit confirmation flag for bulk delete")
            AiToolId.REQUEST_KIT_PACKET -> string("kitName", "The kit's name exactly as it appears in the household's inventory")
            AiToolId.SAVE_KIT_INSTRUCTIONS -> {
                string("kitName", "The kit's name exactly as it appears in the household's inventory, such as \"Old El Paso Fajita Kit\"")
                string("instructions", "What is in the kit, what else the packet says to add (with amounts), and the cooking steps with times, as the packet gives them")
                choice("source", "Where the instructions came from", listOf("PHOTO", "WEB", "USER"))
                string("sourceNote", "For WEB, the page URL the instructions came from; otherwise leave out")
            }
            else -> {
                // Read tools without arguments have no properties.
            }
        }
    }

    fun required(tool: AiToolId): List<String> = when (tool) {
        AiToolId.ADD_PLANNED_MEAL -> listOf("title", "servings", "ingredients", "instructions")
        AiToolId.OFFER_ADD_PLANNED_MEAL -> listOf("title", "servings", "ingredients", "steps")
        AiToolId.SAVE_KIT_INSTRUCTIONS -> listOf("kitName", "instructions", "source")
        AiToolId.REQUEST_KIT_PACKET -> listOf("kitName")
        else -> emptyList()
    }

    private fun JsonObjectBuilder.string(name: String, description: String) = field(name, "string", description)
    private fun JsonObjectBuilder.number(name: String, description: String) = field(name, "number", description)
    private fun JsonObjectBuilder.boolean(name: String, description: String) = field(name, "boolean", description)

    private fun JsonObjectBuilder.choice(name: String, description: String, values: List<String>) {
        putJsonObject(name) {
            put("type", "string")
            put("description", description)
            putJsonArray("enum") { values.forEach { add(it) } }
        }
    }

    private fun JsonObjectBuilder.field(name: String, type: String, description: String) {
        putJsonObject(name) {
            put("type", type)
            put("description", description)
        }
    }
}
