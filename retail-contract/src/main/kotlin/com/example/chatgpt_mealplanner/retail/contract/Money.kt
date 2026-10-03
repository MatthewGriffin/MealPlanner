package com.example.chatgpt_mealplanner.retail.contract

import kotlinx.serialization.Serializable

@Serializable
data class Money(
    val amountMinor: Long, // Integer pence (e.g. 138L = £1.38)
    val currencyCode: String = "GBP"
) : Comparable<Money> {

    operator fun plus(other: Money): Money {
        require(currencyCode == other.currencyCode) { "Currency mismatch: $currencyCode vs ${other.currencyCode}" }
        return Money(amountMinor + other.amountMinor, currencyCode)
    }

    operator fun minus(other: Money): Money {
        require(currencyCode == other.currencyCode) { "Currency mismatch: $currencyCode vs ${other.currencyCode}" }
        return Money(amountMinor - other.amountMinor, currencyCode)
    }

    operator fun times(multiplier: Int): Money {
        return Money(amountMinor * multiplier, currencyCode)
    }

    override fun compareTo(other: Money): Int {
        require(currencyCode == other.currencyCode) { "Currency mismatch: $currencyCode vs ${other.currencyCode}" }
        return amountMinor.compareTo(other.amountMinor)
    }

    fun toFormattedString(): String {
        val pounds = amountMinor / 100
        val pence = Math.abs(amountMinor % 100)
        val symbol = if (currencyCode == "GBP") "£" else "$currencyCode "
        return "$symbol$pounds.${pence.toString().padStart(2, '0')}"
    }

    companion object {
        fun zero(currencyCode: String = "GBP") = Money(0L, currencyCode)
        fun fromPoundsPence(pounds: Long, pence: Long, currencyCode: String = "GBP") =
            Money(pounds * 100L + pence, currencyCode)
    }
}
