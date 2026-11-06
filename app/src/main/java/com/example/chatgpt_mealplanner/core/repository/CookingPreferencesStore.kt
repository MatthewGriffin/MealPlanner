package com.example.chatgpt_mealplanner.core.repository

import com.example.chatgpt_mealplanner.contract.UserPreferencesSummaryDto
import com.example.chatgpt_mealplanner.core.database.dao.CookingPreferencesDao
import com.example.chatgpt_mealplanner.core.database.entity.CookingPreferencesEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

fun interface CookingPreferencesSource {
    suspend fun current(): UserPreferencesSummaryDto
}

/** For tests that aren't about preferences. The app always gets [CookingPreferencesStore]. */
object NoCookingPreferences : CookingPreferencesSource {
    override suspend fun current() = UserPreferencesSummaryDto()
}

@Singleton
class CookingPreferencesStore @Inject constructor(
    private val dao: CookingPreferencesDao
) : CookingPreferencesSource {

    override suspend fun current(): UserPreferencesSummaryDto = dao.get().toPreferences()

    fun observe(): Flow<UserPreferencesSummaryDto> = dao.observe().map { it.toPreferences() }

    suspend fun save(preferences: UserPreferencesSummaryDto) {
        dao.save(
            CookingPreferencesEntity(
                allergiesJson = Json.encodeToString(preferences.allergies),
                dietsJson = Json.encodeToString(preferences.diets),
                dislikesJson = Json.encodeToString(preferences.dislikes),
                equipmentJson = Json.encodeToString(preferences.equipment),
                householdSize = preferences.householdSize,
                skillLevel = preferences.skillLevel,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun CookingPreferencesEntity?.toPreferences(): UserPreferencesSummaryDto {
        if (this == null) return UserPreferencesSummaryDto()
        return UserPreferencesSummaryDto(
            householdSize = householdSize,
            allergies = readList(allergiesJson),
            diets = readList(dietsJson),
            dislikes = readList(dislikesJson),
            equipment = readList(equipmentJson),
            skillLevel = skillLevel
        )
    }

    private fun readList(json: String): List<String> =
        runCatching { Json.decodeFromString<List<String>>(json) }.getOrDefault(emptyList())
}
