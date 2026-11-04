package com.example.chatgpt_mealplanner.core.service

import com.example.chatgpt_mealplanner.core.database.entity.PurchasePriceObservationEntity
import com.example.chatgpt_mealplanner.core.database.entity.PurchasePriceWithStore
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.parseQuantityUnit
import kotlin.math.ceil
import kotlin.math.max

/**
 * One shop's recorded price for a shopping item. The cost fields are null when the amount needed
 * and the pack size can't be compared, such as a missing pack size or grams against tins.
 */
data class ShopPriceOption(
    val price: PurchasePriceWithStore,
    val packsToBuy: Int?,
    val totalCostMinorUnits: Long?,
    val purchasedQuantity: Double?,
    val purchasedUnit: QuantityUnit?,
    val isBest: Boolean = false
)

/**
 * Ranks shops using prices the user recorded. The best option is the lowest total spend that
 * buys enough whole packs to cover the amount needed. Price per unit only breaks ties, followed
 * by the most recently recorded price.
 */
object ShopPriceComparator {

    private const val PACK_ROUNDING_TOLERANCE = 1e-9

    private data class Amount(val dimension: QuantityUnit, val baseQuantity: Double)

    private data class Candidate(
        val option: ShopPriceOption,
        val pricePerBaseUnit: Double?
    )

    /**
     * Matches on the exact concept or name only. A loose text match could pair "milk" with
     * "milk chocolate", and a confident wrong price is worse than no price.
     */
    fun matchesItem(
        canonicalIngredientId: String?,
        displayName: String,
        observation: PurchasePriceObservationEntity
    ): Boolean {
        val itemKeys = setOfNotNull(normalize(canonicalIngredientId), normalize(displayName))
        val observationKeys = setOfNotNull(normalize(observation.canonicalName), normalize(observation.productName))
        return itemKeys.any { it in observationKeys }
    }

    /** Returns one option per shop, best first. [prices] may contain a product's older prices. */
    fun compare(
        neededQuantity: Double?,
        neededUnit: QuantityUnit?,
        prices: List<PurchasePriceWithStore>
    ): List<ShopPriceOption> {
        val needed = toAmount(neededQuantity, neededUnit)
        val latestPerProduct = prices
            .sortedByDescending { it.observation.purchasedAt }
            .distinctBy { price ->
                val observation = price.observation
                listOf(
                    observation.storeId,
                    observation.barcode ?: normalize(observation.productName),
                    observation.packQuantity,
                    observation.packUnit,
                    observation.packCount
                )
            }

        val bestPerStore = latestPerProduct
            .map { buildCandidate(it, needed) }
            .groupBy { it.option.price.observation.storeId }
            .values
            .map { candidates -> candidates.sortedWith(RANKING).first() }
            .sortedWith(RANKING)

        return bestPerStore.mapIndexed { index, candidate ->
            candidate.option.copy(isBest = index == 0 && candidate.option.totalCostMinorUnits != null)
        }
    }

    private fun buildCandidate(price: PurchasePriceWithStore, needed: Amount?): Candidate {
        val observation = price.observation
        val packCount = max(observation.packCount ?: 1, 1)
        val packAmount = toAmount(
            observation.packQuantity?.let { it * packCount },
            observation.packUnit?.takeIf { it.isNotBlank() }?.let(::parseQuantityUnit)
        )
        val pricePerBaseUnit = packAmount?.let { observation.priceMinorUnits / it.baseQuantity }

        if (needed == null || packAmount == null || needed.dimension != packAmount.dimension) {
            return Candidate(
                option = ShopPriceOption(price, null, null, null, null),
                pricePerBaseUnit = pricePerBaseUnit
            )
        }

        val packsToBuy = max(ceil(needed.baseQuantity / packAmount.baseQuantity - PACK_ROUNDING_TOLERANCE).toInt(), 1)
        return Candidate(
            option = ShopPriceOption(
                price = price,
                packsToBuy = packsToBuy,
                totalCostMinorUnits = observation.priceMinorUnits * packsToBuy,
                purchasedQuantity = packAmount.baseQuantity * packsToBuy,
                purchasedUnit = packAmount.dimension
            ),
            pricePerBaseUnit = pricePerBaseUnit
        )
    }

    private val RANKING = compareBy<Candidate> { it.option.totalCostMinorUnits == null }
        .thenBy { it.option.totalCostMinorUnits ?: Long.MAX_VALUE }
        .thenBy { it.pricePerBaseUnit ?: Double.MAX_VALUE }
        .thenByDescending { it.option.price.observation.purchasedAt }
        .thenBy { it.option.price.storeName }

    private fun toAmount(quantity: Double?, unit: QuantityUnit?): Amount? {
        if (quantity == null || unit == null || !quantity.isFinite() || quantity <= 0.0) return null
        return when (unit) {
            QuantityUnit.KILOGRAM -> Amount(QuantityUnit.GRAM, quantity * 1000.0)
            QuantityUnit.LITRE -> Amount(QuantityUnit.MILLILITRE, quantity * 1000.0)
            else -> Amount(unit, quantity)
        }
    }

    private fun normalize(value: String?): String? =
        value?.trim()?.lowercase()?.replace('_', ' ')?.takeIf { it.isNotEmpty() }
}
