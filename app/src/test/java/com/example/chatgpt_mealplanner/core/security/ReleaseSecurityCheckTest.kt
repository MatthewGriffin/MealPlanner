package com.example.chatgpt_mealplanner.core.security

import com.example.chatgpt_mealplanner.contract.AI_CONTRACT_VERSION
import com.example.chatgpt_mealplanner.retail.contract.Money
import com.example.chatgpt_mealplanner.retail.contract.RetailAvailability
import com.example.chatgpt_mealplanner.retail.contract.RetailOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailOfferSourceType
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ReleaseSecurityCheckTest {

    @Test
    fun productionContract_verifiesV2ContractVersion() {
        assertEquals("ai-contract-v2", AI_CONTRACT_VERSION)
    }

    @Test
    fun productionOfferSourceType_isNotFixture() {
        val prodOffer = RetailOffer(
            retailerId = RetailerId.ICELAND,
            retailerProductId = "p1",
            price = Money(180L),
            availability = RetailAvailability.IN_STOCK,
            sourceType = RetailOfferSourceType.LIVE
        )

        assertFalse(prodOffer.sourceType == RetailOfferSourceType.FIXTURE)
    }
}
