package com.example.chatgpt_mealplanner.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.ShoppingRequirementSource

@Entity(
    tableName = "shopping_requirement_sources",
    foreignKeys = [
        ForeignKey(
            entity = ShoppingListItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["shoppingListItemId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MealEntity::class,
            parentColumns = ["id"],
            childColumns = ["mealId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("shoppingListItemId"),
        Index("mealId"),
        Index("mealIngredientId")
    ]
)
data class ShoppingRequirementSourceEntity(
    @PrimaryKey
    val id: String,
    val shoppingListItemId: String,
    val mealId: String,
    val mealIngredientId: String?,
    val mealTitle: String,
    val mealDate: String,
    val requiredQuantity: Double,
    val requiredUnit: QuantityUnit
)

fun ShoppingRequirementSourceEntity.toDomainModel(): ShoppingRequirementSource = ShoppingRequirementSource(
    id = id,
    shoppingListItemId = shoppingListItemId,
    mealId = mealId,
    mealIngredientId = mealIngredientId,
    mealTitle = mealTitle,
    mealDate = mealDate,
    requiredQuantity = requiredQuantity,
    requiredUnit = requiredUnit
)

fun ShoppingRequirementSource.toEntity(): ShoppingRequirementSourceEntity = ShoppingRequirementSourceEntity(
    id = id,
    shoppingListItemId = shoppingListItemId,
    mealId = mealId,
    mealIngredientId = mealIngredientId,
    mealTitle = mealTitle,
    mealDate = mealDate,
    requiredQuantity = requiredQuantity,
    requiredUnit = requiredUnit
)
