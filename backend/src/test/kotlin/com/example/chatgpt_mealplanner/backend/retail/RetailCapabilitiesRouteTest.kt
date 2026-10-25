package com.example.chatgpt_mealplanner.backend.retail

import com.example.chatgpt_mealplanner.backend.module
import com.example.chatgpt_mealplanner.retail.contract.RetailerCapabilityMode
import com.example.chatgpt_mealplanner.retail.contract.RetailerCapabilitiesResponse
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class RetailCapabilitiesRouteTest {

    @Test
    fun getCapabilitiesDistinguishesLiveAndBranchOnlyRetailers() = testApplication {
        application { module() }

        val response = client.get("/v1/retail/capabilities")
        assertEquals(HttpStatusCode.OK, response.status)

        val jsonStr = response.bodyAsText()
        val capsRes = Json { ignoreUnknownKeys = true }.decodeFromString<RetailerCapabilitiesResponse>(jsonStr)

        assertEquals(8, capsRes.capabilities.size)

        val icelandCap = capsRes.capabilities.first { it.retailerId == RetailerId.ICELAND }
        assertEquals(RetailerCapabilityMode.LIVE_SEARCH_AVAILABLE, icelandCap.mode)

        val asdaCap = capsRes.capabilities.first { it.retailerId == RetailerId.ASDA }
        assertEquals(RetailerCapabilityMode.LIVE_SEARCH_AVAILABLE, asdaCap.mode)
        assertEquals(true, asdaCap.searchSupported)
        assertEquals(true, asdaCap.livePriceSupported)
        assertEquals(false, asdaCap.availabilitySupported)
        assertEquals(true, asdaCap.promotionsSupported)

        val fwCap = capsRes.capabilities.first { it.retailerId == RetailerId.FOOD_WAREHOUSE }
        assertEquals(RetailerCapabilityMode.SHARED_CATALOGUE_WITH_ICELAND, fwCap.mode)

        val aldiCap = capsRes.capabilities.first { it.retailerId == RetailerId.ALDI }
        assertEquals(RetailerCapabilityMode.LIVE_SEARCH_AVAILABLE, aldiCap.mode)
        assertEquals(true, aldiCap.searchSupported)
        assertEquals(true, aldiCap.livePriceSupported)
        assertEquals(false, aldiCap.availabilitySupported)

        val tescoCap = capsRes.capabilities.first { it.retailerId == RetailerId.TESCO }
        assertEquals(RetailerCapabilityMode.LIVE_SEARCH_AVAILABLE, tescoCap.mode)
        assertEquals(true, tescoCap.searchSupported)
        assertEquals(true, tescoCap.livePriceSupported)
        assertEquals(false, tescoCap.availabilitySupported)

        listOf(RetailerId.MORRISONS, RetailerId.SAINSBURYS, RetailerId.LIDL).forEach { retailerId ->
            val capability = capsRes.capabilities.first { it.retailerId == retailerId }
            assertEquals(RetailerCapabilityMode.LIVE_SEARCH_AVAILABLE, capability.mode)
            assertEquals(true, capability.searchSupported)
            assertEquals(true, capability.livePriceSupported)
            assertEquals(false, capability.availabilitySupported)
            assertEquals(false, capability.promotionsSupported)
        }
    }
}
