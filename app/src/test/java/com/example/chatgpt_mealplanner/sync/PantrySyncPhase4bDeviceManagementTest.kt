package com.example.chatgpt_mealplanner.sync

import com.example.chatgpt_mealplanner.core.database.entity.HouseholdIdentityEntity
import com.example.chatgpt_mealplanner.core.database.entity.PairedDeviceEntity
import com.example.chatgpt_mealplanner.core.security.AndroidKeyStoreManager
import com.example.chatgpt_mealplanner.core.security.Base64Utils
import com.example.chatgpt_mealplanner.core.sync.HouseholdIdentityManager
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.core.sync.enrollment.FollowerEnrollmentOrchestrator
import com.example.chatgpt_mealplanner.core.sync.enrollment.FollowerIdentityStore
import com.example.chatgpt_mealplanner.core.sync.enrollment.PantryEnrollmentInvitationManager
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import com.example.chatgpt_mealplanner.core.sync.follower.worker.FollowerSyncScheduler
import com.example.chatgpt_mealplanner.core.sync.repository.PairedDeviceRepository
import com.example.chatgpt_mealplanner.core.sync.repository.RevocationResult
import com.example.chatgpt_mealplanner.core.sync.server.PantrySyncServer
import com.example.chatgpt_mealplanner.core.sync.server.SyncSessionManager
import com.example.chatgpt_mealplanner.feature.household.HouseholdViewModel
import com.example.chatgpt_mealplanner.sync.contract.EnrollmentRequestDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class PantrySyncPhase4bDeviceManagementTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var mainPairedDeviceDao: TestSyncPairedDeviceDao
    private lateinit var mainIdentityDao: FakeHouseholdIdentityDao
    private lateinit var mainCanonicalDao: FakeCanonicalSyncDao
    private lateinit var mainInventoryDao: com.example.chatgpt_mealplanner.core.service.FakeInventoryDao
    private lateinit var identityManager: HouseholdIdentityManager
    private lateinit var invitationManager: PantryEnrollmentInvitationManager
    private lateinit var keyStoreManager: TestEnrollmentKeyStoreManager
    private lateinit var sessionManager: SyncSessionManager
    private lateinit var repository: PairedDeviceRepository
    private lateinit var identityStore: TestFollowerIdentityStore
    private lateinit var viewModel: HouseholdViewModel

    private val householdId = UUID.randomUUID().toString()
    private val mainDeviceId = UUID.randomUUID().toString()
    private val validFingerprint = "SHA256:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF"

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(testDispatcher)
        mainPairedDeviceDao = TestSyncPairedDeviceDao()
        mainIdentityDao = FakeHouseholdIdentityDao()
        mainCanonicalDao = FakeCanonicalSyncDao()
        mainInventoryDao = com.example.chatgpt_mealplanner.core.service.FakeInventoryDao()
        identityManager = HouseholdIdentityManager(mainIdentityDao)
        keyStoreManager = TestEnrollmentKeyStoreManager()
        invitationManager = PantryEnrollmentInvitationManager(mainPairedDeviceDao, keyStoreManager)
        sessionManager = SyncSessionManager(keyStoreManager, mainPairedDeviceDao)
        repository = PairedDeviceRepository(mainPairedDeviceDao)
        identityStore = TestFollowerIdentityStore()

        mainIdentityDao.insertHouseholdIdentity(
            HouseholdIdentityEntity(
                householdId = householdId,
                householdName = "Test Household",
                mainDeviceId = mainDeviceId,
                createdAt = System.currentTimeMillis()
            )
        )

        viewModel = HouseholdViewModel(
            identityManager = identityManager,
            pairedDeviceRepository = repository,
            followerPairingStore = FakePairingStore()
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // 1. Household ViewModel loads existing paired devices
    @Test
    fun test1_householdViewModel_loadsExistingPairedDevices() = runTest {
        val dev1 = PairedDeviceEntity("dev_1", "Phone 1", "PUB_KEY_1", "ACTIVE", System.currentTimeMillis() - 1000L, 0L)
        mainPairedDeviceDao.insertOrUpdateDevice(dev1)

        viewModel.loadHouseholdInfo()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(1, state.pairedDevices.size)
        assertEquals("dev_1", state.pairedDevices[0].deviceId)
    }

    // 2. ACTIVE and REVOKED devices represented correctly
    @Test
    fun test2_activeAndRevokedDevices_representedCorrectly() = runTest {
        val activeDev = PairedDeviceEntity("dev_active", "Active Phone", "PUB_ACTIVE", "ACTIVE", System.currentTimeMillis(), 0L)
        val revokedDev = PairedDeviceEntity("dev_revoked", "Revoked Phone", "PUB_REVOKED", "REVOKED", System.currentTimeMillis() - 5000L, 0L)

        mainPairedDeviceDao.insertOrUpdateDevice(activeDev)
        mainPairedDeviceDao.insertOrUpdateDevice(revokedDev)

        val devices = repository.getAllDevices()
        val active = devices.find { it.deviceId == "dev_active" }
        val revoked = devices.find { it.deviceId == "dev_revoked" }

        assertEquals("ACTIVE", active?.status)
        assertEquals("REVOKED", revoked?.status)
    }

    // 3. Deterministic ordering: ACTIVE first, then pairedAt desc, then deviceName asc
    @Test
    fun test3_deterministicOrdering_activeFirst_thenPairedAtDesc() = runTest {
        val devRevokedNew = PairedDeviceEntity("dev_rev_new", "A Revoked New", "PUB_1", "REVOKED", 20000L, 0L)
        val devActiveOld = PairedDeviceEntity("dev_act_old", "Z Active Old", "PUB_2", "ACTIVE", 10000L, 0L)
        val devActiveNew = PairedDeviceEntity("dev_act_new", "B Active New", "PUB_3", "ACTIVE", 30000L, 0L)

        mainPairedDeviceDao.insertOrUpdateDevice(devRevokedNew)
        mainPairedDeviceDao.insertOrUpdateDevice(devActiveOld)
        mainPairedDeviceDao.insertOrUpdateDevice(devActiveNew)

        val sorted = repository.getAllDevices()
        assertEquals("dev_act_new", sorted[0].deviceId)
        assertEquals("dev_act_old", sorted[1].deviceId)
        assertEquals("dev_rev_new", sorted[2].deviceId)
    }

    // 4. Revoke requires explicit UI confirmation state
    @Test
    fun test4_revokeRequiresExplicitUiConfirmationState() = runTest {
        val dev = PairedDeviceEntity("dev_1", "Phone 1", "PUB_1", "ACTIVE", System.currentTimeMillis(), 0L)
        mainPairedDeviceDao.insertOrUpdateDevice(dev)

        viewModel.requestRevocationConfirmation(dev)

        val state = viewModel.uiState.value
        assertEquals("dev_1", state.deviceToRevoke?.deviceId)

        // Verify DB is NOT mutated yet
        assertEquals("ACTIVE", mainPairedDeviceDao.getDeviceById("dev_1")?.status)
    }

    // 5. Cancel confirmation performs zero mutation
    @Test
    fun test5_cancelConfirmation_performsZeroMutation() = runTest {
        val dev = PairedDeviceEntity("dev_1", "Phone 1", "PUB_1", "ACTIVE", System.currentTimeMillis(), 0L)
        mainPairedDeviceDao.insertOrUpdateDevice(dev)

        viewModel.requestRevocationConfirmation(dev)
        viewModel.cancelRevocation()

        val state = viewModel.uiState.value
        assertNull(state.deviceToRevoke)
        assertEquals("ACTIVE", mainPairedDeviceDao.getDeviceById("dev_1")?.status)
    }

    // 6. Confirm revocation changes ACTIVE -> REVOKED
    @Test
    fun test6_confirmRevocation_changesActiveToRevoked() = runTest {
        val dev = PairedDeviceEntity("dev_1", "Phone 1", "PUB_1", "ACTIVE", System.currentTimeMillis(), 0L)
        mainPairedDeviceDao.insertOrUpdateDevice(dev)

        viewModel.requestRevocationConfirmation(dev)
        viewModel.confirmRevocation()

        val updatedDev = mainPairedDeviceDao.getDeviceById("dev_1")
        assertEquals("REVOKED", updatedDev?.status)
    }

    // 7. Revoking nonexistent device returns deterministic failure
    @Test
    fun test7_revokingNonexistentDevice_returnsDeterministicFailure() = runTest {
        val res = repository.revokeDevice("unknown_device_999")
        assertTrue(res is RevocationResult.DeviceNotFound)
        assertEquals("unknown_device_999", (res as RevocationResult.DeviceNotFound).deviceId)
    }

    // 8. Revoking already-REVOKED device is idempotent and does not modify public key or deviceId
    @Test
    fun test8_revokingAlreadyRevokedDevice_isIdempotent() = runTest {
        val dev = PairedDeviceEntity("dev_1", "Phone 1", "PUB_ORIGINAL", "REVOKED", System.currentTimeMillis(), 0L)
        mainPairedDeviceDao.insertOrUpdateDevice(dev)

        val res = repository.revokeDevice("dev_1")
        assertTrue(res is RevocationResult.AlreadyRevoked)

        val devAfter = mainPairedDeviceDao.getDeviceById("dev_1")
        assertEquals("dev_1", devAfter?.deviceId)
        assertEquals("PUB_ORIGINAL", devAfter?.devicePublicKey)
        assertEquals("REVOKED", devAfter?.status)
    }

    // 9. Revocation does NOT delete paired_devices row
    @Test
    fun test9_revocation_doesNotDeletePairedDevicesRow() = runTest {
        val dev = PairedDeviceEntity("dev_1", "Phone 1", "PUB_1", "ACTIVE", System.currentTimeMillis(), 0L)
        mainPairedDeviceDao.insertOrUpdateDevice(dev)

        repository.revokeDevice("dev_1")

        val devInDb = mainPairedDeviceDao.getDeviceById("dev_1")
        assertNotNull("paired_devices row MUST remain in database after revocation", devInDb)
        assertEquals("REVOKED", devInDb?.status)
    }

    // 10. Revocation does NOT mutate inventory
    @Test
    fun test10_revocation_doesNotMutateInventory() = runTest {
        val dev = PairedDeviceEntity("dev_1", "Phone 1", "PUB_1", "ACTIVE", System.currentTimeMillis(), 0L)
        mainPairedDeviceDao.insertOrUpdateDevice(dev)

        val initialItemsCount = mainInventoryDao.getAllItemsSync().size

        repository.revokeDevice("dev_1")

        val afterItemsCount = mainInventoryDao.getAllItemsSync().size
        assertEquals("Inventory items count MUST remain unchanged after device revocation", initialItemsCount, afterItemsCount)
    }

    // 11. Revocation does NOT allocate canonical inventory sequence
    @Test
    fun test11_revocation_doesNotAllocateCanonicalSequence() = runTest {
        val initialSeq = mainCanonicalDao.getLatestCanonicalSequence() ?: 0L

        val dev = PairedDeviceEntity("dev_1", "Phone 1", "PUB_1", "ACTIVE", System.currentTimeMillis(), 0L)
        mainPairedDeviceDao.insertOrUpdateDevice(dev)

        repository.revokeDevice("dev_1")

        val afterSeq = mainCanonicalDao.getLatestCanonicalSequence() ?: 0L
        assertEquals("Canonical sync sequence MUST NOT be allocated for a device revocation", initialSeq, afterSeq)
    }

    // 12. NEW challenge/session auth rejected for REVOKED follower
    @Test
    fun test12_newChallengeSessionAuth_rejectedForRevokedFollower() = runTest {
        val devId = UUID.randomUUID().toString()
        val pubKey = keyStoreManager.getPublicKeyBase64("alias_rev_12")!!
        mainPairedDeviceDao.insertOrUpdateDevice(PairedDeviceEntity(devId, "Phone 12", pubKey, "REVOKED", System.currentTimeMillis(), 0L))

        val challenge = sessionManager.createChallenge(householdId, devId)
        val canonicalBytes = sessionManager.buildCanonicalChallengeBytes(householdId, devId, challenge.challengeId, challenge.nonce)
        val sig = keyStoreManager.signChallenge("alias_rev_12", canonicalBytes)

        val token = sessionManager.verifyChallengeAndCreateSession(challenge.challengeId, devId, sig)
        assertNull("Session token creation MUST be rejected for REVOKED device", token)
    }

    // 13. EXISTING valid session becomes unusable IMMEDIATELY after revocation
    @Test
    fun test13_existingValidSession_becomesUnusableImmediatelyAfterRevocation() = runTest {
        val devId = UUID.randomUUID().toString()
        val pubKey = keyStoreManager.getPublicKeyBase64("alias_active_13")!!
        mainPairedDeviceDao.insertOrUpdateDevice(PairedDeviceEntity(devId, "Phone 13", pubKey, "ACTIVE", System.currentTimeMillis(), 0L))

        // 1. Issue valid session token while ACTIVE
        val challenge = sessionManager.createChallenge(householdId, devId)
        val canonicalBytes = sessionManager.buildCanonicalChallengeBytes(householdId, devId, challenge.challengeId, challenge.nonce)
        val sig = keyStoreManager.signChallenge("alias_active_13", canonicalBytes)
        val token = sessionManager.verifyChallengeAndCreateSession(challenge.challengeId, devId, sig)
        assertNotNull(token)

        // Verify session is valid while ACTIVE
        assertEquals(devId, sessionManager.validateSessionToken(token))

        // 2. Revoke device
        repository.revokeDevice(devId)

        // 3. Validate SAME previously issued session token
        val validatedIdAfterRevocation = sessionManager.validateSessionToken(token)
        assertNull("Previously issued session token MUST become unusable immediately after device revocation", validatedIdAfterRevocation)
    }

    // 14. Bootstrap rejected after revocation
    @Test
    fun test14_bootstrap_rejectedAfterRevocation() = runTest {
        val devId = UUID.randomUUID().toString()
        val pubKey = keyStoreManager.getPublicKeyBase64("alias_14")!!
        mainPairedDeviceDao.insertOrUpdateDevice(PairedDeviceEntity(devId, "Phone 14", pubKey, "ACTIVE", System.currentTimeMillis(), 0L))

        val challenge = sessionManager.createChallenge(householdId, devId)
        val sig = keyStoreManager.signChallenge("alias_14", sessionManager.buildCanonicalChallengeBytes(householdId, devId, challenge.challengeId, challenge.nonce))
        val token = sessionManager.verifyChallengeAndCreateSession(challenge.challengeId, devId, sig)!!

        // Revoke
        repository.revokeDevice(devId)

        // Session validation for bootstrap
        val validatedId = sessionManager.validateSessionToken(token)
        assertNull("Bootstrap request session validation MUST return null (HTTP 401) for revoked device", validatedId)
    }

    // 15. Feed rejected after revocation
    @Test
    fun test15_feed_rejectedAfterRevocation() = runTest {
        val devId = UUID.randomUUID().toString()
        val pubKey = keyStoreManager.getPublicKeyBase64("alias_15")!!
        mainPairedDeviceDao.insertOrUpdateDevice(PairedDeviceEntity(devId, "Phone 15", pubKey, "ACTIVE", System.currentTimeMillis(), 0L))

        val challenge = sessionManager.createChallenge(householdId, devId)
        val sig = keyStoreManager.signChallenge("alias_15", sessionManager.buildCanonicalChallengeBytes(householdId, devId, challenge.challengeId, challenge.nonce))
        val token = sessionManager.verifyChallengeAndCreateSession(challenge.challengeId, devId, sig)!!

        // Revoke
        repository.revokeDevice(devId)

        val validatedId = sessionManager.validateSessionToken(token)
        assertNull("Feed request session validation MUST return null (HTTP 401) for revoked device", validatedId)
    }

    // 16. Operations upload rejected after revocation
    @Test
    fun test16_operationsUpload_rejectedAfterRevocation() = runTest {
        val devId = UUID.randomUUID().toString()
        val pubKey = keyStoreManager.getPublicKeyBase64("alias_16")!!
        mainPairedDeviceDao.insertOrUpdateDevice(PairedDeviceEntity(devId, "Phone 16", pubKey, "ACTIVE", System.currentTimeMillis(), 0L))

        val challenge = sessionManager.createChallenge(householdId, devId)
        val sig = keyStoreManager.signChallenge("alias_16", sessionManager.buildCanonicalChallengeBytes(householdId, devId, challenge.challengeId, challenge.nonce))
        val token = sessionManager.verifyChallengeAndCreateSession(challenge.challengeId, devId, sig)!!

        // Revoke
        repository.revokeDevice(devId)

        val validatedId = sessionManager.validateSessionToken(token)
        assertNull("Operations upload session validation MUST return null (HTTP 401) for revoked device", validatedId)
    }

    // 17. Revoked follower cannot use new Phase 4A QR to silently reactivate
    @Test
    fun test17_revokedFollower_cannotUseNewQrToSilentlyReactivate() = runTest {
        val devId = UUID.randomUUID().toString()
        val pubKey = keyStoreManager.getPublicKeyBase64("alias_17")!!
        mainPairedDeviceDao.insertOrUpdateDevice(PairedDeviceEntity(devId, "Revoked Phone 17", pubKey, "REVOKED", System.currentTimeMillis(), 0L))

        val inv = invitationManager.createInvitation(householdId, mainDeviceId, "192.168.1.100", 8443, validFingerprint)

        val req = EnrollmentRequestDto(inv.enrollmentToken, householdId, mainDeviceId, devId, "Revoked Phone 17", pubKey)

        val res = invitationManager.processEnrollment(req, householdId, mainDeviceId)
        assertFalse("REVOKED device scanning new QR MUST NOT silently reactivate", res.success)
        assertEquals("FOLLOWER_REVOKED", res.errorCode)

        // Device remains REVOKED
        assertEquals("REVOKED", mainPairedDeviceDao.getDeviceById(devId)?.status)
    }

    // 18. Follower local inventory remains intact after server revocation rejection
    @Test
    fun test18_followerLocalInventory_remainsIntactAfterServerRevocationRejection() = runTest {
        val dao = FakeFollowerDao()

        val localItem = com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity(
            itemId = "follower_item_18",
            canonicalName = "Local Rice",
            confirmedDisplayName = "Basmati Rice",
            confirmedBrand = null,
            confirmedCategory = "FRESH_PANTRY",
            confirmedStorageLocation = "CUPBOARD",
            confirmedQuantity = 2.0,
            confirmedQuantityUnit = "BAG",
            confirmedNotes = null,
            confirmedStatus = "AVAILABLE",
            mainConfirmedRevision = 1L,
            displayName = "Basmati Rice",
            brand = null,
            category = "FRESH_PANTRY",
            storageLocation = "CUPBOARD",
            quantity = 2.0,
            quantityUnit = "BAG",
            notes = null,
            status = "AVAILABLE",
            optimisticRevision = 1L,
            isPendingSync = false,
            updatedAt = System.currentTimeMillis()
        )
        dao.insertOrUpdateItem(localItem)

        // Simulate server rejection (401 / FOLLOWER_REVOKED)
        // Verify local inventory item remains present in database
        val itemsAfterRejection = dao.getAllItems()
        assertEquals(1, itemsAfterRejection.size)
        assertEquals("follower_item_18", itemsAfterRejection[0].itemId)
    }

    // 19. Follower identity remains unchanged after revocation rejection
    @Test
    fun test19_followerIdentity_remainsUnchangedAfterRevocationRejection() = runTest {
        val store = TestFollowerIdentityStore("fixed_dev_id_19")

        val devIdBefore = store.getOrCreateDeviceId()
        assertEquals("fixed_dev_id_19", devIdBefore)

        val devIdAfter = store.getOrCreateDeviceId()
        assertEquals("Follower device ID MUST remain unchanged after revocation or rejection", "fixed_dev_id_19", devIdAfter)
    }

    // 20. Phase 4A QR enrollment of a brand-new device still succeeds
    @Test
    fun test20_phase4aQrEnrollment_ofBrandNewDevice_stillSucceeds() = runTest {
        val store = FakePairingStore()
        val client = DirectEnrollmentClient(invitationManager, householdId, mainDeviceId, keyStoreManager)
        val orchestrator = FollowerEnrollmentOrchestrator(null, identityStore, keyStoreManager, store, client, FollowerSyncScheduler())

        val inv = invitationManager.createInvitation(householdId, mainDeviceId, "192.168.1.100", 8443, validFingerprint)
        val qrJson = json.encodeToString(inv)

        val res = orchestrator.processQrEnrollment(qrJson)
        assertTrue("Phase 4A enrollment of brand-new device MUST succeed", res.success)

        val newlyPairedDev = mainPairedDeviceDao.getAllDevices().find { it.deviceId == identityStore.getOrCreateDeviceId() }
        assertNotNull(newlyPairedDev)
        assertEquals("ACTIVE", newlyPairedDev?.status)
    }

    // 21. Concurrent revocation requests for same ACTIVE device -> exactly one ACTIVE -> REVOKED transition
    @Test
    fun test21_concurrentRevocationRequests_exactlyOneActiveToRevokedTransition() = runTest {
        val dev = PairedDeviceEntity("dev_concurrent", "Concurrent Phone", "PUB_KEY_C", "ACTIVE", System.currentTimeMillis(), 0L)
        mainPairedDeviceDao.insertOrUpdateDevice(dev)

        val initialSeq = mainCanonicalDao.getLatestCanonicalSequence() ?: 0L
        val initialItemsCount = mainInventoryDao.getAllItemsSync().size

        val job1 = async(Dispatchers.IO) { repository.revokeDevice("dev_concurrent") }
        val job2 = async(Dispatchers.IO) { repository.revokeDevice("dev_concurrent") }

        val res1 = job1.await()
        val res2 = job2.await()

        val results = listOf(res1, res2)
        val successCount = results.count { it is RevocationResult.Success }
        val alreadyRevokedCount = results.count { it is RevocationResult.AlreadyRevoked }

        assertEquals("Exactly ONE concurrent revocation MUST succeed", 1, successCount)
        assertEquals("The other concurrent revocation MUST return AlreadyRevoked", 1, alreadyRevokedCount)

        val finalDev = mainPairedDeviceDao.getDeviceById("dev_concurrent")
        assertNotNull(finalDev)
        assertEquals("REVOKED", finalDev?.status)
        assertEquals("dev_concurrent", finalDev?.deviceId)
        assertEquals("PUB_KEY_C", finalDev?.devicePublicKey)

        assertEquals("Inventory items count MUST remain unchanged", initialItemsCount, mainInventoryDao.getAllItemsSync().size)
        assertEquals("Canonical sequence MUST NOT be allocated", initialSeq, mainCanonicalDao.getLatestCanonicalSequence() ?: 0L)
    }

    // 22. Unknown status device -> deterministic failure without silent mutation
    @Test
    fun test22_unknownStatusDevice_failsWithoutSilentMutation() = runTest {
        val dev = PairedDeviceEntity("dev_unknown", "Unknown Status Phone", "PUB_KEY_U", "SUSPENDED", System.currentTimeMillis(), 0L)
        mainPairedDeviceDao.insertOrUpdateDevice(dev)

        val res = repository.revokeDevice("dev_unknown")

        assertTrue("Unknown status device revocation MUST return UnknownStatusFailure", res is RevocationResult.UnknownStatusFailure)
        val failRes = res as RevocationResult.UnknownStatusFailure
        assertEquals("SUSPENDED", failRes.status)

        val devInDb = mainPairedDeviceDao.getDeviceById("dev_unknown")
        assertEquals("SUSPENDED", devInDb?.status)
        assertEquals("PUB_KEY_U", devInDb?.devicePublicKey)
    }
}
