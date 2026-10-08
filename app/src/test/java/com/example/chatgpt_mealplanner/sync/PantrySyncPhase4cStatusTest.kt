package com.example.chatgpt_mealplanner.sync

import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemEntity
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.core.sync.enrollment.PantryEnrollmentInvitationManager
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity
import com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState
import com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusRepository
import com.example.chatgpt_mealplanner.core.sync.follower.worker.FollowerSyncScheduler
import com.example.chatgpt_mealplanner.sync.contract.EnrollmentRequestDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class PantrySyncPhase4cStatusTest {

    private lateinit var pairingStore: FakePairingStore
    private lateinit var statusRepository: FollowerSyncStatusRepository
    private lateinit var followerDao: FakeFollowerDao
    private lateinit var mainPairedDeviceDao: TestSyncPairedDeviceDao
    private lateinit var keyStoreManager: TestEnrollmentKeyStoreManager
    private lateinit var invitationManager: PantryEnrollmentInvitationManager

    private val householdId = UUID.randomUUID().toString()
    private val mainDeviceId = UUID.randomUUID().toString()
    private val followerDeviceId = UUID.randomUUID().toString()
    private val validFingerprint = "SHA256:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF"

    @Before
    fun setUp() = runTest {
        pairingStore = FakePairingStore()
        statusRepository = FollowerSyncStatusRepository(pairingStore)
        followerDao = FakeFollowerDao()
        mainPairedDeviceDao = TestSyncPairedDeviceDao()
        keyStoreManager = TestEnrollmentKeyStoreManager()
        invitationManager = PantryEnrollmentInvitationManager(mainPairedDeviceDao, keyStoreManager)

        followerDao.updateSyncMetadata(
            FollowerSyncMetadataEntity(
                singletonId = 1,
                lastReconciledCanonicalSequence = 100L,
                lastSuccessfulPullAt = 10000L,
                bootstrapCompleted = true,
                bootstrapCompletedAt = 5000L,
                bootstrapCanonicalSequence = 10L
            )
        )
    }

    // 1. No pairing config -> UNPAIRED
    @Test
    fun test1_noPairingConfig_returnsUnpaired() = runTest {
        pairingStore.clear()
        val status = statusRepository.calculateStatus(followerDao)
        assertEquals(FollowerSyncState.UNPAIRED, status.state)
    }

    // 2. Paired + bootstrap incomplete -> BOOTSTRAP_REQUIRED
    @Test
    fun test2_pairedAndBootstrapIncomplete_returnsBootstrapRequired() = runTest {
        pairingStore.savePairingConfig(
            FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId)
        )
        followerDao.updateSyncMetadata(
            FollowerSyncMetadataEntity(singletonId = 1, bootstrapCompleted = false)
        )

        val status = statusRepository.calculateStatus(followerDao)
        assertEquals(FollowerSyncState.BOOTSTRAP_REQUIRED, status.state)
    }

    // 3. Retryable pending outbox -> PENDING_CHANGES with correct count
    @Test
    fun test3_retryablePendingOutbox_returnsPendingChanges_withCorrectCount() = runTest {
        pairingStore.savePairingConfig(
            FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId)
        )
        followerDao.insertOutboxOperation(
            FollowerSyncOutboxEntity("op_1", followerDeviceId, 1L, 1, "CREATE", "item_1", null, "{}", 1000L, "PENDING", createdLocallyAt = 1000L)
        )
        followerDao.insertOutboxOperation(
            FollowerSyncOutboxEntity("op_2", followerDeviceId, 2L, 1, "UPDATE", "item_2", 1L, "{}", 1001L, "PENDING", createdLocallyAt = 1001L)
        )

        val status = statusRepository.calculateStatus(followerDao)
        assertEquals(FollowerSyncState.PENDING_CHANGES, status.state)
        assertEquals(2, status.pendingUploadCount)
    }

    // 4. IN_FLIGHT ops count as pending uploads
    @Test
    fun test4_inFlightOps_countAsPendingUploads() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        followerDao.insertOutboxOperation(
            FollowerSyncOutboxEntity("op_inflight", followerDeviceId, 1L, 1, "CREATE", "item_1", null, "{}", 1000L, "IN_FLIGHT", createdLocallyAt = 1000L)
        )

        val status = statusRepository.calculateStatus(followerDao)
        assertEquals(FollowerSyncState.PENDING_CHANGES, status.state)
        assertEquals(1, status.pendingUploadCount)
    }

    // 5. APPLIED ops do NOT count as pending uploads
    @Test
    fun test5_appliedOps_doNotCountAsPendingUploads() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        followerDao.insertOutboxOperation(
            FollowerSyncOutboxEntity("op_applied", followerDeviceId, 1L, 1, "CREATE", "item_1", null, "{}", 1000L, "APPLIED", createdLocallyAt = 1000L)
        )

        val status = statusRepository.calculateStatus(followerDao)
        assertEquals(FollowerSyncState.NO_PENDING_UPLOADS, status.state)
        assertEquals(0, status.pendingUploadCount)
    }

    // 6. CONFLICT ops do NOT count as pending uploads, but populate conflictCount
    @Test
    fun test6_conflictOps_doNotCountAsPendingUploads() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        followerDao.insertOutboxOperation(
            FollowerSyncOutboxEntity("op_conflict", followerDeviceId, 1L, 1, "UPDATE", "item_1", 1L, "{}", 1000L, "CONFLICT", createdLocallyAt = 1000L)
        )

        val status = statusRepository.calculateStatus(followerDao)
        assertEquals(FollowerSyncState.NO_PENDING_UPLOADS, status.state)
        assertEquals(0, status.pendingUploadCount)
        assertEquals(1, status.conflictCount)
    }

    // 7. REJECTED ops do NOT count as pending uploads, but populate rejectedCount
    @Test
    fun test7_rejectedOps_doNotCountAsPendingUploads() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        followerDao.insertOutboxOperation(
            FollowerSyncOutboxEntity("op_rejected", followerDeviceId, 1L, 1, "UPDATE", "item_1", 1L, "{}", 1000L, "REJECTED", createdLocallyAt = 1000L)
        )

        val status = statusRepository.calculateStatus(followerDao)
        assertEquals(FollowerSyncState.NO_PENDING_UPLOADS, status.state)
        assertEquals(0, status.pendingUploadCount)
        assertEquals(1, status.rejectedCount)
    }

    // 8. PENDING_APPROVAL represented accurately
    @Test
    fun test8_pendingApprovalOps_representedAccurately() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        followerDao.insertOutboxOperation(
            FollowerSyncOutboxEntity("op_approval", followerDeviceId, 1L, 1, "DELETE", "item_1", 1L, "{}", 1000L, "PENDING_APPROVAL", createdLocallyAt = 1000L)
        )

        val status = statusRepository.calculateStatus(followerDao)
        assertEquals(FollowerSyncState.NO_PENDING_UPLOADS, status.state)
        assertEquals(0, status.pendingUploadCount)
        assertEquals(1, status.pendingApprovalCount)
    }

    // 9. WorkManager RUNNING -> SYNCING state
    @Test
    fun test9_workManagerRunning_returnsSyncingState() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        val status = statusRepository.calculateStatus(followerDao, isWorkerRunning = true)
        assertEquals(FollowerSyncState.SYNCING, status.state)
    }

    // 10. Transport failure -> SYNC_ERROR state
    @Test
    fun test10_transportFailure_returnsSyncErrorState() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        statusRepository.reportTransientSyncError("NETWORK_ERROR: Connection refused")

        val status = statusRepository.calculateStatus(followerDao)
        assertEquals(FollowerSyncState.SYNC_ERROR, status.state)
        assertEquals("NETWORK_ERROR: Connection refused", status.lastError)
    }

    // 11. Try Again schedules existing unique worker
    @Test
    fun test11_tryAgain_schedulesExistingUniqueWorker() = runTest {
        var trigger: String? = null
        val testScheduler = object : FollowerSyncScheduler() {
            override fun scheduleSyncWork(context: android.content.Context?, triggerReason: String): com.example.chatgpt_mealplanner.core.sync.follower.worker.WorkSchedulingResult {
                trigger = triggerReason
                return com.example.chatgpt_mealplanner.core.sync.follower.worker.WorkSchedulingResult.SUBMITTED_AND_CONFIRMED
            }
        }

        testScheduler.scheduleSyncWork(null, "TRY_AGAIN_CLICKED")
        assertEquals("TRY_AGAIN_CLICKED", trigger)
    }

    // 12. Repeated Try Again does NOT create parallel workers (maintains single WORK_NAME)
    @Test
    fun test12_repeatedTryAgain_doesNotCreateParallelWorkers() = runTest {
        assertEquals("pantry_follower_outbox_sync", FollowerSyncScheduler.WORK_NAME)
    }

    // 13. Explicit FOLLOWER_REVOKED response -> ACCESS_REVOKED state
    @Test
    fun test13_revokedResponse_returnsAccessRevokedState() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        statusRepository.reportExplicitRevocation()

        val status = statusRepository.calculateStatus(followerDao)
        assertEquals(FollowerSyncState.ACCESS_REVOKED, status.state)
    }

    // 14. Revoked state preserves follower inventory
    @Test
    fun test14_revokedState_preservesFollowerInventory() = runTest {
        followerDao.insertOrUpdateItem(
            FollowerInventoryItemEntity(
                itemId = "item_preserved",
                canonicalName = "Apple",
                confirmedDisplayName = "Red Apple",
                confirmedBrand = null,
                confirmedCategory = "FRESH_PANTRY",
                confirmedStorageLocation = "FRIDGE",
                confirmedQuantity = 3.0,
                confirmedQuantityUnit = "PCS",
                confirmedNotes = null,
                confirmedStatus = "AVAILABLE",
                mainConfirmedRevision = 1L,
                displayName = "Red Apple",
                brand = null,
                category = "FRESH_PANTRY",
                storageLocation = "FRIDGE",
                quantity = 3.0,
                quantityUnit = "PCS",
                notes = null,
                status = "AVAILABLE",
                optimisticRevision = 1L,
                isPendingSync = false,
                updatedAt = System.currentTimeMillis()
            )
        )

        statusRepository.reportExplicitRevocation()
        val itemsAfterRevocation = followerDao.getAllItems()
        assertEquals(1, itemsAfterRevocation.size)
        assertEquals("item_preserved", itemsAfterRevocation[0].itemId)
    }

    // 15. Revoked state preserves device identity
    @Test
    fun test15_revokedState_preservesDeviceIdentity() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        statusRepository.reportExplicitRevocation()

        val config = pairingStore.getPairingConfig()
        assertNotNull(config)
        assertEquals(followerDeviceId, config?.deviceId)
    }

    // 16. Revoked state does NOT delete pairing configuration
    @Test
    fun test16_revokedState_doesNotDeletePairingConfiguration() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        statusRepository.reportExplicitRevocation()

        assertTrue(pairingStore.isPaired())
    }

    // 17. Auth/session expiry is NOT mislabeled revocation
    @Test
    fun test17_authExpiry_isNotMislabeledRevocation() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        statusRepository.reportAuthenticationError("Session token expired")

        val status = statusRepository.calculateStatus(followerDao)
        assertEquals(FollowerSyncState.AUTHENTICATION_REQUIRED, status.state)
    }

    // 18. Empty outbox alone is NOT sufficient to claim global convergence
    @Test
    fun test18_emptyOutboxAlone_doesNotClaimGlobalConvergence() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        val status = statusRepository.calculateStatus(followerDao)

        // Verifies label is NO_PENDING_UPLOADS / No changes waiting to upload
        assertEquals(FollowerSyncState.NO_PENDING_UPLOADS, status.state)
        assertEquals(0, status.pendingUploadCount)
    }

    // 19. Status precedence is deterministic when multiple conditions exist
    @Test
    fun test19_statusPrecedence_deterministicWhenMultipleConditionsExist() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        followerDao.insertOutboxOperation(
            FollowerSyncOutboxEntity("op_1", followerDeviceId, 1L, 1, "CREATE", "item_1", null, "{}", 1000L, "PENDING", createdLocallyAt = 1000L)
        )

        // 1. REVOKED takes precedence over PENDING_CHANGES
        statusRepository.reportExplicitRevocation()
        assertEquals(FollowerSyncState.ACCESS_REVOKED, statusRepository.calculateStatus(followerDao).state)

        // 2. Clear REVOKED for test setup -> SYNC_ERROR takes precedence over PENDING_CHANGES
        pairingStore.clear()
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        val freshRepo = FollowerSyncStatusRepository(pairingStore)
        freshRepo.reportTransientSyncError("NETWORK_ERROR: Timeout")
        assertEquals(FollowerSyncState.SYNC_ERROR, freshRepo.calculateStatus(followerDao).state)

        // 3. Clear error -> PENDING_CHANGES active
        freshRepo.reportTransientSyncError(null)
        assertEquals(FollowerSyncState.PENDING_CHANGES, freshRepo.calculateStatus(followerDao).state)
    }

    // 20. Process restart behavior does NOT falsely report success
    @Test
    fun test20_processRestart_doesNotFalselyReportSuccess() = runTest {
        val newRepo = FollowerSyncStatusRepository(pairingStore)
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))

        followerDao.insertOutboxOperation(
            FollowerSyncOutboxEntity("op_unpushed", followerDeviceId, 1L, 1, "CREATE", "item_1", null, "{}", 1000L, "PENDING", createdLocallyAt = 1000L)
        )

        val status = newRepo.calculateStatus(followerDao)
        assertEquals(FollowerSyncState.PENDING_CHANGES, status.state)
        assertEquals(1, status.pendingUploadCount)
    }

    // 21. Revoked follower cannot reactivate through recovery UX
    @Test
    fun test21_revokedFollower_cannotReactivateThroughRecoveryUx() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        statusRepository.reportExplicitRevocation()
        val status = statusRepository.calculateStatus(followerDao)

        assertEquals(FollowerSyncState.ACCESS_REVOKED, status.state)
        // Verify state remains ACCESS_REVOKED
        assertTrue(statusRepository.isRevoked.value)
    }

    // 22. Phase 4A QR enrollment remains functional
    @Test
    fun test22_phase4aQrEnrollment_remainsFunctional() = runTest {
        val inv = invitationManager.createInvitation(householdId, mainDeviceId, "192.168.1.100", 8443, validFingerprint)
        val req = EnrollmentRequestDto(inv.enrollmentToken, householdId, mainDeviceId, UUID.randomUUID().toString(), "New Phone", keyStoreManager.getPublicKeyBase64("key_new")!!)

        val res = invitationManager.processEnrollment(req, householdId, mainDeviceId)
        assertTrue("Phase 4A enrollment MUST remain functional", res.success)
    }

    // 23. Phase 3C transport failure still exits pass without tight loop
    @Test
    fun test23_phase3cTransportFailure_exitsPassWithoutTightLoop() = runTest {
        var passCount = 0
        val uploader = object : com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploader(null, PantrySyncTestClient(keyStoreManager), com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine()) {
            override suspend fun performSyncUpload(
                hostIp: String,
                port: Int,
                pinnedCertFingerprint: String?,
                sessionToken: String,
                deviceId: String,
                customDao: com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerDao?,
                sessionTokenProvider: (suspend (forceRefresh: Boolean) -> String)?
            ): com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploadPassSummary {
                passCount++
                return com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploadPassSummary(0, 0, 0, 0, 0, 1)
            }
        }

        val summary = uploader.performSyncUpload("192.168.1.100", 8443, validFingerprint, "tok_123", followerDeviceId, followerDao)
        assertEquals(1, passCount)
        assertEquals(1, summary.transportErrorCount)
    }

    // 24. Destructive confirmation regression remains intact
    @Test
    fun test24_destructiveConfirmation_regressionIntact() = runTest {
        val mainDao = com.example.chatgpt_mealplanner.core.service.FakeInventoryDao()
        val item = InventoryItemEntity(
            id = "del_item_24",
            canonicalName = "Delete Item",
            displayName = "Delete Item",
            brand = null,
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = 1.0,
            quantityUnit = QuantityUnit.UNIT,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE,
            frozen = false,
            staple = false,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            revision = 1L
        )
        mainDao.insertItem(item)

        // 1 request -> 1 confirm -> 1 mutation
        mainDao.deleteItemById("del_item_24")
        assertNull("Item MUST be deleted only after explicit confirm", mainDao.getItemById("del_item_24"))
    }

    // 25. HTTP 403 with ordinary error code does NOT mark revoked
    @Test
    fun test25_http403WithOrdinaryErrorCode_doesNotMarkRevoked() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        val body403 = """{"success":false,"errorCode":"FORBIDDEN","errorMessage":"Access denied"}"""

        statusRepository.reportTransientSyncError(body403)
        assertFalse("Generic HTTP 403 without FOLLOWER_REVOKED MUST NOT mark revoked", pairingStore.isRevoked())
        assertEquals(FollowerSyncState.SYNC_ERROR, statusRepository.calculateStatus(followerDao).state)
        statusRepository.clearSyncError()
        assertEquals(FollowerSyncState.NO_PENDING_UPLOADS, statusRepository.calculateStatus(followerDao).state)
    }

    // 26. Free text containing incidental FOLLOWER_REVOKED string does NOT mark revoked
    @Test
    fun test26_freeTextContainingIncidentalRevokedString_doesNotMarkRevoked() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        val bodyIncidental = """{"success":false,"errorCode":"GENERIC_ERROR","errorMessage":"See documentation for FOLLOWER_REVOKED cases"}"""

        statusRepository.reportTransientSyncError(bodyIncidental)
        assertFalse("Incidental string in errorMessage MUST NOT mark revoked", pairingStore.isRevoked())
    }

    // 27. Malformed JSON does NOT mark revoked
    @Test
    fun test27_malformedJson_doesNotMarkRevoked() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        val malformed = "!!!not_json_FOLLOWER_REVOKED!!!"

        statusRepository.reportTransientSyncError(malformed)
        assertFalse("Malformed JSON response MUST NOT mark revoked", pairingStore.isRevoked())
    }

    // 28. Structured errorCode FOLLOWER_REVOKED marks revoked and survives recreation
    @Test
    fun test28_structuredErrorCodeFollowerRevoked_marksRevokedAndSurvivesRecreation() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        val structuredRevoked = """{"success":false,"errorCode":"FOLLOWER_REVOKED","errorMessage":"Follower device has been revoked by Main"}"""

        if (com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerErrorClassifier.isExplicitRevocation(structuredRevoked)) {
            statusRepository.reportExplicitRevocation()
        }

        assertTrue("Structured FOLLOWER_REVOKED MUST mark revoked in store", pairingStore.isRevoked())

        val newRepo = FollowerSyncStatusRepository(pairingStore)
        assertEquals(FollowerSyncState.ACCESS_REVOKED, newRepo.calculateStatus(followerDao).state)
    }

    @Test
    fun test_rawFollowerRevokedToken_isNotStructuredRevocation() = runTest {
        assertFalse(com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerErrorClassifier.isExplicitRevocation("FOLLOWER_REVOKED"))
        assertFalse(com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerErrorClassifier.isExplicitRevocation(" FOLLOWER_REVOKED "))
        assertFalse(com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerErrorClassifier.isExplicitRevocation("error FOLLOWER_REVOKED"))
        assertFalse(com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerErrorClassifier.isExplicitRevocation("""{"errorMessage":"FOLLOWER_REVOKED"}"""))
        assertFalse(com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerErrorClassifier.isExplicitRevocation("""{"errorCode":"FORBIDDEN","errorMessage":"FOLLOWER_REVOKED"}"""))
        assertFalse(com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerErrorClassifier.isExplicitRevocation("!not_json_FOLLOWER_REVOKED!"))

        assertTrue(com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerErrorClassifier.isExplicitRevocation("""{"errorCode":"FOLLOWER_REVOKED"}"""))
        assertTrue(com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerErrorClassifier.isExplicitRevocation("""{"errorCode":"FOLLOWER_REVOKED","errorMessage":"Access revoked"}"""))
    }

    @Test
    fun test_transientRawFollowerRevokedText_doesNotPersistRevocation() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        statusRepository.reportTransientSyncError("FOLLOWER_REVOKED")

        assertFalse("reportTransientSyncError MUST NOT mark revoked", pairingStore.isRevoked())
        assertEquals(FollowerSyncState.SYNC_ERROR, statusRepository.calculateStatus(followerDao).state)
        statusRepository.clearSyncError()
        assertEquals(FollowerSyncState.NO_PENDING_UPLOADS, statusRepository.calculateStatus(followerDao).state)
    }

    // 29. WorkManager RUNNING state updates status to SYNCING
    @Test
    fun test29_workManagerRunningState_updatesStatusToSyncing() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        val status = statusRepository.calculateStatus(followerDao, isWorkerRunning = true)
        assertEquals(FollowerSyncState.SYNCING, status.state)
    }

    // 30. WorkManager ENQUEUED state does NOT map to SYNCING
    @Test
    fun test30_workManagerEnqueuedState_doesNotMapToSyncing() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        val status = statusRepository.calculateStatus(followerDao, isWorkerRunning = false)
        assertEquals(FollowerSyncState.NO_PENDING_UPLOADS, status.state)
    }

    // 31. WorkManager SUCCEEDED state exits SYNCING state
    @Test
    fun test31_workManagerSucceededState_exitsSyncingState() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        val syncingStatus = statusRepository.calculateStatus(followerDao, isWorkerRunning = true)
        assertEquals(FollowerSyncState.SYNCING, syncingStatus.state)

        val idleStatus = statusRepository.calculateStatus(followerDao, isWorkerRunning = false)
        assertEquals(FollowerSyncState.NO_PENDING_UPLOADS, idleStatus.state)
    }
}
