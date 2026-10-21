package com.example.chatgpt_mealplanner.core.sync.follower.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(tableName = "follower_meals", indices = [Index(value = ["mealDate"])])
data class FollowerMealEntity(@PrimaryKey val id: String, val payloadJson: String, val mealDate: String? = null)
