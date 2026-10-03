package com.example.chatgpt_mealplanner.backend.retail

import com.example.chatgpt_mealplanner.retail.contract.Money
import com.example.chatgpt_mealplanner.retail.contract.RetailAvailability
import com.example.chatgpt_mealplanner.retail.contract.RetailOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailOfferSourceType
import com.example.chatgpt_mealplanner.retail.contract.RetailPriceScope
import com.example.chatgpt_mealplanner.retail.contract.RetailProduct
import com.example.chatgpt_mealplanner.retail.contract.RetailProductWithOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailPromotion
import com.example.chatgpt_mealplanner.retail.contract.RetailPromotionScope
import com.example.chatgpt_mealplanner.retail.contract.RetailStorageClass
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode

class AsdaProductParser {

    private val json = Json { ignoreUnknownKeys = true }

    data class ParseResult(
        val productsWithOffers: List<RetailProductWithOffer>,
        val hitsReceived: Int,
        val hitsAccepted: Int,
        val hitsRejected: Int
    )

    fun parseAlgoliaResponse(responseJson: String): ParseResult {
        val parsed = mutableListOf<RetailProductWithOffer>()
        var received = 0
        var accepted = 0
        var rejected = 0

        try {
            val root = json.parseToJsonElement(responseJson).jsonObject
            val results = root["results"]?.jsonArray ?: return ParseResult(emptyList(), 0, 0, 0)
            val firstResult = results.firstOrNull()?.jsonObject ?: return ParseResult(emptyList(), 0, 0, 0)
            val hits = firstResult["hits"]?.jsonArray ?: return ParseResult(emptyList(), 0, 0, 0)

            received = hits.size

            for (hitElement in hits) {
                try {
                    val hit = hitElement.jsonObject
                    val cin = hit["CIN"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: hit["objectID"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: hit["ID"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

                    if (cin == null) {
                        rejected++
                        continue
                    }

                    val name = hit["NAME"]?.jsonPrimitive?.contentOrNull ?: "Unknown Product"
                    val brand = hit["BRAND"]?.jsonPrimitive?.contentOrNull
                    val packSizeStr = hit["PACK_SIZE"]?.jsonPrimitive?.contentOrNull

                    val pricesObj = hit["PRICES"]?.jsonObject?.get("EN")?.jsonObject
                    val priceElem = pricesObj?.get("PRICE")
                    val pricePence = parsePriceToMinorPence(priceElem)
                    if (pricePence == null) {
                        rejected++
                        continue
                    }

                    val wasPriceElem = pricesObj?.get("WASPRICE")
                    val wasPricePence = parsePriceToMinorPence(wasPriceElem)
                    val offerType = pricesObj?.get("OFFER")?.jsonPrimitive?.contentOrNull
                    val promosArray = hit["PROMOS"]?.jsonObject?.get("EN")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

                    val promotion = parsePromotion(pricePence, wasPricePence, offerType, promosArray)

                    val (netQty, netUnit, packSz, packUnit) = parsePackSize(packSizeStr)

                    val product = RetailProduct(
                        retailerId = RetailerId.ASDA,
                        retailerProductId = cin,
                        name = name,
                        brand = brand,
                        canonicalIngredientId = null,
                        barcode = null,
                        category = null,
                        imageUrl = null,
                        packageType = null,
                        netQuantity = netQty,
                        netQuantityUnit = netUnit,
                        packCount = null,
                        packSize = packSz,
                        packSizeUnit = packUnit,
                        storageClass = RetailStorageClass.UNKNOWN
                    )

                    val offer = RetailOffer(
                        retailerId = RetailerId.ASDA,
                        retailerProductId = cin,
                        price = Money(pricePence, "GBP"),
                        promotion = promotion,
                        availability = RetailAvailability.UNKNOWN,
                        priceScope = RetailPriceScope.ONLINE,
                        sourceType = RetailOfferSourceType.LIVE,
                        observedAt = System.currentTimeMillis()
                    )

                    parsed.add(RetailProductWithOffer(product, offer))
                    accepted++
                } catch (_: Exception) {
                    rejected++
                }
            }
        } catch (_: Exception) {
            // Malformed json root
        }

        return ParseResult(parsed, received, accepted, rejected)
    }

    private fun parsePromotion(
        pricePence: Long,
        wasPricePence: Long?,
        offerType: String?,
        promos: List<String>
    ): RetailPromotion? {
        val allText = (listOfNotNull(offerType) + promos).joinToString(" ").lowercase()

        // Check for "Any N for £X"
        val multibuyRegex = Regex("any\\s+(\\d+)\\s+for\\s+£?(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
        val mbMatch = multibuyRegex.find(allText)
        if (mbMatch != null) {
            val reqQty = mbMatch.groupValues[1].toIntOrNull() ?: 2
            val totalStr = mbMatch.groupValues[2]
            val totalPence = parsePriceToMinorPence(kotlinx.serialization.json.JsonPrimitive(totalStr))
            if (totalPence != null) {
                return RetailPromotion.Multibuy(reqQty, Money(totalPence, "GBP"), RetailPromotionScope.SAME_PRODUCT)
            }
        }

        if (wasPricePence != null && pricePence < wasPricePence) {
            return RetailPromotion.SalePrice(Money(wasPricePence, "GBP"), RetailPromotionScope.SAME_PRODUCT)
        }

        return null
    }

    fun parsePriceToMinorPence(priceElement: JsonElement?): Long? {
        val priceStr = priceElement?.jsonPrimitive?.contentOrNull ?: return null
        return try {
            BigDecimal(priceStr).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
        } catch (_: Exception) {
            null
        }
    }

    private data class ParsedPack(
        val netQuantity: Double?,
        val netUnit: String?,
        val packSize: Double?,
        val packUnit: String?
    )

    private fun parsePackSize(packSizeStr: String?): ParsedPack {
        if (packSizeStr.isNullOrBlank()) return ParsedPack(null, null, null, null)
        val upper = packSizeStr.uppercase().trim()

        val regexGrams = Regex("(\\d+(?:\\.\\d+)?)\\s*(G|KG)")
        val regexMl = Regex("(\\d+(?:\\.\\d+)?)\\s*(ML|L)")
        val regexPack = Regex("(\\d+)\\s*(PACK|PC|PKS|SLICES)")

        val matchG = regexGrams.find(upper)
        if (matchG != null) {
            val num = matchG.groupValues[1].toDoubleOrNull()
            val unit = matchG.groupValues[2]
            val baseNum = if (unit == "KG") (num?.times(1000.0)) else num
            return ParsedPack(baseNum, "GRAM", baseNum, "GRAM")
        }

        val matchMl = regexMl.find(upper)
        if (matchMl != null) {
            val num = matchMl.groupValues[1].toDoubleOrNull()
            val unit = matchMl.groupValues[2]
            val baseNum = if (unit == "L") (num?.times(1000.0)) else num
            return ParsedPack(baseNum, "ML", baseNum, "ML")
        }

        val matchP = regexPack.find(upper)
        if (matchP != null) {
            val count = matchP.groupValues[1].toDoubleOrNull()
            return ParsedPack(count, "UNIT", count, "UNIT")
        }

        return ParsedPack(null, null, null, null)
    }
}
