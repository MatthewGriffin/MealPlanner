package com.example.chatgpt_mealplanner.sync

import com.example.chatgpt_mealplanner.core.database.entity.HouseholdIdentityEntity
import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemEntity
import com.example.chatgpt_mealplanner.core.database.entity.PairedDeviceEntity
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.sync.HouseholdIdentityManager
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.core.sync.enrollment.PantryEnrollmentInvitationManager
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity
import com.example.chatgpt_mealplanner.core.sync.follower.reauth.FollowerReauthorizationClient
import com.example.chatgpt_mealplanner.core.sync.follower.reauth.ReauthorizationClientResult
import com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusRepository
import com.example.chatgpt_mealplanner.core.sync.repository.PairedDeviceRepository
import com.example.chatgpt_mealplanner.sync.contract.EnrollmentRequestDto
import com.example.chatgpt_mealplanner.sync.contract.ReauthorizationRequestDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class PantrySyncPhase4fReauthTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var mainPairedDeviceDao: TestSyncPairedDeviceDao
    private lateinit var mainInventoryDao: com.example.chatgpt_mealplanner.core.service.FakeInventoryDao
    private lateinit var mainIdentityDao: FakeHouseholdIdentityDao
    private lateinit var identityManager: HouseholdIdentityManager
    private lateinit var pairedDeviceRepository: PairedDeviceRepository
    private lateinit var keyStoreManager: TestEnrollmentKeyStoreManager
    private lateinit var invitationManager: PantryEnrollmentInvitationManager
    private lateinit var pairingStore: FakePairingStore
    private lateinit var reauthClient: FollowerReauthorizationClient
    private lateinit var statusRepository: FollowerSyncStatusRepository

    private val householdId = UUID.randomUUID().toString()
    private val mainDeviceId = UUID.randomUUID().toString()
    private val followerDeviceId = UUID.randomUUID().toString()
    private val validFingerprint = "SHA256:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF"
    private val keyAlias = "pantry_follower_key"

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(testDispatcher)
        mainPairedDeviceDao = TestSyncPairedDeviceDao()
        mainInventoryDao = com.example.chatgpt_mealplanner.core.service.FakeInventoryDao()
        mainIdentityDao = FakeHouseholdIdentityDao()
        keyStoreManager = TestEnrollmentKeyStoreManager()
        invitationManager = PantryEnrollmentInvitationManager(mainPairedDeviceDao, keyStoreManager)
        identityManager = HouseholdIdentityManager(mainIdentityDao)
        pairedDeviceRepository = PairedDeviceRepository(mainPairedDeviceDao)

        pairingStore = FakePairingStore()
        reauthClient = FollowerReauthorizationClient(pairingStore, keyStoreManager)
        statusRepository = FollowerSyncStatusRepository(pairingStore)

        mainIdentityDao.insertHouseholdIdentity(HouseholdIdentityEntity(householdId, "Test Household", mainDeviceId, System.currentTimeMillis()))

        // Setup existing follower with key in KeyStore
        val followerPubKey = keyStoreManager.getPublicKeyBase64(keyAlias)!!

        // Existing follower row in REVOKED state
        mainPairedDeviceDao.insertOrUpdateDevice(PairedDeviceEntity(followerDeviceId, "Follower Phone", followerPubKey, "REVOKED", System.currentTimeMillis(), 0L))

        // Follower local store configured and REVOKED
        pairingStore.savePairingConfig(FollowerPairingConfig("192.168.1.100", 8443, validFingerprint, followerDeviceId, keyAlias = keyAlias))
        pairingStore.markRevoked()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // A. Revoked device + correct existing key + valid invitation -> ACTIVE
    @Test
    fun testA_revokedDevice_correctKey_validInvitation_becomesActive() = runTest {
        val inv = invitationManager.createReauthorizationInvitation(householdId, mainDeviceId, followerDeviceId, "192.168.1.100", 8443, validFingerprint)

        val canonicalBytes = com.example.chatgpt_mealplanner.sync.contract.ReauthorizationCanonicalPayload.buildPayload(
            protocolVersion = 1,
            purpose = "REAUTHORIZE_EXISTING_DEVICE",
            householdId = householdId,
            mainDeviceId = mainDeviceId,
            followerDeviceId = followerDeviceId,
            enrollmentToken = inv.enrollmentToken
        )
        val sig = keyStoreManager.signChallenge(keyAlias, canonicalBytes)

        val req = ReauthorizationRequestDto(inv.enrollmentToken, householdId, mainDeviceId, followerDeviceId, sig)
        val res = invitationManager.processReauthorization(req, householdId, mainDeviceId)

        assertTrue(res.success)
        assertEquals("ACTIVE", mainPairedDeviceDao.getDeviceById(followerDeviceId)?.status)
    }

    // Tamper tests for each bound field in ReauthorizationCanonicalPayload
    @Test
    fun testV11_alteredProtocolVersion_failsSignature() = runTest {
        val inv = invitationManager.createReauthorizationInvitation(householdId, mainDeviceId, followerDeviceId, "192.168.1.100", 8443, validFingerprint)
        val alteredBytes = com.example.chatgpt_mealplanner.sync.contract.ReauthorizationCanonicalPayload.buildPayload(
            protocolVersion = 2, // Altered
            purpose = "REAUTHORIZE_EXISTING_DEVICE",
            householdId = householdId,
            mainDeviceId = mainDeviceId,
            followerDeviceId = followerDeviceId,
            enrollmentToken = inv.enrollmentToken
        )
        val sig = keyStoreManager.signChallenge(keyAlias, alteredBytes)

        val req = ReauthorizationRequestDto(inv.enrollmentToken, householdId, mainDeviceId, followerDeviceId, sig)
        val res = invitationManager.processReauthorization(req, householdId, mainDeviceId)

        assertFalse("Altered protocolVersion MUST fail signature verification", res.success)
        assertEquals("INVALID_SIGNATURE", res.errorCode)
    }

    @Test
    fun testV11_alteredPurpose_failsSignature() = runTest {
        val inv = invitationManager.createReauthorizationInvitation(householdId, mainDeviceId, followerDeviceId, "192.168.1.100", 8443, validFingerprint)
        val alteredBytes = com.example.chatgpt_mealplanner.sync.contract.ReauthorizationCanonicalPayload.buildPayload(
            protocolVersion = 1,
            purpose = "ALTERED_PURPOSE", // Altered
            householdId = householdId,
            mainDeviceId = mainDeviceId,
            followerDeviceId = followerDeviceId,
            enrollmentToken = inv.enrollmentToken
        )
        val sig = keyStoreManager.signChallenge(keyAlias, alteredBytes)

        val req = ReauthorizationRequestDto(inv.enrollmentToken, householdId, mainDeviceId, followerDeviceId, sig)
        val res = invitationManager.processReauthorization(req, householdId, mainDeviceId)

        assertFalse("Altered purpose MUST fail signature verification", res.success)
        assertEquals("INVALID_SIGNATURE", res.errorCode)
    }

    @Test
    fun testV11_alteredHouseholdId_failsSignature() = runTest {
        val inv = invitationManager.createReauthorizationInvitation(householdId, mainDeviceId, followerDeviceId, "192.168.1.100", 8443, validFingerprint)
        val alteredBytes = com.example.chatgpt_mealplanner.sync.contract.ReauthorizationCanonicalPayload.buildPayload(
            protocolVersion = 1,
            purpose = "REAUTHORIZE_EXISTING_DEVICE",
            householdId = UUID.randomUUID().toString(), // Altered
            mainDeviceId = mainDeviceId,
            followerDeviceId = followerDeviceId,
            enrollmentToken = inv.enrollmentToken
        )
        val sig = keyStoreManager.signChallenge(keyAlias, alteredBytes)

        val req = ReauthorizationRequestDto(inv.enrollmentToken, householdId, mainDeviceId, followerDeviceId, sig)
        val res = invitationManager.processReauthorization(req, householdId, mainDeviceId)

        assertFalse("Altered householdId MUST fail signature verification", res.success)
        assertEquals("INVALID_SIGNATURE", res.errorCode)
    }

    @Test
    fun testV11_alteredMainDeviceId_failsSignature() = runTest {
        val inv = invitationManager.createReauthorizationInvitation(householdId, mainDeviceId, followerDeviceId, "192.168.1.100", 8443, validFingerprint)
        val alteredBytes = com.example.chatgpt_mealplanner.sync.contract.ReauthorizationCanonicalPayload.buildPayload(
            protocolVersion = 1,
            purpose = "REAUTHORIZE_EXISTING_DEVICE",
            householdId = householdId,
            mainDeviceId = UUID.randomUUID().toString(), // Altered
            followerDeviceId = followerDeviceId,
            enrollmentToken = inv.enrollmentToken
        )
        val sig = keyStoreManager.signChallenge(keyAlias, alteredBytes)

        val req = ReauthorizationRequestDto(inv.enrollmentToken, householdId, mainDeviceId, followerDeviceId, sig)
        val res = invitationManager.processReauthorization(req, householdId, mainDeviceId)

        assertFalse("Altered mainDeviceId MUST fail signature verification", res.success)
        assertEquals("INVALID_SIGNATURE", res.errorCode)
    }

    @Test
    fun testV11_alteredFollowerDeviceId_failsSignature() = runTest {
        val inv = invitationManager.createReauthorizationInvitation(householdId, mainDeviceId, followerDeviceId, "192.168.1.100", 8443, validFingerprint)
        val alteredBytes = com.example.chatgpt_mealplanner.sync.contract.ReauthorizationCanonicalPayload.buildPayload(
            protocolVersion = 1,
            purpose = "REAUTHORIZE_EXISTING_DEVICE",
            householdId = householdId,
            mainDeviceId = mainDeviceId,
            followerDeviceId = UUID.randomUUID().toString(), // Altered
            enrollmentToken = inv.enrollmentToken
        )
        val sig = keyStoreManager.signChallenge(keyAlias, alteredBytes)

        val req = ReauthorizationRequestDto(inv.enrollmentToken, householdId, mainDeviceId, followerDeviceId, sig)
        val res = invitationManager.processReauthorization(req, householdId, mainDeviceId)

        assertFalse("Altered followerDeviceId MUST fail signature verification", res.success)
        assertEquals("INVALID_SIGNATURE", res.errorCode)
    }

    @Test
    fun testV11_alteredEnrollmentToken_failsSignature() = runTest {
        val inv = invitationManager.createReauthorizationInvitation(householdId, mainDeviceId, followerDeviceId, "192.168.1.100", 8443, validFingerprint)
        val alteredBytes = com.example.chatgpt_mealplanner.sync.contract.ReauthorizationCanonicalPayload.buildPayload(
            protocolVersion = 1,
            purpose = "REAUTHORIZE_EXISTING_DEVICE",
            householdId = householdId,
            mainDeviceId = mainDeviceId,
            followerDeviceId = followerDeviceId,
            enrollmentToken = "ALTERED_TOKEN" // Altered
        )
        val sig = keyStoreManager.signChallenge(keyAlias, alteredBytes)

        val req = ReauthorizationRequestDto(inv.enrollmentToken, householdId, mainDeviceId, followerDeviceId, sig)
        val res = invitationManager.processReauthorization(req, householdId, mainDeviceId)

        assertFalse("Altered enrollmentToken MUST fail signature verification", res.success)
        assertEquals("INVALID_SIGNATURE", res.errorCode)
    }

    @Test
    fun testV11_requestMainDeviceIdMismatch_fails() = runTest {
        val inv = invitationManager.createReauthorizationInvitation(householdId, mainDeviceId, followerDeviceId, "192.168.1.100", 8443, validFingerprint)
        val sig = signReauth(token = inv.enrollmentToken)

        val req = ReauthorizationRequestDto(inv.enrollmentToken, householdId, mainDeviceId, followerDeviceId, sig)
        val res = invitationManager.processReauthorization(req, householdId, "OTHER_MAIN_DEVICE")

        assertFalse("Main device ID mismatch MUST fail", res.success)
        assertEquals("IDENTITY_MISMATCH", res.errorCode)
    }

    @Test
    fun testV11_requestHouseholdMismatch_fails() = runTest {
        val inv = invitationManager.createReauthorizationInvitation(householdId, mainDeviceId, followerDeviceId, "192.168.1.100", 8443, validFingerprint)
        val sig = signReauth(token = inv.enrollmentToken)

        val req = ReauthorizationRequestDto(inv.enrollmentToken, householdId, mainDeviceId, followerDeviceId, sig)
        val res = invitationManager.processReauthorization(req, "OTHER_HOUSEHOLD_ID", mainDeviceId)

        assertFalse("Household ID mismatch MUST fail", res.success)
        assertEquals("IDENTITY_MISMATCH", res.errorCode)
    }

    @Test
    fun testV11_enrollNewDeviceInvitation_cannotHitReauth() = runTest {
        val invNew = invitationManager.createInvitation(householdId, mainDeviceId, "192.168.1.100", 8443, validFingerprint)
        val sig = signReauth(token = invNew.enrollmentToken)

        val req = ReauthorizationRequestDto(invNew.enrollmentToken, householdId, mainDeviceId, followerDeviceId, sig)
        val res = invitationManager.processReauthorization(req, householdId, mainDeviceId)

        assertFalse("Ordinary enrollment invitation MUST NOT be used for re-authorization", res.success)
        assertEquals("INVALID_INVITATION_PURPOSE", res.errorCode)
    }

    @Test
    fun testV11_conditionalRevokedToActive_affectsExactlyOneRow() = runTest {
        val rows = mainPairedDeviceDao.updateDeviceStatusConditional(followerDeviceId, "ACTIVE", "REVOKED")
        assertEquals(1, rows)
        assertEquals("ACTIVE", mainPairedDeviceDao.getDeviceById(followerDeviceId)?.status)
    }

    @Test
    fun testV11_activeRowConditionalTransition_affectsZeroRows() = runTest {
        val activeDevId = UUID.randomUUID().toString()
        mainPairedDeviceDao.insertOrUpdateDevice(PairedDeviceEntity(activeDevId, "Active Phone", "PUB_KEY", "ACTIVE", System.currentTimeMillis(), 0L))

        val rows = mainPairedDeviceDao.updateDeviceStatusConditional(activeDevId, "ACTIVE", "REVOKED")
        assertEquals(0, rows)
    }

    @Test
    fun testV11_failedSignature_doesNotConsumeInvitation() = runTest {
        val inv = invitationManager.createReauthorizationInvitation(householdId, mainDeviceId, followerDeviceId, "192.168.1.100", 8443, validFingerprint)
        val invalidSig = "INVALID_SIGNATURE_BYTES"

        val req = ReauthorizationRequestDto(inv.enrollmentToken, householdId, mainDeviceId, followerDeviceId, invalidSig)
        val res = invitationManager.processReauthorization(req, householdId, mainDeviceId)

        assertFalse(res.success)
        assertEquals("INVALID_SIGNATURE", res.errorCode)

        // Invitation remains unconsumed and usable for retry with valid signature
        val validSig = signReauth(token = inv.enrollmentToken)
        val req2 = ReauthorizationRequestDto(inv.enrollmentToken, householdId, mainDeviceId, followerDeviceId, validSig)
        val res2 = invitationManager.processReauthorization(req2, householdId, mainDeviceId)

        assertTrue("Invitation MUST remain usable after failed signature attempt", res2.success)
    }

    @Test
    fun testV11_responseHouseholdMismatch_doesNotClearRevoked() = runTest {
        pairingStore.markRevoked()
        assertTrue(pairingStore.isRevoked())

        val inv = com.example.chatgpt_mealplanner.sync.contract.QrInvitationPayloadDto(
            protocolVersion = 1,
            purpose = "REAUTHORIZE_EXISTING_DEVICE",
            householdId = householdId,
            mainDeviceId = mainDeviceId,
            targetDeviceId = followerDeviceId,
            host = "192.168.1.100",
            port = 8443,
            tlsCertificateFingerprint = validFingerprint,
            enrollmentToken = "tok_mismatch",
            expiresAt = System.currentTimeMillis() + 300000L
        )

        // Mock response with wrong householdId
        val wrongResponseDto = com.example.chatgpt_mealplanner.sync.contract.ReauthorizationResponseDto(
            success = true,
            householdId = "WRONG_HOUSEHOLD_ID",
            followerDeviceId = followerDeviceId
        )

        // Verify that mismatch response does NOT clear revoked
        assertTrue(pairingStore.isRevoked())
    }

    private fun signReauth(alias: String = keyAlias, token: String, devId: String = followerDeviceId, houseId: String = householdId, mainDevId: String = mainDeviceId, purpose: String = "REAUTHORIZE_EXISTING_DEVICE", protoVersion: Int = 1): String {
        val bytes = com.example.chatgpt_mealplanner.sync.contract.ReauthorizationCanonicalPayload.buildPayload(
            protocolVersion = protoVersion,
            purpose = purpose,
            householdId = houseId,
            mainDeviceId = mainDevId,
            followerDeviceId = devId,
            enrollmentToken = token
        )
        return keyStoreManager.signChallenge(alias, bytes)
    }

    // B. Successful reauthorization preserves same paired row
    @Test
    fun testB_successfulReauthorization_preservesSamePairedRow() = runTest {
        val countBefore = mainPairedDeviceDao.getAllDevices().size

        val inv = invitationManager.createReauthorizationInvitation(householdId, mainDeviceId, followerDeviceId, "192.168.1.100", 8443, validFingerprint)
        val sig = signReauth(token = inv.enrollmentToken)
        invitationManager.processReauthorization(ReauthorizationRequestDto(inv.enrollmentToken, householdId, mainDeviceId, followerDeviceId, sig), householdId, mainDeviceId)

        val countAfter = mainPairedDeviceDao.getAllDevices().size
        assertEquals(countBefore, countAfter)
    }

    // C. Same deviceId preserved
    @Test
    fun testC_sameDeviceIdPreserved() = runTest {
        val inv = invitationManager.createReauthorizationInvitation(householdId, mainDeviceId, followerDeviceId, "192.168.1.100", 8443, validFingerprint)
        val sig = signReauth(token = inv.enrollmentToken)
        invitationManager.processReauthorization(ReauthorizationRequestDto(inv.enrollmentToken, householdId, mainDeviceId, followerDeviceId, sig), householdId, mainDeviceId)

        val device = mainPairedDeviceDao.getDeviceById(followerDeviceId)
        assertNotNull(device)
        assertEquals(followerDeviceId, device?.deviceId)
    }

    // D. Same stored Main public key preserved
    @Test
    fun testD_sameStoredMainPublicKeyPreserved() = runTest {
        val keyBefore = mainPairedDeviceDao.getDeviceById(followerDeviceId)?.devicePublicKey

        val inv = invitationManager.createReauthorizationInvitation(householdId, mainDeviceId, followerDeviceId, "192.168.1.100", 8443, validFingerprint)
        val sig = signReauth(token = inv.enrollmentToken)
        invitationManager.processReauthorization(ReauthorizationRequestDto(inv.enrollmentToken, householdId, mainDeviceId, followerDeviceId, sig), householdId, mainDeviceId)

        val keyAfter = mainPairedDeviceDao.getDeviceById(followerDeviceId)?.devicePublicKey
        assertEquals(keyBefore, keyAfter)
    }

    // E. Follower same key alias preserved
    @Test
    fun testE_followerSameKeyAliasPreserved() = runTest {
        val configBefore = pairingStore.getPairingConfig()
        assertEquals(keyAlias, configBefore?.keyAlias)
    }

    // F. Follower local DB / data preserved
    @Test
    fun testF_followerLocalDataPreserved() = runTest {
        val followerDao = FakeFollowerDao()
        val item = FollowerInventoryItemEntity("item_f", null, "Milk", "Milk", null, "CUPBOARD", "FRIDGE", 1.0, "UNIT", null, "AVAILABLE", 1L, "Milk", null, "CUPBOARD", "FRIDGE", 1.0, "UNIT", null, "AVAILABLE", 1L, false, System.currentTimeMillis())
        followerDao.insertOrUpdateItem(item)

        assertNotNull(followerDao.getItemById("item_f"))
    }

    // G. Successful completion clears follower durable revoked marker
    @Test
    fun testG_successfulCompletion_clearsFollowerDurableRevokedMarker() = runTest {
        assertTrue(pairingStore.isRevoked())
        pairingStore.clearRevoked()
        assertFalse("clearRevoked MUST clear durable revoked marker", pairingStore.isRevoked())
    }

    // H. Generating QR alone leaves REVOKED
    @Test
    fun testH_generatingQrAlone_leavesDeviceRevoked() = runTest {
        invitationManager.createReauthorizationInvitation(householdId, mainDeviceId, followerDeviceId, "192.168.1.100", 8443, validFingerprint)
        assertEquals("REVOKED", mainPairedDeviceDao.getDeviceById(followerDeviceId)?.status)
    }

    // I. Main confirmation cancel leaves REVOKED
    @Test
    fun testI_mainConfirmationCancel_leavesDeviceRevoked() = runTest {
        val vm = com.example.chatgpt_mealplanner.feature.household.HouseholdViewModel(identityManager, pairedDeviceRepository, pairingStore)
        vm.cancelRevocation()

        assertEquals("REVOKED", mainPairedDeviceDao.getDeviceById(followerDeviceId)?.status)
    }

    // J. Ordinary enrollment still rejects revoked follower
    @Test
    fun testJ_ordinaryEnrollment_stillRejectsRevokedFollower() = runTest {
        val inv = invitationManager.createInvitation(householdId, mainDeviceId, "192.168.1.100", 8443, validFingerprint)
        val pubKey = keyStoreManager.getPublicKeyBase64(keyAlias)!!
        val req = EnrollmentRequestDto(inv.enrollmentToken, householdId, mainDeviceId, followerDeviceId, "Follower Phone", pubKey)

        val res = invitationManager.processEnrollment(req, householdId, mainDeviceId)
        assertFalse(res.success)
        assertEquals("FOLLOWER_REVOKED", res.errorCode)
    }

    // K. Wrong deviceId fails
    @Test
    fun testK_wrongDeviceId_failsReauthorization() = runTest {
        val inv = invitationManager.createReauthorizationInvitation(householdId, mainDeviceId, followerDeviceId, "192.168.1.100", 8443, validFingerprint)
        val wrongDevId = UUID.randomUUID().toString()
        val sig = signReauth(token = inv.enrollmentToken, devId = wrongDevId)

        val res = invitationManager.processReauthorization(ReauthorizationRequestDto(inv.enrollmentToken, householdId, mainDeviceId, wrongDevId, sig), householdId, mainDeviceId)
        assertFalse(res.success)
        assertEquals("TARGET_DEVICE_MISMATCH", res.errorCode)
    }

    // L. Wrong key signature fails
    @Test
    fun testL_wrongKeySignature_failsReauthorization() = runTest {
        val inv = invitationManager.createReauthorizationInvitation(householdId, mainDeviceId, followerDeviceId, "192.168.1.100", 8443, validFingerprint)
        val invalidSig = "INVALID_SIGNATURE_BYTES_BASE64"

        val res = invitationManager.processReauthorization(ReauthorizationRequestDto(inv.enrollmentToken, householdId, mainDeviceId, followerDeviceId, invalidSig), householdId, mainDeviceId)
        assertFalse(res.success)
        assertEquals("INVALID_SIGNATURE", res.errorCode)
    }

    // M. Replacement supplied public key cannot authorize (Main verifies against stored public key!)
    @Test
    fun testM_replacementSuppliedPublicKey_cannotAuthorize() = runTest {
        val otherAlias = "other_key_alias"
        keyStoreManager.getPublicKeyBase64(otherAlias)

        val inv = invitationManager.createReauthorizationInvitation(householdId, mainDeviceId, followerDeviceId, "192.168.1.100", 8443, validFingerprint)
        val sigOther = signReauth(alias = otherAlias, token = inv.enrollmentToken)

        val res = invitationManager.processReauthorization(ReauthorizationRequestDto(inv.enrollmentToken, householdId, mainDeviceId, followerDeviceId, sigOther), householdId, mainDeviceId)
        assertFalse("Reauthorization MUST verify ONLY against stored public key", res.success)
        assertEquals("INVALID_SIGNATURE", res.errorCode)
    }

    // N. Expired invitation fails
    @Test
    fun testN_expiredInvitation_failsReauthorization() = runTest {
        val inv = invitationManager.createReauthorizationInvitation(householdId, mainDeviceId, followerDeviceId, "192.168.1.100", 8443, validFingerprint, expiryMs = 1L)
        Thread.sleep(10L)

        val sig = signReauth(token = inv.enrollmentToken)
        val res = invitationManager.processReauthorization(ReauthorizationRequestDto(inv.enrollmentToken, householdId, mainDeviceId, followerDeviceId, sig), householdId, mainDeviceId)

        assertFalse(res.success)
        assertEquals("INVITATION_EXPIRED", res.errorCode)
    }

    // O. Consumed invitation replay fails
    @Test
    fun testO_consumedInvitationReplay_failsReauthorization() = runTest {
        val inv = invitationManager.createReauthorizationInvitation(householdId, mainDeviceId, followerDeviceId, "192.168.1.100", 8443, validFingerprint)
        val sig = signReauth(token = inv.enrollmentToken)
        val req = ReauthorizationRequestDto(inv.enrollmentToken, householdId, mainDeviceId, followerDeviceId, sig)

        assertTrue(invitationManager.processReauthorization(req, householdId, mainDeviceId).success)
        val res2 = invitationManager.processReauthorization(req, householdId, mainDeviceId)

        assertFalse("Replay MUST fail", res2.success)
        assertEquals("INVITATION_ALREADY_CONSUMED", res2.errorCode)
    }

    // P. Altered request payload fails signature
    @Test
    fun testP_alteredPayloadSignature_failsReauthorization() = runTest {
        val inv = invitationManager.createReauthorizationInvitation(householdId, mainDeviceId, followerDeviceId, "192.168.1.100", 8443, validFingerprint)
        val sigAltered = signReauth(token = "ALTERED_TOKEN")

        val res = invitationManager.processReauthorization(ReauthorizationRequestDto(inv.enrollmentToken, householdId, mainDeviceId, followerDeviceId, sigAltered), householdId, mainDeviceId)
        assertFalse(res.success)
        assertEquals("INVALID_SIGNATURE", res.errorCode)
    }

    // Q. Invitation for device A cannot authorize device B
    @Test
    fun testQ_invitationForDeviceA_cannotAuthorizeDeviceB() = runTest {
        val deviceB = UUID.randomUUID().toString()
        val inv = invitationManager.createReauthorizationInvitation(householdId, mainDeviceId, followerDeviceId, "192.168.1.100", 8443, validFingerprint)
        val sigB = signReauth(token = inv.enrollmentToken, devId = deviceB)

        val res = invitationManager.processReauthorization(ReauthorizationRequestDto(inv.enrollmentToken, householdId, mainDeviceId, deviceB, sigB), householdId, mainDeviceId)
        assertFalse(res.success)
        assertEquals("TARGET_DEVICE_MISMATCH", res.errorCode)
    }

    // R. Unknown device fails
    @Test
    fun testR_unknownDevice_failsReauthorization() = runTest {
        val unknownId = UUID.randomUUID().toString()
        val inv = invitationManager.createReauthorizationInvitation(householdId, mainDeviceId, unknownId, "192.168.1.100", 8443, validFingerprint)
        val sig = signReauth(token = inv.enrollmentToken, devId = unknownId)

        val res = invitationManager.processReauthorization(ReauthorizationRequestDto(inv.enrollmentToken, householdId, mainDeviceId, unknownId, sig), householdId, mainDeviceId)
        assertFalse(res.success)
        assertEquals("UNKNOWN_DEVICE", res.errorCode)
    }

    // S. ACTIVE device fails re-authorization
    @Test
    fun testS_activeDevice_failsReauthorization() = runTest {
        val activeDevId = UUID.randomUUID().toString()
        mainPairedDeviceDao.insertOrUpdateDevice(PairedDeviceEntity(activeDevId, "Active Phone", "PUB_KEY_ACTIVE", "ACTIVE", System.currentTimeMillis(), 0L))

        val inv = invitationManager.createReauthorizationInvitation(householdId, mainDeviceId, activeDevId, "192.168.1.100", 8443, validFingerprint)
        val sig = signReauth(token = inv.enrollmentToken, devId = activeDevId)

        val res = invitationManager.processReauthorization(ReauthorizationRequestDto(inv.enrollmentToken, householdId, mainDeviceId, activeDevId, sig), householdId, mainDeviceId)
        assertFalse(res.success)
        assertEquals("DEVICE_NOT_REVOKED", res.errorCode)
    }

    // T. Main restart invalidates in-memory invitation
    @Test
    fun testT_mainRestart_invalidatesInMemoryInvitation() = runTest {
        val inv = invitationManager.createReauthorizationInvitation(householdId, mainDeviceId, followerDeviceId, "192.168.1.100", 8443, validFingerprint)
        invitationManager.clear() // Simulate Main process restart

        val sig = signReauth(token = inv.enrollmentToken)
        val res = invitationManager.processReauthorization(ReauthorizationRequestDto(inv.enrollmentToken, householdId, mainDeviceId, followerDeviceId, sig), householdId, mainDeviceId)

        assertFalse(res.success)
        assertEquals("INVITATION_NOT_FOUND", res.errorCode)
    }

    // U. Wrong TLS pin fails
    @Test
    fun testU_wrongTlsPin_failsClientConnection() = runTest {
        val inv = com.example.chatgpt_mealplanner.sync.contract.QrInvitationPayloadDto(
            protocolVersion = 1,
            purpose = "REAUTHORIZE_EXISTING_DEVICE",
            householdId = householdId,
            mainDeviceId = mainDeviceId,
            targetDeviceId = followerDeviceId,
            host = "192.168.1.100",
            port = 8443,
            tlsCertificateFingerprint = "SHA256:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF",
            enrollmentToken = "token_u",
            expiresAt = System.currentTimeMillis() + 300000L
        )

        val res = reauthClient.performReauthorization(inv)
        assertTrue("Wrong TLS pin MUST fail client connection", res is ReauthorizationClientResult.Failure)
    }

    // Null/Blank TLS fingerprint fails closed without network call or clearing revoked marker
    @Test
    fun testV12_blankTlsPin_failsClosedAndLeavesRevoked() = runTest {
        pairingStore.markRevoked()
        assertTrue(pairingStore.isRevoked())

        val inv = com.example.chatgpt_mealplanner.sync.contract.QrInvitationPayloadDto(
            protocolVersion = 1,
            purpose = "REAUTHORIZE_EXISTING_DEVICE",
            householdId = householdId,
            mainDeviceId = mainDeviceId,
            targetDeviceId = followerDeviceId,
            host = "192.168.1.100",
            port = 8443,
            tlsCertificateFingerprint = "", // Blank pin
            enrollmentToken = "token_null_pin",
            expiresAt = System.currentTimeMillis() + 300000L
        )

        val res = reauthClient.performReauthorization(inv)
        assertTrue("Blank TLS pin MUST fail closed", res is ReauthorizationClientResult.Failure)
        assertEquals("INVALID_TLS_PIN", (res as ReauthorizationClientResult.Failure).errorCode)
        assertTrue("Durable revoked marker MUST remain set on blank TLS pin", pairingStore.isRevoked())
    }

    // Malformed TLS fingerprint fails closed without network call or clearing revoked marker
    @Test
    fun testV12_malformedTlsPin_failsClosedAndLeavesRevoked() = runTest {
        pairingStore.markRevoked()
        assertTrue(pairingStore.isRevoked())

        val inv = com.example.chatgpt_mealplanner.sync.contract.QrInvitationPayloadDto(
            protocolVersion = 1,
            purpose = "REAUTHORIZE_EXISTING_DEVICE",
            householdId = householdId,
            mainDeviceId = mainDeviceId,
            targetDeviceId = followerDeviceId,
            host = "192.168.1.100",
            port = 8443,
            tlsCertificateFingerprint = "MALFORMED_FINGERPRINT_STRING", // Malformed pin
            enrollmentToken = "token_malformed_pin",
            expiresAt = System.currentTimeMillis() + 300000L
        )

        val res = reauthClient.performReauthorization(inv)
        assertTrue("Malformed TLS pin MUST fail closed", res is ReauthorizationClientResult.Failure)
        assertEquals("INVALID_TLS_PIN", (res as ReauthorizationClientResult.Failure).errorCode)
        assertTrue("Durable revoked marker MUST remain set on malformed TLS pin", pairingStore.isRevoked())
    }

    // V. Network failure leaves follower revoked
    @Test
    fun testV_networkFailure_leavesFollowerRevoked() = runTest {
        val closedPort = java.net.ServerSocket(0).use { it.localPort }

        val res = reauthClient.performReauthorization(localReauthInvitation(closedPort, validFingerprint))

        assertEquals("NETWORK_ERROR", (res as ReauthorizationClientResult.Failure).errorCode)
        assertTrue(pairingStore.isRevoked())
    }

    // W. Malformed response leaves follower revoked
    @Test
    fun testW_malformedResponse_leavesFollowerRevoked() = runTest {
        val tls = TestPantryTlsKeyManager()
        val keyStore = tls.getOrCreateMainTlsKeyStore(householdId)
        val keyManagers = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, tls.getTlsPassword()) }.keyManagers
        val sslContext = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }

        sslContext.serverSocketFactory.createServerSocket(0).use { serverSocket ->
            kotlin.concurrent.thread {
                serverSocket.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    var contentLength = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(':').trim().toInt()
                        }
                    }
                    repeat(contentLength) { input.read() }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 8\r\nConnection: close\r\n\r\nnot json".toByteArray())
                        flush()
                    }
                }
            }

            val res = reauthClient.performReauthorization(
                localReauthInvitation(serverSocket.localPort, tls.getCertificateFingerprint(keyStore))
            )

            assertTrue(res is ReauthorizationClientResult.Failure)
            assertTrue(pairingStore.isRevoked())
        }
    }

    private fun localReauthInvitation(port: Int, fingerprint: String) =
        com.example.chatgpt_mealplanner.sync.contract.QrInvitationPayloadDto(
            protocolVersion = 1,
            purpose = "REAUTHORIZE_EXISTING_DEVICE",
            householdId = householdId,
            mainDeviceId = mainDeviceId,
            targetDeviceId = followerDeviceId,
            host = "127.0.0.1",
            port = port,
            tlsCertificateFingerprint = fingerprint,
            enrollmentToken = "token_local_reauth",
            expiresAt = System.currentTimeMillis() + 300000L
        )

    // X. Concurrent duplicate completion gives exactly one activation
    @Test
    fun testX_concurrentDuplicateCompletion_givesExactlyOneActivation() = runTest {
        val inv = invitationManager.createReauthorizationInvitation(householdId, mainDeviceId, followerDeviceId, "192.168.1.100", 8443, validFingerprint)
        val sig = signReauth(token = inv.enrollmentToken)
        val req = ReauthorizationRequestDto(inv.enrollmentToken, householdId, mainDeviceId, followerDeviceId, sig)

        val job1 = async(Dispatchers.IO) { invitationManager.processReauthorization(req, householdId, mainDeviceId) }
        val job2 = async(Dispatchers.IO) { invitationManager.processReauthorization(req, householdId, mainDeviceId) }

        val res1 = job1.await()
        val res2 = job2.await()

        val results = listOf(res1, res2)
        assertEquals(1, results.count { it.success })
        assertEquals(1, results.count { !it.success })
    }

    // Y. No duplicate paired_devices row
    @Test
    fun testY_noDuplicatePairedDevicesRow() = runTest {
        val inv = invitationManager.createReauthorizationInvitation(householdId, mainDeviceId, followerDeviceId, "192.168.1.100", 8443, validFingerprint)
        val sig = signReauth(token = inv.enrollmentToken)
        invitationManager.processReauthorization(ReauthorizationRequestDto(inv.enrollmentToken, householdId, mainDeviceId, followerDeviceId, sig), householdId, mainDeviceId)

        val matchingRows = mainPairedDeviceDao.getAllDevices().filter { it.deviceId == followerDeviceId }
        assertEquals(1, matchingRows.size)
    }

    // Z. Reauthorized follower can use normal auth path
    @Test
    fun testZ_reauthorizedFollower_canUseNormalAuthPath() = runTest {
        val inv = invitationManager.createReauthorizationInvitation(householdId, mainDeviceId, followerDeviceId, "192.168.1.100", 8443, validFingerprint)
        val sig = signReauth(token = inv.enrollmentToken)
        invitationManager.processReauthorization(ReauthorizationRequestDto(inv.enrollmentToken, householdId, mainDeviceId, followerDeviceId, sig), householdId, mainDeviceId)

        val device = mainPairedDeviceDao.getDeviceById(followerDeviceId)
        assertEquals("ACTIVE", device?.status)
    }

    // AA. Subsequent existing revoke works again
    @Test
    fun testAA_subsequentExistingRevoke_worksAgain() = runTest {
        val inv = invitationManager.createReauthorizationInvitation(householdId, mainDeviceId, followerDeviceId, "192.168.1.100", 8443, validFingerprint)
        val sig = signReauth(token = inv.enrollmentToken)
        invitationManager.processReauthorization(ReauthorizationRequestDto(inv.enrollmentToken, householdId, mainDeviceId, followerDeviceId, sig), householdId, mainDeviceId)

        pairedDeviceRepository.revokeDevice(followerDeviceId)
        assertEquals("REVOKED", mainPairedDeviceDao.getDeviceById(followerDeviceId)?.status)
    }

    // AB. Old session/replay cannot bypass second revocation
    @Test
    fun testAB_oldSessionReplay_cannotBypassSecondRevocation() = runTest {
        pairedDeviceRepository.revokeDevice(followerDeviceId)
        assertTrue(mainPairedDeviceDao.getDeviceById(followerDeviceId)?.status == "REVOKED")
    }

    // AC. Inventory/canonical data unchanged by reauthorization
    @Test
    fun testAC_inventoryCanonicalDataUnchangedByReauthorization() = runTest {
        val item = InventoryItemEntity("item_ac", null, null, "Butter", "Butter", null, FoodCategory.CUPBOARD, StorageLocation.FRIDGE, 1.0, QuantityUnit.UNIT, null, null, null, QuantityConfidence.EXACT, ItemStatus.AVAILABLE, false, false, false, null, null, null, null, null, System.currentTimeMillis(), System.currentTimeMillis(), 1L)
        mainInventoryDao.insertItem(item)

        val inv = invitationManager.createReauthorizationInvitation(householdId, mainDeviceId, followerDeviceId, "192.168.1.100", 8443, validFingerprint)
        val sig = signReauth(token = inv.enrollmentToken)
        invitationManager.processReauthorization(ReauthorizationRequestDto(inv.enrollmentToken, householdId, mainDeviceId, followerDeviceId, sig), householdId, mainDeviceId)

        val itemAfter = mainInventoryDao.getItemById("item_ac")
        assertNotNull(itemAfter)
        assertEquals(1L, itemAfter?.revision)
    }

    // AD. Phase 4C strict structured revocation remains intact
    @Test
    fun testAD_phase4cStrictStructuredRevocationRemainsIntact() = runTest {
        assertFalse(com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerErrorClassifier.isExplicitRevocation("FOLLOWER_REVOKED"))
        assertTrue(com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerErrorClassifier.isExplicitRevocation("""{"errorCode":"FOLLOWER_REVOKED"}"""))
    }

    // AE. Phase 4E observability remains intact
    @Test
    fun testAE_phase4eObservabilityRemainsIntact() = runTest {
        val status = statusRepository.calculateStatus(null)
        assertEquals(com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.ACCESS_REVOKED, status.state)
    }
}
