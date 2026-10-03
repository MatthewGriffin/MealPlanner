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
}
