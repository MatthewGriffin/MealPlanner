package com.example.chatgpt_mealplanner.core.database

import org.junit.Assert.assertEquals
import org.junit.Test

class Migration30To31Test {
    @Test fun versionNumbers() {
        assertEquals(30, MIGRATION_30_31.startVersion)
        assertEquals(31, MIGRATION_30_31.endVersion)
        assertEquals(MIGRATION_30_31, ALL_MIGRATIONS.last())
    }
}
