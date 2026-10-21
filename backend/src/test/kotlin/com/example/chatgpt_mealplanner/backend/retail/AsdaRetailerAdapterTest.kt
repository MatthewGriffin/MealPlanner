package com.example.chatgpt_mealplanner.backend.retail

import com.example.chatgpt_mealplanner.retail.contract.RetailProductSearchResult
import com.example.chatgpt_mealplanner.retail.contract.RetailerCapabilityMode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AsdaRetailerAdapterTest {

    @Test
    fun capabilityDoesNotAdvertiseUnapprovedLiveData() {
        val capability = AsdaRetailerAdapter().getCapability()

        assertEquals(RetailerCapabilityMode.MANUAL_AND_CACHED_ONLY, capability.mode)
        assertFalse(capability.searchSupported)
        assertFalse(capability.livePriceSupported)
        assertFalse(capability.availabilitySupported)
        assertFalse(capability.promotionsSupported)
    }

    @Test
    fun searchReturnsPermissionRequiredWithoutCallingUndocumentedEndpoint() = runBlocking {
        val result = AsdaRetailerAdapter().searchProducts("milk")

        val unavailable = assertIs<RetailProductSearchResult.Unavailable>(result)
        assertEquals("SOURCE_PERMISSION_REQUIRED", unavailable.errorCode)
        assertTrue(unavailable.cachedFallbackAvailable)
    }
}
