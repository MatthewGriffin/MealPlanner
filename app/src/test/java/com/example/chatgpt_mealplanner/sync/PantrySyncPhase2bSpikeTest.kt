package com.example.chatgpt_mealplanner.sync

import android.content.Context
import com.example.chatgpt_mealplanner.core.database.dao.PairedDeviceDao
import com.example.chatgpt_mealplanner.core.database.entity.PairedDeviceEntity
import com.example.chatgpt_mealplanner.core.security.AndroidKeyStoreManager
import com.example.chatgpt_mealplanner.core.sync.security.PantryTlsKeyManager
import com.example.chatgpt_mealplanner.core.sync.server.SyncSessionManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito.mock
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class FakeSyncPairedDeviceDao : PairedDeviceDao {
    private val devicesMap = mutableMapOf<String, PairedDeviceEntity>()

    override suspend fun getDeviceById(deviceId: String): PairedDeviceEntity? = devicesMap[deviceId]

    override suspend fun getAllDevices(): List<PairedDeviceEntity> = devicesMap.values.toList()

    override suspend fun insertOrUpdateDevice(device: PairedDeviceEntity) {
        devicesMap[device.deviceId] = device
    }

    override suspend fun updateDeviceStatus(deviceId: String, status: String) {
        devicesMap[deviceId]?.let {
            devicesMap[deviceId] = it.copy(status = status)
        }
    }

    override suspend fun updateDeviceStatusConditional(deviceId: String, newStatus: String, expectedCurrentStatus: String): Int {
        val dev = devicesMap[deviceId] ?: return 0
        if (dev.status == expectedCurrentStatus) {
            devicesMap[deviceId] = dev.copy(status = newStatus)
            return 1
        }
        return 0
    }

    override suspend fun revokeDeviceIfActive(deviceId: String): Int {
        val dev = devicesMap[deviceId] ?: return 0
        if (dev.status == "ACTIVE") {
            devicesMap[deviceId] = dev.copy(status = "REVOKED")
            return 1
        }
        return 0
    }
}

@RunWith(JUnit4::class)
class PantrySyncPhase2bSpikeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var pairedDeviceDao: FakeSyncPairedDeviceDao
    private lateinit var keyStoreManager: AndroidKeyStoreManager
    private lateinit var sessionManager: SyncSessionManager
    private lateinit var tlsKeyManager: PantryTlsKeyManager

    @Before
    fun setUp() {
        pairedDeviceDao = FakeSyncPairedDeviceDao()
        keyStoreManager = AndroidKeyStoreManager()
        sessionManager = SyncSessionManager(keyStoreManager, pairedDeviceDao)
        tlsKeyManager = PantryTlsKeyManager(null)
    }

    @Test
    fun canonicalChallengeEncoding_isDeterministic() {
        val canonicalBytes = sessionManager.buildCanonicalChallengeBytes(
            householdId = "HH_123",
            deviceId = "DEV_456",
            challengeId = "CH_789",
            nonce = "NONCE_000"
        )

        val canonicalStr = String(canonicalBytes, Charsets.UTF_8)
        assertEquals("pantry-household-sync|1|HH_123|DEV_456|CH_789|NONCE_000", canonicalStr)
    }

    @Test
    fun sessionValidation_activeDeviceSucceeds_revokedDeviceFails() = runTest {
        val keyPairGen = KeyPairGenerator.getInstance("EC")
        keyPairGen.initialize(256)
        val keyPair = keyPairGen.generateKeyPair()

        val pubKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)

        pairedDeviceDao.insertOrUpdateDevice(
            PairedDeviceEntity(
                deviceId = "dev_spike_1",
                deviceName = "Test Device",
                devicePublicKey = pubKeyBase64,
                status = "ACTIVE",
                pairedAt = System.currentTimeMillis(),
                lastSyncTimestamp = System.currentTimeMillis()
            )
        )

        val challenge = sessionManager.createChallenge("HH_SPIKE", "dev_spike_1")

        val canonicalBytes = sessionManager.buildCanonicalChallengeBytes(
            householdId = "HH_SPIKE",
            deviceId = "dev_spike_1",
            challengeId = challenge.challengeId,
            nonce = challenge.nonce
        )

        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(canonicalBytes)
        val signatureBytes = signer.sign()
        val signatureBase64 = Base64.getEncoder().encodeToString(signatureBytes)

        val token = sessionManager.verifyChallengeAndCreateSession(
            challengeId = challenge.challengeId,
            deviceId = "dev_spike_1",
            signatureBase64 = signatureBase64
        )

        assertNotNull("Session token must be issued for valid active device", token)

        val authenticatedDeviceId = sessionManager.validateSessionToken(token)
        assertEquals("dev_spike_1", authenticatedDeviceId)

        pairedDeviceDao.updateDeviceStatus("dev_spike_1", "REVOKED")

        val revokedDeviceId = sessionManager.validateSessionToken(token)
        assertNull("Session validation MUST return null for revoked device", revokedDeviceId)
    }

    @Test
    fun tlsFingerprintCalculation_isFormattedCorrectly() {
        val testBytes = "pantry_cert_bytes_test".toByteArray()
        val fingerprint = tlsKeyManager.computeSha256Fingerprint(testBytes)

        assertTrue("Fingerprint must start with SHA256:", fingerprint.startsWith("SHA256:"))
        assertTrue("Fingerprint must contain hex colons", fingerprint.contains(":"))
    }

    @Test
    fun fingerprintMatching_isFormatAndCaseInsensitive() {
        val fp1 = "SHA256:AA:BB:CC:DD"
        val fp2 = "sha256:aa:bb:cc:dd"
        val fp3 = "AABBCCDD"
        val fp4 = "SHA256:11:22:33:44"

        assertTrue("Matching fingerprints must pass", tlsKeyManager.isFingerprintMatching(fp1, fp2))
        assertTrue("Fingerprints without prefix must pass", tlsKeyManager.isFingerprintMatching(fp1, fp3))
        assertFalse("Mismatched fingerprints must fail", tlsKeyManager.isFingerprintMatching(fp1, fp4))
    }

    @Test
    fun persistentTlsKeyStore_reloadsSameCertificate_andFingerprintIsIdentical() {
        val testDir = tempFolder.newFolder("tls_test_dir")
        val ks1 = tlsKeyManager.getOrCreateMainTlsKeyStore("HH_TEST_RELOAD", testDir)
        val fp1 = tlsKeyManager.getCertificateFingerprint(ks1)

        val ks2 = tlsKeyManager.getOrCreateMainTlsKeyStore("HH_TEST_RELOAD", testDir)
        val fp2 = tlsKeyManager.getCertificateFingerprint(ks2)

        assertEquals("Main TLS certificate fingerprint MUST remain 100% identical on reload", fp1, fp2)
    }

    @Test
    fun persistentTlsKeyStore_corruptFile_failsClosedWithoutGeneratingNewCert() {
        val testDir = tempFolder.newFolder("tls_corrupt_dir")
        val corruptFile = File(testDir, "pantry_tls_keystore.p12")
        corruptFile.writeText("corrupted_garbage_bytes")

        try {
            tlsKeyManager.getOrCreateMainTlsKeyStore("HH_TEST_CORRUPT", testDir)
            fail("Corrupt KeyStore file MUST fail closed")
        } catch (e: SecurityException) {
            assertTrue("SecurityException must indicate failed load", e.message?.contains("Refusing to generate a new certificate") == true)
        }
    }
}
