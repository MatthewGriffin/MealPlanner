package com.example.chatgpt_mealplanner.core.database

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class Migration16To17Test {

    @Test
    fun migration16_17_versionNumbersValidation() {
        assertEquals(16, MIGRATION_16_17.startVersion)
        assertEquals(17, MIGRATION_16_17.endVersion)
    }

    @Test
    fun allMigrationsRegisteredInProduction_covers1To23Path() {
        assertEquals(22, ALL_MIGRATIONS.size)
        assertEquals(1, ALL_MIGRATIONS.first().startVersion)
        assertEquals(2, ALL_MIGRATIONS.first().endVersion)
        assertEquals(22, ALL_MIGRATIONS.last().startVersion)
        assertEquals(23, ALL_MIGRATIONS.last().endVersion)

        // Verify continuous upgrade path from version 1 to 23
        for (i in 0 until ALL_MIGRATIONS.size - 1) {
            val current = ALL_MIGRATIONS[i]
            val next = ALL_MIGRATIONS[i + 1]
            assertEquals(current.endVersion, next.startVersion)
        }
    }
}
