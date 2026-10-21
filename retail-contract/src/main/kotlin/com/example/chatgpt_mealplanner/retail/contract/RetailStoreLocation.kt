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
    val isPreferred: Boolean = true,
    /**
     * Stable identifier supplied by the retailer's authorised store directory/feed.
     * This is distinct from [id], which is the app's local preference-record ID.
     */
    val retailerStoreId: String? = null,
    /** Source record for branch discovery; never treated as a retailer-issued branch ID. */
    val directorySource: StoreDirectorySource? = null,
    val directorySourceId: String? = null
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
