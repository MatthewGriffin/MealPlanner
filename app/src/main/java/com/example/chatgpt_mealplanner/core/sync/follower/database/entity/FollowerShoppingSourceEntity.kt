package com.example.chatgpt_mealplanner.core.sync.follower.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Which meal each shopping item is for, as published by Main. The main app's own sources table points at meals, which a
 * follower does not keep there, so followers hold these here and the shopping screen reads them to split the trip.
 */
@Entity(tableName = "follower_shopping_sources", indices = [Index("shoppingListItemId")])
data class FollowerShoppingSourceEntity(
    @PrimaryKey val id: String,
    val shoppingListItemId: String,
    val mealId: String,
    val mealIngredientId: String?,
    val mealTitle: String,
    val mealDate: String,
    val requiredQuantity: Double,
    val requiredUnit: String
)
