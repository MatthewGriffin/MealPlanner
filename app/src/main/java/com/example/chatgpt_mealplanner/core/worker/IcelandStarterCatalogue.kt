package com.example.chatgpt_mealplanner.core.worker

import com.example.chatgpt_mealplanner.retail.contract.Money
import com.example.chatgpt_mealplanner.retail.contract.RetailAvailability
import com.example.chatgpt_mealplanner.retail.contract.RetailOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailOfferSourceType
import com.example.chatgpt_mealplanner.retail.contract.RetailPriceScope
import com.example.chatgpt_mealplanner.retail.contract.RetailProduct
import com.example.chatgpt_mealplanner.retail.contract.RetailProductWithOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailStorageClass
import com.example.chatgpt_mealplanner.retail.contract.RetailerId

/**
 * Small bundled first-launch fallback, not a complete catalogue.
 *
 * Prices were checked against Iceland's public online category pages on 2026-10-23.
 * IDs are local starter IDs, not Iceland product identifiers. All offers are estimates
 * for online catalogue pricing; availability is deliberately UNKNOWN.
 */
internal object IcelandStarterCatalogue {
    const val CHECKED_AT = 1792713600000L // 2026-10-23T00:00:00Z

    fun products(): List<RetailProductWithOffer> = listOf(
        item("STARTER_FROZEN_BROCCOLI_1KG", "Iceland Broccoli Florets 1kg", 200, 1.0, "kg", RetailStorageClass.FROZEN, "Frozen vegetables"),
        item("STARTER_FROZEN_PEPPERS_650G", "Iceland Mixed Peppers 650g", 200, 650.0, "g", RetailStorageClass.FROZEN, "Frozen vegetables"),
        item("STARTER_FROZEN_ONIONS_500G", "Iceland Diced Onions 500g", 125, 500.0, "g", RetailStorageClass.FROZEN, "Frozen vegetables"),
        item("STARTER_FROZEN_PEAS_950G", "Iceland Garden Peas 950g", 200, 950.0, "g", RetailStorageClass.FROZEN, "Frozen vegetables"),
        item("STARTER_FROZEN_SWEETCORN_860G", "Iceland Supersweet Sweetcorn 860g", 200, 860.0, "g", RetailStorageClass.FROZEN, "Frozen vegetables"),
        item("STARTER_FROZEN_MIXED_VEG_1200G", "Iceland Mixed Vegetables 1.2kg", 200, 1.2, "kg", RetailStorageClass.FROZEN, "Frozen vegetables"),
        item("STARTER_FROZEN_CAULIFLOWER_1KG", "Iceland Cauliflower Florets 1kg", 200, 1.0, "kg", RetailStorageClass.FROZEN, "Frozen vegetables"),
        item("STARTER_FROZEN_CARROTS_1KG", "Iceland Sliced Carrots 1kg", 200, 1.0, "kg", RetailStorageClass.FROZEN, "Frozen vegetables"),
        item("STARTER_FROZEN_BEANS_800G", "Iceland Sliced Green Beans 800g", 200, 800.0, "g", RetailStorageClass.FROZEN, "Frozen vegetables"),
        item("STARTER_CHICKEN_FILLET_600G", "Iceland Chicken Breast Fillets 600g", 400, 600.0, "g", RetailStorageClass.FROZEN, "Frozen meat"),
        item("STARTER_CHIPS_1250G", "Iceland Straight Cut Chips 1.25kg", 200, 1.25, "kg", RetailStorageClass.FROZEN, "Frozen potatoes"),
        item("STARTER_BEEF_MINCE_400G", "Iceland Lean Beef Steak Mince 400g", 400, 400.0, "g", RetailStorageClass.FROZEN, "Frozen meat"),
        item("STARTER_SAUSAGES_1800G", "Butchers Market Value Sausages 1.8kg", 300, 1.8, "kg", RetailStorageClass.FROZEN, "Frozen meat"),
        item("STARTER_POTATOES_1600G", "Iceland White Potatoes 1.6kg", 175, 1.6, "kg", RetailStorageClass.FRESH, "Vegetables"),
        item("STARTER_CARROTS_1KG", "Iceland Carrots 1kg", 100, 1.0, "kg", RetailStorageClass.FRESH, "Vegetables"),
        item("STARTER_MUSHROOMS_300G", "Iceland Closed Cup Mushrooms 300g", 100, 300.0, "g", RetailStorageClass.FRESH, "Vegetables"),
        item("STARTER_ONIONS_3PK", "Iceland 3pk Onions", 100, null, null, RetailStorageClass.FRESH, "Vegetables"),
        item("STARTER_EGGS_6PK", "Iceland 6 Large Free Range British Eggs", 200, null, null, RetailStorageClass.CHILLED, "Eggs"),
        item("STARTER_HAM_210G", "Iceland Wafer Thin Honey Roast Ham 210g", 200, 210.0, "g", RetailStorageClass.CHILLED, "Chilled meat")
    )

    private fun item(
        id: String,
        name: String,
        pricePence: Long,
        quantity: Double?,
        quantityUnit: String?,
        storage: RetailStorageClass,
        category: String
    ): RetailProductWithOffer {
        val product = RetailProduct(
            retailerId = RetailerId.ICELAND,
            retailerProductId = id,
            name = name,
            brand = name.substringBefore(' '),
            category = category,
            netQuantity = quantity,
            netQuantityUnit = quantityUnit,
            storageClass = storage
        )
        val offer = RetailOffer(
            retailerId = RetailerId.ICELAND,
            retailerProductId = id,
            price = Money(pricePence, "GBP"),
            availability = RetailAvailability.UNKNOWN,
            priceScope = RetailPriceScope.ONLINE,
            sourceType = RetailOfferSourceType.CACHE,
            observedAt = CHECKED_AT
        )
        return RetailProductWithOffer(product, offer)
    }
}
