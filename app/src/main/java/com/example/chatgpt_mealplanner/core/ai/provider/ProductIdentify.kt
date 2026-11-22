package com.example.chatgpt_mealplanner.core.ai.provider

import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.model.quantityUnitOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A product as Claude read it from a photo of its pack. The user reviews and corrects it before it is saved. */
data class ReadProduct(
    val name: String,
    val brand: String?,
    val category: FoodCategory,
    val storage: StorageLocation,
    /** What the whole pack holds, such as 55 g, when the pack says. */
    val netQuantity: Double?,
    val netQuantityUnit: QuantityUnit?,
    val packCount: Int?
)

sealed interface ProductIdentifyResult {
    data class Read(val product: ReadProduct) : ProductIdentifyResult
    data class Failed(val message: String) : ProductIdentifyResult
}

internal object ProductIdentifyParsing {
    const val TOOL_NAME = "record_product"

    val SYSTEM_PROMPT = """
        You identify a grocery or household product from photos of its pack, from any country and in any language, and record it by calling $TOOL_NAME once.
        1. name: the product as a person would write it, without the brand or the weight ("Nacho crisps sweet chilli", "Fajita seasoning kit").
        2. brand: the brand printed on the pack, if any.
        3. category: PRODUCE, MEAT_SEAFOOD, DAIRY_EGGS, BAKERY, DRY_GOODS, TINS_CANS, FROZEN_FOOD, SAUCES_CONDIMENTS, SNACKS, DRINKS, READY_MEALS, KITS_MIXES (meal kits, seasoning or spice kits, sachets and mixes), HOUSEHOLD (cleaning, toiletries and other non-food), or OTHER.
        4. storage: CUPBOARD, PANTRY, FRIDGE or FREEZER, as the pack says to store it (CUPBOARD for non-food).
        5. netQuantity and netQuantityUnit: the total contents printed on the pack ("55 g" is 55 and GRAM; "1 l" is 1 and LITRE). packCount: how many packs it holds if it is a multipack, else leave out.
        Read only what is on the pack. If something is not visible, leave it out rather than guess.
    """.trimIndent()

    private val categories = FoodCategory.entries.map { it.name }
    private val storages = listOf("CUPBOARD", "PANTRY", "FRIDGE", "FREEZER")
    private val units = listOf("GRAM", "KILOGRAM", "MILLILITRE", "LITRE", "UNIT", "PACK", "TIN", "BOTTLE", "BAG", "SLICE", "PORTION")

    fun schema(): JsonObject = buildJsonObject {
        putJsonObject("name") { put("type", "string") }
        putJsonObject("brand") { put("type", "string") }
        putJsonObject("category") { put("type", "string"); putJsonArray("enum") { categories.forEach { add(JsonPrimitive(it)) } } }
        putJsonObject("storage") { put("type", "string"); putJsonArray("enum") { storages.forEach { add(JsonPrimitive(it)) } } }
        putJsonObject("netQuantity") { put("type", "number"); put("description", "Total contents printed on the pack, in netQuantityUnit") }
        putJsonObject("netQuantityUnit") { put("type", "string"); putJsonArray("enum") { units.forEach { add(JsonPrimitive(it)) } } }
        putJsonObject("packCount") { put("type", "integer") }
    }

    val REQUIRED = listOf("name", "category", "storage")

    fun parse(argsJson: String): ReadProduct? {
        val root = try { Json.parseToJsonElement(argsJson).jsonObject } catch (_: Exception) { return null }
        fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val name = root.str("name") ?: return null
        val category = root.str("category")?.let { c -> FoodCategory.entries.firstOrNull { it.name.equals(c, ignoreCase = true) } } ?: FoodCategory.OTHER
        val storage = when (root.str("storage")?.uppercase()) {
            "FRIDGE" -> StorageLocation.FRIDGE
            "FREEZER" -> StorageLocation.FREEZER
            "PANTRY" -> StorageLocation.PANTRY
            else -> StorageLocation.CUPBOARD
        }
        val quantity = (root["netQuantity"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.takeIf { it > 0.0 }
        val unit = quantityUnitOrNull(root.str("netQuantityUnit"))
        return ReadProduct(
            name = name,
            brand = root.str("brand"),
            category = category,
            storage = storage,
            netQuantity = quantity.takeIf { unit != null },
            netQuantityUnit = unit.takeIf { quantity != null },
            packCount = (root["packCount"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()?.takeIf { it in 2..99 }
        )
    }
}
