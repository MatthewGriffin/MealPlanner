package com.example.chatgpt_mealplanner.core.sync.follower.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "follower_meals")
data class FollowerMealEntity(@PrimaryKey val id: String, val payloadJson: String)
