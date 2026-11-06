package com.example.chatgpt_mealplanner.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** The household's cooking preferences. There is only ever one row. */
@Entity(tableName = "cooking_preferences")
data class CookingPreferencesEntity(
    @PrimaryKey val id: Int = SINGLE_ROW_ID,
    val allergiesJson: String,
    val dietsJson: String,
    val dislikesJson: String,
    val equipmentJson: String,
    val householdSize: Int,
    val skillLevel: String?,
    val updatedAt: Long
) {
    companion object {
        const val SINGLE_ROW_ID = 1
    }
}
