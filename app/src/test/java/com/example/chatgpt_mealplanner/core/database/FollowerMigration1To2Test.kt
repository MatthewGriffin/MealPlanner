package com.example.chatgpt_mealplanner.core.database

import com.example.chatgpt_mealplanner.core.sync.follower.database.FOLLOWER_MIGRATION_1_2
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class FollowerMigration1To2Test {

    @Test
    fun followerMigration1_2_versionNumbersValidation() {
        assertEquals(1, FOLLOWER_MIGRATION_1_2.startVersion)
        assertEquals(2, FOLLOWER_MIGRATION_1_2.endVersion)
    }
}
