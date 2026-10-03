package com.example.chatgpt_mealplanner.core.database

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class Migration15To16Test {

    @Test
    fun migration15_16_versionNumbersValidation() {
        assertEquals(15, MIGRATION_15_16.startVersion)
        assertEquals(16, MIGRATION_15_16.endVersion)
    }

    @Test
    fun allMigrationsRegisteredInProduction_covers1To16Path() {
        assertEquals(15, ALL_MIGRATIONS.size)
        assertEquals(1, ALL_MIGRATIONS.first().startVersion)
        assertEquals(2, ALL_MIGRATIONS.first().endVersion)
        assertEquals(15, ALL_MIGRATIONS.last().startVersion)
        assertEquals(16, ALL_MIGRATIONS.last().endVersion)

        // Verify continuous upgrade path from version 1 to 16
        for (i in 0 until ALL_MIGRATIONS.size - 1) {
            val current = ALL_MIGRATIONS[i]
            val next = ALL_MIGRATIONS[i + 1]
            assertEquals(current.endVersion, next.startVersion)
        }
    }
}
