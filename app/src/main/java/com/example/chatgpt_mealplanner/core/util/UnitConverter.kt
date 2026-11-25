package com.example.chatgpt_mealplanner.core.util

import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.QuantityUnit

object UnitConverter {

    private val CONTAINERS = setOf(QuantityUnit.PACK, QuantityUnit.TIN, QuantityUnit.BOTTLE, QuantityUnit.BAG)

    /** Units that count things rather than measure them. */
    val WHOLE_ITEMS = CONTAINERS + QuantityUnit.UNIT

    fun convert(
        value: Double,
        from: QuantityUnit,
        to: QuantityUnit,
        packSize: Double? = null,
        packSizeUnit: QuantityUnit? = null
    ): DomainResult<Double> {
        if (from == to) return DomainResult.Success(value)

        // Deterministic Weight Conversions
        if (from == QuantityUnit.KILOGRAM && to == QuantityUnit.GRAM) {
            return DomainResult.Success(value * 1000.0)
        }
        if (from == QuantityUnit.GRAM && to == QuantityUnit.KILOGRAM) {
            return DomainResult.Success(value / 1000.0)
        }

        // Deterministic Volume Conversions
        if (from == QuantityUnit.LITRE && to == QuantityUnit.MILLILITRE) {
            return DomainResult.Success(value * 1000.0)
        }
        if (from == QuantityUnit.MILLILITRE && to == QuantityUnit.LITRE) {
            return DomainResult.Success(value / 1000.0)
        }

        // What is inside one pack, when recorded: a weight or volume, or a number of items. Only for packs, tins, bottles and
        // bags: "1 UNIT onion" is one onion, never the whole 1 kg bag whose size the pantry knows.
        if (packSize != null && packSize > 0.0 && packSizeUnit != null) {
            if (from in CONTAINERS && packSizeUnit !in WHOLE_ITEMS) {
                val inner = convert(value * packSize, packSizeUnit, to)
                if (inner is DomainResult.Success) return inner
            }
            if (to in CONTAINERS && packSizeUnit !in WHOLE_ITEMS) {
                val inner = convert(value, from, packSizeUnit)
                if (inner is DomainResult.Success) return DomainResult.Success(inner.value / packSize)
            }
            // A box of 6 eggs: 1 pack is 6 units.
            if (packSizeUnit == QuantityUnit.UNIT) {
                if (from in CONTAINERS && to == QuantityUnit.UNIT) return DomainResult.Success(value * packSize)
                if (from == QuantityUnit.UNIT && to in CONTAINERS) return DomainResult.Success(value / packSize)
            }
        }

        // Otherwise a unit, a pack, a tin, a bottle and a bag each count one of the thing as bought.
        if (from in WHOLE_ITEMS && to in WHOLE_ITEMS) return DomainResult.Success(value)

        // Disallowed conversion
        return DomainResult.Failure(
            DomainError.InvalidUnitConversion(from = from, to = to)
        )
    }
}
