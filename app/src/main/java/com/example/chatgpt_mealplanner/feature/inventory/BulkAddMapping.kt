package com.example.chatgpt_mealplanner.feature.inventory

import com.example.chatgpt_mealplanner.core.barcode.BarcodeLookupResult
import com.example.chatgpt_mealplanner.core.barcode.model.ConfirmedProductInput
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit

/** Units that count whole packs, so a product's own pack size gives the real amount. */
private val PACK_UNITS = setOf(QuantityUnit.UNIT, QuantityUnit.PACK, QuantityUnit.TIN, QuantityUnit.BOTTLE, QuantityUnit.BAG)

/** The product's size as a person reads it, such as "415 gram", or null when the database has none. */
fun BarcodeLookupResult.sizeText(): String? {
    val amount = netQuantity ?: packSize ?: return null
    val unit = netQuantityUnit ?: packSizeUnit ?: return null
    val number = if (amount == Math.floor(amount)) amount.toLong().toString() else amount.toString()
    return "$number ${unit.name.lowercase()}"
}

/** How a product reads in the choice list: name, brand and size. */
fun BarcodeLookupResult.choiceLabel(): String = buildString {
    append(displayName)
    brand?.takeIf { it.isNotBlank() && !displayName.contains(it, ignoreCase = true) }?.let { append(" (").append(it).append(")") }
    sizeText()?.let { append(" · ").append(it) }
}

/**
 * The stock record for a checked row. The user's own amount and the category and place Claude chose
 * are kept. A matched product adds its real name, brand, barcode and pack size. When the user gave a
 * count of packs and the product has a pack size, the total is worked out from it.
 * Returns null while the amount can't be read.
 */
fun BulkAddRow.toProductInput(): ConfirmedProductInput? {
    val quantity = quantityText.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() } ?: return null
    val name = displayName.trim().ifEmpty { return null }
    val product = matched
    val productSize = product?.let { it.netQuantity ?: it.packSize }
    val productUnit = product?.let { it.netQuantityUnit ?: it.packSizeUnit }
    val packs = unit in PACK_UNITS && productSize != null && productUnit != null && quantity == Math.floor(quantity)
    return ConfirmedProductInput(
        barcode = product?.barcode?.takeIf { it.isNotBlank() },
        displayName = name,
        brand = product?.brand ?: brand,
        canonicalName = canonicalName.ifBlank { name.lowercase() },
        category = category,
        storageLocation = storageLocation,
        netQuantity = if (packs) null else quantity,
        netQuantityUnit = if (packs) null else unit,
        packCount = if (packs) quantity.toInt() else null,
        packSize = if (packs) productSize else null,
        packSizeUnit = if (packs) productUnit else null,
        quantityConfidence = if (quantityGuessed) QuantityConfidence.APPROXIMATE else QuantityConfidence.EXACT
    )
}
