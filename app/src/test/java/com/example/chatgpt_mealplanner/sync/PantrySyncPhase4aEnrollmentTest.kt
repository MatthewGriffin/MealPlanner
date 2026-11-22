package com.example.chatgpt_mealplanner.sync

import com.example.chatgpt_mealplanner.core.database.dao.PairedDeviceDao
import com.example.chatgpt_mealplanner.core.database.entity.PairedDeviceEntity
import com.example.chatgpt_mealplanner.core.security.AndroidKeyStoreManager
import com.example.chatgpt_mealplanner.core.security.Base64Utils
import com.example.chatgpt_mealplanner.core.sync.HouseholdIdentityManager
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.core.sync.enrollment.FollowerEnrollmentOrchestrator
import com.example.chatgpt_mealplanner.core.sync.enrollment.FollowerIdentityStore
import com.example.chatgpt_mealplanner.core.sync.enrollment.PantryEnrollmentInvitationManager
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import com.example.chatgpt_mealplanner.core.sync.follower.worker.FollowerSyncScheduler
import com.example.chatgpt_mealplanner.core.sync.server.SyncSessionManager
import com.example.chatgpt_mealplanner.feature.household.JoinHouseholdState
import com.example.chatgpt_mealplanner.feature.household.JoinHouseholdViewModel
import com.example.chatgpt_mealplanner.sync.contract.EnrollmentRequestDto
import com.example.chatgpt_mealplanner.sync.contract.EnrollmentResponseDto
import com.example.chatgpt_mealplanner.sync.contract.QrInvitationPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.SyncContractValidation
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
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID

open class TestEnrollmentKeyStoreManager : AndroidKeyStoreManager() {
    private val keyPairMap = mutableMapOf<String, KeyPair>()

    override fun ensureKeyPair(alias: String): KeyPair {
        return keyPairMap.getOrPut(alias) {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(256)
            kpg.generateKeyPair()
        }
    }

    override fun getPublicKey(alias: String): PublicKey? {
        return ensureKeyPair(alias).public
    }

    override fun getPublicKeyBase64(alias: String): String? {
        val key = getPublicKey(alias) ?: return null
        return Base64Utils.encodeToString(key.encoded)
    }

    override fun signChallenge(alias: String, challengeData: ByteArray): String {
        val kp = ensureKeyPair(alias)
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(kp.private)
        sig.update(challengeData)
        return Base64Utils.encodeToString(sig.sign())
    }

    override fun verifySignature(publicKeyBase64: String, challengeData: ByteArray, signatureBase64: String): Boolean {
        return try {
            val keyBytes = Base64Utils.decode(publicKeyBase64)
            val keyFactory = KeyFactory.getInstance("EC")
            val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(keyBytes))

            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(publicKey)
            sig.update(challengeData)
            val sigBytes = Base64Utils.decode(signatureBase64)
            sig.verify(sigBytes)
        } catch (_: Exception) {
            false
        }
    }
}

open class FakePairingStore : FollowerPairingStore(null) {
    var savedConfig: FollowerPairingConfig? = null
    var shouldFailSave = false
    var revokedMarker = false
    var forceInaccessible = false
    var verifiedBootstrapV1Flag = false

    override fun isBootstrapVerifiedV1(): Boolean = verifiedBootstrapV1Flag

    override fun markBootstrapVerifiedV1(): Boolean {
        verifiedBootstrapV1Flag = true
        return true
    }

    override fun clearBootstrapVerifiedV1(): Boolean {
        verifiedBootstrapV1Flag = false
        return true
    }

    override fun isStoreInaccessible(): Boolean = forceInaccessible

    override fun isPaired(): Boolean = savedConfig != null

    override fun getPairingConfig(): FollowerPairingConfig? = savedConfig

    override fun updateSessionToken(sessionToken: String?) {
        savedConfig = savedConfig?.copy(sessionToken = sessionToken)
    }

    override fun savePairingConfig(config: FollowerPairingConfig): Boolean {
        if (shouldFailSave) return false
        savedConfig = config
        verifiedBootstrapV1Flag = false
        return true
    }

    override fun markRevoked(): Boolean {
        revokedMarker = true
        return true
    }

    override fun isRevoked(): Boolean = revokedMarker

    override fun clearRevoked(): Boolean {
        revokedMarker = false
        return true
    }

    override fun clear() {
        savedConfig = null
        revokedMarker = false
    }
}

open class TestFollowerIdentityStore(
    var simulatedDeviceId: String? = UUID.randomUUID().toString()
) : FollowerIdentityStore(null) {
    var shouldFailWrite = false

    override fun getOrCreateDeviceId(): String? {
        if (shouldFailWrite) return null
        return simulatedDeviceId
    }
}

open class TestSyncPairedDeviceDao : PairedDeviceDao {
    var shouldFailInsert = false
    private val devicesMap = java.util.concurrent.ConcurrentHashMap<String, PairedDeviceEntity>()

    override suspend fun getDeviceById(deviceId: String): PairedDeviceEntity? = devicesMap[deviceId]
    override suspend fun getAllDevices(): List<PairedDeviceEntity> = devicesMap.values.toList()

    override suspend fun insertOrUpdateDevice(device: PairedDeviceEntity) {
        if (shouldFailInsert) {
            throw RuntimeException("Database error during insert")
        }
        devicesMap[device.deviceId] = device
    }

    override suspend fun touchLastSync(deviceId: String, timestamp: Long) {
        devicesMap[deviceId]?.let { devicesMap[deviceId] = it.copy(lastSyncTimestamp = timestamp) }
    }

    override suspend fun updateDeviceStatus(deviceId: String, status: String) {
        devicesMap[deviceId]?.let {
            devicesMap[deviceId] = it.copy(status = status)
        }
    }

    override suspend fun updateDeviceStatusConditional(deviceId: String, newStatus: String, expectedCurrentStatus: String): Int {
        synchronized(this) {
            val existing = devicesMap[deviceId] ?: return 0
            if (existing.status == expectedCurrentStatus) {
                devicesMap[deviceId] = existing.copy(status = newStatus)
                return 1
            }
            return 0
        }
    }

    override suspend fun deleteIfRevoked(deviceId: String): Int {
        val dev = devicesMap[deviceId] ?: return 0
        if (dev.status != "REVOKED") return 0
        devicesMap.remove(deviceId)
        return 1
    }

    override suspend fun revokeDeviceIfActive(deviceId: String): Int {
        synchronized(this) {
            val existing = devicesMap[deviceId] ?: return 0
            if (existing.status == "ACTIVE") {
                devicesMap[deviceId] = existing.copy(status = "REVOKED")
                return 1
            }
            return 0
        }
    }
}

open class DirectEnrollmentClient(
    private val invitationManager: PantryEnrollmentInvitationManager,
    private val expectedHouseholdId: String,
    private val expectedMainDeviceId: String,
    keyStoreManager: AndroidKeyStoreManager
) : PantrySyncTestClient(keyStoreManager) {

    var simulateTlsMismatch = false

    override suspend fun registerEnrollment(
        hostIp: String,
        port: Int,
        pinnedCertFingerprint: String,
        requestDto: EnrollmentRequestDto
    ): EnrollmentResponseDto {
        if (simulateTlsMismatch) {
            return EnrollmentResponseDto(
                success = false,
                householdId = requestDto.householdId,
                errorCode = "TLS_PIN_MISMATCH",
                errorMessage = "TLS Certificate Pin Mismatch"
            )
        }
        return invitationManager.processEnrollment(
            request = requestDto,
            expectedHouseholdId = expectedHouseholdId,
            expectedMainDeviceId = expectedMainDeviceId
        )
    }
}

@RunWith(JUnit4::class)
class PantrySyncPhase4aEnrollmentTest {

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var mainPairedDeviceDao: TestSyncPairedDeviceDao
    private lateinit var mainIdentityDao: FakeHouseholdIdentityDao
    private lateinit var identityManager: HouseholdIdentityManager
    private lateinit var invitationManager: PantryEnrollmentInvitationManager
    private lateinit var keyStoreManager: TestEnrollmentKeyStoreManager
    private lateinit var sessionManager: SyncSessionManager
    private lateinit var identityStore: TestFollowerIdentityStore

    private val householdId = UUID.randomUUID().toString()
    private val mainDeviceId = UUID.randomUUID().toString()
    private val validFingerprint = "SHA256:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF"

    @Before
    fun setUp() = runTest {
        mainPairedDeviceDao = TestSyncPairedDeviceDao()
        mainIdentityDao = FakeHouseholdIdentityDao()
        identityManager = HouseholdIdentityManager(mainIdentityDao)
        keyStoreManager = TestEnrollmentKeyStoreManager()
        invitationManager = PantryEnrollmentInvitationManager(mainPairedDeviceDao, keyStoreManager)
        sessionManager = SyncSessionManager(keyStoreManager, mainPairedDeviceDao)
        identityStore = TestFollowerIdentityStore()

        mainIdentityDao.insertHouseholdIdentity(
            com.example.chatgpt_mealplanner.core.database.entity.HouseholdIdentityEntity(
                householdId = householdId,
                householdName = "Test Household",
                mainDeviceId = mainDeviceId,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    // 1. invitation_expires
    @Test
    fun test1_invitationExpires() = runTest {
        val inv = invitationManager.createInvitation(
            householdId = householdId,
            mainDeviceId = mainDeviceId,
            hostIp = "192.168.1.100",
            port = 8443,
            tlsFingerprint = validFingerprint,
            expiryMs = 1000L
        )

        val req = EnrollmentRequestDto(
            enrollmentToken = inv.enrollmentToken,
            householdId = householdId,
            mainDeviceId = mainDeviceId,
            followerDeviceId = UUID.randomUUID().toString(),
            followerDeviceName = "Follower A",
            followerPublicKey = keyStoreManager.getPublicKeyBase64("key_a")!!
        )

        val res = invitationManager.processEnrollment(req, householdId, mainDeviceId, currentTimeMs = System.currentTimeMillis() + 2000L)
        assertFalse(res.success)
        assertEquals("INVITATION_EXPIRED", res.errorCode)
    }

    // 2. invitation_singleUse
    @Test
    fun test2_invitationSingleUse() = runTest {
        val inv = invitationManager.createInvitation(householdId, mainDeviceId, "192.168.1.100", 8443, validFingerprint)

        val req1 = EnrollmentRequestDto(inv.enrollmentToken, householdId, mainDeviceId, UUID.randomUUID().toString(), "Follower A", keyStoreManager.getPublicKeyBase64("key_a")!!)
        val req2 = EnrollmentRequestDto(inv.enrollmentToken, householdId, mainDeviceId, UUID.randomUUID().toString(), "Follower B", keyStoreManager.getPublicKeyBase64("key_b")!!)

        val res1 = invitationManager.processEnrollment(req1, householdId, mainDeviceId)
        assertTrue(res1.success)

        val res2 = invitationManager.processEnrollment(req2, householdId, mainDeviceId)
        assertFalse(res2.success)
        assertEquals("INVITATION_ALREADY_CONSUMED", res2.errorCode)
    }

    // BLOCKER 1 Tests: D, E, F
    @Test
    fun test1D_nonexistentInvitation_existingActiveDevice_rejected() = runTest {
        val followerDevId = UUID.randomUUID().toString()
        val pubKey = keyStoreManager.getPublicKeyBase64("key_active")!!
        mainPairedDeviceDao.insertOrUpdateDevice(
            PairedDeviceEntity(followerDevId, "Active Phone", pubKey, "ACTIVE", System.currentTimeMillis(), 0L)
        )

        val req = EnrollmentRequestDto("nonexistent_token_123", householdId, mainDeviceId, followerDevId, "Active Phone", pubKey)
        val res = invitationManager.processEnrollment(req, householdId, mainDeviceId)

        assertFalse("Nonexistent token MUST be rejected even if followerDeviceId is ACTIVE", res.success)
        assertEquals("INVITATION_NOT_FOUND", res.errorCode)
    }

    @Test
    fun test1E_expiredInvitation_existingActiveDevice_rejected() = runTest {
        val inv = invitationManager.createInvitation(householdId, mainDeviceId, "192.168.1.100", 8443, validFingerprint, expiryMs = 1000L)
        val followerDevId = UUID.randomUUID().toString()
        val pubKey = keyStoreManager.getPublicKeyBase64("key_active_e")!!
        mainPairedDeviceDao.insertOrUpdateDevice(
            PairedDeviceEntity(followerDevId, "Active Phone", pubKey, "ACTIVE", System.currentTimeMillis(), 0L)
        )

        val req = EnrollmentRequestDto(inv.enrollmentToken, householdId, mainDeviceId, followerDevId, "Active Phone", pubKey)
        val res = invitationManager.processEnrollment(req, householdId, mainDeviceId, currentTimeMs = System.currentTimeMillis() + 2000L)

        assertFalse("Expired token MUST be rejected even if followerDeviceId is ACTIVE", res.success)
        assertEquals("INVITATION_EXPIRED", res.errorCode)
    }

    @Test
    fun test1F_unrelatedInvitation_existingActiveDevice_rejected() = runTest {
        val inv = invitationManager.createInvitation(householdId, mainDeviceId, "192.168.1.100", 8443, validFingerprint)
        val dev1 = UUID.randomUUID().toString()
        val pubKey1 = keyStoreManager.getPublicKeyBase64("key_1")!!

        val dev2 = UUID.randomUUID().toString()
        val pubKey2 = keyStoreManager.getPublicKeyBase64("key_2")!!
        mainPairedDeviceDao.insertOrUpdateDevice(PairedDeviceEntity(dev2, "Active Phone 2", pubKey2, "ACTIVE", System.currentTimeMillis(), 0L))

        // Consumer 1 uses invitation
        val req1 = EnrollmentRequestDto(inv.enrollmentToken, householdId, mainDeviceId, dev1, "Phone 1", pubKey1)
        assertTrue(invitationManager.processEnrollment(req1, householdId, mainDeviceId).success)

        // Consumer 2 tries to use same invitation
        val req2 = EnrollmentRequestDto(inv.enrollmentToken, householdId, mainDeviceId, dev2, "Phone 2", pubKey2)
        val res2 = invitationManager.processEnrollment(req2, householdId, mainDeviceId)

        assertFalse("Unrelated invitation consumed by Dev1 MUST NOT authorize Dev2", res2.success)
        assertEquals("INVITATION_ALREADY_CONSUMED", res2.errorCode)
    }

    @Test
    fun enrollment_forcesFullResync_forAPhoneThatWasPairedBefore() = runTest {
        val store = FakePairingStore().apply { verifiedBootstrapV1Flag = true }
        val client = object : PantrySyncTestClient(keyStoreManager) {
            override suspend fun registerEnrollment(hostIp: String, port: Int, pinnedCertFingerprint: String, requestDto: EnrollmentRequestDto) =
                EnrollmentResponseDto(true, householdId)
        }
        val orchestrator = FollowerEnrollmentOrchestrator(null, TestFollowerIdentityStore(), keyStoreManager, store, client, FollowerSyncScheduler())

        val inv = invitationManager.createInvitation(householdId, mainDeviceId, "192.168.1.100", 8443, validFingerprint)
        val res = orchestrator.processQrEnrollment(json.encodeToString(inv))

        assertTrue(res.success)
        assertFalse("A re-paired phone MUST resync from scratch", store.isBootstrapVerifiedV1())
    }

    @Test
    fun reauthorization_forcesFullResync_afterMainIsRestored() = runTest {
        val store = FakePairingStore().apply { verifiedBootstrapV1Flag = true; revokedMarker = true }
        val identityStore = TestFollowerIdentityStore()
        val reauthClient = object : com.example.chatgpt_mealplanner.core.sync.follower.reauth.FollowerReauthorizationClient(store, keyStoreManager, identityStore) {
            override suspend fun performReauthorization(invitation: QrInvitationPayloadDto) =
                com.example.chatgpt_mealplanner.core.sync.follower.reauth.ReauthorizationClientResult.Success
        }
        val orchestrator = FollowerEnrollmentOrchestrator(null, identityStore, keyStoreManager, store, PantrySyncTestClient(keyStoreManager), FollowerSyncScheduler(), reauthClient)

        val inv = invitationManager.createInvitation(householdId, mainDeviceId, "192.168.1.100", 8443, validFingerprint)
            .copy(purpose = "REAUTHORIZE_EXISTING_DEVICE")
        val res = orchestrator.processQrEnrollment(json.encodeToString(inv))

        assertTrue(res.success)
        assertFalse("A re-paired phone MUST resync from scratch", store.isBootstrapVerifiedV1())
    }

    @Test
    fun joiningAgainAfterBeingRevoked_clearsTheRevocation() = runTest {
        val store = FakePairingStore().apply { revokedMarker = true }
        val client = object : PantrySyncTestClient(keyStoreManager) {
            override suspend fun registerEnrollment(hostIp: String, port: Int, pinnedCertFingerprint: String, requestDto: EnrollmentRequestDto) =
                EnrollmentResponseDto(true, householdId)
        }
        val orchestrator = FollowerEnrollmentOrchestrator(null, identityStore, keyStoreManager, store, client, FollowerSyncScheduler())

        val inv = invitationManager.createInvitation(householdId, mainDeviceId, "192.168.1.100", 8443, validFingerprint)
        val res = orchestrator.processQrEnrollment(json.encodeToString(inv))

        assertTrue(res.success)
        assertFalse("A fresh pairing MUST NOT stay revoked", store.isRevoked())
    }

    // BLOCKER 2 Tests: Identity Fail Closed
    @Test
    fun test2A_secureIdentityStoreUnavailable_failsClosed() = runTest {
        val failingStore = TestFollowerIdentityStore().apply { shouldFailWrite = true }
        val orchestrator = FollowerEnrollmentOrchestrator(null, failingStore, keyStoreManager, FakePairingStore(), PantrySyncTestClient(keyStoreManager), FollowerSyncScheduler())

        val inv = invitationManager.createInvitation(householdId, mainDeviceId, "192.168.1.100", 8443, validFingerprint)
        val res = orchestrator.processQrEnrollment(json.encodeToString(inv))

        assertFalse("Orchestrator MUST fail closed when identity store is unavailable", res.success)
        assertEquals("IDENTITY_STORAGE_UNAVAILABLE", res.errorCode)
    }

    @Test
    fun test2B_identityPersistenceWriteFailure_noEnrollmentRequest() = runTest {
        var networkCallAttempted = false
        val customClient = object : PantrySyncTestClient(keyStoreManager) {
            override suspend fun registerEnrollment(hostIp: String, port: Int, pinnedCertFingerprint: String, requestDto: EnrollmentRequestDto): EnrollmentResponseDto {
                networkCallAttempted = true
                return EnrollmentResponseDto(true, householdId)
            }
        }
        val failingStore = TestFollowerIdentityStore().apply { shouldFailWrite = true }
        val orchestrator = FollowerEnrollmentOrchestrator(null, failingStore, keyStoreManager, FakePairingStore(), customClient, FollowerSyncScheduler())

        val inv = invitationManager.createInvitation(householdId, mainDeviceId, "192.168.1.100", 8443, validFingerprint)
        val res = orchestrator.processQrEnrollment(json.encodeToString(inv))

        assertFalse(res.success)
        assertFalse("No network enrollment request MUST be sent if identity write fails", networkCallAttempted)
    }

    @Test
    fun test2C_reauthorizationQrScanned_delegatesToReauthorizationFlowAndClearsRevoked() = runTest {
        val pairingStore = FakePairingStore().apply { markRevoked() }
        assertTrue(pairingStore.isRevoked())

        val reauthClient = object : com.example.chatgpt_mealplanner.core.sync.follower.reauth.FollowerReauthorizationClient(pairingStore, keyStoreManager, identityStore) {
            override suspend fun performReauthorization(invitation: QrInvitationPayloadDto): com.example.chatgpt_mealplanner.core.sync.follower.reauth.ReauthorizationClientResult {
                pairingStore.clearRevoked()
                return com.example.chatgpt_mealplanner.core.sync.follower.reauth.ReauthorizationClientResult.Success
            }
        }

        val orchestrator = FollowerEnrollmentOrchestrator(
            null,
            identityStore,
            keyStoreManager,
            pairingStore,
            PantrySyncTestClient(keyStoreManager),
            FollowerSyncScheduler(),
            reauthClient
        )

        val reauthInv = invitationManager.createReauthorizationInvitation(
            householdId = householdId,
            mainDeviceId = mainDeviceId,
            targetDeviceId = identityStore.getOrCreateDeviceId()!!,
            hostIp = "192.168.1.100",
            port = 8443,
            tlsFingerprint = validFingerprint
        )

        val res = orchestrator.processQrEnrollment(json.encodeToString(reauthInv))

        assertTrue("Scanning reauthorization QR MUST succeed", res.success)
        assertFalse("Re-authorization MUST clear revoked state in pairing store", pairingStore.isRevoked())
    }

    // BLOCKER 3 Tests: Strict Fingerprint Validation
    @Test
    fun test3A_malformedTlsFingerprint_rejectedLocally() = runTest {
        val orchestrator = FollowerEnrollmentOrchestrator(null, identityStore, keyStoreManager, FakePairingStore(), PantrySyncTestClient(keyStoreManager), FollowerSyncScheduler())

        val badFingerprints = listOf(
            "NOT_A_HEX_FP",
            "SHA256:123",
            "SHA256:ZZ:ZZ:ZZ:ZZ:ZZ:ZZ:ZZ:ZZ:ZZ:ZZ:ZZ:ZZ:ZZ:ZZ:ZZ:ZZ:ZZ:ZZ:ZZ:ZZ:ZZ:ZZ:ZZ:ZZ:ZZ:ZZ:ZZ:ZZ:ZZ:ZZ:ZZ:ZZ",
            "SHA256:00:11"
        )

        for (fp in badFingerprints) {
            val inv = QrInvitationPayloadDto(1, householdId, mainDeviceId, "192.168.1.100", 8443, fp, "tok_123", System.currentTimeMillis() + 60000L)
            val res = orchestrator.processQrEnrollment(json.encodeToString(inv))
            assertFalse("Malformed TLS fingerprint '$fp' MUST be rejected locally", res.success)
            assertEquals("INVALID_TLS_FINGERPRINT", res.errorCode)
        }
    }

    @Test
    fun test3B_canonicalSha256Fingerprint_accepted() = runTest {
        assertTrue(SyncContractValidation.isValidSha256Fingerprint(validFingerprint))
    }

    @Test
    fun test3C_nonCanonicalFingerprints_rejected() = runTest {
        val bare64Hex = "00112233445566778899AABBCCDDEEFF00112233445566778899AABBCCDDEEFF"
        val truncated = "SHA256:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE"
        val extraByte = "SHA256:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:99"
        val missingSeparators = "SHA256:00112233445566778899AABBCCDDEEFF00112233445566778899AABBCCDDEEFF"
        val nonHexChars = "SHA256:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:ZZ:ZZ"
        val sha1Prefix = "SHA1:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF"

        assertFalse("Bare 64 hex MUST be rejected (SHA256: prefix required)", SyncContractValidation.isValidSha256Fingerprint(bare64Hex))
        assertFalse("Truncated fingerprint MUST be rejected", SyncContractValidation.isValidSha256Fingerprint(truncated))
        assertFalse("Extra byte MUST be rejected", SyncContractValidation.isValidSha256Fingerprint(extraByte))
        assertFalse("Missing separators MUST be rejected", SyncContractValidation.isValidSha256Fingerprint(missingSeparators))
        assertFalse("Non-hex characters MUST be rejected", SyncContractValidation.isValidSha256Fingerprint(nonHexChars))
        assertFalse("SHA1 prefix MUST be rejected", SyncContractValidation.isValidSha256Fingerprint(sha1Prefix))
    }

    // AUDIT PUBLIC KEY Tests
    @Test
    fun test4A_randomBase64Bytes_rejected() = runTest {
        val inv = invitationManager.createInvitation(householdId, mainDeviceId, "192.168.1.100", 8443, validFingerprint)
        val randomBase64 = Base64Utils.encodeToString(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))

        val req = EnrollmentRequestDto(inv.enrollmentToken, householdId, mainDeviceId, UUID.randomUUID().toString(), "Follower A", randomBase64)
        val res = invitationManager.processEnrollment(req, householdId, mainDeviceId)

        assertFalse("Random Base64 bytes MUST be rejected as invalid EC public key", res.success)
        assertEquals("INVALID_PUBLIC_KEY", res.errorCode)
    }

    @Test
    fun test4B_rsaPublicKey_rejected() = runTest {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val rsaPair = kpg.generateKeyPair()
        val rsaPubKeyBase64 = Base64Utils.encodeToString(rsaPair.public.encoded)

        val inv = invitationManager.createInvitation(householdId, mainDeviceId, "192.168.1.100", 8443, validFingerprint)
        val req = EnrollmentRequestDto(inv.enrollmentToken, householdId, mainDeviceId, UUID.randomUUID().toString(), "Follower A", rsaPubKeyBase64)
        val res = invitationManager.processEnrollment(req, householdId, mainDeviceId)

        assertFalse("RSA public key MUST be rejected (only EC P-256 allowed)", res.success)
        assertEquals("INVALID_PUBLIC_KEY", res.errorCode)
    }

    @Test
    fun test4C_malformedBase64_rejected() = runTest {
        val inv = invitationManager.createInvitation(householdId, mainDeviceId, "192.168.1.100", 8443, validFingerprint)
        val req = EnrollmentRequestDto(inv.enrollmentToken, householdId, mainDeviceId, UUID.randomUUID().toString(), "Follower A", "!!!not_base64!!!")
        val res = invitationManager.processEnrollment(req, householdId, mainDeviceId)

        assertFalse("Malformed Base64 MUST be rejected", res.success)
        assertEquals("INVALID_PUBLIC_KEY", res.errorCode)
    }

    @Test
    fun test4D_validP256FollowerKey_accepted() = runTest {
        val inv = invitationManager.createInvitation(householdId, mainDeviceId, "192.168.1.100", 8443, validFingerprint)
        val ecPubKey = keyStoreManager.getPublicKeyBase64("valid_p256_key")!!

        val req = EnrollmentRequestDto(inv.enrollmentToken, householdId, mainDeviceId, UUID.randomUUID().toString(), "Follower A", ecPubKey)
        val res = invitationManager.processEnrollment(req, householdId, mainDeviceId)

        assertTrue("Valid EC P-256 public key MUST be accepted", res.success)
    }

    @Test
    fun test4E_nonP256EcCurve_rejected() = runTest {
        val kpg384 = KeyPairGenerator.getInstance("EC")
        kpg384.initialize(java.security.spec.ECGenParameterSpec("secp384r1"))
        val pair384 = kpg384.generateKeyPair()
        val pubKey384Base64 = Base64Utils.encodeToString(pair384.public.encoded)

        val inv = invitationManager.createInvitation(householdId, mainDeviceId, "192.168.1.100", 8443, validFingerprint)
        val req = EnrollmentRequestDto(inv.enrollmentToken, householdId, mainDeviceId, UUID.randomUUID().toString(), "Follower A", pubKey384Base64)
        val res = invitationManager.processEnrollment(req, householdId, mainDeviceId)

        assertFalse("secp384r1 EC key MUST be rejected (only secp256r1/P-256 allowed)", res.success)
        assertEquals("INVALID_PUBLIC_KEY", res.errorCode)
    }

    // FOLLOWER PAIRING STORE Write Failure Test
    @Test
    fun test5A_pairingStoreWriteFailure_failsClosed() = runTest {
        val failingStore = FakePairingStore().apply { shouldFailSave = true }
        val client = DirectEnrollmentClient(invitationManager, householdId, mainDeviceId, keyStoreManager)
        val orchestrator = FollowerEnrollmentOrchestrator(null, identityStore, keyStoreManager, failingStore, client, FollowerSyncScheduler())

        val inv = invitationManager.createInvitation(householdId, mainDeviceId, "192.168.1.100", 8443, validFingerprint)
        val res = orchestrator.processQrEnrollment(json.encodeToString(inv))

        assertFalse("Orchestrator MUST fail closed if pairing store write fails", res.success)
        assertEquals("PAIRING_STORE_PERSISTENCE_FAILED", res.errorCode)
    }

    // Additional original tests
    @Test
    fun test3_concurrentSameTokenEnrollment_atMostOneSuccess() = runTest {
        val inv = invitationManager.createInvitation(householdId, mainDeviceId, "192.168.1.100", 8443, validFingerprint)

        val req1 = EnrollmentRequestDto(inv.enrollmentToken, householdId, mainDeviceId, UUID.randomUUID().toString(), "Follower 1", keyStoreManager.getPublicKeyBase64("key_1")!!)
        val req2 = EnrollmentRequestDto(inv.enrollmentToken, householdId, mainDeviceId, UUID.randomUUID().toString(), "Follower 2", keyStoreManager.getPublicKeyBase64("key_2")!!)

        val job1 = async(Dispatchers.IO) { invitationManager.processEnrollment(req1, householdId, mainDeviceId) }
        val job2 = async(Dispatchers.IO) { invitationManager.processEnrollment(req2, householdId, mainDeviceId) }

        val res1 = job1.await()
        val res2 = job2.await()

        val successCount = listOf(res1, res2).count { it.success }
        assertEquals("At most one concurrent enrollment request MUST succeed", 1, successCount)
    }

    @Test
    fun test14_successfulEnrollment_persistsCorrectFollowerPairingConfig() = runTest {
        val store = FakePairingStore()
        val client = DirectEnrollmentClient(invitationManager, householdId, mainDeviceId, keyStoreManager)
        val orchestrator = FollowerEnrollmentOrchestrator(null, identityStore, keyStoreManager, store, client, FollowerSyncScheduler())

        val inv = invitationManager.createInvitation(householdId, mainDeviceId, "192.168.1.100", 8443, validFingerprint)
        val qrJson = json.encodeToString(inv)

        val res = orchestrator.processQrEnrollment(qrJson)
        assertTrue(res.success)

        val saved = store.getPairingConfig()
        assertNotNull(saved)
        assertEquals("192.168.1.100", saved?.hostIp)
        assertEquals(8443, saved?.port)
        assertEquals(validFingerprint, saved?.pinnedCertFingerprint)
        assertNotNull(saved?.deviceId)
    }

    @Test
    fun test20_phase2bAuth_worksImmediatelyUsingNewlyEnrolledPublicKey() = runTest {
        val inv = invitationManager.createInvitation(householdId, mainDeviceId, "192.168.1.100", 8443, validFingerprint)
        val followerDevId = UUID.randomUUID().toString()
        val pubKey = keyStoreManager.getPublicKeyBase64("follower_alias_20")!!

        val req = EnrollmentRequestDto(inv.enrollmentToken, householdId, mainDeviceId, followerDevId, "Follower 20", pubKey)

        val resEnroll = invitationManager.processEnrollment(req, householdId, mainDeviceId)
        assertTrue(resEnroll.success)

        // Immediately execute Phase 2B Auth Challenge & Session
        val challenge = sessionManager.createChallenge(householdId, followerDevId)
        val canonicalBytes = sessionManager.buildCanonicalChallengeBytes(householdId, followerDevId, challenge.challengeId, challenge.nonce)

        val signatureBase64 = keyStoreManager.signChallenge("follower_alias_20", canonicalBytes)

        val sessionToken = sessionManager.verifyChallengeAndCreateSession(challenge.challengeId, followerDevId, signatureBase64)
        assertNotNull("Phase 2B session token MUST be created immediately for newly enrolled follower", sessionToken)

        val validatedDevId = sessionManager.validateSessionToken(sessionToken)
        assertEquals(followerDevId, validatedDevId)
    }
}
