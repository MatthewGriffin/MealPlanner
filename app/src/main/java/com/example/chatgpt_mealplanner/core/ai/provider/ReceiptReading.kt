package com.example.chatgpt_mealplanner.core.ai.provider

import com.example.chatgpt_mealplanner.core.receipt.ReceiptSpendCategory
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

/** One printed line of a receipt as Claude read it. [amount] is the line's price as printed, as a decimal string ("3.74", "130"). */
data class ReadReceiptLine(
    val printedText: String,
    val name: String,
    val category: String,
    val quantity: Int,
    val amount: String,
    /** The product without brand or pack words, e.g. "White bread" for "Warburtons Soft White Bread 800g". */
    val genericName: String? = null,
    val brand: String? = null,
    /** A pantry food category and storage location, for food and drink lines. */
    val foodCategory: String? = null,
    val storageLocation: String? = null
)

/** A whole till receipt as Claude read it from the photo. The user reviews it before it is saved. */
data class ReadReceipt(
    val retailer: String,
    /** ISO 4217 code, or blank if the receipt doesn't show one. */
    val currency: String,
    /** yyyy-MM-dd, or null if no date could be read. */
    val date: String?,
    val items: List<ReadReceiptLine>,
    /** Money taken off: multibuy savings, coupons, manager discounts. Each as a positive decimal string. */
    val discounts: List<String>,
    val subtotal: String?,
    val totalPaid: String?
)

sealed interface ReceiptReadResult {
    data class Read(val receipt: ReadReceipt) : ReceiptReadResult
    data class Failed(val message: String) : ReceiptReadResult
}

internal object ReceiptReadingParsing {
    const val TOOL_NAME = "record_receipt"

    val SYSTEM_PROMPT = """
        You read photos of shop till receipts, from any country and in any language, and record them by calling $TOOL_NAME once. The photos are consecutive overlapping parts of ONE receipt from top to bottom: a line shown at the bottom of one part may be shown again at the top of the next, so record it once.

        1. retailer: the shop's name as printed in its header (for example "Lidl" or "Morrisons"), not the branch address.
        2. currency: the ISO 4217 code (GBP, EUR, USD, JPY...). Use a code or symbol printed on the receipt; if none, use what the country and language suggest.
        3. date: the purchase date as yyyy-MM-dd. Where the day and month could be swapped, use the receipt's own country to decide. null if there is no date.
        4. items: every product line bought, in order. printedText is the line exactly as printed. name is a clear product name a person would write (expand abbreviations such as "Hovis Sort Whit" to "Hovis Soft White Bread"), in the receipt's own language. category is one of FOOD, DRINKS (including alcohol), HOUSEHOLD_CLEANING, PERSONAL_CARE, PET, BABY, OTHER. quantity is the number bought (weighed goods are 1). amount is the line's total price as a plain decimal number with a dot ("3.74", or "130" for a currency with no cents), with no currency symbol or thousands separator. genericName is the plain product a household would keep in the pantry, with the brand, size and marketing words removed ("Warburtons Soft White Bread 800g" is "White bread", "Heinz Baked Beans" is "Baked beans", "Hovis Sort Whit" is "White bread"); brand is the brand if there is one. Also give foodCategory for every line (HOUSEHOLD for cleaning products, toiletries, pet, baby and other non-food goods; otherwise one of PRODUCE, MEAT_SEAFOOD, DAIRY_EGGS, BAKERY, DRY_GOODS, TINS_CANS, FROZEN_FOOD, SAUCES_CONDIMENTS, SNACKS, DRINKS, READY_MEALS, KITS_MIXES, OTHER) and storageLocation (CUPBOARD, FRIDGE, FREEZER, PANTRY; CUPBOARD for non-food). Do not include discount, tax, subtotal, total, payment, change, loyalty-points or card lines as items.
        5. discounts: each amount taken off the bill (multibuy deal, coupon, manager or price-override reduction), as a positive number. Do not also reduce the item's own amount.
        6. subtotal: the items total before discounts, if printed. totalPaid: the final amount the customer paid. Read these from the receipt, never calculate them.
        Never record card numbers, account numbers or loyalty-card numbers. If a value is not on the receipt, leave it out rather than guess.
    """.trimIndent()

    fun schema(): JsonObject = buildJsonObject {
        putJsonObject("retailer") { put("type", "string") }
        putJsonObject("currency") { put("type", "string"); put("description", "ISO 4217 code such as GBP or EUR") }
        putJsonObject("date") { put("type", "string"); put("description", "yyyy-MM-dd; omit if unreadable") }
        putJsonObject("items") {
            put("type", "array")
            putJsonObject("items") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("printedText") { put("type", "string") }
                    putJsonObject("name") { put("type", "string") }
                    putJsonObject("category") {
                        put("type", "string")
                        putJsonArray("enum") { listOf("FOOD", "DRINKS", "HOUSEHOLD_CLEANING", "PERSONAL_CARE", "PET", "BABY", "OTHER").forEach { add(JsonPrimitive(it)) } }
                    }
                    putJsonObject("quantity") { put("type", "integer") }
                    putJsonObject("amount") { put("type", "string"); put("description", "Line total as a plain decimal, e.g. 3.74") }
                    putJsonObject("genericName") { put("type", "string"); put("description", "Product without brand/size words, e.g. White bread") }
                    putJsonObject("brand") { put("type", "string") }
                    putJsonObject("foodCategory") {
                        put("type", "string")
                        putJsonArray("enum") { listOf("PRODUCE", "MEAT_SEAFOOD", "DAIRY_EGGS", "BAKERY", "DRY_GOODS", "TINS_CANS", "FROZEN_FOOD", "SAUCES_CONDIMENTS", "SNACKS", "DRINKS", "READY_MEALS", "KITS_MIXES", "HOUSEHOLD", "OTHER").forEach { add(JsonPrimitive(it)) } }
                    }
                    putJsonObject("storageLocation") {
                        put("type", "string")
                        putJsonArray("enum") { listOf("CUPBOARD", "FRIDGE", "FREEZER", "PANTRY").forEach { add(JsonPrimitive(it)) } }
                    }
                }
                putJsonArray("required") { listOf("printedText", "name", "category", "quantity", "amount").forEach { add(JsonPrimitive(it)) } }
            }
        }
        putJsonObject("discounts") {
            put("type", "array")
            putJsonObject("items") { put("type", "string") }
            put("description", "Each amount taken off, as a positive plain decimal")
        }
        putJsonObject("subtotal") { put("type", "string") }
        putJsonObject("totalPaid") { put("type", "string") }
    }

    val REQUIRED = listOf("retailer", "currency", "items", "totalPaid")

    fun parse(argsJson: String): ReadReceipt? {
        val root = try { Json.parseToJsonElement(argsJson).jsonObject } catch (_: Exception) { return null }
        fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val items = (root["items"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val amount = o.str("amount") ?: return@mapNotNull null
            val printed = o.str("printedText") ?: o.str("name") ?: return@mapNotNull null
            ReadReceiptLine(
                printedText = printed,
                name = o.str("name") ?: printed,
                category = ReceiptSpendCategory.fromName(o.str("category").orEmpty()).name,
                quantity = ((o["quantity"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt() ?: 1).coerceIn(1, 999),
                amount = amount,
                genericName = o.str("genericName"),
                brand = o.str("brand"),
                foodCategory = o.str("foodCategory"),
                storageLocation = o.str("storageLocation")
            )
        }
        val discounts = (root["discounts"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        return ReadReceipt(
            retailer = root.str("retailer").orEmpty(),
            currency = root.str("currency")?.uppercase().orEmpty(),
            date = root.str("date"),
            items = items,
            discounts = discounts,
            subtotal = root.str("subtotal"),
            totalPaid = root.str("totalPaid")
        )
    }
}
