package com.example.chatgpt_mealplanner.retail.contract

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class RetailContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun moneyAndEnumsSerialization_roundTripsCorrectly() {
        val money = Money(380L, "GBP")
        val serializedMoney = json.encodeToString(Money.serializer(), money)
        val deserializedMoney = json.decodeFromString<Money>(serializedMoney)
        assertEquals(money, deserializedMoney)

        val scope = RetailPriceScope.ONLINE
        val serializedScope = json.encodeToString(RetailPriceScope.serializer(), scope)
        val deserializedScope = json.decodeFromString<RetailPriceScope>(serializedScope)
        assertEquals(scope, deserializedScope)

        val sourceType = RetailOfferSourceType.LIVE
        val serializedSourceType = json.encodeToString(RetailOfferSourceType.serializer(), sourceType)
        val deserializedSourceType = json.decodeFromString<RetailOfferSourceType>(serializedSourceType)
        assertEquals(sourceType, deserializedSourceType)
    }

    @Test
    fun preferredStoreLocation_roundTripsWithExactBranchFormat() {
        val store = RetailStoreLocation(
            id = "branch-1",
            retailerId = RetailerId.TESCO,
            name = "High Street",
            postcode = "AB1 2CD",
            format = RetailStoreFormat.TESCO_EXPRESS,
            isPreferred = true,
            retailerStoreId = "tesco-store-1"
        )

        val encoded = json.encodeToString(RetailStoreLocation.serializer(), store)
        val decoded = json.decodeFromString<RetailStoreLocation>(encoded)

        assertEquals(store, decoded)
    }
    @Test
    fun preferredBranchOfferAndShoppingPreferences_roundTrip() {
        val store = RetailStoreLocation(
            id = "branch-1",
            retailerId = RetailerId.TESCO,
            name = "High Street",
            postcode = "AB1 2CD",
            format = RetailStoreFormat.TESCO_EXPRESS,
            retailerStoreId = "tesco-store-1"
        )
        val offer = RetailOffer(
            retailerId = RetailerId.TESCO,
            retailerProductId = "milk-1",
            price = Money(150L),
            priceScope = RetailPriceScope.STORE_SPECIFIC,
            storeLocationId = store.retailerStoreId,
            availability = RetailAvailability.IN_STOCK
        )
        val preferences = UserShoppingPreferences(
            enabledRetailers = setOf(RetailerId.TESCO, RetailerId.MORRISONS),
            preferredStores = listOf(store)
        )

        val decodedOffer = json.decodeFromString(
            RetailOffer.serializer(),
            json.encodeToString(RetailOffer.serializer(), offer)
        )
        val decodedPreferences = json.decodeFromString(
            UserShoppingPreferences.serializer(),
            json.encodeToString(UserShoppingPreferences.serializer(), preferences)
        )

        assertEquals(offer, decodedOffer)
        assertEquals(preferences, decodedPreferences)
    }

}
