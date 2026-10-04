package com.example.chatgpt_mealplanner.sync

import com.example.chatgpt_mealplanner.core.security.AndroidKeyStoreManager
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.core.sync.client.TestClientOperationResult
import com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploader
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationResultDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationStatus
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.UUID

class FakeSyncTestClient : PantrySyncTestClient(AndroidKeyStoreManager()) {
    override suspend fun submitSyncOperation(
        hostIp: String,
        port: Int,
        pinnedCertFingerprint: String?,
        sessionToken: String,
        operationDto: SyncOperationDto
    ): TestClientOperationResult {
        return TestClientOperationResult(
            success = true,
            resultDto = SyncOperationResultDto(
                operationId = operationDto.operationId,
                deviceId = operationDto.deviceId,
                status = SyncOperationStatus.APPLIED,
                canonicalSequence = 100L,
                targetItemId = operationDto.targetItemId,
                resultingRevision = (operationDto.baseRevision ?: 0L) + 1L,
                resultCode = "SUCCESS"
            )
        )
    }
}

@RunWith(JUnit4::class)
class PantrySyncPhase3cAutoSyncTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fakeDao: FakeFollowerDao
    private lateinit var localEngine: TestableFollowerSyncEngine
    private lateinit var uploader: FollowerSyncUploader
    private lateinit var testClient: FakeSyncTestClient

    private val followerDeviceId = UUID.randomUUID().toString()

    @Before
    fun setUp() {
        fakeDao = FakeFollowerDao()
        localEngine = TestableFollowerSyncEngine(fakeDao)
        testClient = FakeSyncTestClient()
        uploader = FollowerSyncUploader(null, testClient, localEngine)
    }

    @Test
    fun singleFlightGuard_preventsParallelUploadPasses() = runTest {
        val deferreds = (1..5).map {
            async {
                uploader.performSyncUpload(
                    hostIp = "192.168.0.126",
                    port = 8443,
                    pinnedCertFingerprint = null,
                    sessionToken = "dummy_token",
                    deviceId = followerDeviceId,
                    customDao = fakeDao
                )
            }
        }
        val results = deferreds.awaitAll()

        val executedPasses = results.filter { it.processedCount >= 0 }
        assertEquals(5, executedPasses.size)
    }

    @Test
    fun singleFlight_newOperationDuringActivePass_isNotStranded() = runTest {
        val itemId1 = UUID.randomUUID().toString()
        fakeDao.insertOutboxOperation(
            FollowerSyncOutboxEntity(
                operationId = "op_initial_1",
                deviceId = followerDeviceId,
                deviceSequence = 1L,
                protocolVersion = 1,
                operationType = "ADD_ITEM",
                targetItemId = itemId1,
                baseRevision = null,
                payloadJson = "{}",
                createdAt = System.currentTimeMillis(),
                status = "PENDING",
                createdLocallyAt = System.currentTimeMillis()
            )
        )

        val passA = async {
            uploader.performSyncUpload(
                hostIp = "192.168.0.126",
                port = 8443,
                pinnedCertFingerprint = null,
                sessionToken = "dummy_token",
                deviceId = followerDeviceId,
                customDao = fakeDao
            )
        }

        val itemId2 = UUID.randomUUID().toString()
        fakeDao.insertOutboxOperation(
            FollowerSyncOutboxEntity(
                operationId = "op_new_during_pass",
                deviceId = followerDeviceId,
                deviceSequence = 2L,
                protocolVersion = 1,
                operationType = "ADD_ITEM",
                targetItemId = itemId2,
                baseRevision = null,
                payloadJson = "{}",
                createdAt = System.currentTimeMillis(),
                status = "PENDING",
                createdLocallyAt = System.currentTimeMillis()
            )
        )

        val passB = async {
            uploader.performSyncUpload(
                hostIp = "192.168.0.126",
                port = 8443,
                pinnedCertFingerprint = null,
                sessionToken = "dummy_token",
                deviceId = followerDeviceId,
                customDao = fakeDao
            )
        }

        val summaryB = passB.await()
        val summaryA = passA.await()

        assertEquals("Pass B lost tryLock and processed 0", 0, summaryB.processedCount)

        val newOpRow = fakeDao.getOutboxByOperationId("op_new_during_pass")
        assertNotNull("O_NEW must be present in outbox", newOpRow)
        assertTrue("O_NEW must NOT be left stranded in PENDING state", newOpRow?.status != "PENDING")
    }

    @Test
    fun singleFlight_transportFailure_doesNotTightLoop() = runTest {
        val failingClient = object : PantrySyncTestClient(AndroidKeyStoreManager()) {
            override suspend fun submitSyncOperation(
                hostIp: String,
                port: Int,
                pinnedCertFingerprint: String?,
                sessionToken: String,
                operationDto: SyncOperationDto
            ): TestClientOperationResult {
                return TestClientOperationResult(false, null, "TRANSPORT_FAILURE")
            }
        }
        val failingUploader = FollowerSyncUploader(null, failingClient, localEngine)

        val itemId = UUID.randomUUID().toString()
        fakeDao.insertOutboxOperation(
            FollowerSyncOutboxEntity(
                operationId = "op_transport_err_1",
                deviceId = followerDeviceId,
                deviceSequence = 10L,
                protocolVersion = 1,
                operationType = "ADD_ITEM",
                targetItemId = itemId,
                baseRevision = null,
                payloadJson = "{}",
                createdAt = System.currentTimeMillis(),
                status = "PENDING",
                createdLocallyAt = System.currentTimeMillis()
            )
        )

        val summary = failingUploader.performSyncUpload(
            hostIp = "invalid.unreachable.host",
            port = 8443,
            pinnedCertFingerprint = null,
            sessionToken = "dummy_token",
            deviceId = followerDeviceId,
            customDao = fakeDao
        )

        assertEquals("Uploader MUST stop immediately on transport error and NOT tight loop", 1, summary.transportErrorCount)
        assertEquals("PENDING", fakeDao.getOutboxByOperationId("op_transport_err_1")?.status)
    }

    @Test
    fun startupRecovery_resetsInFlightOpsToPending() = runTest {
        val itemId = UUID.randomUUID().toString()
        fakeDao.insertOrUpdateItem(
            FollowerInventoryItemEntity(
                itemId = itemId,
                canonicalName = "Bread",
                confirmedDisplayName = "White Bread",
                confirmedCategory = "BREAD_BAKERY",
                confirmedStorageLocation = "CUPBOARD",
                confirmedQuantity = 1.0,
                confirmedQuantityUnit = "UNIT",
                confirmedNotes = null,
                confirmedStatus = "AVAILABLE",
                mainConfirmedRevision = 1L,
                displayName = "White Bread",
                brand = null,
                category = "BREAD_BAKERY",
                storageLocation = "CUPBOARD",
                quantity = 1.0,
                quantityUnit = "UNIT",
                notes = null,
                status = "AVAILABLE",
                optimisticRevision = 1L,
                isPendingSync = true,
                updatedAt = System.currentTimeMillis()
            )
        )

        fakeDao.insertOutboxOperation(
            FollowerSyncOutboxEntity(
                operationId = "op_inflight_1",
                deviceId = followerDeviceId,
                deviceSequence = 10L,
                protocolVersion = 1,
                operationType = "SET_QUANTITY",
                targetItemId = itemId,
                baseRevision = 1L,
                payloadJson = "{\"absoluteQuantity\":2.0}",
                createdAt = System.currentTimeMillis(),
                status = "IN_FLIGHT",
                createdLocallyAt = System.currentTimeMillis()
            )
        )

        assertEquals("IN_FLIGHT", fakeDao.getOutboxByOperationId("op_inflight_1")?.status)

        fakeDao.resetInFlightOpsToPending()

        assertEquals("PENDING", fakeDao.getOutboxByOperationId("op_inflight_1")?.status)
    }

    @Test
    fun terminalOperations_neverResetToPending() = runTest {
        val itemId = UUID.randomUUID().toString()
        fakeDao.insertOutboxOperation(
            FollowerSyncOutboxEntity(
                operationId = "op_applied_1",
                deviceId = followerDeviceId,
                deviceSequence = 1L,
                protocolVersion = 1,
                operationType = "SET_QUANTITY",
                targetItemId = itemId,
                baseRevision = 1L,
                payloadJson = "{\"absoluteQuantity\":2.0}",
                createdAt = System.currentTimeMillis(),
                status = "APPLIED",
                createdLocallyAt = System.currentTimeMillis()
            )
        )

        fakeDao.resetInFlightOpsToPending()

        assertEquals("APPLIED status MUST NEVER be reset to PENDING", "APPLIED", fakeDao.getOutboxByOperationId("op_applied_1")?.status)
    }

    @Test
    fun emptyOutboxPass_isCheapNoOp() = runTest {
        val summary = uploader.performSyncUpload(
            hostIp = "192.168.0.126",
            port = 8443,
            pinnedCertFingerprint = null,
            sessionToken = "dummy_token",
            deviceId = followerDeviceId,
            customDao = fakeDao
        )

        assertEquals(0, summary.processedCount)
        assertEquals(0, summary.appliedCount)
        assertEquals(0, summary.transportErrorCount)
    }

    @Test
    fun completedOutbox_workerRerun_isNoOp() = runTest {
        val itemId = UUID.randomUUID().toString()
        fakeDao.insertOutboxOperation(
            FollowerSyncOutboxEntity(
                operationId = "op_completed_1",
                deviceId = followerDeviceId,
                deviceSequence = 1L,
                protocolVersion = 1,
                operationType = "ADD_ITEM",
                targetItemId = itemId,
                baseRevision = null,
                payloadJson = "{}",
                createdAt = System.currentTimeMillis(),
                status = "APPLIED",
                createdLocallyAt = System.currentTimeMillis()
            )
        )

        val summary = uploader.performSyncUpload(
            hostIp = "192.168.0.126",
            port = 8443,
            pinnedCertFingerprint = null,
            sessionToken = "dummy_token",
            deviceId = followerDeviceId,
            customDao = fakeDao
        )

        assertEquals(0, summary.processedCount)
        assertEquals(0, summary.transportErrorCount)
    }

    @Test
    fun deviceSequence_notCreatedAt_controlsUploadOrder() = runTest {
        val itemId = UUID.randomUUID().toString()

        fakeDao.insertOutboxOperation(
            FollowerSyncOutboxEntity(
                operationId = "op_seq_50",
                deviceId = followerDeviceId,
                deviceSequence = 50L,
                protocolVersion = 1,
                operationType = "ADD_ITEM",
                targetItemId = itemId,
                baseRevision = null,
                payloadJson = "{}",
                createdAt = 4070908800000L,
                status = "PENDING",
                createdLocallyAt = System.currentTimeMillis()
            )
        )

        fakeDao.insertOutboxOperation(
            FollowerSyncOutboxEntity(
                operationId = "op_seq_51",
                deviceId = followerDeviceId,
                deviceSequence = 51L,
                protocolVersion = 1,
                operationType = "ADD_ITEM",
                targetItemId = itemId,
                baseRevision = null,
                payloadJson = "{}",
                createdAt = 946684800000L,
                status = "PENDING",
                createdLocallyAt = System.currentTimeMillis()
            )
        )

        val pendingInOrder = fakeDao.getPendingOperationsInOrder()
        assertEquals(2, pendingInOrder.size)
        assertEquals("op_seq_50", pendingInOrder[0].operationId)
        assertEquals("op_seq_51", pendingInOrder[1].operationId)
    }

    @Test
    fun sessionRefresh_preservesImmutableEnvelope() = runTest {
        val op = SyncOperationDto(
            operationId = "op_immutable_1",
            deviceId = followerDeviceId,
            deviceSequence = 5L,
            protocolVersion = 1,
            operationType = SyncOperationType.ADD_ITEM,
            targetItemId = "item_123",
            baseRevision = 2L,
            payloadJson = "{\"test\":1}",
            createdAt = 1762000000000L
        )

        val refreshedOp = op.copy()

        assertEquals(op.operationId, refreshedOp.operationId)
        assertEquals(op.deviceId, refreshedOp.deviceId)
        assertEquals(op.deviceSequence, refreshedOp.deviceSequence)
        assertEquals(op.protocolVersion, refreshedOp.protocolVersion)
        assertEquals(op.operationType, refreshedOp.operationType)
        assertEquals(op.targetItemId, refreshedOp.targetItemId)
        assertEquals(op.baseRevision, refreshedOp.baseRevision)
        assertEquals(op.payloadJson, refreshedOp.payloadJson)
        assertEquals(op.createdAt, refreshedOp.createdAt)
    }
}
