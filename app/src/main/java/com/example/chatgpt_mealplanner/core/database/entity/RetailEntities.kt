package com.example.chatgpt_mealplanner.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.chatgpt_mealplanner.retail.contract.BasketFeasibilityTier
import com.example.chatgpt_mealplanner.retail.contract.MatchConfidence
import com.example.chatgpt_mealplanner.retail.contract.RetailAvailability
import com.example.chatgpt_mealplanner.retail.contract.RetailProductMappingDecision
import com.example.chatgpt_mealplanner.retail.contract.RetailStorageClass
import com.example.chatgpt_mealplanner.retail.contract.RetailerId

@Entity(
    tableName = "retail_products",
    indices = [
        Index(value = ["retailerId", "retailerProductId"], unique = true),
        Index("canonicalIngredientId")
    ]
)
data class RetailProductEntity(
    @PrimaryKey
    val id: String, // retailerId_retailerProductId
    val retailerId: RetailerId,
    val retailerProductId: String,
    val name: String,
    val brand: String?,
    val canonicalIngredientId: String?,
    val barcode: String?,
    val category: String?,
    val imageUrl: String?,
    val packageType: String?,
    val netQuantity: Double?,
    val netQuantityUnit: String?,
    val packCount: Int?,
    val packSize: Double?,
    val packSizeUnit: String?,
    val storageClass: RetailStorageClass,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "retail_price_snapshots",
    indices = [
        Index("retailerProductId"),
        Index("observedAt")
    ]
)
data class RetailPriceSnapshotEntity(
    @PrimaryKey
    val id: String,
    val retailerId: RetailerId,
    val retailerProductId: String,
    val priceMinorPence: Long,
    val currencyCode: String,
    val promotionJson: String?,
    val availability: RetailAvailability,
    val observedAt: Long
)

@Entity(
    tableName = "retail_product_mappings",
    indices = [
        Index(value = ["retailerId", "retailerProductId", "canonicalIngredientId"], unique = true)
    ]
)
data class RetailProductMappingEntity(
    @PrimaryKey
    val id: String,
    val retailerId: RetailerId,
    val retailerProductId: String,
    val canonicalIngredientId: String,
    val decision: RetailProductMappingDecision, // CONFIRMED_MATCH or REJECTED_MATCH
    val userConfirmed: Boolean,
    val updatedAt: Long
)

@Entity(
    tableName = "basket_plans",
    indices = [
        Index("generatedAt")
    ]
)
data class BasketPlanEntity(
    @PrimaryKey
    val planId: String,
    val storesCsv: String,
    val feasibilityTier: BasketFeasibilityTier,
    val basketSubtotalMinorPence: Long,
    val estimatedTripPenaltyMinorPence: Long,
    val estimatedWastePenaltyScore: Double,
    val unmatchedRequirementsCsv: String,
    val uncertainItemsCsv: String,
    val explanationText: String,
    val generatedAt: Long
)

@Entity(
    tableName = "basket_lines",
    foreignKeys = [
        ForeignKey(
            entity = BasketPlanEntity::class,
            parentColumns = ["planId"],
            childColumns = ["basketPlanId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("basketPlanId")
    ]
)
data class BasketLineEntity(
    @PrimaryKey
    val id: String,
    val basketPlanId: String,
    val shoppingRequirementId: String,
    val requirementDisplayName: String,
    val retailerId: RetailerId,
    val retailerProductId: String,
    val productName: String,
    val packCountToBuy: Int,
    val lineTotalCostMinorPence: Long,
    val requirementCovered: Double,
    val expectedRemainder: Double,
    val matchConfidence: MatchConfidence
)
