package com.example.chatgpt_mealplanner.core.sync

import com.example.chatgpt_mealplanner.core.database.dao.HouseholdIdentityDao
import com.example.chatgpt_mealplanner.core.database.entity.HouseholdIdentityEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HouseholdIdentityManager @Inject constructor(
    private val householdIdentityDao: HouseholdIdentityDao
) {
    private val mutex = Mutex()

    suspend fun getOrCreateHouseholdIdentity(): HouseholdIdentityEntity {
        val existing = householdIdentityDao.getHouseholdIdentity()
        if (existing != null) {
            return existing
        }

        return mutex.withLock {
            val checkAgain = householdIdentityDao.getHouseholdIdentity()
            if (checkAgain != null) {
                return@withLock checkAgain
            }

            val newIdentity = HouseholdIdentityEntity(
                householdId = UUID.randomUUID().toString(),
                householdName = "Pantry Household",
                mainDeviceId = UUID.randomUUID().toString(),
                createdAt = System.currentTimeMillis()
            )
            householdIdentityDao.insertHouseholdIdentity(newIdentity)
            newIdentity
        }
    }
}
