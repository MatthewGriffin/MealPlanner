package com.example.chatgpt_mealplanner.retail.contract

import kotlinx.serialization.Serializable

@Serializable
enum class StoreDirectorySource { OPENSTREETMAP, RETAILER_DIRECTORY }

/** Directory metadata, not a retailer-issued branch ID or proof of branch-level prices. */
@Serializable
data class StoreDirectoryEntry(
    val directorySource: StoreDirectorySource,
    val directorySourceId: String,
    val retailerId: RetailerId,
    val name: String,
    val address: String,
    val postcode: String? = null,
    val latitude: Double,
    val longitude: Double,
    val format: RetailStoreFormat = RetailStoreFormat.UNKNOWN,
    val distanceMeters: Int? = null
)

@Serializable
data class StoreDirectorySearchRequest(
    val retailerId: RetailerId,
    val postcode: String? = null,
    /** When true, return all supported retailers for this area in one shared search. */
    val searchAllRetailers: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusMeters: Int = 5_000
)

@Serializable
data class StoreDirectorySearchResponse(
    val stores: List<StoreDirectoryEntry>,
    val source: StoreDirectorySource = StoreDirectorySource.OPENSTREETMAP,
    val sourceAttribution: String = "© OpenStreetMap contributors",
    val attributionUrl: String = "https://www.openstreetmap.org/copyright",
    val observedAt: Long,
    val fromCache: Boolean = false,
    val warnings: List<String> = emptyList()
)
