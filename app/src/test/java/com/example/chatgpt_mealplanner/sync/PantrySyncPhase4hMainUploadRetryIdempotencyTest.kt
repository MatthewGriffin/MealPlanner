package com.example.chatgpt_mealplanner.sync

import com.example.chatgpt_mealplanner.core.database.DatabaseTransactionRunner
import com.example.chatgpt_mealplanner.core.database.dao.CanonicalSyncDao
import com.example.chatgpt_mealplanner.core.database.dao.InventoryDao
import com.example.chatgpt_mealplanner.core.database.dao.PairedDeviceDao
import com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncOperationEntity
import com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncSequenceEntity
import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemEntity
import com.example.chatgpt_mealplanner.core.database.entity.PairedDeviceEntity
import com.example.chatgpt_mealplanner.core.sync.engine.MainCanonicalSyncEngine
import com.example.chatgpt_mealplanner.sync.contract.AddItemPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock

class PantrySyncPhase4hMainUploadRetryIdempotencyTest {

    private val json = Json { ignoreUnknownKeys = true }

    private class TestTransactionRunner : DatabaseTransactionRunner {
        override suspend fun <T> runInTransaction(block: suspend () -> T): T = block()
    }

    private val followerDevId = "2bfabb01-b84d-437b-aed6-7822763ff816"
    private val itemRetryId = "f69ca318-9fc8-46a5-b802-622e985f358d"
    private val opRetryId = "a33edcfc-24fc-4e13-bd92-230def26da30"
    private val itemSeqId = "16a6bfcb-fcc2-4faf-95ef-5f44edc7071b"
    private val opSeq1Id = "4f67a144-8319-4a66-b9ed-ef78b4a2e333"
    private val opSeq2Id = "3ddf1c49-e7be-4f3f-983e-a1ed23b8859b"

    private fun createPairedDeviceDao(): PairedDeviceDao = mock {
        onBlocking { getDeviceById(followerDevId) } doAnswer {
            PairedDeviceEntity(
                deviceId = followerDevId,
                deviceName = "Follower Phone",
                devicePublicKey = "pub-key",
                status = "ACTIVE",
                pairedAt = 1000L,
                lastSyncTimestamp = 1000L
            )
        }
    }

    @Test
    fun testMainProcessOperation_duplicateOperationId_returnsExistingResultWithoutSecondMutation() = runTest {
        val canonicalOpsMap = mutableMapOf<String, CanonicalSyncOperationEntity>()
        val itemsMap = mutableMapOf<String, InventoryItemEntity>()
        var nextSeq = 10L

        val canonicalSyncDao: CanonicalSyncDao = mock {
            onBlocking { getByOperationId(any()) } doAnswer { invocation ->
                canonicalOpsMap[invocation.getArgument<String>(0)]
            }
            onBlocking { getSequenceEntity() } doAnswer { CanonicalSyncSequenceEntity(1, nextSeq) }
            onBlocking { updateSequenceEntity(any()) } doAnswer { invocation ->
                nextSeq = invocation.getArgument<CanonicalSyncSequenceEntity>(0).nextSequence
                Unit
            }
            onBlocking { insertCanonicalOperation(any()) } doAnswer { invocation ->
                val op = invocation.getArgument<CanonicalSyncOperationEntity>(0)
                canonicalOpsMap[op.operationId] = op
                Unit
            }
        }

        val inventoryDao: InventoryDao = mock {
            onBlocking { getItemById(any()) } doAnswer { invocation ->
                itemsMap[invocation.getArgument<String>(0)]
            }
            onBlocking { insertItem(any()) } doAnswer { invocation ->
                val item = invocation.getArgument<InventoryItemEntity>(0)
                itemsMap[item.id] = item
                Unit
            }
        }

        val engine = MainCanonicalSyncEngine(
            canonicalSyncDao = canonicalSyncDao,
            pairedDeviceDao = createPairedDeviceDao(),
            inventoryDao = inventoryDao,
            transactionRunner = TestTransactionRunner()
        )

        val payload = AddItemPayloadDto(
            itemId = itemRetryId,
            barcode = null,
            canonicalName = "milk",
            displayName = "Whole Milk",
            brand = null,
            category = "REFRIGERATED",
            storageLocation = "FRIDGE",
            quantity = 2.0,
            quantityUnit = "LITRE",
            notes = null
        )

        val opDto = SyncOperationDto(
            operationId = opRetryId,
            deviceId = followerDevId,
            deviceSequence = 1L,
            protocolVersion = 1,
            operationType = SyncOperationType.ADD_ITEM,
            targetItemId = itemRetryId,
            baseRevision = 0L,
            payloadJson = json.encodeToString(payload),
            createdAt = 1000L
        )

        // 1st Delivery: Process operation on Main
        val result1 = engine.processOperation(followerDevId, opDto)
        assertEquals("SUCCESS", result1.resultCode)
        assertEquals(10L, result1.canonicalSequence)
        assertEquals(1, itemsMap.size)
        assertEquals(1, canonicalOpsMap.size)
        assertEquals(11L, nextSeq)

        // 2nd Delivery: Retried upload with identical operationId
        val result2 = engine.processOperation(followerDevId, opDto)

        // Assertions:
        // 1. Result code and sequence match original delivery
        assertEquals("SUCCESS", result2.resultCode)
        assertEquals(10L, result2.canonicalSequence)

        // 2. Inventory item count remains 1 (no duplicate item inserted)
        assertEquals(1, itemsMap.size)

        // 3. Canonical operations count remains 1 (no duplicate canonical op inserted)
        assertEquals(1, canonicalOpsMap.size)

        // 4. Sequence pointer remains 11L (no extra sequence allocated)
        assertEquals(11L, nextSeq)
    }

    @Test
    fun testMainProcessOperation_duplicateDeviceSequence_returnsExistingOrConflict() = runTest {
        val canonicalOpsMap = mutableMapOf<String, CanonicalSyncOperationEntity>()
        val seqOpsMap = mutableMapOf<String, CanonicalSyncOperationEntity>()
        var nextSeq = 10L

        val canonicalSyncDao: CanonicalSyncDao = mock {
            onBlocking { getByOperationId(any()) } doAnswer { invocation ->
                canonicalOpsMap[invocation.getArgument<String>(0)]
            }
            onBlocking { getByDeviceSequence(any(), any()) } doAnswer { invocation ->
                val devId = invocation.getArgument<String>(0)
                val devSeq = invocation.getArgument<Long>(1)
                seqOpsMap["$devId:$devSeq"]
            }
            onBlocking { getSequenceEntity() } doAnswer { CanonicalSyncSequenceEntity(1, nextSeq) }
            onBlocking { updateSequenceEntity(any()) } doAnswer { invocation ->
                nextSeq = invocation.getArgument<CanonicalSyncSequenceEntity>(0).nextSequence
                Unit
            }
            onBlocking { insertCanonicalOperation(any()) } doAnswer { invocation ->
                val op = invocation.getArgument<CanonicalSyncOperationEntity>(0)
                canonicalOpsMap[op.operationId] = op
                seqOpsMap["${op.deviceId}:${op.deviceSequence}"] = op
                Unit
            }
        }

        val engine = MainCanonicalSyncEngine(
            canonicalSyncDao = canonicalSyncDao,
            pairedDeviceDao = createPairedDeviceDao(),
            inventoryDao = mock(),
            transactionRunner = TestTransactionRunner()
        )

        val payload = AddItemPayloadDto(
            itemId = itemSeqId,
            barcode = null,
            canonicalName = "cheese",
            displayName = "Swiss Cheese",
            brand = null,
            category = "REFRIGERATED",
            storageLocation = "FRIDGE",
            quantity = 1.0,
            quantityUnit = "PACK",
            notes = null
        )

        val opDto1 = SyncOperationDto(
            operationId = opSeq1Id,
            deviceId = followerDevId,
            deviceSequence = 5L,
            protocolVersion = 1,
            operationType = SyncOperationType.ADD_ITEM,
            targetItemId = itemSeqId,
            baseRevision = 0L,
            payloadJson = json.encodeToString(payload),
            createdAt = 1000L
        )

        val result1 = engine.processOperation(followerDevId, opDto1)
        assertEquals("SUCCESS", result1.resultCode)
        assertEquals(10L, result1.canonicalSequence)

        // 2nd Delivery with DIFFERENT operationId but SAME deviceId and deviceSequence
        val opDto2 = opDto1.copy(operationId = opSeq2Id)
        val result2 = engine.processOperation(followerDevId, opDto2)

        // Assertions:
        // Main detects existing device sequence 5L and handles idempotently
        assertNotNull(result2)
        assertEquals("DEVICE_SEQUENCE_REUSE", result2.resultCode)
        assertEquals(11L, nextSeq)
    }
}
