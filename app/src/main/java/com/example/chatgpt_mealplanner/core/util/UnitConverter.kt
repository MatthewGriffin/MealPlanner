package com.example.chatgpt_mealplanner.core.util

import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.QuantityUnit

object UnitConverter {

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

        // Pack / Container Conversions when packSize metadata is present
        if ((from == QuantityUnit.PACK || from == QuantityUnit.TIN || from == QuantityUnit.BOTTLE || from == QuantityUnit.BAG) &&
            packSize != null && packSizeUnit != null
        ) {
            val totalInPackUnit = value * packSize
            return convert(totalInPackUnit, packSizeUnit, to)
        }

        // Disallowed conversion
        return DomainResult.Failure(
            DomainError.InvalidUnitConversion(from = from, to = to)
        )
    }
}
