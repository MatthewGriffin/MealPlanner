package com.example.chatgpt_mealplanner.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.chatgpt_mealplanner.core.model.ShoppingList
import com.example.chatgpt_mealplanner.core.model.ShoppingListStatus

@Entity(tableName = "shopping_lists")
data class ShoppingListEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val startDate: String?,
    val endDate: String?,
    val status: ShoppingListStatus,
    val createdAt: Long,
    val updatedAt: Long
)

fun ShoppingListEntity.toDomainModel(): ShoppingList = ShoppingList(
    id = id,
    name = name,
    startDate = startDate,
    endDate = endDate,
    status = status,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun ShoppingList.toEntity(): ShoppingListEntity = ShoppingListEntity(
    id = id,
    name = name,
    startDate = startDate,
    endDate = endDate,
    status = status,
    createdAt = createdAt,
    updatedAt = updatedAt
)
