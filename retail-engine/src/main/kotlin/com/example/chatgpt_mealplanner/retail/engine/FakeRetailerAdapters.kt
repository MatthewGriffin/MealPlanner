package com.example.chatgpt_mealplanner.retail.engine

import com.example.chatgpt_mealplanner.retail.contract.Money
import com.example.chatgpt_mealplanner.retail.contract.RetailAvailability
import com.example.chatgpt_mealplanner.retail.contract.RetailOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailProduct
import com.example.chatgpt_mealplanner.retail.contract.RetailProductWithOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailPromotion
import com.example.chatgpt_mealplanner.retail.contract.RetailStorageClass
import com.example.chatgpt_mealplanner.retail.contract.RetailerId

object FakeRetailerFixtures {

    fun getAsdaFixtures(): List<RetailProductWithOffer> = listOf(
        RetailProductWithOffer(
            product = RetailProduct(
                retailerId = RetailerId.ASDA,
                retailerProductId = "asda_chicken_400g",
                name = "Asda Fresh Chicken Breast Portions",
                brand = "Asda",
                canonicalIngredientId = "CHICKEN",
                packCount = 1,
                packSize = 400.0,
                packSizeUnit = "GRAM",
                storageClass = RetailStorageClass.FRESH
            ),
            offer = RetailOffer(
                retailerId = RetailerId.ASDA,
                retailerProductId = "asda_chicken_400g",
                price = Money(380L), // £3.80
                availability = RetailAvailability.IN_STOCK
            )
        ),
        RetailProductWithOffer(
            product = RetailProduct(
                retailerId = RetailerId.ASDA,
                retailerProductId = "asda_broccoli_500g",
                name = "Asda Frozen Broccoli Florets",
                brand = "Asda",
                canonicalIngredientId = "BROCCOLI",
                packCount = 1,
                packSize = 500.0,
                packSizeUnit = "GRAM",
                storageClass = RetailStorageClass.FROZEN
            ),
            offer = RetailOffer(
                retailerId = RetailerId.ASDA,
                retailerProductId = "asda_broccoli_500g",
                price = Money(120L), // £1.20
                availability = RetailAvailability.IN_STOCK
            )
        ),
        RetailProductWithOffer(
            product = RetailProduct(
                retailerId = RetailerId.ASDA,
                retailerProductId = "asda_beans_400g",
                name = "Asda Baked Beans in Tomato Sauce",
                brand = "Asda",
                canonicalIngredientId = "BAKED_BEANS",
                packCount = 1,
                packSize = 410.0,
                packSizeUnit = "GRAM",
                storageClass = RetailStorageClass.AMBIENT
            ),
            offer = RetailOffer(
                retailerId = RetailerId.ASDA,
                retailerProductId = "asda_beans_400g",
                price = Money(50L), // £0.50
                availability = RetailAvailability.IN_STOCK
            )
        )
    )

    fun getIcelandFixtures(): List<RetailProductWithOffer> = listOf(
        RetailProductWithOffer(
            product = RetailProduct(
                retailerId = RetailerId.ICELAND,
                retailerProductId = "iceland_broccoli_1kg",
                name = "Iceland Frozen Broccoli Florets 1kg",
                brand = "Iceland",
                canonicalIngredientId = "BROCCOLI",
                packCount = 1,
                packSize = 1000.0,
                packSizeUnit = "GRAM",
                storageClass = RetailStorageClass.FROZEN
            ),
            offer = RetailOffer(
                retailerId = RetailerId.ICELAND,
                retailerProductId = "iceland_broccoli_1kg",
                price = Money(180L), // £1.80
                availability = RetailAvailability.IN_STOCK
            )
        ),
        RetailProductWithOffer(
            product = RetailProduct(
                retailerId = RetailerId.ICELAND,
                retailerProductId = "iceland_beans_sausages_415g",
                name = "Iceland Baked Beans with Pork Sausages",
                brand = "Iceland",
                canonicalIngredientId = "BAKED_BEANS_SAUSAGES",
                packCount = 1,
                packSize = 415.0,
                packSizeUnit = "GRAM",
                storageClass = RetailStorageClass.AMBIENT
            ),
            offer = RetailOffer(
                retailerId = RetailerId.ICELAND,
                retailerProductId = "iceland_beans_sausages_415g",
                price = Money(100L), // £1.00
                availability = RetailAvailability.IN_STOCK
            )
        )
    )
}
