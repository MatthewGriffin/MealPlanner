package com.example.chatgpt_mealplanner.retail.contract

import kotlinx.serialization.Serializable

/** A user-selected physical branch, kept separate from retailer-wide online offers. */
@Serializable
data class RetailStoreLocation(
    val id: String,
    val retailerId: RetailerId,
    val name: String,
    val postcode: String,
    val format: RetailStoreFormat,
    val isPreferred: Boolean = true
)

@Serializable
enum class RetailStoreFormat {
    ASDA_EXPRESS,
    ASDA_SUPERMARKET,
    ASDA_SUPERSTORE,
    ASDA_SUPERCENTRE,
    TESCO_EXPRESS,
    TESCO_METRO,
    TESCO_SUPERMARKET,
    TESCO_SUPERSTORE,
    TESCO_EXTRA,
    MORRISONS_DAILY,
    MORRISONS_SUPERMARKET,
    SAINSBURYS_LOCAL,
    SAINSBURYS_SUPERMARKET,
    SAINSBURYS_SUPERSTORE,
    ICELAND,
    FOOD_WAREHOUSE,
    UNKNOWN
}
