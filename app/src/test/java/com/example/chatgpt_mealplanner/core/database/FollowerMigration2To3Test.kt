package com.example.chatgpt_mealplanner.core.database

import com.example.chatgpt_mealplanner.core.sync.follower.database.FOLLOWER_MIGRATION_2_3
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class FollowerMigration2To3Test {

    @Test
    fun followerMigration2_3_versionNumbersValidation() {
        assertEquals(2, FOLLOWER_MIGRATION_2_3.startVersion)
        assertEquals(3, FOLLOWER_MIGRATION_2_3.endVersion)
    }
}
