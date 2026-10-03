package com.example.chatgpt_mealplanner.core.backup

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class BackupImportExportTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }

    @Test
    fun exportAndImport_verifiesVersionAndFieldPreservation() = runTest {
        val export = PantryPlannerExportDto(
            exportFormatVersion = "pantry-planner-export-v1",
            exportedAt = 1000000L,
            appVersionName = "1.0",
            inventory = listOf(
                InventoryItemExportDto(
                    id = "inv_1",
                    barcode = "123456",
                    canonicalIngredientId = "CHICKEN",
                    canonicalName = "Chicken Breast",
                    displayName = "Asda Fresh Chicken Breast",
                    brand = "Asda",
                    category = "FRIDGE",
                    storageLocation = "FRIDGE",
                    quantity = 400.0,
                    quantityUnit = "GRAM",
                    packCount = 1,
                    packSize = 400.0,
                    packSizeUnit = "GRAM",
                    quantityConfidence = "EXACT",
                    status = "AVAILABLE",
                    opened = false,
                    frozen = false,
                    staple = false,
                    purchaseDate = "2026-09-26",
                    openedDate = null,
                    useByDate = "2026-09-30",
                    bestBeforeDate = null,
                    notes = null,
                    createdAt = 1000000L,
                    updatedAt = 1000000L
                )
            )
        )

        val jsonStr = json.encodeToString(PantryPlannerExportDto.serializer(), export)
        assertTrue(jsonStr.contains("pantry-planner-export-v1"))

        val deserialized = json.decodeFromString<PantryPlannerExportDto>(jsonStr)
        assertEquals("pantry-planner-export-v1", deserialized.exportFormatVersion)
        assertEquals(1, deserialized.inventory.size)
        assertEquals("inv_1", deserialized.inventory.first().id)
        assertEquals(400.0, deserialized.inventory.first().quantity, 0.001)
    }
}
