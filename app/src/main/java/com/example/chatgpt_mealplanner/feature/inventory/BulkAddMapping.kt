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
 * The stock record for a checked row. Inventory names stay generic, "White bread" and not
 * "Asda White Bread 800g", so the same food bought under different brands lands on one item, and every
 * barcode that means it is attached to that item. The brand and the product's full name are kept in
 * the barcode mapping ([toMappingInput]) instead. The user's own amount and the category and place
 * Claude chose are kept. When the user gave a count of packs and a matched product has a pack size,
 * the total is worked out from it. Returns null while the amount can't be read.
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
        brand = null,
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

/**
 * What to remember about the matched product so a later list or scan recognises it: its real name,
 * brand, barcode and size, pointing at the generic name used in inventory. Null for a row with no match.
 */
fun BulkAddRow.toMappingInput(): ConfirmedProductInput? {
    val product = matched ?: return null
    val barcode = product.barcode.takeIf { it.isNotBlank() } ?: return null
    return ConfirmedProductInput(
        barcode = barcode,
        displayName = product.displayName,
        brand = product.brand,
        canonicalName = canonicalName.ifBlank { displayName.trim().lowercase() },
        category = category,
        storageLocation = storageLocation,
        netQuantity = product.netQuantity,
        netQuantityUnit = product.netQuantityUnit,
        packCount = product.packCount,
        packSize = product.packSize,
        packSizeUnit = product.packSizeUnit
    )
}

/**
 * The same stock expressed in [target], for when the item it merges into is counted in another unit,
 * such as a loaf recorded as 1 unit when this one came as 800 g. Weight and volume convert between
 * kilo/gram and litre/millilitre; a count of packs carries over between pack-like units. Null when
 * there is no honest conversion, so the caller can leave the line for the user.
 */
fun ConfirmedProductInput.inUnit(target: QuantityUnit, rowQuantity: Double, rowUnit: QuantityUnit): ConfirmedProductInput? {
    val total = if (packCount != null && packSize != null) packCount * packSize else netQuantity
    val totalUnit = if (packCount != null && packSize != null) packSizeUnit else netQuantityUnit
    fun with(quantity: Double) = copy(netQuantity = quantity, netQuantityUnit = target, packCount = null, packSize = null, packSizeUnit = null)
    if (total != null && totalUnit != null) {
        if (totalUnit == target) return with(total)
        val grams = mapOf(QuantityUnit.GRAM to 1.0, QuantityUnit.KILOGRAM to 1000.0)
        val millilitres = mapOf(QuantityUnit.MILLILITRE to 1.0, QuantityUnit.LITRE to 1000.0)
        for (family in listOf(grams, millilitres)) {
            val from = family[totalUnit]
            val to = family[target]
            if (from != null && to != null) return with(total * from / to)
        }
    }
    // A count of packs, as the user said it, carries over when both sides count whole packs.
    if (target in PACK_UNITS && rowUnit in PACK_UNITS) return with(rowQuantity)
    return null
}
