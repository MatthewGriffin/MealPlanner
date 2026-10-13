package com.example.chatgpt_mealplanner.core.backup

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class PantryPhase6Step3MainBackupSnapshotTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }

    @Test
    fun mainHouseholdBackupDto_verifiesFormatAndSchemaMetadata() = runTest {
        val backup = MainHouseholdBackupDto(
            exportedAt = 1791890000000L,
            householdId = "house-123",
            householdName = "Family Pantry",
            mainDeviceId = "main-dev-1",
            nextCanonicalSequence = 42L,
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
                    purchaseDate = "2026-10-13",
                    openedDate = null,
                    useByDate = "2026-10-20",
                    bestBeforeDate = null,
                    notes = "Backup test",
                    createdAt = 1791890000000L,
                    updatedAt = 1791890000000L
                )
            ),
            canonicalOperations = listOf(
                CanonicalSyncOperationExportDto(
                    operationId = "op-10",
                    deviceId = "follower-dev-1",
                    deviceSequence = 1L,
                    protocolVersion = 1,
                    operationType = "SET_QUANTITY",
                    targetItemId = "inv_1",
                    baseRevision = 1L,
                    payloadJson = "{}",
                    createdAt = 1791890000000L,
                    canonicalSequence = 10L,
                    receivedAt = 1791890000000L,
                    status = "APPLIED",
                    resultCode = "OK",
                    resultPayloadJson = null
                )
            )
        )

        val jsonStr = json.encodeToString(MainHouseholdBackupDto.serializer(), backup)
        assertTrue(jsonStr.contains("pantry-household-backup-v1"))
        assertTrue(jsonStr.contains("house-123"))
        assertTrue(jsonStr.contains("op-10"))

        val deserialized = json.decodeFromString<MainHouseholdBackupDto>(jsonStr)
        assertEquals("pantry-household-backup-v1", deserialized.backupFormatVersion)
        assertEquals(16, deserialized.roomSchemaVersion)
        assertEquals("MAIN", deserialized.role)
        assertEquals("house-123", deserialized.householdId)
        assertEquals(42L, deserialized.nextCanonicalSequence)
        assertEquals(1, deserialized.inventory.size)
        assertEquals("inv_1", deserialized.inventory.first().id)
        assertEquals(1, deserialized.canonicalOperations.size)
        assertEquals(10L, deserialized.canonicalOperations.first().canonicalSequence)
    }

    @Test
    fun nullDatabase_returnsEmptyMainHouseholdBackupDto() = runTest {
        val noOpRunner = object : com.example.chatgpt_mealplanner.core.database.DatabaseTransactionRunner {
            override suspend fun <T> runInTransaction(block: suspend () -> T): T = block()
        }
        val snapshotBuilder = MainBackupSnapshotBuilder(null, noOpRunner)

        val dto = snapshotBuilder.createMainBackupSnapshot(currentTimeMs = 1000000L)
        assertNotNull(dto)
        assertEquals("pantry-household-backup-v1", dto.backupFormatVersion)
        assertEquals("MAIN", dto.role)
        assertEquals(1000000L, dto.exportedAt)
        assertTrue(dto.inventory.isEmpty())
        assertTrue(dto.canonicalOperations.isEmpty())
    }
}
