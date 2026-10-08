package com.example.chatgpt_mealplanner.core.sync

import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import javax.inject.Inject
import javax.inject.Singleton

enum class AppRole {
    MAIN,
    FOLLOWER
}

@Singleton
open class AppRoleResolver @Inject constructor(
    private val followerPairingStore: FollowerPairingStore
) {
    open fun getAppRole(): AppRole {
        return if (followerPairingStore.isPaired()) {
            AppRole.FOLLOWER
        } else {
            AppRole.MAIN
        }
    }

    open fun isFollower(): Boolean = getAppRole() == AppRole.FOLLOWER
    open fun isMain(): Boolean = getAppRole() == AppRole.MAIN
}
