package com.example.chatgpt_mealplanner.core.database

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class Migration11To12Test {

    @Test
    fun migration11_12_versionNumbersAreCorrect() {
        assertEquals(11, MIGRATION_11_12.startVersion)
        assertEquals(12, MIGRATION_11_12.endVersion)
    }
}
