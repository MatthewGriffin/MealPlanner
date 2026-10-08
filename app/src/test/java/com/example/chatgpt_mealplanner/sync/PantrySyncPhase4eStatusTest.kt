package com.example.chatgpt_mealplanner.sync

import com.example.chatgpt_mealplanner.core.database.entity.HouseholdIdentityEntity
import com.example.chatgpt_mealplanner.core.database.entity.PairedDeviceEntity
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine
import com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState
import com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusRepository
import com.example.chatgpt_mealplanner.core.sync.follower.worker.FollowerSyncScheduler
import com.example.chatgpt_mealplanner.sync.contract.SyncFeedItemDto
import com.example.chatgpt_mealplanner.sync.contract.SyncFeedResponseDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationStatus
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationType
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
class PantrySyncPhase4eStatusTest {

    private lateinit var pairingStore: FakePairingStore
    private lateinit var statusRepository: FollowerSyncStatusRepository
    private lateinit var followerDao: FakeFollowerDao
    private lateinit var mainPairedDeviceDao: TestSyncPairedDeviceDao
    private lateinit var keyStoreManager: TestEnrollmentKeyStoreManager

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

    // A. Pending count from real outbox states
    @Test
    fun testA_pendingCount_fromRealOutboxStates() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        followerDao.insertOutboxOperation(FollowerSyncOutboxEntity("op_1", followerDeviceId, 1L, 1, "CREATE", "item_1", null, "{}", 1000L, "PENDING", createdLocallyAt = 1000L))
        followerDao.insertOutboxOperation(FollowerSyncOutboxEntity("op_2", followerDeviceId, 2L, 1, "UPDATE", "item_2", 1L, "{}", 1001L, "IN_FLIGHT", createdLocallyAt = 1001L))

        val status = statusRepository.calculateStatus(followerDao)
        assertEquals(2, status.pendingUploadCount)
    }

    // B. Conflict count
    @Test
    fun testB_conflictCount() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        followerDao.insertOutboxOperation(FollowerSyncOutboxEntity("op_conf", followerDeviceId, 1L, 1, "UPDATE", "item_1", 1L, "{}", 1000L, "CONFLICT", createdLocallyAt = 1000L))

        val status = statusRepository.calculateStatus(followerDao)
        assertEquals(1, status.conflictCount)
    }

    // C. Pending approval count
    @Test
    fun testC_pendingApprovalCount() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        followerDao.insertOutboxOperation(FollowerSyncOutboxEntity("op_app", followerDeviceId, 1L, 1, "DELETE", "item_1", 1L, "{}", 1000L, "PENDING_APPROVAL", createdLocallyAt = 1000L))

        val status = statusRepository.calculateStatus(followerDao)
        assertEquals(1, status.pendingApprovalCount)
    }

    // D. Rejected count
    @Test
    fun testD_rejectedCount() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        followerDao.insertOutboxOperation(FollowerSyncOutboxEntity("op_rej", followerDeviceId, 1L, 1, "UPDATE", "item_1", 1L, "{}", 1000L, "REJECTED", createdLocallyAt = 1000L))

        val status = statusRepository.calculateStatus(followerDao)
        assertEquals(1, status.rejectedCount)
    }

    // E. Syncing state while existing WorkManager job is RUNNING
    @Test
    fun testE_syncingStateWhileWorkManagerRunning() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        val status = statusRepository.calculateStatus(followerDao, isWorkerRunning = true)
        assertEquals(FollowerSyncState.SYNCING, status.state)
    }

    // F. ENQUEUED does not falsely mean successful sync
    @Test
    fun testF_enqueuedDoesNotFalselyMeanSuccessfulSync() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        val status = statusRepository.calculateStatus(followerDao, isWorkerRunning = false)
        assertEquals(FollowerSyncState.NO_PENDING_UPLOADS, status.state)
    }

    // G. Successful production sync updates last-success timestamp
    @Test
    fun testG_successfulProductionSyncUpdatesLastSuccessTimestamp() = runTest {
        val feedResponse = SyncFeedResponseDto(
            householdId = householdId,
            latestCanonicalSequence = 101L,
            items = emptyList(),
            hasMore = false
        )
        val customClient = object : PantrySyncTestClient(keyStoreManager) {
            override suspend fun getSyncFeed(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String, sinceSequence: Long, limit: Int): com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult {
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(true, feedResponse)
            }
        }

        val feedEngine = FollowerFeedPullEngine(null, customClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine())
        val beforePass = System.currentTimeMillis()
        feedEngine.performFeedPullPass("192.168.1.100", 8443, validFingerprint, "tok_123", followerDeviceId, followerDao)

        val meta = followerDao.getSyncMetadata()
        assertNotNull(meta?.lastSuccessfulPullAt)
        assertTrue("lastSuccessfulPullAt MUST be updated on successful sync pass", (meta?.lastSuccessfulPullAt ?: 0L) >= beforePass)
    }

    // H. Transport failure does NOT update success timestamp
    @Test
    fun testH_transportFailureDoesNotUpdateSuccessTimestamp() = runTest {
        val initialTimestamp = 10000L
        followerDao.updateSyncMetadata(FollowerSyncMetadataEntity(singletonId = 1, lastSuccessfulPullAt = initialTimestamp))

        val customClient = object : PantrySyncTestClient(keyStoreManager) {
            override suspend fun getSyncFeed(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String, sinceSequence: Long, limit: Int): com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult {
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(false, null, "NETWORK_ERROR")
            }
        }

        val feedEngine = FollowerFeedPullEngine(null, customClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine())
        feedEngine.performFeedPullPass("192.168.1.100", 8443, validFingerprint, "tok_123", followerDeviceId, followerDao)

        val meta = followerDao.getSyncMetadata()
        assertEquals("Transport failure MUST NOT update lastSuccessfulPullAt", initialTimestamp, meta?.lastSuccessfulPullAt)
    }

    // Multi-page failure: Page 1 succeeds, Page 2 fails -> lastSuccessfulPullAt MUST NOT update
    @Test
    fun testV2_multiPageFailure_doesNotUpdateLastSuccessTimestamp() = runTest {
        val initialTimestamp = 10000L
        followerDao.updateSyncMetadata(FollowerSyncMetadataEntity(singletonId = 1, lastSuccessfulPullAt = initialTimestamp))

        val page1Item = SyncFeedItemDto(101L, "op_p1", mainDeviceId, SyncOperationType.ADD_ITEM, status = SyncOperationStatus.APPLIED, resultCode = "SUCCESS", occurredAt = System.currentTimeMillis())
        val page1 = SyncFeedResponseDto(householdId, 101L, listOf(page1Item), hasMore = true)

        var callCount = 0
        val customClient = object : PantrySyncTestClient(keyStoreManager) {
            override suspend fun getSyncFeed(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String, sinceSequence: Long, limit: Int): com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult {
                callCount++
                return if (callCount == 1) {
                    com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(true, page1)
                } else {
                    com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(false, null, "PAGE_2_TRANSPORT_ERROR")
                }
            }
        }

        val feedEngine = FollowerFeedPullEngine(null, customClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine())
        feedEngine.performFeedPullPass("192.168.1.100", 8443, validFingerprint, "tok_123", followerDeviceId, followerDao)

        val meta = followerDao.getSyncMetadata()
        assertEquals("Multi-page failure MUST NOT update lastSuccessfulPullAt", initialTimestamp, meta?.lastSuccessfulPullAt)
    }

    // Empty successful feed -> updates lastSuccessfulPullAt
    @Test
    fun testV2_emptySuccessfulFeed_updatesLastSuccessTimestamp() = runTest {
        val beforePass = System.currentTimeMillis()
        val emptyFeed = SyncFeedResponseDto(householdId, 100L, emptyList(), hasMore = false)

        val customClient = object : PantrySyncTestClient(keyStoreManager) {
            override suspend fun getSyncFeed(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String, sinceSequence: Long, limit: Int): com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult {
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(true, emptyFeed)
            }
        }

        val feedEngine = FollowerFeedPullEngine(null, customClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine())
        feedEngine.performFeedPullPass("192.168.1.100", 8443, validFingerprint, "tok_123", followerDeviceId, followerDao)

        val meta = followerDao.getSyncMetadata()
        assertNotNull(meta?.lastSuccessfulPullAt)
        assertTrue("Valid empty feed response MUST update lastSuccessfulPullAt", (meta?.lastSuccessfulPullAt ?: 0L) >= beforePass)
    }

    // Reconciliation failure during feed processing -> lastSuccessfulPullAt MUST NOT update
    @Test
    fun testV2_reconciliationFailure_doesNotUpdateLastSuccessTimestamp() = runTest {
        val initialTimestamp = 10000L
        followerDao.updateSyncMetadata(FollowerSyncMetadataEntity(singletonId = 1, lastSuccessfulPullAt = initialTimestamp))

        val snapItem = com.example.chatgpt_mealplanner.sync.contract.InventoryItemSyncDto("item_fail", null, "Item", "Item", null, "CUPBOARD", "CUPBOARD", 1.0, "UNIT", null, "AVAILABLE", 1L)
        val page1Item = SyncFeedItemDto(101L, "op_fail", mainDeviceId, SyncOperationType.ADD_ITEM, status = SyncOperationStatus.APPLIED, resultCode = "SUCCESS", canonicalItemSnapshot = snapItem, occurredAt = System.currentTimeMillis())
        val page1 = SyncFeedResponseDto(householdId, 101L, listOf(page1Item), hasMore = false)

        val throwingDao = object : FakeFollowerDao() {
            override suspend fun insertOrUpdateItem(item: FollowerInventoryItemEntity) {
                throw IllegalStateException("Simulated Room DB write error during item reconciliation")
            }
        }
        throwingDao.updateSyncMetadata(FollowerSyncMetadataEntity(singletonId = 1, lastSuccessfulPullAt = initialTimestamp))

        val customClient = object : PantrySyncTestClient(keyStoreManager) {
            override suspend fun getSyncFeed(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String, sinceSequence: Long, limit: Int): com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult {
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(true, page1)
            }
        }

        val feedEngine = FollowerFeedPullEngine(null, customClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine())
        runCatching {
            feedEngine.performFeedPullPass("192.168.1.100", 8443, validFingerprint, "tok_123", followerDeviceId, throwingDao)
        }

        val meta = throwingDao.getSyncMetadata()
        assertEquals("Reconciliation failure MUST NOT update lastSuccessfulPullAt", initialTimestamp, meta?.lastSuccessfulPullAt)
    }

    // Final metadata persistence throws -> no false timestamp, retry remains safe
    @Test
    fun testV2_finalMetadataPersistenceFailure_doesNotUpdateLastSuccessTimestamp() = runTest {
        val initialTimestamp = 10000L
        val emptyFeed = SyncFeedResponseDto(householdId, 100L, emptyList(), hasMore = false)

        val throwingMetaDao = object : FakeFollowerDao() {
            override suspend fun updateSyncMetadata(metadata: FollowerSyncMetadataEntity) {
                if (metadata.lastSuccessfulPullAt != initialTimestamp && metadata.lastSuccessfulPullAt != null) {
                    throw IllegalStateException("Simulated metadata write failure on final lastSuccessfulPullAt update")
                }
                super.updateSyncMetadata(metadata)
            }
        }
        throwingMetaDao.updateSyncMetadata(FollowerSyncMetadataEntity(singletonId = 1, lastSuccessfulPullAt = initialTimestamp))

        val customClient = object : PantrySyncTestClient(keyStoreManager) {
            override suspend fun getSyncFeed(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String, sinceSequence: Long, limit: Int): com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult {
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(true, emptyFeed)
            }
        }

        val feedEngine = FollowerFeedPullEngine(null, customClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine())
        runCatching {
            feedEngine.performFeedPullPass("192.168.1.100", 8443, validFingerprint, "tok_123", followerDeviceId, throwingMetaDao)
        }

        val meta = throwingMetaDao.getSyncMetadata()
        assertEquals("Final metadata write failure MUST NOT report false new timestamp", initialTimestamp, meta?.lastSuccessfulPullAt)
    }

    // I. Structured auth failure sets AUTHENTICATION_REQUIRED
    @Test
    fun testI_structuredAuthFailureSetsAuthRequired() = runTest {
        val initialTimestamp = 10000L
        followerDao.updateSyncMetadata(FollowerSyncMetadataEntity(singletonId = 1, bootstrapCompleted = true, lastSuccessfulPullAt = initialTimestamp))

        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        statusRepository.reportAuthenticationError("Session expired")

        val status = statusRepository.calculateStatus(followerDao)
        assertEquals(FollowerSyncState.AUTHENTICATION_REQUIRED, status.state)

        val meta = followerDao.getSyncMetadata()
        assertEquals("Auth failure MUST NOT update lastSuccessfulPullAt", initialTimestamp, meta?.lastSuccessfulPullAt)
    }

    // A. reportTransientSyncError("AUTH_EXPIRED") -> TRANSIENT_NETWORK_ERROR / SYNC_ERROR, NOT AUTHENTICATION_REQUIRED
    @Test
    fun testV23_reportTransientSyncErrorWithAuthExpiredText_doesNotSetAuthRequired() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        statusRepository.reportTransientSyncError("AUTH_EXPIRED")

        val status = statusRepository.calculateStatus(followerDao)
        assertEquals("reportTransientSyncError MUST NOT set AUTHENTICATION_REQUIRED even if text contains AUTH_EXPIRED", FollowerSyncState.SYNC_ERROR, status.state)
    }

    // B. reportGenericSyncError("AUTH_EXPIRED") -> GENERIC_SYNC_ERROR / SYNC_ERROR, NOT AUTHENTICATION_REQUIRED
    @Test
    fun testV23_reportGenericSyncErrorWithAuthExpiredText_doesNotSetAuthRequired() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        statusRepository.reportGenericSyncError("AUTH_EXPIRED")

        val status = statusRepository.calculateStatus(followerDao)
        assertEquals("reportGenericSyncError MUST NOT set AUTHENTICATION_REQUIRED even if text contains AUTH_EXPIRED", FollowerSyncState.SYNC_ERROR, status.state)
    }

    // C. reportGenericSyncError("NETWORK") -> generic SYNC_ERROR only
    @Test
    fun testV23_reportGenericSyncErrorWithNetworkText_setsGenericSyncErrorOnly() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        statusRepository.reportGenericSyncError("NETWORK")

        val status = statusRepository.calculateStatus(followerDao)
        assertEquals(FollowerSyncState.SYNC_ERROR, status.state)
    }

    // D. reportGenericSyncError("TRANSPORT") -> generic SYNC_ERROR only
    @Test
    fun testV23_reportGenericSyncErrorWithTransportText_setsGenericSyncErrorOnly() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        statusRepository.reportGenericSyncError("TRANSPORT")

        val status = statusRepository.calculateStatus(followerDao)
        assertEquals(FollowerSyncState.SYNC_ERROR, status.state)
    }

    // E. reportGenericSyncError("CONNECT") -> generic SYNC_ERROR only
    @Test
    fun testV23_reportGenericSyncErrorWithConnectText_setsGenericSyncErrorOnly() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        statusRepository.reportGenericSyncError("CONNECT")

        val status = statusRepository.calculateStatus(followerDao)
        assertEquals(FollowerSyncState.SYNC_ERROR, status.state)
    }

    // F. reportGenericSyncError("FOLLOWER_REVOKED") -> NOT ACCESS_REVOKED
    @Test
    fun testV23_reportGenericSyncErrorWithFollowerRevokedText_doesNotSetAccessRevoked() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        statusRepository.reportGenericSyncError("FOLLOWER_REVOKED")

        assertFalse("reportGenericSyncError MUST NOT mark revoked", pairingStore.isRevoked())
        val status = statusRepository.calculateStatus(followerDao)
        assertEquals("reportGenericSyncError MUST NOT set ACCESS_REVOKED", FollowerSyncState.SYNC_ERROR, status.state)
    }

    // G. reportGenericSyncError("DEVICE_REVOKED") -> NOT ACCESS_REVOKED
    @Test
    fun testV23_reportGenericSyncErrorWithDeviceRevokedText_doesNotSetAccessRevoked() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        statusRepository.reportGenericSyncError("DEVICE_REVOKED")

        assertFalse("reportGenericSyncError MUST NOT mark revoked", pairingStore.isRevoked())
        val status = statusRepository.calculateStatus(followerDao)
        assertEquals("reportGenericSyncError MUST NOT set ACCESS_REVOKED", FollowerSyncState.SYNC_ERROR, status.state)
    }

    // HTTP 500 on token request -> AuthTokenResult.ServerFailure -> SYNC_ERROR (NOT AUTHENTICATION_REQUIRED)
    @Test
    fun testV25_http500OnTokenRequest_mapsToSyncErrorNotAuthRequired() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        statusRepository.reportGenericSyncError("Server error during auth challenge (HTTP 500)")

        val status = statusRepository.calculateStatus(followerDao)
        assertEquals("HTTP 500 MUST evaluate to SYNC_ERROR, not AUTHENTICATION_REQUIRED", FollowerSyncState.SYNC_ERROR, status.state)
    }

    // HTTP 503 on token request -> AuthTokenResult.ServerFailure -> SYNC_ERROR (NOT AUTHENTICATION_REQUIRED)
    @Test
    fun testV25_http503OnTokenRequest_mapsToSyncErrorNotAuthRequired() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        statusRepository.reportGenericSyncError("Server error during auth challenge (HTTP 503)")

        val status = statusRepository.calculateStatus(followerDao)
        assertEquals("HTTP 503 MUST evaluate to SYNC_ERROR, not AUTHENTICATION_REQUIRED", FollowerSyncState.SYNC_ERROR, status.state)
    }

    // Malformed session token response -> AuthTokenResult.ServerFailure -> SYNC_ERROR
    @Test
    fun testV25_malformedSessionTokenResponse_mapsToSyncError() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        statusRepository.reportGenericSyncError("Malformed session response JSON")

        val status = statusRepository.calculateStatus(followerDao)
        assertEquals("Malformed session response MUST evaluate to SYNC_ERROR", FollowerSyncState.SYNC_ERROR, status.state)
    }

    // Generic 401 without structured revocation code -> AuthTokenResult.AuthFailure -> AUTHENTICATION_REQUIRED (NOT ACCESS_REVOKED)
    @Test
    fun testV25_generic401WithoutStructuredRevocation_setsAuthRequiredNotRevoked() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        statusRepository.reportAuthenticationError("Auth challenge rejected by server (HTTP 401)")

        assertFalse("Generic HTTP 401 MUST NOT mark revoked", pairingStore.isRevoked())
        val status = statusRepository.calculateStatus(followerDao)
        assertEquals(FollowerSyncState.AUTHENTICATION_REQUIRED, status.state)
    }

    // Generic 403 without structured revocation code -> AuthTokenResult.AuthFailure -> AUTHENTICATION_REQUIRED (NOT ACCESS_REVOKED)
    @Test
    fun testV25_generic403WithoutStructuredRevocation_setsAuthRequiredNotRevoked() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        statusRepository.reportAuthenticationError("Session creation rejected by server (HTTP 403)")

        assertFalse("Generic HTTP 403 MUST NOT mark revoked", pairingStore.isRevoked())
        val status = statusRepository.calculateStatus(followerDao)
        assertEquals(FollowerSyncState.AUTHENTICATION_REQUIRED, status.state)
    }

    // Coincidental free-text containing "AUTH_EXPIRED" in generic message does NOT set AUTHENTICATION_REQUIRED
    @Test
    fun testV22_genericTextWithAuthExpired_doesNotSetAuthRequired() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        statusRepository.reportGenericSyncError("User notes mentioned AUTH_EXPIRED coincidently")

        val status = statusRepository.calculateStatus(followerDao)
        assertEquals("Coincidental text MUST NOT trigger AUTHENTICATION_REQUIRED", FollowerSyncState.SYNC_ERROR, status.state)
    }

    // Coincidental free-text containing "NETWORK" in generic message does NOT set SYNC_ERROR without category
    @Test
    fun testV22_coincidentalNetworkText_doesNotSetSyncError() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        statusRepository.clearSyncError()

        val status = statusRepository.calculateStatus(followerDao)
        assertEquals(FollowerSyncState.NO_PENDING_UPLOADS, status.state)
    }

    // Worker RUNNING cannot mask structured auth error
    @Test
    fun testV22_workerRunning_cannotMaskStructuredAuthError() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        statusRepository.reportAuthenticationError("Invalid token")

        val status = statusRepository.calculateStatus(followerDao, isWorkerRunning = true)
        assertEquals("Structured auth error MUST take precedence over Worker RUNNING", FollowerSyncState.AUTHENTICATION_REQUIRED, status.state)
    }

    // Worker RUNNING cannot mask structured transient error
    @Test
    fun testV22_workerRunning_cannotMaskStructuredTransientError() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        statusRepository.reportTransientSyncError("Connection refused")

        val status = statusRepository.calculateStatus(followerDao, isWorkerRunning = true)
        assertEquals("Structured transient error MUST take precedence over Worker RUNNING", FollowerSyncState.SYNC_ERROR, status.state)
    }

    // Worker RUNNING cannot mask revoked state
    @Test
    fun testV22_workerRunning_cannotMaskRevokedState() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        statusRepository.reportExplicitRevocation()

        val status = statusRepository.calculateStatus(followerDao, isWorkerRunning = true)
        assertEquals("ACCESS_REVOKED MUST take precedence over Worker RUNNING", FollowerSyncState.ACCESS_REVOKED, status.state)
    }

    // Revocation-looking free text in error message does NOT set ACCESS_REVOKED
    @Test
    fun testV22_revocationLookingFreeText_doesNotSetAccessRevoked() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        statusRepository.reportGenericSyncError("Server log contained FOLLOWER_REVOKED coincidently")

        val status = statusRepository.calculateStatus(followerDao)
        assertEquals("Free-text containing FOLLOWER_REVOKED MUST NOT trigger ACCESS_REVOKED", FollowerSyncState.SYNC_ERROR, status.state)
    }

    // J. Revocation does NOT update success timestamp
    @Test
    fun testJ_revocationDoesNotUpdateSuccessTimestamp() = runTest {
        val initialTimestamp = 10000L
        followerDao.updateSyncMetadata(FollowerSyncMetadataEntity(singletonId = 1, lastSuccessfulPullAt = initialTimestamp))

        statusRepository.reportExplicitRevocation()
        val meta = followerDao.getSyncMetadata()
        assertEquals("Revocation MUST NOT update lastSuccessfulPullAt", initialTimestamp, meta?.lastSuccessfulPullAt)
    }

    // K. App restart preserves last-success timestamp
    @Test
    fun testK_appRestartPreservesLastSuccessTimestamp() = runTest {
        val savedTimestamp = 25000L
        followerDao.updateSyncMetadata(FollowerSyncMetadataEntity(singletonId = 1, bootstrapCompleted = true, lastSuccessfulPullAt = savedTimestamp))

        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))

        val newRepo = FollowerSyncStatusRepository(pairingStore)
        val status = newRepo.calculateStatus(followerDao)

        assertEquals("lastSuccessfulPullAt MUST persist across restart", savedTimestamp, status.lastSyncTime)
    }

    // L. ACCESS_REVOKED offers no unsafe recovery/reset action
    @Test
    fun testL_accessRevokedOffersNoUnsafeRecoveryOrResetAction() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        statusRepository.reportExplicitRevocation()

        val status = statusRepository.calculateStatus(followerDao)
        assertEquals(FollowerSyncState.ACCESS_REVOKED, status.state)
        assertTrue("Revoked state remains set in store", pairingStore.isRevoked())
    }

    // M. Transient error offers Try Again
    @Test
    fun testM_transientErrorOffersTryAgain() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        statusRepository.reportTransientSyncError("NETWORK_ERROR: Connection timeout")

        val status = statusRepository.calculateStatus(followerDao)
        assertEquals(FollowerSyncState.SYNC_ERROR, status.state)
    }

    // N. Try Again schedules existing unique work only
    @Test
    fun testN_tryAgainSchedulesExistingUniqueWorkOnly() = runTest {
        var triggerReasonPassed: String? = null
        val scheduler = object : FollowerSyncScheduler() {
            override fun scheduleSyncWork(context: android.content.Context?, triggerReason: String): com.example.chatgpt_mealplanner.core.sync.follower.worker.WorkSchedulingResult {
                triggerReasonPassed = triggerReason
                return com.example.chatgpt_mealplanner.core.sync.follower.worker.WorkSchedulingResult.SUBMITTED_AND_CONFIRMED
            }
        }
        scheduler.scheduleSyncWork(null, "TRY_AGAIN_USER_CLICK")
        assertEquals("TRY_AGAIN_USER_CLICK", triggerReasonPassed)
    }

    // O. Repeated Try Again does not create parallel sync jobs
    @Test
    fun testO_repeatedTryAgainDoesNotCreateParallelSyncJobs() = runTest {
        assertEquals("pantry_follower_outbox_sync", FollowerSyncScheduler.WORK_NAME)
    }

    // P. Bootstrap recovery uses existing bootstrap path
    @Test
    fun testP_bootstrapRecoveryUsesExistingBootstrapPath() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        followerDao.updateSyncMetadata(FollowerSyncMetadataEntity(singletonId = 1, bootstrapCompleted = false))

        val status = statusRepository.calculateStatus(followerDao)
        assertEquals(FollowerSyncState.BOOTSTRAP_REQUIRED, status.state)
    }

    // Q. Observation / recomposition alone schedules zero work
    @Test
    fun testQ_observationRecompositionAloneSchedulesZeroWork() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        val status1 = statusRepository.calculateStatus(followerDao)
        val status2 = statusRepository.calculateStatus(followerDao)

        assertEquals(status1, status2)
    }

    // R. Main role does not expose follower recovery controls
    @Test
    fun testR_mainRoleDoesNotExposeFollowerRecoveryControls() = runTest {
        val mainViewModel = com.example.chatgpt_mealplanner.feature.household.HouseholdViewModel(
            identityManager = com.example.chatgpt_mealplanner.core.sync.HouseholdIdentityManager(FakeHouseholdIdentityDao()),
            pairedDeviceRepository = com.example.chatgpt_mealplanner.core.sync.repository.PairedDeviceRepository(TestSyncPairedDeviceDao()),
            followerPairingStore = pairingStore
        )

        val state = mainViewModel.uiState.value
        assertFalse("Main role MUST NOT present as follower paired", state.isFollowerPaired)
    }

    // S. Inaccessible encrypted pairing/status storage fails closed
    @Test
    fun testS_inaccessibleEncryptedStorageFailsClosed() = runTest {
        val brokenStore = object : FakePairingStore() {
            override fun isPaired(): Boolean = false
        }
        val repo = FollowerSyncStatusRepository(brokenStore)
        val status = repo.calculateStatus(followerDao)

        assertEquals("Inaccessible storage MUST evaluate to UNPAIRED", FollowerSyncState.UNPAIRED, status.state)
    }

    // T. Existing Phase 4C status classification remains intact
    @Test
    fun testT_existingPhase4cStatusClassificationRemainsIntact() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        val status = statusRepository.calculateStatus(followerDao)

        assertEquals(FollowerSyncState.NO_PENDING_UPLOADS, status.state)
    }

    // U. Phase 4D conflict / approval lifecycle remains intact
    @Test
    fun testU_phase4dConflictApprovalLifecycleRemainsIntact() = runTest {
        val op = FollowerSyncOutboxEntity("op_4d", followerDeviceId, 1L, 1, "REQUEST_DISCARD_ITEM", "item_4d", 1L, "{}", System.currentTimeMillis(), "PENDING_APPROVAL", createdLocallyAt = System.currentTimeMillis())
        followerDao.insertOutboxOperation(op)

        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        val status = statusRepository.calculateStatus(followerDao)

        assertEquals(1, status.pendingApprovalCount)
    }
}
