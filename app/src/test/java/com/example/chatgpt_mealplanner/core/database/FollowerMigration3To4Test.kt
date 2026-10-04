package com.example.chatgpt_mealplanner.core.database

import com.example.chatgpt_mealplanner.core.sync.follower.database.FOLLOWER_MIGRATION_3_4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class FollowerMigration3To4Test {

    @Test
    fun followerMigration3_4_versionNumbersValidation() {
        assertEquals(3, FOLLOWER_MIGRATION_3_4.startVersion)
        assertEquals(4, FOLLOWER_MIGRATION_3_4.endVersion)
    }
}
