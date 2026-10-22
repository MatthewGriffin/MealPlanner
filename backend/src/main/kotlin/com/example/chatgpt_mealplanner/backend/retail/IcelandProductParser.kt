package com.example.chatgpt_mealplanner.backend.retail

import com.example.chatgpt_mealplanner.retail.contract.Money
import com.example.chatgpt_mealplanner.retail.contract.RetailAvailability
import com.example.chatgpt_mealplanner.retail.contract.RetailOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailPriceScope
import com.example.chatgpt_mealplanner.retail.contract.RetailProduct
import com.example.chatgpt_mealplanner.retail.contract.RetailProductWithOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailPromotion
import com.example.chatgpt_mealplanner.retail.contract.RetailPromotionScope
import com.example.chatgpt_mealplanner.retail.contract.RetailStorageClass
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ParsedProductRecord(
    val productWithOffer: RetailProductWithOffer,
    val warnings: List<String> = emptyList()
)

class IcelandProductParser(
    private val retailerId: RetailerId = RetailerId.ICELAND,
    private val priceScope: RetailPriceScope = RetailPriceScope.ONLINE,
    private val useStructuredAvailability: Boolean = true
) {

    private val json = Json { ignoreUnknownKeys = true }

    fun parseHtmlPage(htmlContent: String): List<ParsedProductRecord> {
        val records = mutableListOf<ParsedProductRecord>()

        // 1. Extract JSON-LD script blocks
        val jsonLdBlocks = extractJsonLdBlocks(htmlContent)
        for (ldString in jsonLdBlocks) {
            try {
                val element = json.parseToJsonElement(ldString)
                if (element is JsonObject) {
                    val type = element["@type"]?.jsonPrimitive?.content
                    if (type.equals("Product", ignoreCase = true)) {
                        val parsedRecord = parseJsonLdProductObject(element, htmlContent)
                        if (parsedRecord != null) {
                            records.add(parsedRecord)
                        }
                    } else if (type.equals("ItemList", ignoreCase = true)) {
                        val items = element["itemListElement"]?.jsonArray
                        items?.forEach { itemElem ->
                            val prodObj = if (itemElem is JsonObject && itemElem.containsKey("item")) {
                                itemElem["item"] as? JsonObject
                            } else if (itemElem is JsonObject) {
                                itemElem
                            } else null

                            if (prodObj != null) {
                                val parsedRecord = parseJsonLdProductObject(prodObj, htmlContent)
                                if (parsedRecord != null) {
                                    records.add(parsedRecord)
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignore malformed JSON-LD block
            }
        }

        return records
    }

    /**
     * Parse Algolia's standard { hits: [...] } response shape. Product fields vary with
     * index configuration, so accept a small set of common names but require a stable ID,
     * a product name, and a valid price before emitting an offer.
     */
    fun parseAlgoliaResponse(responseJson: String): List<ParsedProductRecord> {
        val root = runCatching { json.parseToJsonElement(responseJson).jsonObject }.getOrNull()
            ?: return emptyList()
        val hits = root["hits"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return emptyList()
        return hits.mapNotNull { element ->
            runCatching {
                val hit = element.jsonObject
                fun value(vararg keys: String): String? {
                    for (key in keys) {
                        val direct = hit[key]?.let(::primitiveContent)
                        if (!direct.isNullOrBlank()) return direct
                    }
                    return null
                }

                val name = value("name", "title", "productName", "displayName") ?: return@runCatching null
                val id = value("sku", "productId", "product_id", "objectID", "id", "code")
                    ?.takeIf { it.isNotBlank() } ?: return@runCatching null
                val priceElement = listOf("price", "currentPrice", "sellingPrice", "unitPrice", "PRICE")
                    .firstNotNullOfOrNull { hit[it] }
                val priceText = priceElement?.let { price ->
                    primitiveContent(price) ?: (price as? JsonObject)?.let { nested ->
                        listOf("value", "amount", "current", "price").firstNotNullOfOrNull { nested[it]?.let(::primitiveContent) }
                    }
                } ?: return@runCatching null
                val money = parsePriceString(priceText) ?: return@runCatching null
                val availabilityText = value("availability", "stockStatus", "stock_status").orEmpty()
                val availability = when {
                    availabilityText.contains("outofstock", true) || availabilityText.equals("false", true) -> RetailAvailability.OUT_OF_STOCK
                    availabilityText.contains("instock", true) || availabilityText.equals("true", true) -> RetailAvailability.IN_STOCK
                    else -> RetailAvailability.UNKNOWN
                }
                val brand = value("brand", "manufacturer")
                val pack = parsePackString(name)
                val product = RetailProduct(
                    retailerId = retailerId,
                    retailerProductId = id,
                    name = name,
                    brand = brand ?: "Iceland",
                    packCount = pack.packCount,
                    packSize = pack.packSize,
                    packSizeUnit = pack.packSizeUnit,
                    netQuantity = pack.netQuantity,
                    netQuantityUnit = pack.netQuantityUnit,
                    storageClass = parseStorageClassFromName(name)
                )
                val offer = RetailOffer(
                    retailerId = retailerId,
                    retailerProductId = id,
                    price = money,
                    promotion = null,
                    availability = if (useStructuredAvailability) availability else RetailAvailability.UNKNOWN,
                    priceScope = priceScope,
                    observedAt = System.currentTimeMillis()
                )
                ParsedProductRecord(RetailProductWithOffer(product, offer))
            }.getOrNull()
        }
    }

    private fun primitiveContent(element: JsonElement): String? =
        runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()

    private fun parseJsonLdProductObject(jsonObj: JsonObject, fullHtml: String): ParsedProductRecord? {
        val name = jsonObj["name"]?.jsonPrimitive?.content ?: return null
        val url = jsonObj["@id"]?.jsonPrimitive?.content ?: jsonObj["url"]?.jsonPrimitive?.content
        val sku = jsonObj["sku"]?.jsonPrimitive?.content
            ?: url?.substringAfterLast("/")?.substringBefore(".html")
            ?: "${retailerId.name.lowercase()}_${Math.abs(name.hashCode())}"

        val offersObj = jsonObj["offers"]?.jsonObject ?: return null

        val jsonLdPrice = parsePriceFromOffers(offersObj) ?: return null

        // Check HTML card price for conflict verification
        val htmlPriceStr = extractHtmlPriceForSku(sku, fullHtml)
        val htmlPrice = htmlPriceStr?.let { parsePriceString(it) }

        val warnings = mutableListOf<String>()
        var finalPrice = jsonLdPrice

        if (htmlPrice != null && htmlPrice != jsonLdPrice) {
            warnings.add("Price conflict detected: JSON-LD price vs HTML price $htmlPriceStr. Using JSON-LD structured price.")
        }

        // Availability mapping according to evidence-based rule
        val ldAvailabilityStr = offersObj["availability"]?.jsonPrimitive?.content.orEmpty()

        val availability = when {
            ldAvailabilityStr.contains("InStock", ignoreCase = true) -> RetailAvailability.IN_STOCK
            ldAvailabilityStr.contains("OutOfStock", ignoreCase = true) -> RetailAvailability.OUT_OF_STOCK
            else -> RetailAvailability.UNKNOWN
        }

        val packMeta = parsePackString(name)
        val storageClass = parseStorageClassFromName(name)

        val product = RetailProduct(
            retailerId = retailerId,
            retailerProductId = sku,
            name = name,
            brand = if (retailerId == RetailerId.ICELAND) "Iceland" else retailerId.name.lowercase().replaceFirstChar { it.uppercase() },
            packCount = packMeta.packCount,
            packSize = packMeta.packSize,
            packSizeUnit = packMeta.packSizeUnit,
            netQuantity = packMeta.netQuantity,
            netQuantityUnit = packMeta.netQuantityUnit,
            storageClass = storageClass
        )

        val offer = RetailOffer(
            retailerId = retailerId,
            retailerProductId = sku,
            price = finalPrice,
            promotion = parsePromotionFromHtml(fullHtml),
            availability = availability,
            priceScope = RetailPriceScope.ONLINE,
            observedAt = System.currentTimeMillis()
        )

        return ParsedProductRecord(
            productWithOffer = RetailProductWithOffer(product, offer),
            warnings = warnings
        )
    }

    fun parsePriceString(text: String): Money? {
        val trimmed = text.trim()
        val clean = trimmed.replace("£", "").replace("p", "").trim()
        val numeric = clean.toDoubleOrNull() ?: return null

        return if (trimmed.endsWith("p") && !trimmed.contains("£")) {
            Money(numeric.toLong())
        } else {
            Money((numeric * 100).toLong())
        }
    }

    data class ParsedPackMeta(
        val packCount: Int?,
        val packSize: Double?,
        val packSizeUnit: String?,
        val netQuantity: Double?,
        val netQuantityUnit: String?
    )

    fun parsePackString(title: String): ParsedPackMeta {
        val lower = title.lowercase()

        // Match 4 x 415g or 4x415g
        val multiRegex = Regex("(\\d+)\\s*x\\s*(\\d+(?:\\.\\d+)?)\\s*(g|kg|ml|l)")
        val multiMatch = multiRegex.find(lower)
        if (multiMatch != null) {
            val count = multiMatch.groupValues[1].toIntOrNull()
            val size = multiMatch.groupValues[2].toDoubleOrNull()
            val unit = multiMatch.groupValues[3].uppercase()
            val net = if (count != null && size != null) count * size else null
            return ParsedPackMeta(count, size, unit, net, unit)
        }

        // Match single 1kg or 500g or 750ml
        val singleRegex = Regex("(\\d+(?:\\.\\d+)?)\\s*(g|kg|ml|l)")
        val singleMatch = singleRegex.find(lower)
        if (singleMatch != null) {
            val size = singleMatch.groupValues[1].toDoubleOrNull()
            val unit = singleMatch.groupValues[2].uppercase()
            return ParsedPackMeta(packCount = 1, packSize = size, packSizeUnit = unit, netQuantity = size, netQuantityUnit = unit)
        }

        return ParsedPackMeta(null, null, null, null, null)
    }

    private fun parseStorageClassFromName(name: String): RetailStorageClass {
        val lower = name.lowercase()
        return when {
            lower.contains("frozen") -> RetailStorageClass.FROZEN
            lower.contains("fresh") || lower.contains("chilled") -> RetailStorageClass.FRESH
            else -> RetailStorageClass.AMBIENT
        }
    }

    private fun parsePromotionFromHtml(html: String): RetailPromotion? {
        if (html.contains("Was £") && html.contains("Now £")) {
            val wasIndex = html.indexOf("Now £") + 5
            var end = wasIndex
            while (end < html.length && (html[end].isDigit() || html[end] == '.')) end++
            val promoPriceStr = html.substring(wasIndex, end)
            val promoPrice = parsePriceString("£$promoPriceStr")
            if (promoPrice != null) {
                return RetailPromotion.SalePrice(promoPrice, RetailPromotionScope.SAME_PRODUCT)
            }
        }
        return null
    }

    private fun parsePriceFromOffers(offersObj: JsonObject): Money? {
        val priceStr = offersObj["price"]?.jsonPrimitive?.content
        if (priceStr != null) {
            val parsed = parsePriceString(priceStr)
            if (parsed != null) return parsed
        }

        val priceSpecElem = offersObj["priceSpecification"]
        if (priceSpecElem is JsonObject) {
            val specPriceStr = priceSpecElem["price"]?.jsonPrimitive?.content
            if (specPriceStr != null) {
                return parsePriceString(specPriceStr)
            }
        } else if (priceSpecElem is JsonArray && priceSpecElem.isNotEmpty()) {
            val firstSpec = priceSpecElem.first() as? JsonObject
            val specPriceStr = firstSpec?.get("price")?.jsonPrimitive?.content
            if (specPriceStr != null) {
                return parsePriceString(specPriceStr)
            }
        }

        return null
    }

    private fun extractJsonLdBlocks(html: String): List<String> {
        val blocks = mutableListOf<String>()
        val regex = Regex("""<script[^>]*type=["']application/ld\+json["'][^>]*>(.*?)</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        regex.findAll(html).forEach { match ->
            val content = match.groupValues[1].trim()
            if (content.isNotEmpty()) {
                blocks.add(content)
            }
        }
        return blocks
    }

    private fun extractHtmlPriceForSku(sku: String, html: String): String? {
        if (!html.contains(sku)) return null
        val skuIdx = html.indexOf(sku)
        val priceIdx = html.indexOf("£", skuIdx)
        if (priceIdx != -1 && priceIdx - skuIdx < 500) {
            var end = priceIdx + 1
            while (end < html.length && (html[end].isDigit() || html[end] == '.')) end++
            return html.substring(priceIdx, end)
        }
        return null
    }
}
