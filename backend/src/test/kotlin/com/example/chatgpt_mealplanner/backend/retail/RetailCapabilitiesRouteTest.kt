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
import kotlin.test.assertNotNull

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

        val fwCap = capsRes.capabilities.first { it.retailerId == RetailerId.FOOD_WAREHOUSE }
        assertEquals(RetailerCapabilityMode.SHARED_CATALOGUE_WITH_ICELAND, fwCap.mode)

        listOf(RetailerId.TESCO, RetailerId.MORRISONS, RetailerId.SAINSBURYS, RetailerId.ALDI, RetailerId.LIDL).forEach { retailerId ->
            val capability = capsRes.capabilities.first { it.retailerId == retailerId }
            assertEquals(RetailerCapabilityMode.MANUAL_AND_CACHED_ONLY, capability.mode)
            assertEquals(false, capability.searchSupported)
            assertEquals(false, capability.livePriceSupported)
        }
    }
}
