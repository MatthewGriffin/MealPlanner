package com.example.chatgpt_mealplanner.retail.engine

import com.example.chatgpt_mealplanner.retail.contract.Money
import com.example.chatgpt_mealplanner.retail.contract.RetailAvailability
import com.example.chatgpt_mealplanner.retail.contract.RetailOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailOfferSourceType
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RetailObservationPolicyTest {

    @Test
    fun freshestObservationPolicy_selectsNewerManualObservation_overStaleCache() {
        val staleCacheOffer = RetailOffer(
            retailerId = RetailerId.ASDA,
            retailerProductId = "asda_chick",
            price = Money(400L),
            availability = RetailAvailability.IN_STOCK,
            sourceType = RetailOfferSourceType.CACHE,
            observedAt = System.currentTimeMillis() - (5 * 24 * 60 * 60 * 1000L) // 5 days old
        )

        val freshManualOffer = RetailOffer(
            retailerId = RetailerId.ASDA,
            retailerProductId = "asda_chick",
            price = Money(350L),
            availability = RetailAvailability.IN_STOCK,
            sourceType = RetailOfferSourceType.MANUAL,
            observedAt = System.currentTimeMillis() - (10 * 60 * 1000L) // 10 minutes ago
        )

        val selected = listOf(staleCacheOffer, freshManualOffer).maxByOrNull { it.observedAt }

        assertEquals(freshManualOffer, selected)
        assertEquals(RetailOfferSourceType.MANUAL, selected?.sourceType)
    }
}
