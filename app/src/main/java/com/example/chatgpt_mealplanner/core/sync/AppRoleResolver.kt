package com.example.chatgpt_mealplanner.core.sync

import com.example.chatgpt_mealplanner.core.database.dao.HouseholdIdentityDao
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import javax.inject.Inject
import javax.inject.Singleton

enum class AppRole {
    MAIN,
    FOLLOWER
}

@Singleton
open class AppRoleResolver @Inject constructor(
    private val followerPairingStore: FollowerPairingStore,
    private val householdIdentityDao: HouseholdIdentityDao
) {
    constructor(followerPairingStore: FollowerPairingStore) : this(
        followerPairingStore = followerPairingStore,
        householdIdentityDao = object : HouseholdIdentityDao {
            override suspend fun getHouseholdIdentity() = null
            override fun getHouseholdIdentitySync() = null
            override suspend fun insertHouseholdIdentity(identity: com.example.chatgpt_mealplanner.core.database.entity.HouseholdIdentityEntity) {}
        }
    )

    open suspend fun getAppRoleAsync(): AppRole {
        val identity = householdIdentityDao.getHouseholdIdentity()
        if (identity != null) {
            return AppRole.MAIN
        }
        return if (followerPairingStore.isPaired()) {
            AppRole.FOLLOWER
        } else {
            AppRole.MAIN
        }
    }

    open suspend fun isMainAsync(): Boolean = getAppRoleAsync() == AppRole.MAIN
    open suspend fun isFollowerAsync(): Boolean = getAppRoleAsync() == AppRole.FOLLOWER

    open fun getAppRole(): AppRole {
        val hasMainIdentity = try {
            householdIdentityDao.getHouseholdIdentitySync() != null
        } catch (e: IllegalStateException) {
            false
        }
        if (hasMainIdentity) {
            return AppRole.MAIN
        }
        return if (followerPairingStore.isPaired()) {
            AppRole.FOLLOWER
        } else {
            AppRole.MAIN
        }
    }

    open fun isFollower(): Boolean = getAppRole() == AppRole.FOLLOWER
    open fun isMain(): Boolean = getAppRole() == AppRole.MAIN
}
