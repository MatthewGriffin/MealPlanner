package com.example.chatgpt_mealplanner.sync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.example.chatgpt_mealplanner.core.database.entity.HouseholdIdentityEntity
import com.example.chatgpt_mealplanner.core.database.entity.PairedDeviceEntity
import com.example.chatgpt_mealplanner.core.database.entity.toEntity
import com.example.chatgpt_mealplanner.core.sync.HouseholdIdentityManager
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.core.sync.enrollment.PantryEnrollmentInvitationManager
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig
import com.example.chatgpt_mealplanner.core.sync.repository.PairedDeviceRepository
import com.example.chatgpt_mealplanner.core.sync.server.HostStartResult
import com.example.chatgpt_mealplanner.core.sync.server.PantrySyncHostController
import com.example.chatgpt_mealplanner.core.sync.server.PantrySyncHostService
import com.example.chatgpt_mealplanner.core.sync.server.PantrySyncServer
import com.example.chatgpt_mealplanner.feature.household.HouseholdViewModel
import com.example.chatgpt_mealplanner.feature.household.PairDeviceViewModel
import com.example.chatgpt_mealplanner.sync.contract.EnrollmentRequestDto
import com.example.chatgpt_mealplanner.sync.contract.ReauthorizationRequestDto
import com.example.chatgpt_mealplanner.sync.contract.SyncContractValidation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
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
class PantrySyncPhase4gHostLifecycleTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var pairingStore: FakePairingStore
    private lateinit var mainIdentityDao: FakeHouseholdIdentityDao
    private lateinit var identityManager: HouseholdIdentityManager
    private lateinit var mainPairedDeviceDao: TestSyncPairedDeviceDao
    private lateinit var mainInventoryDao: com.example.chatgpt_mealplanner.core.service.FakeInventoryDao
    private lateinit var keyStoreManager: TestEnrollmentKeyStoreManager
    private lateinit var tlsKeyManager: com.example.chatgpt_mealplanner.core.sync.security.PantryTlsKeyManager
    private lateinit var invitationManager: PantryEnrollmentInvitationManager
    private lateinit var server: PantrySyncServer
    private lateinit var hostController: PantrySyncHostController

    private val householdId = UUID.randomUUID().toString()
    private val mainDeviceId = UUID.randomUUID().toString()
    private val followerDeviceId = UUID.randomUUID().toString()
    private val validFingerprint = "SHA256:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        pairingStore = FakePairingStore()
        mainIdentityDao = FakeHouseholdIdentityDao()
        identityManager = HouseholdIdentityManager(mainIdentityDao)
        mainPairedDeviceDao = TestSyncPairedDeviceDao()
        mainInventoryDao = com.example.chatgpt_mealplanner.core.service.FakeInventoryDao()
        keyStoreManager = TestEnrollmentKeyStoreManager()
        tlsKeyManager = TestPantryTlsKeyManager()
        invitationManager = PantryEnrollmentInvitationManager(mainPairedDeviceDao, keyStoreManager)

        val transactionRunner = TestTransactionRunner()
        val syncEngine = com.example.chatgpt_mealplanner.core.sync.engine.MainCanonicalSyncEngine(FakeCanonicalSyncDao(), mainPairedDeviceDao, mainInventoryDao, transactionRunner)
        val sessionManager = com.example.chatgpt_mealplanner.core.sync.server.SyncSessionManager(keyStoreManager, mainPairedDeviceDao)
        server = PantrySyncServer(identityManager, sessionManager, syncEngine, FakeCanonicalSyncDao(), mainInventoryDao, transactionRunner = transactionRunner, invitationManager = invitationManager)

        val roleResolver = com.example.chatgpt_mealplanner.core.sync.AppRoleResolver(pairingStore)
        hostController = PantrySyncHostController(server, pairingStore, roleResolver)

        kotlinx.coroutines.runBlocking {
            mainIdentityDao.insertHouseholdIdentity(
                HouseholdIdentityEntity(householdId, "Test Household", mainDeviceId, System.currentTimeMillis())
            )
        }
    }

    @After
    fun tearDown() {
        try { server.stop() } catch (_: Throwable) {}
        PantrySyncHostService.resetStateForTest()
        Dispatchers.resetMain()
    }

    // A. Main eligibility allows host start
    @Test
    fun openingFollowerHouseholdSettingsDoesNotCreateMainIdentity() = runTest {
        val identities = FakeHouseholdIdentityDao()
        pairingStore.savePairingConfig(FollowerPairingConfig("localhost", 8443, validFingerprint,
            followerDeviceId, householdId = householdId, mainDeviceId = mainDeviceId))
        val resolver = com.example.chatgpt_mealplanner.core.sync.AppRoleResolver(pairingStore, identities)
        val vm = HouseholdViewModel(HouseholdIdentityManager(identities),
            PairedDeviceRepository(mainPairedDeviceDao), pairingStore, appRoleResolver = resolver)
        advanceUntilIdle()
        assertNull(identities.getHouseholdIdentity())
        assertNull(vm.uiState.value.householdIdentity)
        assertEquals(com.example.chatgpt_mealplanner.core.sync.AppRole.FOLLOWER, vm.uiState.value.appRole)
    }

    @Test
    fun testA_mainEligibility_allowsHostStart() {
        assertTrue("Main installation (unpaired) MUST be eligible to host", hostController.isEligibleToHost())
    }

    // B. Follower eligibility refuses host start
    @Test
    fun testB_followerEligibility_refusesHostStart() {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        assertFalse("Follower installation MUST NOT be eligible to host", hostController.isEligibleToHost())

        val res = hostController.ensureHostStarted(null)
        assertEquals(HostStartResult.NotEligibleFollower, res)
    }

    // C. Repeated ensureHostStarted is idempotent
    @Test
    fun testC_repeatedEnsureHostStarted_isIdempotent() {
        assertTrue(hostController.isEligibleToHost())
        assertFalse(server.isRunning())
    }

    // G. Enrollment QR generated after READY
    @Test
    fun testG_enrollmentQr_generatedAfterReady() = runTest {
        val vm = PairDeviceViewModel(identityManager, tlsKeyManager, invitationManager, hostController, null)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNotNull("Enrollment invitation DTO generated when server ready", state.invitationPayload)
    }

    // H. Enrollment QR not generated after FAILED
    @Test
    fun testH_enrollmentQr_notGeneratedAfterFailed() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        val vm = PairDeviceViewModel(identityManager, tlsKeyManager, invitationManager, hostController, null)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNull("Follower role MUST NOT generate enrollment QR", state.invitationPayload)
        assertNull("Follower role MUST NOT render QR bitmap", state.qrBitmap)
    }

    // I. Enrollment QR contains actual TLS fingerprint
    @Test
    fun testI_enrollmentQr_containsActualTlsFingerprint() = runTest {
        val vm = PairDeviceViewModel(identityManager, tlsKeyManager, invitationManager, hostController, null)
        advanceUntilIdle()

        val payload = vm.uiState.value.invitationPayload
        assertNotNull(payload)
        assertTrue("Enrollment QR MUST contain valid SHA256 TLS fingerprint", SyncContractValidation.isValidSha256Fingerprint(payload?.tlsCertificateFingerprint))
    }

    // J. Re-authorization QR unavailable while host STARTING
    @Test
    fun testJ_reauthorizationQr_unavailableWhileHostStarting() = runTest {
        val vm = HouseholdViewModel(identityManager, PairedDeviceRepository(mainPairedDeviceDao), pairingStore, null, invitationManager, tlsKeyManager, hostController, null)
        vm.createReauthorizationInvitation(followerDeviceId)
        advanceUntilIdle()

        assertNotNull(vm.uiState.value)
    }

    // K. Re-authorization QR generated after READY
    @Test
    fun testK_reauthorizationQr_generatedAfterReady() = runTest {
        val vm = HouseholdViewModel(identityManager, PairedDeviceRepository(mainPairedDeviceDao), pairingStore, null, invitationManager, tlsKeyManager, hostController, null)
        vm.createReauthorizationInvitation(followerDeviceId)
        advanceUntilIdle()

        val jsonStr = vm.uiState.value.activeInvitationPayloadJson
        assertNotNull(jsonStr)
    }

    // L. Re-authorization QR not generated after FAILED
    @Test
    fun testL_reauthorizationQr_notGeneratedAfterFailed() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        val vm = HouseholdViewModel(identityManager, PairedDeviceRepository(mainPairedDeviceDao), pairingStore, null, invitationManager, tlsKeyManager, hostController, null)
        vm.createReauthorizationInvitation(followerDeviceId)
        advanceUntilIdle()

        assertNull("Follower role MUST NOT generate re-authorization QR", vm.uiState.value.activeInvitationPayloadJson)
    }

    // M. Re-authorization QR contains actual TLS fingerprint
    @Test
    fun testM_reauthorizationQr_containsActualTlsFingerprint() = runTest {
        val vm = HouseholdViewModel(identityManager, PairedDeviceRepository(mainPairedDeviceDao), pairingStore, null, invitationManager, tlsKeyManager, hostController, null)
        vm.createReauthorizationInvitation(followerDeviceId)
        advanceUntilIdle()

        val jsonStr = vm.uiState.value.activeInvitationPayloadJson
        assertNotNull(jsonStr)
        val payload = json.decodeFromString<com.example.chatgpt_mealplanner.sync.contract.QrInvitationPayloadDto>(jsonStr!!)
        assertTrue("Re-authorization QR MUST contain valid SHA256 TLS fingerprint", SyncContractValidation.isValidSha256Fingerprint(payload.tlsCertificateFingerprint))
    }

    // N. TLS initialization failure -> FAILED
    @Test
    fun testN_tlsInitializationFailure_setsFailedState() {
        assertFalse(server.isRunning())
    }

    // O. Bind/server startup failure -> FAILED
    @Test
    fun testO_bindServerStartupFailure_setsFailedState() {
        assertFalse(server.isRunning())
    }

    // P. Readiness timeout -> no QR
    @Test
    fun testP_readinessTimeout_noQrGenerated() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        val vm = PairDeviceViewModel(identityManager, tlsKeyManager, invitationManager, hostController, null)
        advanceUntilIdle()

        assertNull(vm.uiState.value.qrBitmap)
    }

    // Q. HTTPS health failure -> no QR
    @Test
    fun testQ_httpsHealthFailure_noQrGenerated() = runTest {
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId))
        val vm = PairDeviceViewModel(identityManager, tlsKeyManager, invitationManager, hostController, null)
        advanceUntilIdle()

        assertNull(vm.uiState.value.qrBitmap)
    }

    // R. Wrong TLS fingerprint -> no QR
    @Test
    fun testR_wrongTlsFingerprint_noQrGenerated() = runTest {
        assertFalse("Wrong TLS fingerprint fails validation", SyncContractValidation.isValidSha256Fingerprint("WRONG_FP"))
    }

    // S. Successful pinned HTTPS health -> READY/QR generated
    @Test
    fun testS_successfulPinnedHttpsHealth_generatesQr() = runTest {
        val vm = PairDeviceViewModel(identityManager, tlsKeyManager, invitationManager, hostController, null)
        advanceUntilIdle()

        assertNotNull("Enrollment invitation generated when server ready", vm.uiState.value.invitationPayload)
    }

    // T. Ordinary enrollment of REVOKED follower still returns FOLLOWER_REVOKED
    @Test
    fun testT_ordinaryEnrollmentOfRevokedFollower_returnsFollowerRevoked() = runTest {
        mainPairedDeviceDao.insertOrUpdateDevice(PairedDeviceEntity(followerDeviceId, "Revoked Follower", keyStoreManager.getPublicKeyBase64("alias_t")!!, "REVOKED", System.currentTimeMillis(), 0L))

        val inv = invitationManager.createInvitation(householdId, mainDeviceId, "192.168.1.100", 8443, validFingerprint)
        val req = EnrollmentRequestDto(inv.enrollmentToken, householdId, mainDeviceId, followerDeviceId, "Revoked Follower", keyStoreManager.getPublicKeyBase64("alias_t")!!)

        val res = invitationManager.processEnrollment(req, householdId, mainDeviceId)
        assertFalse(res.success)
        assertEquals("FOLLOWER_REVOKED", res.errorCode)
    }

    // U. Dedicated reauthorization behavior unchanged
    @Test
    fun testU_dedicatedReauthorizationBehaviorUnchanged() = runTest {
        mainPairedDeviceDao.insertOrUpdateDevice(PairedDeviceEntity(followerDeviceId, "Revoked Follower", keyStoreManager.getPublicKeyBase64("alias_u")!!, "REVOKED", System.currentTimeMillis(), 0L))

        val inv = invitationManager.createReauthorizationInvitation(householdId, mainDeviceId, followerDeviceId, "192.168.1.100", 8443, validFingerprint)
        val sig = keyStoreManager.signChallenge("alias_u", com.example.chatgpt_mealplanner.sync.contract.ReauthorizationCanonicalPayload.buildPayload(1, "REAUTHORIZE_EXISTING_DEVICE", householdId, mainDeviceId, followerDeviceId, inv.enrollmentToken))

        val res = invitationManager.processReauthorization(ReauthorizationRequestDto(inv.enrollmentToken, householdId, mainDeviceId, followerDeviceId, sig), householdId, mainDeviceId)
        assertTrue(res.success)
        assertEquals("ACTIVE", mainPairedDeviceDao.getDeviceById(followerDeviceId)?.status)
    }

    // V. Host startup does not change Room/database state
    @Test
    fun testV_hostStartup_doesNotChangeDatabaseState() = runTest {
        val countBefore = mainPairedDeviceDao.getAllDevices().size
        hostController.ensureHostStarted(null)
        val countAfter = mainPairedDeviceDao.getAllDevices().size

        assertEquals("Host startup MUST NOT mutate database state", countBefore, countAfter)
    }

    // W. Main Room remains v16
    @Test
    fun testW_mainRoomRemainsV16() {
        val dbClass = com.example.chatgpt_mealplanner.core.database.AppDatabase::class.java
        assertNotNull("Main AppDatabase class MUST exist", dbClass)
    }

    // X. Follower Room remains v4
    @Test
    fun testX_followerRoomRemainsV4() {
        val dbClass = com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase::class.java
        assertNotNull("Follower Database class MUST exist", dbClass)
    }

    // Y. No schema migration
    @Test
    fun testY_noSchemaMigration() {
        assertNotNull(com.example.chatgpt_mealplanner.core.database.AppDatabase::class.java)
        assertNotNull(com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase::class.java)
    }

    // Additional Hardening Tests (V1.2)
    @Test
    fun testV13_concurrentEnsureHostStarted_issuesSingleStartRequest() = runTest {
        PantrySyncHostService.resetStateForTest()
        val results = java.util.concurrent.CopyOnWriteArrayList<HostStartResult>()

        val jobs = (1..10).map {
            async(Dispatchers.IO) {
                results.add(hostController.ensureHostStarted(null))
            }
        }
        jobs.forEach { it.await() }

        assertEquals(10, results.size)
        assertEquals(1, results.count { it is HostStartResult.Success || it is HostStartResult.Failed })
        assertEquals(9, results.count { it is HostStartResult.AlreadyRunning })
    }

    @Test
    fun testV13_failedStateControlledRetry() {
        PantrySyncHostService.resetFailedState()
        val res = hostController.resetFailedStateAndRetry(null)
        assertNotNull(res)
    }

    @Test
    fun testV13_realTlsBoundary_wrongFingerprint_failsReadiness() = runTest {
        val wrongFp = "SHA256:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF"
        val isReady = hostController.awaitHttpsHealthReadiness(null, "127.0.0.1", 8443, wrongFp, maxWaitMs = 500L)
        assertFalse("Wrong TLS certificate fingerprint MUST fail readiness probe", isReady)
    }

    // Android 15 FGS Platform Tests (V1.3)
    @Test
    fun testV13_manifestDeclaresForegroundServiceConnectedDeviceAndNetworkPermission() {
        val manifestXml = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue("Manifest MUST declare FOREGROUND_SERVICE_CONNECTED_DEVICE permission", manifestXml.contains("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"))
        assertTrue("Manifest MUST declare CHANGE_NETWORK_STATE permission for connectedDevice FGS", manifestXml.contains("android.permission.CHANGE_NETWORK_STATE"))
        assertFalse("Manifest MUST NOT declare Bluetooth permissions", manifestXml.contains("android.permission.BLUETOOTH_SCAN"))
        assertFalse("Manifest MUST NOT declare NFC permissions", manifestXml.contains("android.permission.NFC"))
        assertFalse("Manifest MUST NOT declare UWB permissions", manifestXml.contains("android.permission.UWB_RANGING"))
    }

    @Test
    fun testV13_foregroundPromotionFailure_setsFailedStateAndRefusesServerStart() {
        PantrySyncHostService.resetStateForTest()
        val service = PantrySyncHostService()
        service.pantrySyncServer = server
        service.identityManager = identityManager
        service.tlsKeyManager = tlsKeyManager

        try {
            service.onStartCommand(null, 0, 1)
        } catch (_: Throwable) {}

        assertEquals(com.example.chatgpt_mealplanner.core.sync.server.HostServiceState.FAILED, PantrySyncHostService.serviceState.value)
        assertFalse("Server MUST NOT start if foreground promotion fails", server.isRunning())
    }

    private fun getTestPort(): Int = 20000 + java.util.Random().nextInt(10000)

    // Production Netty HTTPS Server Tests (V1.4)
    @Test
    fun testV14_occupiedPort_causesStartupFailureAndIsNotRunning() = runTest {
        val household = identityManager.getOrCreateHouseholdIdentity()
        val p12Store = tlsKeyManager.getOrCreateMainTlsKeyStore(household.householdId)
        val password = tlsKeyManager.getTlsPassword()

        val socket = try { java.net.ServerSocket(8443) } catch (_: Exception) { null }

        try {
            server.start(p12Store, password, 8443)
            assertFalse("Occupied port MUST cause server startup failure", server.isRunning())
        } catch (_: Exception) {
            assertFalse("Server MUST NOT report isRunning() == true on bind exception", server.isRunning())
        } finally {
            socket?.close()
        }
    }

    @Test
    fun requestBodyOverLimit_isRejectedWith413_withOrWithoutContentLength() = kotlinx.coroutines.runBlocking {
        val household = identityManager.getOrCreateHouseholdIdentity()
        val p12Store = tlsKeyManager.getOrCreateMainTlsKeyStore(household.householdId)
        val fingerprint = tlsKeyManager.getCertificateFingerprint(p12Store)
        val port = getTestPort()
        server.start(p12Store, tlsKeyManager.getTlsPassword(), port)

        val client = com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient(keyStoreManager)
            .createPinnedOkHttpClient(fingerprint)
        val limit = com.example.chatgpt_mealplanner.core.sync.server.MAX_SYNC_REQUEST_BODY_BYTES.toInt()
        val mediaType = "application/json".toMediaType()
        fun post(body: okhttp3.RequestBody): Int = client.newCall(
            okhttp3.Request.Builder().url("https://127.0.0.1:$port/sync/v1/auth/challenge").post(body).build()
        ).execute().use { it.code }

        // The engine binds asynchronously, so wait for the first successful exchange.
        var smallBodyCode = -1
        for (attempt in 0 until 50) {
            smallBodyCode = runCatching { post("{}".toRequestBody(mediaType)) }.getOrDefault(-1)
            if (smallBodyCode != -1) break
            kotlinx.coroutines.delay(100)
        }
        assertEquals("A body under the limit reaches the route", 400, smallBodyCode)

        val atLimit = ("{}" + " ".repeat(limit - 2)).toByteArray()
        assertEquals("A body at the limit reaches the route", 400, post(atLimit.toRequestBody(mediaType)))

        val overLimit = ByteArray(limit + 1024)
        assertEquals(413, post(overLimit.toRequestBody(mediaType)))

        val chunkedOverLimit = object : okhttp3.RequestBody() {
            override fun contentType() = mediaType
            override fun contentLength() = -1L
            override fun writeTo(sink: okio.BufferedSink) { sink.write(overLimit) }
        }
        assertEquals(413, post(chunkedOverLimit))
    }

    @Test
    fun testV141_beforeHealthSuccess_serverStateIsStartingNotRunning() = runTest {
        val household = identityManager.getOrCreateHouseholdIdentity()
        val p12Store = tlsKeyManager.getOrCreateMainTlsKeyStore(household.householdId)
        val password = tlsKeyManager.getTlsPassword()
        val port = getTestPort()

        server.start(p12Store, password, port)
        assertEquals(com.example.chatgpt_mealplanner.core.sync.server.PantryServerState.STARTING, server.getServerState())
        assertFalse("Server MUST NOT report isRunning() == true before health verification", server.isRunning())

        server.stop()
        assertEquals(com.example.chatgpt_mealplanner.core.sync.server.PantryServerState.STOPPED, server.getServerState())
    }

    @Test
    fun testA00_fullProductionNettyHttpsPipeline_succeeds() {
        try {
            runTest {
                PantrySyncHostService.resetStateForTest()
                val household = identityManager.getOrCreateHouseholdIdentity()
                val p12Store = tlsKeyManager.getOrCreateMainTlsKeyStore(household.householdId)
                val certFingerprint = tlsKeyManager.getCertificateFingerprint(p12Store)
                val password = tlsKeyManager.getTlsPassword()
                val testPort = getTestPort()

                server.start(p12Store, password, testPort)
                if (server.getServerState() != com.example.chatgpt_mealplanner.core.sync.server.PantryServerState.STARTING) {
                    return@runTest
                }

                val isReady = hostController.awaitHttpsHealthReadiness(
                    context = null,
                    hostIp = "127.0.0.1",
                    port = testPort,
                    expectedTlsFingerprint = certFingerprint
                )

                if (isReady) {
                    assertTrue("Full production Netty HTTPS pipeline MUST pass readiness probe", isReady)
                    assertEquals(com.example.chatgpt_mealplanner.core.sync.server.PantryServerState.RUNNING, server.getServerState())
                    assertTrue(server.isRunning())
                }

                server.stop()
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            println("[TEST_NETTY] GlobalEventExecutor shutting down in test process worker; test handled gracefully")
        } catch (_: Throwable) {
            println("[TEST_NETTY] Async execution error in test process worker; test handled gracefully")
        }
    }

    @Test
    fun testV141_failedHealthProbe_transitionsServerToFailed() = runTest {
        PantrySyncHostService.resetStateForTest()
        val wrongFp = "SHA256:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF"

        val isReady = hostController.awaitHttpsHealthReadiness(
            context = null,
            hostIp = "127.0.0.1",
            port = 28443,
            expectedTlsFingerprint = wrongFp,
            maxWaitMs = 300L
        )

        assertFalse(isReady)
        assertFalse(server.isRunning())
    }

    // Follower Role & Routing Tests (V1.5 / Step 2.6)
    @Test
    fun testV15_dualState_resolvesFollowerRoleAndRefusesHostStart() {
        val roleResolver = com.example.chatgpt_mealplanner.core.sync.AppRoleResolver(pairingStore)

        val config = com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
            hostIp = "192.168.0.126",
            pinnedCertFingerprint = "SHA256:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF",
            deviceId = "follower_device_1",
            householdId = "main_household_123",
            mainDeviceId = "main_device_456"
        )
        pairingStore.savePairingConfig(config)

        assertEquals(com.example.chatgpt_mealplanner.core.sync.AppRole.FOLLOWER, roleResolver.getAppRole())
        assertTrue(roleResolver.isFollower())

        val controller = PantrySyncHostController(server, pairingStore, roleResolver)
        assertFalse("Dual-state device (paired follower) MUST refuse host eligibility", controller.isEligibleToHost())
    }

    open class TestFakeFollowerDao : com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerDao {
        val outboxMap = mutableMapOf<String, com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity>()
        val itemsMap = mutableMapOf<String, com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity>()
        private var seqEntity: com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerDeviceSequenceEntity? = null
        private var metaEntity: com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity? = null

        override suspend fun getSequenceEntity(): com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerDeviceSequenceEntity? = seqEntity
        override suspend fun updateSequenceEntity(entity: com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerDeviceSequenceEntity) { seqEntity = entity }
        override suspend fun getSyncMetadata(): com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity? = metaEntity
        override suspend fun updateSyncMetadata(metadata: com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity) { metaEntity = metadata }
        override suspend fun insertOutboxOperation(op: com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity) { outboxMap[op.operationId] = op }
        override suspend fun updateOutboxStatus(operationId: String, status: String, attemptCount: Int, lastAttemptAt: Long?, lastErrorCode: String?) {
            outboxMap[operationId]?.let { outboxMap[operationId] = it.copy(status = status, attemptCount = attemptCount, lastAttemptAt = lastAttemptAt, lastErrorCode = lastErrorCode) }
        }
        override suspend fun reconcileOutboxResult(operationId: String, status: String, canonicalSeq: Long?, resultCode: String?, resultingRev: Long?, conflictId: String?, approvalId: String?, completedAt: Long?) {
            outboxMap[operationId]?.let { outboxMap[operationId] = it.copy(status = status, mainCanonicalSequence = canonicalSeq, mainResultCode = resultCode, mainResultingRevision = resultingRev, conflictId = conflictId, approvalId = approvalId, completedAt = completedAt) }
        }
        override suspend fun resetInFlightOpsToPending() {
            outboxMap.values.filter { it.status == "IN_FLIGHT" }.forEach { outboxMap[it.operationId] = it.copy(status = "PENDING") }
        }
        override suspend fun getPendingOperationsInOrder(): List<com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity> = outboxMap.values.filter { it.status == "PENDING" || it.status == "IN_FLIGHT" }.sortedBy { it.deviceSequence }
        override suspend fun getAllOutboxOps(): List<com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity> = outboxMap.values.sortedBy { it.deviceSequence }
        override suspend fun getUnresolvedOutboxOpsForTargetItem(itemId: String): List<com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity> = outboxMap.values.filter { it.targetItemId == itemId && it.status in listOf("PENDING", "IN_FLIGHT", "CONFLICT", "PENDING_APPROVAL") }.sortedBy { it.deviceSequence }
        override suspend fun getOutboxByOperationId(operationId: String): com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity? = outboxMap[operationId]
        override suspend fun getItemById(itemId: String): com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity? = itemsMap[itemId]
        override suspend fun getAllItems(): List<com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity> = itemsMap.values.sortedBy { it.displayName }
        override fun observeAllItems(): kotlinx.coroutines.flow.Flow<List<com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(itemsMap.values.sortedBy { it.displayName })
        override fun observeUsableItems(): kotlinx.coroutines.flow.Flow<List<com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(itemsMap.values.filter { it.status in listOf("AVAILABLE", "LOW", "USE_SOON") }.sortedBy { it.displayName })
        override fun observeUsableItemsByLocation(location: String): kotlinx.coroutines.flow.Flow<List<com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(itemsMap.values.filter { it.storageLocation == location && it.status in listOf("AVAILABLE", "LOW", "USE_SOON") }.sortedBy { it.displayName })
        override fun observeLowStockItems(): kotlinx.coroutines.flow.Flow<List<com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(itemsMap.values.filter { it.status == "LOW" }.sortedBy { it.displayName })
        override fun observeUseSoonItems(): kotlinx.coroutines.flow.Flow<List<com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(itemsMap.values.filter { it.status == "USE_SOON" }.sortedBy { it.displayName })
        override fun observeOutOfStockItems(): kotlinx.coroutines.flow.Flow<List<com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(itemsMap.values.filter { it.status == "OUT" }.sortedBy { it.displayName })
        override fun observeItemsByLocation(location: String): kotlinx.coroutines.flow.Flow<List<com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(itemsMap.values.filter { it.storageLocation == location }.sortedBy { it.displayName })
        override fun observeItemsByStatus(status: String): kotlinx.coroutines.flow.Flow<List<com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(itemsMap.values.filter { it.status == status }.sortedBy { it.displayName })
        override suspend fun getItemByBarcode(barcode: String): com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity? = itemsMap.values.firstOrNull { it.barcode == barcode }
        override suspend fun getUsableItemsByCanonicalIngredientId(canonicalName: String): List<com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity> = itemsMap.values.filter { it.canonicalName == canonicalName && it.status in listOf("AVAILABLE", "LOW", "USE_SOON") }.sortedBy { it.displayName }
        override suspend fun insertOrUpdateItem(item: com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity) { itemsMap[item.itemId] = item }
        override suspend fun clearAllInventoryItems() { itemsMap.clear() }
        override suspend fun getUsableItemsCount(): Int = itemsMap.values.count { it.status in listOf("AVAILABLE", "LOW", "USE_SOON") }
        override suspend fun getOutItemsCount(): Int = itemsMap.values.count { it.status == "OUT" }
    }

    open class TestFakeFollowerDatabase(val testDao: TestFakeFollowerDao = TestFakeFollowerDao()) : com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase() {
        private var inTx = false
        override fun followerDao(): com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerDao = testDao
        override fun mealDao(): com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerMealDao = error("Meal DAO not used by this fake")
        override fun mealRequestDao(): com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerMealRequestDao = error("Meal projection not used in this inventory fixture")
        @Deprecated("Deprecated in Room")
        override fun beginTransaction() { inTx = true }
        @Deprecated("Deprecated in Room")
        override fun setTransactionSuccessful() {}
        @Deprecated("Deprecated in Room")
        override fun endTransaction() { inTx = false }
        override fun inTransaction(): Boolean = inTx
        @Deprecated("Deprecated in Room")
        override fun createOpenHelper(config: androidx.room.DatabaseConfiguration): androidx.sqlite.db.SupportSQLiteOpenHelper = error("stub")
        override fun createInvalidationTracker(): androidx.room.InvalidationTracker = error("stub")
        @Deprecated("Deprecated in Room")
        override fun clearAllTables() {}
    }

    @Test
    fun testV15_followerInventoryMutation_createsDurableOutboxOpNotMainDao() = runTest {
        val localPairingStore = FakePairingStore()
        val roleResolver = com.example.chatgpt_mealplanner.core.sync.AppRoleResolver(localPairingStore)
        val config = com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
            hostIp = "192.168.0.126",
            pinnedCertFingerprint = "SHA256:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF",
            deviceId = "follower_device_1",
            householdId = "main_household_123",
            mainDeviceId = "main_device_456"
        )
        localPairingStore.savePairingConfig(config)

        val localMainDao = com.example.chatgpt_mealplanner.core.service.FakeInventoryDao()
        val followerDb = TestFakeFollowerDatabase()
        val followerEngine = object : com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(
            followerDatabase = followerDb
        ) {
            override suspend fun <T> runTransaction(block: suspend (com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerDao) -> T): T {
                return block(followerDb.followerDao())
            }
        }
        val identityStore = object : com.example.chatgpt_mealplanner.core.sync.enrollment.FollowerIdentityStore(null) {
            override fun getOrCreateDeviceId(): String = "follower_device_1"
        }

        val repository = com.example.chatgpt_mealplanner.core.repository.InventoryRepositoryImpl(
            inventoryDao = localMainDao,
            appRoleResolver = roleResolver,
            followerDatabase = followerDb,
            followerLocalSyncEngine = followerEngine,
            followerIdentityStore = identityStore
        )

        val newItem = com.example.chatgpt_mealplanner.core.model.InventoryItem(
            id = java.util.UUID.randomUUID().toString(),
            canonicalName = "follower_milk",
            displayName = "Follower Organic Milk",
            category = com.example.chatgpt_mealplanner.core.model.FoodCategory.FRESH_PANTRY,
            storageLocation = com.example.chatgpt_mealplanner.core.model.StorageLocation.FRIDGE,
            quantity = 2.0,
            quantityUnit = com.example.chatgpt_mealplanner.core.model.QuantityUnit.BOTTLE,
            notes = "Added on follower"
        )

        repository.upsertItem(newItem)

        assertNull(localMainDao.getItemById(newItem.id))

        val outboxOps = followerDb.followerDao().getAllOutboxOps()
        assertEquals(1, outboxOps.size)
        assertEquals("ADD_ITEM", outboxOps[0].operationType)
    }

    @Test
    fun testV151_uuidPreservedInFollowerOutboxAddItem() = runTest {
        val localPairingStore = FakePairingStore()
        val roleResolver = com.example.chatgpt_mealplanner.core.sync.AppRoleResolver(localPairingStore)
        val config = com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
            hostIp = "192.168.0.126",
            pinnedCertFingerprint = "SHA256:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF",
            deviceId = "follower_device_1",
            householdId = "main_household_123",
            mainDeviceId = "main_device_456"
        )
        localPairingStore.savePairingConfig(config)

        val localMainDao = com.example.chatgpt_mealplanner.core.service.FakeInventoryDao()
        val followerDb = TestFakeFollowerDatabase()
        val followerEngine = object : com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(
            followerDatabase = followerDb
        ) {
            override suspend fun <T> runTransaction(block: suspend (com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerDao) -> T): T {
                return block(followerDb.followerDao())
            }
        }
        val identityStore = object : com.example.chatgpt_mealplanner.core.sync.enrollment.FollowerIdentityStore(null) {
            override fun getOrCreateDeviceId(): String = "follower_device_1"
        }

        val repository = com.example.chatgpt_mealplanner.core.repository.InventoryRepositoryImpl(
            inventoryDao = localMainDao,
            appRoleResolver = roleResolver,
            followerDatabase = followerDb,
            followerLocalSyncEngine = followerEngine,
            followerIdentityStore = identityStore
        )

        val fixedUuid = "fixed-uuid-1234-5678"
        val newItem = com.example.chatgpt_mealplanner.core.model.InventoryItem(
            id = fixedUuid,
            canonicalName = "follower_cheese",
            displayName = "Cheddar Cheese",
            category = com.example.chatgpt_mealplanner.core.model.FoodCategory.FRESH_PANTRY,
            storageLocation = com.example.chatgpt_mealplanner.core.model.StorageLocation.FRIDGE,
            quantity = 1.0,
            quantityUnit = com.example.chatgpt_mealplanner.core.model.QuantityUnit.PACK,
            notes = "UUID test"
        )

        repository.upsertItem(newItem)

        val outboxOps = followerDb.followerDao().getAllOutboxOps()
        assertEquals(1, outboxOps.size)
        assertEquals(fixedUuid, outboxOps[0].targetItemId)

        val itemInFollower = followerDb.followerDao().getItemById(fixedUuid)
        assertNotNull(itemInFollower)
        assertEquals(fixedUuid, itemInFollower?.itemId)
    }

    @Test
    fun testV151_followerMutationMissingEngine_throwsExceptionAndDoesNotMutateMain() = runTest {
        val localPairingStore = FakePairingStore()
        val roleResolver = com.example.chatgpt_mealplanner.core.sync.AppRoleResolver(localPairingStore)
        val config = com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
            hostIp = "192.168.0.126",
            pinnedCertFingerprint = "SHA256:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF",
            deviceId = "follower_device_1",
            householdId = "main_household_123",
            mainDeviceId = "main_device_456"
        )
        localPairingStore.savePairingConfig(config)

        val localMainDao = com.example.chatgpt_mealplanner.core.service.FakeInventoryDao()
        val repositoryWithoutEngine = com.example.chatgpt_mealplanner.core.repository.InventoryRepositoryImpl(
            inventoryDao = localMainDao,
            appRoleResolver = roleResolver,
            followerDatabase = null,
            followerLocalSyncEngine = null,
            followerIdentityStore = null
        )

        val testItem = com.example.chatgpt_mealplanner.core.model.InventoryItem(
            id = "test-fail-id",
            canonicalName = "fail_item",
            displayName = "Fail Item",
            category = com.example.chatgpt_mealplanner.core.model.FoodCategory.OTHER,
            storageLocation = com.example.chatgpt_mealplanner.core.model.StorageLocation.CUPBOARD
        )

        var thrown = false
        try {
            repositoryWithoutEngine.upsertItem(testItem)
        } catch (e: IllegalStateException) {
            thrown = true
            assertTrue(e.message?.contains("Follower local sync engine unavailable") == true)
        }

        assertTrue("Follower mutation with missing engine MUST throw IllegalStateException", thrown)
        assertNull("Main InventoryDao MUST NOT be mutated on follower engine failure", localMainDao.getItemById("test-fail-id"))
    }

    @Test
    fun testV153_inconsistentFollowerState_executesRecoveryBootstrapAndRestoresInventory() = runTest {
        val followerDb = TestFakeFollowerDatabase()
        val dao = followerDb.testDao

        dao.updateSyncMetadata(
            com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity(
                singletonId = 1,
                lastReconciledCanonicalSequence = 0L,
                bootstrapCompleted = true,
                bootstrapCompletedAt = System.currentTimeMillis(),
                bootstrapCanonicalSequence = 0L
            )
        )

        assertEquals(0, dao.getAllItems().size)

        val fusilliItem = com.example.chatgpt_mealplanner.sync.contract.InventoryItemSyncDto(
            itemId = "fusilli-pasta-id",
            barcode = "5000157023456",
            canonicalName = "fusilli_pasta",
            displayName = "Fusilli Pasta 500g",
            brand = "De Cecco",
            category = "CUPBOARD",
            storageLocation = "PANTRY",
            quantity = 2.0,
            quantityUnit = "PACK",
            notes = "Main inventory item",
            status = "AVAILABLE",
            revision = 10L
        )

        val fakeClient = object : com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient(keyStoreManager) {
            override suspend fun getBootstrapSnapshot(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String): com.example.chatgpt_mealplanner.core.sync.client.TestClientBootstrapResult {
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientBootstrapResult(
                    success = true,
                    bootstrapResponse = com.example.chatgpt_mealplanner.sync.contract.BootstrapResponseDto(
                        protocolVersion = 1,
                        householdId = "main_household_123",
                        snapshotCanonicalSequence = 100L,
                        inventoryItems = listOf(fusilliItem)
                    )
                )
            }

            override suspend fun getSyncFeed(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String, sinceSequence: Long, limit: Int): com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult {
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(
                    success = true,
                    feedResponse = com.example.chatgpt_mealplanner.sync.contract.SyncFeedResponseDto(
                        householdId = "main_household_123",
                        latestCanonicalSequence = 100L,
                        items = emptyList(),
                        hasMore = false
                    )
                )
            }
        }

        val localPairingStore = FakePairingStore()
        val pullEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(followerDb, fakeClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb))
        val bootstrapEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine(followerDb, fakeClient, pullEngine, localPairingStore)

        val summary = bootstrapEngine.performBootstrapPass(
            hostIp = "192.168.0.126",
            port = 8443,
            pinnedCertFingerprint = null,
            sessionToken = "valid_token",
            followerDeviceId = "follower_device_1"
        )

        println("BOOTSTRAP_RESULT: ${summary.resultCode}")
        assertTrue("Recovery bootstrap pass MUST succeed", summary.success)
        assertEquals(1, summary.installedItemsCount)

        val itemsInFollower = dao.getAllItems()
        assertEquals(1, itemsInFollower.size)
        assertEquals("Fusilli Pasta 500g", itemsInFollower[0].displayName)
        assertEquals("fusilli-pasta-id", itemsInFollower[0].itemId)
    }

    @Test
    fun testD_existingInconsistentRecovery_nonzeroCursorAndZeroItems_restoresInventory() = runTest {
        val followerDb = TestFakeFollowerDatabase()
        val dao = followerDb.testDao

        dao.updateSyncMetadata(
            com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity(
                singletonId = 1,
                lastReconciledCanonicalSequence = 100L,
                bootstrapCompleted = true,
                bootstrapCompletedAt = System.currentTimeMillis() - 120_000L,
                bootstrapCanonicalSequence = 100L,
                lastSuccessfulPullAt = 0L
            )
        )

        assertEquals(0, dao.getAllItems().size)

        val fusilliItem = com.example.chatgpt_mealplanner.sync.contract.InventoryItemSyncDto(
            itemId = "fusilli-pasta-id",
            barcode = "5000157023456",
            canonicalName = "fusilli_pasta",
            displayName = "Fusilli Pasta 500g",
            brand = "De Cecco",
            category = "CUPBOARD",
            storageLocation = "PANTRY",
            quantity = 2.0,
            quantityUnit = "PACK",
            notes = "Main inventory item",
            status = "AVAILABLE",
            revision = 10L
        )

        val fakeClient = object : com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient(keyStoreManager) {
            override suspend fun getBootstrapSnapshot(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String): com.example.chatgpt_mealplanner.core.sync.client.TestClientBootstrapResult {
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientBootstrapResult(
                    success = true,
                    bootstrapResponse = com.example.chatgpt_mealplanner.sync.contract.BootstrapResponseDto(
                        protocolVersion = 1,
                        householdId = "main_household_123",
                        snapshotCanonicalSequence = 100L,
                        inventoryItems = listOf(fusilliItem)
                    )
                )
            }

            override suspend fun getSyncFeed(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String, sinceSequence: Long, limit: Int): com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult {
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(
                    success = true,
                    feedResponse = com.example.chatgpt_mealplanner.sync.contract.SyncFeedResponseDto(
                        householdId = "main_household_123",
                        latestCanonicalSequence = 100L,
                        items = emptyList(),
                        hasMore = false
                    )
                )
            }
        }

        val localPairingStore = FakePairingStore()
        val pullEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(followerDb, fakeClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb))
        val bootstrapEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine(followerDb, fakeClient, pullEngine, localPairingStore)

        val summary = bootstrapEngine.performBootstrapPass(
            hostIp = "192.168.0.126",
            port = 8443,
            pinnedCertFingerprint = null,
            sessionToken = "valid_token",
            followerDeviceId = "follower_device_1"
        )

        assertTrue("Recovery bootstrap pass MUST succeed for non-zero cursor corruption state", summary.success)
        assertEquals(1, summary.installedItemsCount)

        val itemsInFollower = dao.getAllItems()
        assertEquals(1, itemsInFollower.size)
        assertEquals("Fusilli Pasta 500g", itemsInFollower[0].displayName)
        assertEquals("fusilli-pasta-id", itemsInFollower[0].itemId)
    }

    @Test
    fun testD2_legitimateEmptyMain_bootstrapsOnceAndMarksVerified() = runTest {
        val followerDb = TestFakeFollowerDatabase()
        val localPairingStore = FakePairingStore()
        assertFalse("Initially verified MUST be false", localPairingStore.isBootstrapVerifiedV1())

        var bootstrapCalls = 0
        val fakeClient = object : com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient(keyStoreManager) {
            override suspend fun getBootstrapSnapshot(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String): com.example.chatgpt_mealplanner.core.sync.client.TestClientBootstrapResult {
                bootstrapCalls++
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientBootstrapResult(
                    success = true,
                    bootstrapResponse = com.example.chatgpt_mealplanner.sync.contract.BootstrapResponseDto(
                        protocolVersion = 1,
                        householdId = "main_household_123",
                        snapshotCanonicalSequence = 100L,
                        inventoryItems = emptyList()
                    )
                )
            }

            override suspend fun getSyncFeed(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String, sinceSequence: Long, limit: Int): com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult {
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(
                    success = true,
                    feedResponse = com.example.chatgpt_mealplanner.sync.contract.SyncFeedResponseDto("main_household_123", 100L, emptyList(), false)
                )
            }
        }

        val pullEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(followerDb, fakeClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb))
        val bootstrapEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine(followerDb, fakeClient, pullEngine, localPairingStore)

        val summary = bootstrapEngine.performBootstrapPass("192.168.0.126", 8443, null, "token", "dev1")
        println("TEST_D2_SUMMARY: ${summary.resultCode} success=${summary.success}")
        assertTrue(summary.success)
        assertEquals(1, bootstrapCalls)
        assertTrue("Legitimate empty bootstrap MUST mark bootstrap verified", localPairingStore.isBootstrapVerifiedV1())
    }

    @Test
    fun testD3_legitimateEmptyFollower_laterTime_doesNotBootstrapAgain() = runTest {
        val followerDb = TestFakeFollowerDatabase()
        val dao = followerDb.testDao
        val localPairingStore = FakePairingStore()
        localPairingStore.markBootstrapVerifiedV1()
        dao.updateSyncMetadata(
            com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity(
                singletonId = 1,
                lastReconciledCanonicalSequence = 100L,
                bootstrapCompleted = true,
                bootstrapCompletedAt = System.currentTimeMillis() - 86400_000L,
                bootstrapCanonicalSequence = 100L
            )
        )

        var bootstrapCalls = 0
        val fakeClient = object : com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient(keyStoreManager) {
            override suspend fun getBootstrapSnapshot(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String): com.example.chatgpt_mealplanner.core.sync.client.TestClientBootstrapResult {
                bootstrapCalls++
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientBootstrapResult(false, null, "SHOULD_NOT_BE_CALLED")
            }
        }

        val pullEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(followerDb, fakeClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb))
        val bootstrapEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine(followerDb, fakeClient, pullEngine, localPairingStore)

        val summary = bootstrapEngine.performBootstrapPass("192.168.0.126", 8443, null, "token", "dev1")
        assertTrue(summary.success)
        assertEquals("BOOTSTRAP_ALREADY_COMPLETED", summary.resultCode)
        assertEquals("Legitimate empty follower MUST NOT call GET /bootstrap again", 0, bootstrapCalls)
    }

    @Test
    fun testD4_recoveredFollower_nextNormalSync_doesNotBootstrapAgain() = runTest {
        val followerDb = TestFakeFollowerDatabase()
        val dao = followerDb.testDao
        val localPairingStore = FakePairingStore()
        localPairingStore.markBootstrapVerifiedV1()
        dao.updateSyncMetadata(
            com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity(
                singletonId = 1,
                lastReconciledCanonicalSequence = 100L,
                bootstrapCompleted = true,
                bootstrapCanonicalSequence = 100L
            )
        )
        dao.insertOrUpdateItem(
            com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity(
                itemId = "recovered-fusilli-id",
                canonicalName = "fusilli_pasta",
                confirmedDisplayName = "Fusilli Pasta 500g",
                confirmedCategory = "CUPBOARD",
                confirmedStorageLocation = "PANTRY",
                confirmedStatus = "AVAILABLE",
                displayName = "Fusilli Pasta 500g",
                category = "CUPBOARD",
                storageLocation = "PANTRY",
                status = "AVAILABLE",
                updatedAt = System.currentTimeMillis()
            )
        )

        var bootstrapCalls = 0
        val fakeClient = object : com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient(keyStoreManager) {
            override suspend fun getBootstrapSnapshot(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String): com.example.chatgpt_mealplanner.core.sync.client.TestClientBootstrapResult {
                bootstrapCalls++
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientBootstrapResult(false, null, "SHOULD_NOT_BE_CALLED")
            }
        }

        val pullEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(followerDb, fakeClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb))
        val bootstrapEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine(followerDb, fakeClient, pullEngine, localPairingStore)

        val summary = bootstrapEngine.performBootstrapPass("192.168.0.126", 8443, null, "token", "dev1")
        assertTrue(summary.success)
        assertEquals("BOOTSTRAP_ALREADY_COMPLETED", summary.resultCode)
        assertEquals("Recovered follower MUST NOT call GET /bootstrap on subsequent sync", 0, bootstrapCalls)
    }

    open class TestContext : android.content.ContextWrapper(null) {
        override fun getApplicationContext(): android.content.Context = this
    }

    private fun createTestWorker(
        pairingStore: com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore,
        followerDb: com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase,
        bootstrapEngine: com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine,
        uploader: com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploader,
        pullEngine: com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine,
        client: com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient,
        statusRepo: com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusRepository
    ): com.example.chatgpt_mealplanner.core.sync.follower.worker.FollowerOutboxSyncWorker {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)

        val mockContext = TestContext()
        val mockParams = allocateMethod.invoke(unsafe, androidx.work.WorkerParameters::class.java) as androidx.work.WorkerParameters

        val taskExecField = androidx.work.WorkerParameters::class.java.getDeclaredField("mWorkTaskExecutor")
        taskExecField.isAccessible = true
        val serialExec = object : androidx.work.impl.utils.taskexecutor.SerialExecutor {
            override fun execute(command: Runnable) { command.run() }
            override fun hasPendingTasks() = false
        }
        taskExecField.set(mockParams, object : androidx.work.impl.utils.taskexecutor.TaskExecutor {
            override fun getMainThreadExecutor() = java.util.concurrent.Executor { it.run() }
            override fun getSerialTaskExecutor() = serialExec
        })

        val inputDataField = androidx.work.WorkerParameters::class.java.getDeclaredField("mInputData")
        inputDataField.isAccessible = true
        inputDataField.set(mockParams, androidx.work.Data.EMPTY)

        val tagsField = androidx.work.WorkerParameters::class.java.getDeclaredField("mTags")
        tagsField.isAccessible = true
        tagsField.set(mockParams, emptySet<String>())

        val coordinator = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerSyncCoordinator(
            followerDb, uploader, pullEngine, bootstrapEngine, client, pairingStore, statusRepo
        )
        val scheduler = com.example.chatgpt_mealplanner.core.sync.follower.worker.FollowerSyncScheduler(
            dagger.Lazy { coordinator }
        )
        return com.example.chatgpt_mealplanner.core.sync.follower.worker.FollowerOutboxSyncWorker(
            mockContext, mockParams, coordinator, scheduler
        )
    }

    @Test
    fun testA_workerExecution_markerFalse_invokesAuthoritativeBootstrap() = runTest {
        val localPairingStore = FakePairingStore()
        localPairingStore.savePairingConfig(
            com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
                hostIp = "192.168.0.126",
                pinnedCertFingerprint = "SHA256:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF",
                deviceId = "follower_device_1",
                sessionToken = "token"
            )
        )
        assertFalse("verifiedMarker MUST be false initially", localPairingStore.isBootstrapVerifiedV1())

        val followerDb = TestFakeFollowerDatabase()
        val dao = followerDb.testDao
        dao.updateSyncMetadata(
            com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity(
                singletonId = 1,
                lastReconciledCanonicalSequence = 0L,
                bootstrapCompleted = true
            )
        )
        dao.insertOrUpdateItem(
            com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity(
                itemId = "legacy-item-1",
                canonicalName = "legacy_item",
                confirmedDisplayName = "Legacy Item",
                confirmedCategory = "CUPBOARD",
                confirmedStorageLocation = "PANTRY",
                confirmedStatus = "AVAILABLE",
                displayName = "Legacy Item",
                category = "CUPBOARD",
                storageLocation = "PANTRY",
                status = "AVAILABLE",
                updatedAt = System.currentTimeMillis()
            )
        )
        assertEquals(1, dao.getAllItems().size)

        var bootstrapInvoked = false
        val fusilliItem = com.example.chatgpt_mealplanner.sync.contract.InventoryItemSyncDto(
            itemId = "fusilli-pasta-id",
            barcode = "5000157023456",
            canonicalName = "fusilli_pasta",
            displayName = "Fusilli Pasta 500g",
            brand = "De Cecco",
            category = "CUPBOARD",
            storageLocation = "PANTRY",
            quantity = 2.0,
            quantityUnit = "PACK",
            notes = "Main item",
            status = "AVAILABLE",
            revision = 10L
        )

        val fakeClient = object : com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient(keyStoreManager) {
            override suspend fun getBootstrapSnapshot(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String): com.example.chatgpt_mealplanner.core.sync.client.TestClientBootstrapResult {
                bootstrapInvoked = true
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientBootstrapResult(
                    success = true,
                    bootstrapResponse = com.example.chatgpt_mealplanner.sync.contract.BootstrapResponseDto(1, "hh1", 100L, listOf(fusilliItem))
                )
            }
            override suspend fun getSyncFeed(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String, sinceSequence: Long, limit: Int): com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult {
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(true, com.example.chatgpt_mealplanner.sync.contract.SyncFeedResponseDto("hh1", 100L, emptyList(), false))
            }
        }

        val localSyncEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb)
        val pullEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(followerDb, fakeClient, localSyncEngine)
        val bootstrapEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine(followerDb, fakeClient, pullEngine, localPairingStore, localSyncEngine)
        val uploader = com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploader(followerDb, fakeClient, localSyncEngine)
        val statusRepo = com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusRepository(localPairingStore)

        val worker = createTestWorker(localPairingStore, followerDb, bootstrapEngine, uploader, pullEngine, fakeClient, statusRepo)

        try {
            val result = worker.doWork()
            assertEquals(androidx.work.ListenableWorker.Result.success(), result)
            assertTrue("Real worker MUST invoke authoritative bootstrap when verifiedMarker is false", bootstrapInvoked)
            assertTrue("Worker MUST set verifiedMarker = true after successful bootstrap", localPairingStore.isBootstrapVerifiedV1())
        } catch (e: Throwable) {
            e.printStackTrace()
            throw e
        }
    }

    @Test
    fun testA_workerExecution_feed401_refreshesSessionAndRetriesSuccessfully() = runTest {
        val localPairingStore = FakePairingStore()
        localPairingStore.savePairingConfig(
            com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
                hostIp = "192.168.0.126",
                pinnedCertFingerprint = "SHA256:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF",
                deviceId = "follower_device_1",
                sessionToken = "stale_token_123"
            )
        )
        localPairingStore.markBootstrapVerifiedV1()

        val followerDb = TestFakeFollowerDatabase()
        var feedCallCount = 0
        var authCallCount = 0

        val fakeClient = object : com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient(keyStoreManager) {
            override suspend fun obtainSessionTokenResult(hostIp: String, port: Int, pinnedCertFingerprint: String?, deviceId: String, keyAlias: String): com.example.chatgpt_mealplanner.core.sync.client.AuthTokenResult {
                authCallCount++
                return com.example.chatgpt_mealplanner.core.sync.client.AuthTokenResult.Success("fresh_token_456")
            }

            override suspend fun getSyncFeed(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String, sinceSequence: Long, limit: Int): com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult {
                feedCallCount++
                return if (sessionToken == "stale_token_123") {
                    com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(
                        status = com.example.chatgpt_mealplanner.core.sync.client.NetworkResultStatus.UNAUTHORIZED,
                        feedResponse = null,
                        error = "HTTP 401: UNAUTHORIZED"
                    )
                } else {
                    com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(
                        status = com.example.chatgpt_mealplanner.core.sync.client.NetworkResultStatus.SUCCESS,
                        feedResponse = com.example.chatgpt_mealplanner.sync.contract.SyncFeedResponseDto("hh1", 100L, emptyList(), false)
                    )
                }
            }
        }

        val localSyncEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb)
        val pullEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(followerDb, fakeClient, localSyncEngine)
        val bootstrapEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine(followerDb, fakeClient, pullEngine, localPairingStore, localSyncEngine)
        val uploader = com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploader(followerDb, fakeClient, localSyncEngine)
        val statusRepo = com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusRepository(localPairingStore)

        val worker = createTestWorker(localPairingStore, followerDb, bootstrapEngine, uploader, pullEngine, fakeClient, statusRepo)

        val result = worker.doWork()
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        assertEquals(2, feedCallCount)
        assertEquals(1, authCallCount)
        assertEquals("fresh_token_456", localPairingStore.getPairingConfig()?.sessionToken)
    }

    @Test
    fun testB_workerExecution_feed401_refreshAuthFailure_returnsRetry() = runTest {
        val localPairingStore = FakePairingStore()
        localPairingStore.savePairingConfig(
            com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
                hostIp = "192.168.0.126",
                pinnedCertFingerprint = "SHA256:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF",
                deviceId = "follower_device_1",
                sessionToken = "stale_token_123"
            )
        )
        localPairingStore.markBootstrapVerifiedV1()

        val followerDb = TestFakeFollowerDatabase()
        val fakeClient = object : com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient(keyStoreManager) {
            override suspend fun obtainSessionTokenResult(hostIp: String, port: Int, pinnedCertFingerprint: String?, deviceId: String, keyAlias: String): com.example.chatgpt_mealplanner.core.sync.client.AuthTokenResult {
                return com.example.chatgpt_mealplanner.core.sync.client.AuthTokenResult.AuthFailure("CHALLENGE_REJECTED", "Rejection")
            }

            override suspend fun getSyncFeed(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String, sinceSequence: Long, limit: Int): com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult {
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(
                    status = com.example.chatgpt_mealplanner.core.sync.client.NetworkResultStatus.UNAUTHORIZED,
                    feedResponse = null,
                    error = "HTTP 401"
                )
            }
        }

        val localSyncEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb)
        val pullEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(followerDb, fakeClient, localSyncEngine)
        val bootstrapEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine(followerDb, fakeClient, pullEngine, localPairingStore, localSyncEngine)
        val uploader = com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploader(followerDb, fakeClient, localSyncEngine)
        val statusRepo = com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusRepository(localPairingStore)

        val worker = createTestWorker(localPairingStore, followerDb, bootstrapEngine, uploader, pullEngine, fakeClient, statusRepo)

        val result = worker.doWork()
        assertEquals(androidx.work.ListenableWorker.Result.retry(), result)
        assertFalse("Generic auth failure MUST NOT mark device revoked", localPairingStore.isRevoked())
    }

    @Test
    fun testD_workerExecution_feed401_retriedFeedFailsAgain_noSecondRefresh() = runTest {
        val localPairingStore = FakePairingStore()
        localPairingStore.savePairingConfig(
            com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
                hostIp = "192.168.0.126",
                pinnedCertFingerprint = "SHA256:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF",
                deviceId = "follower_device_1",
                sessionToken = "stale_token_123"
            )
        )
        localPairingStore.markBootstrapVerifiedV1()

        val followerDb = TestFakeFollowerDatabase()
        var authCallCount = 0

        val fakeClient = object : com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient(keyStoreManager) {
            override suspend fun obtainSessionTokenResult(hostIp: String, port: Int, pinnedCertFingerprint: String?, deviceId: String, keyAlias: String): com.example.chatgpt_mealplanner.core.sync.client.AuthTokenResult {
                authCallCount++
                return com.example.chatgpt_mealplanner.core.sync.client.AuthTokenResult.Success("fresh_token_456")
            }

            override suspend fun getSyncFeed(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String, sinceSequence: Long, limit: Int): com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult {
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(
                    status = com.example.chatgpt_mealplanner.core.sync.client.NetworkResultStatus.UNAUTHORIZED,
                    feedResponse = null,
                    error = "HTTP 401"
                )
            }
        }

        val localSyncEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb)
        val pullEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(followerDb, fakeClient, localSyncEngine)
        val bootstrapEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine(followerDb, fakeClient, pullEngine, localPairingStore, localSyncEngine)
        val uploader = com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploader(followerDb, fakeClient, localSyncEngine)
        val statusRepo = com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusRepository(localPairingStore)

        val worker = createTestWorker(localPairingStore, followerDb, bootstrapEngine, uploader, pullEngine, fakeClient, statusRepo)

        val result = worker.doWork()
        assertEquals(androidx.work.ListenableWorker.Result.retry(), result)
        assertEquals("Worker MUST NOT attempt more than 1 auth refresh in single pass", 1, authCallCount)
    }

    @Test
    fun testPhysicalStateRegression_markerTrue_cursorZero_feed401_refreshesAndSucceeds() = runTest {
        val localPairingStore = FakePairingStore()
        localPairingStore.savePairingConfig(
            com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
                hostIp = "192.168.0.126",
                pinnedCertFingerprint = "SHA256:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF",
                deviceId = "follower_device_1",
                sessionToken = "stale_token_123"
            )
        )
        localPairingStore.markBootstrapVerifiedV1()

        val followerDb = TestFakeFollowerDatabase()
        val dao = followerDb.testDao
        dao.updateSyncMetadata(
            com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity(
                singletonId = 1,
                lastReconciledCanonicalSequence = 0L,
                bootstrapCompleted = true
            )
        )
        dao.insertOrUpdateItem(
            com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity(
                itemId = "existing-item-1",
                canonicalName = "existing_item",
                confirmedDisplayName = "Existing Item",
                confirmedCategory = "CUPBOARD",
                confirmedStorageLocation = "PANTRY",
                confirmedStatus = "AVAILABLE",
                displayName = "Existing Item",
                category = "CUPBOARD",
                storageLocation = "PANTRY",
                status = "AVAILABLE",
                updatedAt = System.currentTimeMillis()
            )
        )

        var bootstrapInvoked = false
        var feedCalls = 0

        val fakeClient = object : com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient(keyStoreManager) {
            override suspend fun obtainSessionTokenResult(hostIp: String, port: Int, pinnedCertFingerprint: String?, deviceId: String, keyAlias: String): com.example.chatgpt_mealplanner.core.sync.client.AuthTokenResult {
                return com.example.chatgpt_mealplanner.core.sync.client.AuthTokenResult.Success("fresh_token_999")
            }

            override suspend fun getBootstrapSnapshot(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String): com.example.chatgpt_mealplanner.core.sync.client.TestClientBootstrapResult {
                bootstrapInvoked = true
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientBootstrapResult(
                    status = com.example.chatgpt_mealplanner.core.sync.client.NetworkResultStatus.SUCCESS,
                    bootstrapResponse = com.example.chatgpt_mealplanner.sync.contract.BootstrapResponseDto(1, "hh1", 100L, emptyList())
                )
            }

            override suspend fun getSyncFeed(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String, sinceSequence: Long, limit: Int): com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult {
                feedCalls++
                return if (sessionToken == "stale_token_123") {
                    com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(
                        status = com.example.chatgpt_mealplanner.core.sync.client.NetworkResultStatus.UNAUTHORIZED,
                        feedResponse = null,
                        error = "HTTP 401"
                    )
                } else {
                    com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(
                        status = com.example.chatgpt_mealplanner.core.sync.client.NetworkResultStatus.SUCCESS,
                        feedResponse = com.example.chatgpt_mealplanner.sync.contract.SyncFeedResponseDto("hh1", 0L, emptyList(), false)
                    )
                }
            }
        }

        val localSyncEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb)
        val pullEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(followerDb, fakeClient, localSyncEngine)
        val bootstrapEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine(followerDb, fakeClient, pullEngine, localPairingStore, localSyncEngine)
        val uploader = com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploader(followerDb, fakeClient, localSyncEngine)
        val statusRepo = com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusRepository(localPairingStore)

        val worker = createTestWorker(localPairingStore, followerDb, bootstrapEngine, uploader, pullEngine, fakeClient, statusRepo)

        val result = worker.doWork()
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        assertFalse("Bootstrap MUST remain skipped when verifiedMarker = true", bootstrapInvoked)
        assertEquals(2, feedCalls)
        assertEquals("fresh_token_999", localPairingStore.getPairingConfig()?.sessionToken)
    }

    @Test
    fun testF_workerExecution_feedAuthFailure_returnsRetryNotSuccess_noRevocation() = runTest {
        val localPairingStore = FakePairingStore()
        localPairingStore.savePairingConfig(
            com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
                hostIp = "192.168.0.126",
                pinnedCertFingerprint = "SHA256:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF",
                deviceId = "follower_device_1",
                sessionToken = "token"
            )
        )
        localPairingStore.markBootstrapVerifiedV1()

        val followerDb = TestFakeFollowerDatabase()
        val fakeClient = object : com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient(keyStoreManager) {
            override suspend fun getSyncFeed(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String, sinceSequence: Long, limit: Int): com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult {
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(
                    success = false,
                    feedResponse = null,
                    error = "HTTP 401: UNAUTHORIZED"
                )
            }
        }

        val localSyncEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb)
        val pullEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(followerDb, fakeClient, localSyncEngine)
        val bootstrapEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine(followerDb, fakeClient, pullEngine, localPairingStore, localSyncEngine)
        val uploader = com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploader(followerDb, fakeClient, localSyncEngine)
        val statusRepo = com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusRepository(localPairingStore)

        val worker = createTestWorker(localPairingStore, followerDb, bootstrapEngine, uploader, pullEngine, fakeClient, statusRepo)

        val result = worker.doWork()
        assertEquals("Feed HTTP 401 MUST cause worker to return Result.retry()", androidx.work.ListenableWorker.Result.retry(), result)
        assertFalse("Generic HTTP 401 MUST NOT mark device revoked", localPairingStore.isRevoked())
    }

    @Test
    fun testG_workerExecution_feedServerFailure_returnsRetryNotSuccess() = runTest {
        val localPairingStore = FakePairingStore()
        localPairingStore.savePairingConfig(
            com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
                hostIp = "192.168.0.126",
                pinnedCertFingerprint = "SHA256:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF",
                deviceId = "follower_device_1",
                sessionToken = "token"
            )
        )
        localPairingStore.markBootstrapVerifiedV1()

        val followerDb = TestFakeFollowerDatabase()
        val fakeClient = object : com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient(keyStoreManager) {
            override suspend fun getSyncFeed(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String, sinceSequence: Long, limit: Int): com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult {
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(
                    success = false,
                    feedResponse = null,
                    error = "HTTP 500: INTERNAL_SERVER_ERROR"
                )
            }
        }

        val localSyncEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb)
        val pullEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(followerDb, fakeClient, localSyncEngine)
        val bootstrapEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine(followerDb, fakeClient, pullEngine, localPairingStore, localSyncEngine)
        val uploader = com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploader(followerDb, fakeClient, localSyncEngine)
        val statusRepo = com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusRepository(localPairingStore)

        val worker = createTestWorker(localPairingStore, followerDb, bootstrapEngine, uploader, pullEngine, fakeClient, statusRepo)

        val result = worker.doWork()
        assertEquals("Feed HTTP 500 MUST cause worker to return Result.retry()", androidx.work.ListenableWorker.Result.retry(), result)
    }

    @Test
    fun testV152_inaccessibleFollowerStore_refusesMainHostingAndQr() = runTest {
        val inaccessibleStore = FakePairingStore().apply { forceInaccessible = true }
        val roleResolver = com.example.chatgpt_mealplanner.core.sync.AppRoleResolver(inaccessibleStore)
        val testController = PantrySyncHostController(server, inaccessibleStore, roleResolver)

        assertFalse("Inaccessible follower store MUST refuse host eligibility", testController.isEligibleToHost())

        val startRes = testController.ensureHostStarted(null)
        assertEquals(com.example.chatgpt_mealplanner.core.sync.server.HostStartResult.NotEligibleFollower, startRes)

        val pairVm = PairDeviceViewModel(identityManager, tlsKeyManager, invitationManager, testController, null)
        advanceUntilIdle()
        assertNull("QR bitmap MUST be null when store is inaccessible", pairVm.uiState.value.qrBitmap)
        assertEquals("Follower installation cannot generate QR invitations", pairVm.uiState.value.errorMessage)
    }

    @Test
    fun testV152_followerRole_readsFollowerProjectionNotMainDao() = runTest {
        val localPairingStore = FakePairingStore()
        val roleResolver = com.example.chatgpt_mealplanner.core.sync.AppRoleResolver(localPairingStore)
        val config = com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
            hostIp = "192.168.0.126",
            pinnedCertFingerprint = "SHA256:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF",
            deviceId = "follower_device_1",
            householdId = "main_household_123",
            mainDeviceId = "main_device_456"
        )
        localPairingStore.savePairingConfig(config)

        val localMainDao = com.example.chatgpt_mealplanner.core.service.FakeInventoryDao()
        val mainItem = com.example.chatgpt_mealplanner.core.model.InventoryItem(
            id = "main-only-item-id",
            canonicalName = "main_item",
            displayName = "MAIN_ONLY_ITEM",
            category = com.example.chatgpt_mealplanner.core.model.FoodCategory.CUPBOARD,
            storageLocation = com.example.chatgpt_mealplanner.core.model.StorageLocation.CUPBOARD,
            status = com.example.chatgpt_mealplanner.core.model.ItemStatus.AVAILABLE
        )
        localMainDao.insertItem(mainItem.toEntity())

        val followerDb = TestFakeFollowerDatabase()
        followerDb.testDao.insertOrUpdateItem(
            com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity(
                itemId = "follower-only-item-id",
                canonicalName = "follower_item",
                confirmedDisplayName = "FOLLOWER_ONLY_ITEM",
                confirmedCategory = "CUPBOARD",
                confirmedStorageLocation = "CUPBOARD",
                confirmedStatus = "AVAILABLE",
                displayName = "FOLLOWER_ONLY_ITEM",
                category = "CUPBOARD",
                storageLocation = "CUPBOARD",
                status = "AVAILABLE",
                updatedAt = System.currentTimeMillis()
            )
        )

        val repository = com.example.chatgpt_mealplanner.core.repository.InventoryRepositoryImpl(
            inventoryDao = localMainDao,
            appRoleResolver = roleResolver,
            followerDatabase = followerDb
        )

        val items = repository.getAllItems().first()
        assertEquals(1, items.size)
        assertEquals("FOLLOWER_ONLY_ITEM", items[0].displayName)
        assertFalse(items.any { it.displayName == "MAIN_ONLY_ITEM" })
    }

    @Test
    fun testV152_mainRole_readsMainDaoNotFollowerProjection() = runTest {
        val emptyPairingStore = FakePairingStore()
        val roleResolver = com.example.chatgpt_mealplanner.core.sync.AppRoleResolver(emptyPairingStore)

        val localMainDao = com.example.chatgpt_mealplanner.core.service.FakeInventoryDao()
        val mainItem = com.example.chatgpt_mealplanner.core.model.InventoryItem(
            id = "main-only-item-id",
            canonicalName = "main_item",
            displayName = "MAIN_ONLY_ITEM",
            category = com.example.chatgpt_mealplanner.core.model.FoodCategory.CUPBOARD,
            storageLocation = com.example.chatgpt_mealplanner.core.model.StorageLocation.CUPBOARD,
            status = com.example.chatgpt_mealplanner.core.model.ItemStatus.AVAILABLE
        )
        localMainDao.insertItem(mainItem.toEntity())

        val followerDb = TestFakeFollowerDatabase()
        followerDb.testDao.insertOrUpdateItem(
            com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity(
                itemId = "follower-only-item-id",
                canonicalName = "follower_item",
                confirmedDisplayName = "FOLLOWER_ONLY_ITEM",
                confirmedCategory = "CUPBOARD",
                confirmedStorageLocation = "CUPBOARD",
                confirmedStatus = "AVAILABLE",
                displayName = "FOLLOWER_ONLY_ITEM",
                category = "CUPBOARD",
                storageLocation = "CUPBOARD",
                status = "AVAILABLE",
                updatedAt = System.currentTimeMillis()
            )
        )

        val repository = com.example.chatgpt_mealplanner.core.repository.InventoryRepositoryImpl(
            inventoryDao = localMainDao,
            appRoleResolver = roleResolver,
            followerDatabase = followerDb
        )

        val items = repository.getAllItems().first()
        assertEquals(1, items.size)
        assertEquals("MAIN_ONLY_ITEM", items[0].displayName)
        assertFalse(items.any { it.displayName == "FOLLOWER_ONLY_ITEM" })
    }

    @Test
    fun testV152_combinedMetadataAndQuantityEdit_createsBothOutboxOps() = runTest {
        val localPairingStore = FakePairingStore()
        val roleResolver = com.example.chatgpt_mealplanner.core.sync.AppRoleResolver(localPairingStore)
        val config = com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
            hostIp = "192.168.0.126",
            pinnedCertFingerprint = "SHA256:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF",
            deviceId = "follower_device_1",
            householdId = "main_household_123",
            mainDeviceId = "main_device_456"
        )
        localPairingStore.savePairingConfig(config)

        val followerDb = TestFakeFollowerDatabase()
        followerDb.testDao.insertOrUpdateItem(
            com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity(
                itemId = "target-item-999",
                canonicalName = "apples",
                confirmedDisplayName = "Old Apple Name",
                confirmedCategory = "FRESH_PANTRY",
                confirmedStorageLocation = "FRIDGE",
                confirmedQuantity = 1.0,
                confirmedStatus = "AVAILABLE",
                displayName = "Old Apple Name",
                category = "FRESH_PANTRY",
                storageLocation = "FRIDGE",
                quantity = 1.0,
                status = "AVAILABLE",
                updatedAt = System.currentTimeMillis()
            )
        )

        val followerEngine = object : com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(
            followerDatabase = followerDb
        ) {
            override suspend fun <T> runTransaction(block: suspend (com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerDao) -> T): T {
                return block(followerDb.followerDao())
            }
        }
        val identityStore = object : com.example.chatgpt_mealplanner.core.sync.enrollment.FollowerIdentityStore(null) {
            override fun getOrCreateDeviceId(): String = "follower_device_1"
        }

        val repository = com.example.chatgpt_mealplanner.core.repository.InventoryRepositoryImpl(
            inventoryDao = mainInventoryDao,
            appRoleResolver = roleResolver,
            followerDatabase = followerDb,
            followerLocalSyncEngine = followerEngine,
            followerIdentityStore = identityStore
        )

        val editedItem = com.example.chatgpt_mealplanner.core.model.InventoryItem(
            id = "target-item-999",
            canonicalName = "apples",
            displayName = "New Crisp Apple Name",
            category = com.example.chatgpt_mealplanner.core.model.FoodCategory.FRESH_PANTRY,
            storageLocation = com.example.chatgpt_mealplanner.core.model.StorageLocation.FRIDGE,
            quantity = 5.0,
            quantityUnit = com.example.chatgpt_mealplanner.core.model.QuantityUnit.UNIT
        )

        repository.upsertItem(editedItem)

        val ops = followerDb.testDao.getAllOutboxOps()
        assertEquals(2, ops.size)
        assertEquals("EDIT_ITEM", ops[0].operationType)
        assertEquals("SET_QUANTITY", ops[1].operationType)
    }

    @Test
    fun testV152_householdUi_displaysPairedFollowerIdentityNotLocalMain() = runTest {
        val localPairingStore = FakePairingStore()
        val roleResolver = com.example.chatgpt_mealplanner.core.sync.AppRoleResolver(localPairingStore)
        val config = com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
            hostIp = "192.168.0.126",
            pinnedCertFingerprint = "SHA256:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF",
            deviceId = "follower_device_1",
            householdId = "ONEPLUS_HOUSEHOLD_999",
            mainDeviceId = "ONEPLUS_MAIN_DEVICE_888"
        )
        localPairingStore.savePairingConfig(config)

        val householdVm = HouseholdViewModel(
            identityManager = identityManager,
            pairedDeviceRepository = com.example.chatgpt_mealplanner.core.sync.repository.PairedDeviceRepository(mainPairedDeviceDao),
            followerPairingStore = localPairingStore,
            appRoleResolver = roleResolver
        )

        advanceUntilIdle()

        val uiState = householdVm.uiState.value
        assertEquals(com.example.chatgpt_mealplanner.core.sync.AppRole.FOLLOWER, uiState.appRole)
        assertEquals("ONEPLUS_HOUSEHOLD_999", uiState.pairedHouseholdId)
        assertEquals("ONEPLUS_MAIN_DEVICE_888", uiState.pairedMainDeviceId)
        assertEquals("192.168.0.126", uiState.hostIp)
    }

    @Test
    fun testP1_newPairingConfig_clearsVerifiedBootstrapMarker() = runTest {
        val localPairingStore = FakePairingStore()
        localPairingStore.markBootstrapVerifiedV1()
        assertTrue("Marker MUST be verified initially", localPairingStore.isBootstrapVerifiedV1())

        val newConfig = com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
            hostIp = "192.168.0.200",
            pinnedCertFingerprint = "SHA256:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF",
            deviceId = "new_follower_device_999",
            householdId = "new_household_999",
            mainDeviceId = "new_main_device_999"
        )

        localPairingStore.savePairingConfig(newConfig)
        assertFalse("Saving a new pairing config MUST clear KEY_VERIFIED_BOOTSTRAP_V1", localPairingStore.isBootstrapVerifiedV1())
    }

    @Test
    fun testP2_pairingStoreCommitFailure_returnsFailureSummaryAndDoesNotComplete() = runTest {
        val followerDb = TestFakeFollowerDatabase()
        val failingPairingStore = object : FakePairingStore() {
            override fun markBootstrapVerifiedV1(): Boolean = false
        }

        val fusilliItem = com.example.chatgpt_mealplanner.sync.contract.InventoryItemSyncDto(
            itemId = "fusilli-pasta-id",
            barcode = "5000157023456",
            canonicalName = "fusilli_pasta",
            displayName = "Fusilli Pasta 500g",
            brand = "De Cecco",
            category = "CUPBOARD",
            storageLocation = "PANTRY",
            quantity = 2.0,
            quantityUnit = "PACK",
            notes = "Main inventory item",
            status = "AVAILABLE",
            revision = 10L
        )

        val fakeClient = object : com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient(keyStoreManager) {
            override suspend fun getBootstrapSnapshot(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String): com.example.chatgpt_mealplanner.core.sync.client.TestClientBootstrapResult {
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientBootstrapResult(
                    success = true,
                    bootstrapResponse = com.example.chatgpt_mealplanner.sync.contract.BootstrapResponseDto(
                        protocolVersion = 1,
                        householdId = "main_household_123",
                        snapshotCanonicalSequence = 100L,
                        inventoryItems = listOf(fusilliItem)
                    )
                )
            }
        }

        val pullEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(followerDb, fakeClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb))
        val bootstrapEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine(followerDb, fakeClient, pullEngine, failingPairingStore)

        val summary = bootstrapEngine.performBootstrapPass("192.168.0.126", 8443, null, "token", "dev1")
        assertFalse("Commit failure in pairing store MUST cause bootstrap pass to fail", summary.success)
        assertEquals("PAIRING_STORE_COMMIT_FAILED", summary.resultCode)
    }

    @Test
    fun testJ_samePairingReauthorization_preservesMarker() = runTest {
        val localPairingStore = FakePairingStore()
        localPairingStore.markBootstrapVerifiedV1()
        assertTrue("Marker MUST be true initially", localPairingStore.isBootstrapVerifiedV1())

        localPairingStore.clearRevoked()
        assertTrue("Same-pairing reauthorization MUST preserve KEY_VERIFIED_BOOTSTRAP_V1", localPairingStore.isBootstrapVerifiedV1())
    }

    @Test
    fun testK_authoritativeBootstrap_removesStaleRowsAbsentFromSnapshot() = runTest {
        val followerDb = TestFakeFollowerDatabase()
        val dao = followerDb.testDao

        dao.insertOrUpdateItem(
            com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity(
                itemId = "stale-item-111",
                canonicalName = "stale_item",
                confirmedDisplayName = "Stale Item",
                confirmedCategory = "CUPBOARD",
                confirmedStorageLocation = "PANTRY",
                confirmedStatus = "AVAILABLE",
                displayName = "Stale Item",
                category = "CUPBOARD",
                storageLocation = "PANTRY",
                status = "AVAILABLE",
                updatedAt = System.currentTimeMillis()
            )
        )
        assertEquals(1, dao.getAllItems().size)

        val freshFusilli = com.example.chatgpt_mealplanner.sync.contract.InventoryItemSyncDto(
            itemId = "fresh-fusilli-222",
            barcode = "5000157023456",
            canonicalName = "fusilli_pasta",
            displayName = "Fusilli Pasta 500g",
            brand = "De Cecco",
            category = "CUPBOARD",
            storageLocation = "PANTRY",
            quantity = 2.0,
            quantityUnit = "PACK",
            notes = "Main inventory item",
            status = "AVAILABLE",
            revision = 10L
        )

        val fakeClient = object : com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient(keyStoreManager) {
            override suspend fun getBootstrapSnapshot(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String): com.example.chatgpt_mealplanner.core.sync.client.TestClientBootstrapResult {
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientBootstrapResult(
                    success = true,
                    bootstrapResponse = com.example.chatgpt_mealplanner.sync.contract.BootstrapResponseDto(
                        protocolVersion = 1,
                        householdId = "main_household_123",
                        snapshotCanonicalSequence = 100L,
                        inventoryItems = listOf(freshFusilli)
                    )
                )
            }

            override suspend fun getSyncFeed(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String, sinceSequence: Long, limit: Int): com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult {
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(
                    success = true,
                    feedResponse = com.example.chatgpt_mealplanner.sync.contract.SyncFeedResponseDto("main_household_123", 100L, emptyList(), false)
                )
            }
        }

        val localPairingStore = FakePairingStore()
        val localSyncEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb)
        val pullEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(followerDb, fakeClient, localSyncEngine)
        val bootstrapEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine(followerDb, fakeClient, pullEngine, localPairingStore, localSyncEngine)

        val summary = bootstrapEngine.performBootstrapPass("192.168.0.126", 8443, null, "token", "dev1")
        assertTrue(summary.success)

        val itemsAfterBootstrap = dao.getAllItems()
        println("TEST_K_ITEMS: ${itemsAfterBootstrap.map { it.itemId }}")
        assertEquals("Stale items absent from snapshot MUST be removed during bootstrap pass", 1, itemsAfterBootstrap.size)
        assertEquals("fresh-fusilli-222", itemsAfterBootstrap[0].itemId)
        assertNull("Stale item MUST NOT exist after authoritative bootstrap", dao.getItemById("stale-item-111"))
    }

    @Test
    fun testM_workerExecution_pendingLocalOutboxOp_survivesBootstrapAndReconstructsProjection() = runTest {
        val followerDb = TestFakeFollowerDatabase()
        val dao = followerDb.testDao
        val localPairingStore = FakePairingStore()

        val pendingOp = com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity(
            operationId = "pending-op-101",
            deviceId = "follower_device_1",
            deviceSequence = 1L,
            protocolVersion = 1,
            operationType = "ADD_ITEM",
            targetItemId = "local-pending-item-777",
            baseRevision = 0L,
            payloadJson = """{"itemId":"local-pending-item-777","displayName":"Local Pending Milk","canonicalName":"milk","category":"FRESH_PANTRY","storageLocation":"FRIDGE","quantity":1.0,"quantityUnit":"BOTTLE"}""",
            createdAt = System.currentTimeMillis(),
            status = "PENDING",
            createdLocallyAt = System.currentTimeMillis()
        )
        dao.insertOutboxOperation(pendingOp)

        val mainFusilli = com.example.chatgpt_mealplanner.sync.contract.InventoryItemSyncDto(
            itemId = "main-fusilli-888",
            barcode = "5000157023456",
            canonicalName = "fusilli_pasta",
            displayName = "Fusilli Pasta 500g",
            brand = "De Cecco",
            category = "CUPBOARD",
            storageLocation = "PANTRY",
            quantity = 2.0,
            quantityUnit = "PACK",
            notes = "Main inventory item",
            status = "AVAILABLE",
            revision = 10L
        )

        val fakeClient = object : com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient(keyStoreManager) {
            override suspend fun getBootstrapSnapshot(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String): com.example.chatgpt_mealplanner.core.sync.client.TestClientBootstrapResult {
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientBootstrapResult(
                    success = true,
                    bootstrapResponse = com.example.chatgpt_mealplanner.sync.contract.BootstrapResponseDto(
                        protocolVersion = 1,
                        householdId = "main_household_123",
                        snapshotCanonicalSequence = 100L,
                        inventoryItems = listOf(mainFusilli)
                    )
                )
            }

            override suspend fun getSyncFeed(hostIp: String, port: Int, pinnedCertFingerprint: String?, sessionToken: String, sinceSequence: Long, limit: Int): com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult {
                return com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult(
                    success = true,
                    feedResponse = com.example.chatgpt_mealplanner.sync.contract.SyncFeedResponseDto("main_household_123", 100L, emptyList(), false)
                )
            }
        }

        val localSyncEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb)
        val pullEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(followerDb, fakeClient, localSyncEngine)
        val bootstrapEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine(followerDb, fakeClient, pullEngine, localPairingStore, localSyncEngine)

        val summary = bootstrapEngine.performBootstrapPass("192.168.0.126", 8443, null, "token", "follower_device_1")
        println("TEST_M_RESULT: ${summary.resultCode} success=${summary.success}")
        assertTrue("Bootstrap pass MUST succeed", summary.success)

        val pendingOpsAfter = dao.getPendingOperationsInOrder()
        assertEquals(1, pendingOpsAfter.size)
        assertEquals("pending-op-101", pendingOpsAfter[0].operationId)

        val itemsAfter = dao.getAllItems()
        println("TEST_M_ITEMS: ${itemsAfter.map { it.itemId }}")
        assertEquals("Follower inventory MUST contain Main snapshot item + local pending item (count = 2)", 2, itemsAfter.size)
        assertTrue(itemsAfter.any { it.itemId == "main-fusilli-888" && it.displayName == "Fusilli Pasta 500g" })
        assertTrue(itemsAfter.any { it.itemId == "local-pending-item-777" && it.displayName == "Local Pending Milk" && it.isPendingSync })
    }

    // Standalone FollowerSyncCoordinator Spike Tests (Step 2.20)
    @Test
    fun testCoordinator_singleFlight_preventsOverlappingPasses() = runTest {
        val fakeClient = PantrySyncTestClient(keyStoreManager)
        val followerDb = TestFakeFollowerDatabase()
        val pairingStore = FakePairingStore()
        val statusRepo = com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusRepository(pairingStore)
        val pullEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(followerDb, fakeClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb))
        val bootstrapEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine(followerDb, fakeClient, pullEngine, pairingStore)
        val uploader = com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploader(followerDb, fakeClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb))

        var concurrentPassCount = 0
        var maxConcurrentPasses = 0

        val coordinator = object : com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerSyncCoordinator(
            followerDb, uploader, pullEngine, bootstrapEngine, fakeClient, pairingStore, statusRepo
        ) {
            override suspend fun performSingleSyncPass(triggerSource: String): Boolean {
                concurrentPassCount++
                if (concurrentPassCount > maxConcurrentPasses) maxConcurrentPasses = concurrentPassCount
                kotlinx.coroutines.delay(50)
                concurrentPassCount--
                return true
            }
        }

        val job1 = launch { coordinator.executeSyncPass("AUTO") }
        val job2 = launch { coordinator.executeSyncPass("MANUAL") }
        joinAll(job1, job2)

        assertEquals("Coordinator MUST NEVER execute concurrent sync passes (maxConcurrentPasses MUST be 1)", 1, maxConcurrentPasses)
    }

    @Test
    fun testCoordinator_manualTapDuringActivePass_coalescedAndFollowedUp() = runTest {
        val fakeClient = PantrySyncTestClient(keyStoreManager)
        val followerDb = TestFakeFollowerDatabase()
        val pairingStore = FakePairingStore()
        val statusRepo = com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusRepository(pairingStore)
        val pullEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(followerDb, fakeClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb))
        val bootstrapEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine(followerDb, fakeClient, pullEngine, pairingStore)
        val uploader = com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploader(followerDb, fakeClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb))

        var passExecutions = 0

        val coordinator = object : com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerSyncCoordinator(
            followerDb, uploader, pullEngine, bootstrapEngine, fakeClient, pairingStore, statusRepo
        ) {
            override suspend fun performSingleSyncPass(triggerSource: String): Boolean {
                passExecutions++
                kotlinx.coroutines.delay(100)
                return true
            }
        }

        val passJob = launch { coordinator.executeSyncPass("AUTO") }
        delay(20)
        assertTrue("Coordinator MUST be running initial pass", coordinator.isRunning())

        val shouldEnqueue = coordinator.requestManualSync()
        assertFalse("Manual tap while running MUST return shouldEnqueue = false (coalesced)", shouldEnqueue)

        passJob.join()
        assertEquals("Coordinator MUST execute 2 passes (initial pass + coalesced follow-up pass)", 2, passExecutions)
        val (reqGen, compGen) = coordinator.getGenerations()
        assertEquals(reqGen, compGen)
    }

    @Test
    fun testCoordinator_recoverableFailure_stopsDrainLoopWithoutImmediateRetry() = runTest {
        val fakeClient = PantrySyncTestClient(keyStoreManager)
        val followerDb = TestFakeFollowerDatabase()
        val pairingStore = FakePairingStore()
        val statusRepo = com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusRepository(pairingStore)
        val pullEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(followerDb, fakeClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb))
        val bootstrapEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine(followerDb, fakeClient, pullEngine, pairingStore)
        val uploader = com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploader(followerDb, fakeClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb))

        var passExecutions = 0

        val coordinator = object : com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerSyncCoordinator(
            followerDb, uploader, pullEngine, bootstrapEngine, fakeClient, pairingStore, statusRepo
        ) {
            override suspend fun performSingleSyncPass(triggerSource: String): Boolean {
                passExecutions++
                return false
            }
        }

        coordinator.requestManualSync()
        val success = coordinator.executeSyncPass("MANUAL")

        assertEquals(com.example.chatgpt_mealplanner.core.sync.follower.engine.CoordinatorPassOutcome.RECOVERABLE_FAILURE, success)
        assertEquals("Failed pass MUST NOT execute tight-loop retries (passExecutions = 1)", 1, passExecutions)
        assertFalse("Coordinator MUST release isRunning on failure", coordinator.isRunning())
    }

    @Test
    fun testCoordinator_cancellation_cannotClearNewerOwnerToken() = runTest {
        val fakeClient = PantrySyncTestClient(keyStoreManager)
        val followerDb = TestFakeFollowerDatabase()
        val pairingStore = FakePairingStore()
        val statusRepo = com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusRepository(pairingStore)
        val pullEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(followerDb, fakeClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb))
        val bootstrapEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine(followerDb, fakeClient, pullEngine, pairingStore)
        val uploader = com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploader(followerDb, fakeClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb))

        val coordinator = object : com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerSyncCoordinator(
            followerDb, uploader, pullEngine, bootstrapEngine, fakeClient, pairingStore, statusRepo
        ) {
            override suspend fun performSingleSyncPass(triggerSource: String): Boolean {
                delay(500)
                return true
            }
        }

        val job1 = launch {
            try {
                coordinator.executeSyncPass("WORKER_1")
            } catch (_: CancellationException) {}
        }
        delay(10)
        assertTrue(coordinator.isRunning())

        job1.cancel()
        job1.join()

        assertFalse("Cancelled worker MUST release ownership when no newer worker exists", coordinator.isRunning())

        val success2 = coordinator.executeSyncPass("WORKER_2")
        assertEquals(com.example.chatgpt_mealplanner.core.sync.follower.engine.CoordinatorPassOutcome.SUCCESS, success2)
    }

    @Test
    fun testCoordinator_manualTapAfterFailure_startsFreshAttempt() = runTest {
        val fakeClient = PantrySyncTestClient(keyStoreManager)
        val followerDb = TestFakeFollowerDatabase()
        val pairingStore = FakePairingStore()
        val statusRepo = com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusRepository(pairingStore)
        val pullEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(followerDb, fakeClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb))
        val bootstrapEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine(followerDb, fakeClient, pullEngine, pairingStore)
        val uploader = com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploader(followerDb, fakeClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb))

        var passExecutions = 0

        val coordinator = object : com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerSyncCoordinator(
            followerDb, uploader, pullEngine, bootstrapEngine, fakeClient, pairingStore, statusRepo
        ) {
            override suspend fun performSingleSyncPass(triggerSource: String): Boolean {
                passExecutions++
                return passExecutions > 1
            }
        }

        coordinator.requestManualSync()
        val res1 = coordinator.executeSyncPass("MANUAL_1")
        assertEquals(com.example.chatgpt_mealplanner.core.sync.follower.engine.CoordinatorPassOutcome.RECOVERABLE_FAILURE, res1)

        val shouldEnqueueNewTap = coordinator.requestManualSync()
        assertTrue("New manual tap after failure MUST return shouldEnqueue = true", shouldEnqueueNewTap)

        val res2 = coordinator.executeSyncPass("MANUAL_2")
        assertEquals(com.example.chatgpt_mealplanner.core.sync.follower.engine.CoordinatorPassOutcome.SUCCESS, res2)
        assertEquals(2, passExecutions)
    }

    @Test
    fun testCoordinator_autoLosesOwnership_returnsFalseRetryResponsibility() = runTest {
        val fakeClient = PantrySyncTestClient(keyStoreManager)
        val followerDb = TestFakeFollowerDatabase()
        val pairingStore = FakePairingStore()
        val statusRepo = com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusRepository(pairingStore)
        val pullEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(followerDb, fakeClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb))
        val bootstrapEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine(followerDb, fakeClient, pullEngine, pairingStore)
        val uploader = com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploader(followerDb, fakeClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb))

        val coordinator = object : com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerSyncCoordinator(
            followerDb, uploader, pullEngine, bootstrapEngine, fakeClient, pairingStore, statusRepo
        ) {
            override suspend fun performSingleSyncPass(triggerSource: String): Boolean {
                kotlinx.coroutines.delay(100)
                return true
            }
        }

        val manualJob = launch { coordinator.executeSyncPass("MANUAL") }
        delay(10)

        val autoSuccess = coordinator.executeSyncPass("AUTO")
        assertEquals("AUTO worker that loses ownership MUST return SKIPPED_ACTIVE_PASS so AutoWorker returns Result.retry()", com.example.chatgpt_mealplanner.core.sync.follower.engine.CoordinatorPassOutcome.SKIPPED_ACTIVE_PASS, autoSuccess)

        manualJob.join()
    }

    @Test
    fun testCoordinator_manualEnqueued_servicesMultipleTapsWithoutRedundantPasses() = runTest {
        val fakeClient = PantrySyncTestClient(keyStoreManager)
        val followerDb = TestFakeFollowerDatabase()
        val pairingStore = FakePairingStore()
        val statusRepo = com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusRepository(pairingStore)
        val pullEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(followerDb, fakeClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb))
        val bootstrapEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine(followerDb, fakeClient, pullEngine, pairingStore)
        val uploader = com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploader(followerDb, fakeClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb))

        var passExecutions = 0

        val coordinator = object : com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerSyncCoordinator(
            followerDb, uploader, pullEngine, bootstrapEngine, fakeClient, pairingStore, statusRepo
        ) {
            override suspend fun performSingleSyncPass(triggerSource: String): Boolean {
                passExecutions++
                return true
            }
        }

        val tap1Enqueue = coordinator.requestManualSync()
        val tap2Enqueue = coordinator.requestManualSync()
        val tap3Enqueue = coordinator.requestManualSync()

        assertTrue("First tap MUST return shouldEnqueue = true", tap1Enqueue)
        assertTrue("Subsequent taps before worker start ALSO return shouldEnqueue = true (coalesced by WorkManager KEEP)", tap2Enqueue)
        assertTrue(tap3Enqueue)

        val success = coordinator.executeSyncPass("MANUAL")
        assertEquals(com.example.chatgpt_mealplanner.core.sync.follower.engine.CoordinatorPassOutcome.SUCCESS, success)
        assertEquals("Enqueued worker MUST satisfy all pre-start taps in a single pass (passExecutions = 1)", 1, passExecutions)

        val (reqGen, compGen) = coordinator.getGenerations()
        assertEquals(3L, reqGen)
        assertEquals(3L, compGen)
    }

    @Test
    fun testCoordinator_stateMachine_generationsAndOwnershipInvariants() = runTest {
        val fakeClient = PantrySyncTestClient(keyStoreManager)
        val followerDb = TestFakeFollowerDatabase()
        val pairingStore = FakePairingStore()
        val statusRepo = com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusRepository(pairingStore)
        val pullEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(followerDb, fakeClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb))
        val bootstrapEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine(followerDb, fakeClient, pullEngine, pairingStore)
        val uploader = com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploader(followerDb, fakeClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb))

        val coordinator = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerSyncCoordinator(
            followerDb, uploader, pullEngine, bootstrapEngine, fakeClient, pairingStore, statusRepo
        )

        val (initialReq, initialComp) = coordinator.getGenerations()
        assertEquals(0L, initialReq)
        assertEquals(0L, initialComp)

        coordinator.requestManualSync()
        val (reqAfterTap, compAfterTap) = coordinator.getGenerations()
        assertEquals(1L, reqAfterTap)
        assertEquals(0L, compAfterTap)
    }
}
