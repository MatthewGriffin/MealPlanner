package com.example.chatgpt_mealplanner.core.database

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class Migration13To14Test {

    @Test
    fun migration14_15_versionNumbersValidation() {
        assertEquals(14, MIGRATION_14_15.startVersion)
        assertEquals(15, MIGRATION_14_15.endVersion)
    }

    @Test
    fun allMigrationsRegisteredInProduction_covers1To15Path() {
        assertEquals(14, ALL_MIGRATIONS.size)
        assertEquals(1, ALL_MIGRATIONS.first().startVersion)
        assertEquals(2, ALL_MIGRATIONS.first().endVersion)
        assertEquals(14, ALL_MIGRATIONS.last().startVersion)
        assertEquals(15, ALL_MIGRATIONS.last().endVersion)

        // Verify continuous upgrade path from version 1 to 15
        for (i in 0 until ALL_MIGRATIONS.size - 1) {
            val current = ALL_MIGRATIONS[i]
            val next = ALL_MIGRATIONS[i + 1]
            assertEquals(current.endVersion, next.startVersion)
        }
    }
}
