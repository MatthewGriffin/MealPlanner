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

    // Complete enrollment identifies this device as a follower of a different Main.
    // Merely opening Household settings used to create an incidental local identity;
    // that row must not override an established enrollment or require a data reset.
    private fun hasFollowerEnrollment(): Boolean {
        val config = followerPairingStore.getPairingConfig() ?: return false
        return followerPairingStore.isPaired() && !config.householdId.isNullOrBlank() &&
            !config.mainDeviceId.isNullOrBlank() && config.mainDeviceId != config.deviceId
    }

    open suspend fun getAppRoleAsync(): AppRole {
        if (hasFollowerEnrollment()) return AppRole.FOLLOWER
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
        if (hasFollowerEnrollment()) return AppRole.FOLLOWER
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
