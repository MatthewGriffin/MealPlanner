package com.example.chatgpt_mealplanner.core.backup

import android.content.Context
import android.net.Uri
import com.example.chatgpt_mealplanner.core.database.DatabaseTransactionRunner
import com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

@RunWith(JUnit4::class)
class PantryPhase6Step6aSafWriterTest {

    private lateinit var encoderDecoder: MainBackupContainerEncoderDecoder
    private lateinit var codec: MainBackupEncryptionCodec

    @Before
    fun setUp() {
        encoderDecoder = MainBackupContainerEncoderDecoder()
        codec = MainBackupEncryptionCodec(encoderDecoder)
    }

    private val dummyUri: Uri = android.net.FakeUri()

    private class FakeAppRoleResolver(private val isMainRole: Boolean) : AppRoleResolver(
        com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore(null)
    ) {
        override suspend fun isMainAsync(): Boolean = isMainRole
        override fun isMain(): Boolean = isMainRole
        override suspend fun isFollowerAsync(): Boolean = !isMainRole
        override fun isFollower(): Boolean = !isMainRole
    }

    private class FakeSafDocumentStreamProvider(
        private val outputStreamProvider: () -> OutputStream?,
        private val inputStreamProvider: () -> InputStream?
    ) : SafDocumentStreamProvider {
        override fun openOutputStream(appContext: Context?, uri: Uri?): OutputStream? = outputStreamProvider()
        override fun openInputStream(appContext: Context?, uri: Uri?): InputStream? = inputStreamProvider()
    }

    private fun createSampleSnapshot(): MainHouseholdBackupDto = MainHouseholdBackupDto(
        exportedAt = 1791890000000L,
        householdId = "house-saf-100",
        householdName = "SAF Writer Test Household",
        mainDeviceId = "main-dev-1",
        nextCanonicalSequence = 42L,
        inventory = listOf(
            InventoryItemExportDto(
                id = "item-saf-1",
                barcode = "123456789",
                canonicalIngredientId = "ING_OIL",
                canonicalName = "olive oil",
                displayName = "Extra Virgin Olive Oil",
                brand = "Asda",
                category = "CUPBOARD",
                storageLocation = "CUPBOARD",
                quantity = 3.5,
                quantityUnit = "LITRE",
                packCount = 1,
                packSize = 1.0,
                packSizeUnit = "LITRE",
                quantityConfidence = "EXACT",
                status = "AVAILABLE",
                opened = true,
                frozen = false,
                staple = true,
                purchaseDate = "2026-10-01",
                openedDate = "2026-10-02",
                useByDate = "2027-10-01",
                bestBeforeDate = null,
                notes = "SAF test",
                createdAt = 1000L,
                updatedAt = 2000L,
                revision = 5L
            )
        )
    )

    @Test
    fun executeMainBackupSave_mainRoleAndValidUri_writesAndVerifiesEncryptedBackup() = runTest {
        val noOpRunner = object : DatabaseTransactionRunner {
            override suspend fun <T> runInTransaction(block: suspend () -> T): T = block()
        }
        val snapshotBuilder = object : MainBackupSnapshotBuilder(null, noOpRunner) {
            override suspend fun createMainBackupSnapshot(currentTimeMs: Long): MainHouseholdBackupDto {
                return createSampleSnapshot().copy(exportedAt = currentTimeMs)
            }
        }
        val roleResolver = FakeAppRoleResolver(isMainRole = true)

        val writtenBytes = ByteArrayOutputStream()
        val fakeStreamProvider = FakeSafDocumentStreamProvider(
            outputStreamProvider = { writtenBytes },
            inputStreamProvider = { ByteArrayInputStream(writtenBytes.toByteArray()) }
        )
        val safWriter = MainEncryptedBackupSafWriter(fakeStreamProvider)
        val coordinator = MainEncryptedBackupSaveCoordinator(roleResolver, snapshotBuilder, codec, safWriter)

        val passphrase = "PantryMasterPassword#2026!".toCharArray()
        val outcome = coordinator.executeMainBackupSave(null, dummyUri, passphrase, currentTimeMs = 2000000L)

        assertTrue("Outcome MUST be VerifiedSuccess", outcome is SafBackupOutcome.VerifiedSuccess)
        val snapshot = (outcome as SafBackupOutcome.VerifiedSuccess).snapshot
        assertEquals("pantry-household-backup-v1", snapshot.backupFormatVersion)
        assertEquals("house-saf-100", snapshot.householdId)
        assertTrue("Written encrypted bytes MUST be non-empty", writtenBytes.size() > 0)
    }

    @Test
    fun executeMainBackupSave_followerRole_rejectsBeforeSnapshotOrWrite() = runTest {
        var snapshotCalled = false
        val snapshotBuilder = object : MainBackupSnapshotBuilder(null, object : DatabaseTransactionRunner {
            override suspend fun <T> runInTransaction(block: suspend () -> T): T = block()
        }) {
            override suspend fun createMainBackupSnapshot(currentTimeMs: Long): MainHouseholdBackupDto {
                snapshotCalled = true
                return createSampleSnapshot()
            }
        }
        val roleResolver = FakeAppRoleResolver(isMainRole = false)
        val fakeStreamProvider = FakeSafDocumentStreamProvider(
            outputStreamProvider = { error("") },
            inputStreamProvider = { error("") }
        )
        val safWriter = MainEncryptedBackupSafWriter(fakeStreamProvider)
        val coordinator = MainEncryptedBackupSaveCoordinator(roleResolver, snapshotBuilder, codec, safWriter)

        val outcome = coordinator.executeMainBackupSave(null, dummyUri, "Pass123!".toCharArray())

        assertTrue("Follower role MUST return NoDocumentIoAttempted", outcome is SafBackupOutcome.NoDocumentIoAttempted)
        val causeMsg = (outcome as SafBackupOutcome.NoDocumentIoAttempted).cause.cause
        assertTrue("Cause MUST mention Main device only", causeMsg.contains("Main device only"))
        assertFalse("Snapshot builder MUST NOT be called on Follower role", snapshotCalled)
    }

    @Test
    fun executeMainBackupSave_nullUri_returnsCancelledErrorWithoutWrite() = runTest {
        val noOpRunner = object : DatabaseTransactionRunner {
            override suspend fun <T> runInTransaction(block: suspend () -> T): T = block()
        }
        val snapshotBuilder = MainBackupSnapshotBuilder(null, noOpRunner)
        val roleResolver = FakeAppRoleResolver(isMainRole = true)
        val fakeStreamProvider = FakeSafDocumentStreamProvider(
            outputStreamProvider = { error("") },
            inputStreamProvider = { error("") }
        )
        val safWriter = MainEncryptedBackupSafWriter(fakeStreamProvider)
        val coordinator = MainEncryptedBackupSaveCoordinator(roleResolver, snapshotBuilder, codec, safWriter)

        val outcome = coordinator.executeMainBackupSave(null, null, "Pass123!".toCharArray())

        assertTrue("Null URI MUST return NoDocumentIoAttempted", outcome is SafBackupOutcome.NoDocumentIoAttempted)
        val causeMsg = (outcome as SafBackupOutcome.NoDocumentIoAttempted).cause.cause
        assertTrue("Cause MUST mention backup cancelled", causeMsg.contains("cancelled"))
    }

    @Test
    fun executeMainBackupSave_emptyPassphrase_rejectsWithPassphraseError() = runTest {
        val noOpRunner = object : DatabaseTransactionRunner {
            override suspend fun <T> runInTransaction(block: suspend () -> T): T = block()
        }
        val snapshotBuilder = MainBackupSnapshotBuilder(null, noOpRunner)
        val roleResolver = FakeAppRoleResolver(isMainRole = true)
        val fakeStreamProvider = FakeSafDocumentStreamProvider(
            outputStreamProvider = { error("") },
            inputStreamProvider = { error("") }
        )
        val safWriter = MainEncryptedBackupSafWriter(fakeStreamProvider)
        val coordinator = MainEncryptedBackupSaveCoordinator(roleResolver, snapshotBuilder, codec, safWriter)

        val outcome = coordinator.executeMainBackupSave(null, dummyUri, "".toCharArray())

        assertTrue("Empty passphrase MUST return NoDocumentIoAttempted", outcome is SafBackupOutcome.NoDocumentIoAttempted)
        val causeMsg = (outcome as SafBackupOutcome.NoDocumentIoAttempted).cause.cause
        assertTrue("Cause MUST mention passphrase", causeMsg.contains("passphrase"))
    }

    @Test
    fun writeAndVerifyEncryptedBackup_mismatchedReadbackBytes_failsReadbackVerification() = runTest {
        val snapshot = createSampleSnapshot()
        val passphrase = "Pass123!".toCharArray()
        val encryptedBytes = codec.encodeEncryptedBackup(snapshot, passphrase, currentTimeMs = 2000000L)

        // Simulate corrupted readback stream returning altered bytes
        val corruptedReadbackBytes = encryptedBytes.clone()
        corruptedReadbackBytes[corruptedReadbackBytes.size - 1] = (corruptedReadbackBytes[corruptedReadbackBytes.size - 1] + 1).toByte()

        val fakeStreamProvider = FakeSafDocumentStreamProvider(
            outputStreamProvider = { ByteArrayOutputStream() },
            inputStreamProvider = { ByteArrayInputStream(corruptedReadbackBytes) }
        )
        val customSafWriter = MainEncryptedBackupSafWriter(fakeStreamProvider)

        val outcome = customSafWriter.writeAndVerifyEncryptedBackup(null, dummyUri, encryptedBytes, passphrase, codec)

        assertTrue("Mismatched readback bytes MUST return DocumentIoAttemptedUnverified", outcome is SafBackupOutcome.DocumentIoAttemptedUnverified)
        val causeMsg = (outcome as SafBackupOutcome.DocumentIoAttemptedUnverified).cause.cause
        assertTrue("Cause MUST mention byte mismatch", causeMsg.contains("mismatch"))
        assertEquals(dummyUri, outcome.destinationUri)
    }

    @Test
    fun writeAndVerifyEncryptedBackup_failingOutputStream_reportsWriteError() = runTest {
        val snapshot = createSampleSnapshot()
        val passphrase = "Pass123!".toCharArray()
        val encryptedBytes = codec.encodeEncryptedBackup(snapshot, passphrase, currentTimeMs = 2000000L)

        val fakeStreamProvider = FakeSafDocumentStreamProvider(
            outputStreamProvider = { throw java.io.IOException("SAF Provider write permission denied") },
            inputStreamProvider = { error("") }
        )
        val customSafWriter = MainEncryptedBackupSafWriter(fakeStreamProvider)

        val outcome = customSafWriter.writeAndVerifyEncryptedBackup(null, dummyUri, encryptedBytes, passphrase, codec)

        assertTrue("Failing output stream MUST return DocumentIoAttemptedUnverified", outcome is SafBackupOutcome.DocumentIoAttemptedUnverified)
        val causeMsg = (outcome as SafBackupOutcome.DocumentIoAttemptedUnverified).cause.cause
        assertTrue("Cause MUST mention write failure", causeMsg.contains("Write failed") || causeMsg.contains("permission denied"))
        assertEquals(dummyUri, outcome.destinationUri)
    }

    @Test
    fun executeMainBackupSave_roleResolverThrowsException_failsClosed() = runTest {
        val snapshotBuilder = MainBackupSnapshotBuilder(null, object : DatabaseTransactionRunner {
            override suspend fun <T> runInTransaction(block: suspend () -> T): T = block()
        })
        val failingRoleResolver = object : AppRoleResolver(
            com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore(null)
        ) {
            override fun isMain(): Boolean = throw IllegalStateException("Role store unreadable")
        }
        val fakeStreamProvider = FakeSafDocumentStreamProvider(
            outputStreamProvider = { error("") },
            inputStreamProvider = { error("") }
        )
        val safWriter = MainEncryptedBackupSafWriter(fakeStreamProvider)
        val coordinator = MainEncryptedBackupSaveCoordinator(failingRoleResolver, snapshotBuilder, codec, safWriter)

        val outcome = coordinator.executeMainBackupSave(null, dummyUri, "Pass123!".toCharArray())

        assertTrue("Failing role resolver MUST return NoDocumentIoAttempted", outcome is SafBackupOutcome.NoDocumentIoAttempted)
        val causeMsg = (outcome as SafBackupOutcome.NoDocumentIoAttempted).cause.cause
        assertTrue("Cause MUST mention Main device only", causeMsg.contains("Main device only"))
    }

    @Test
    fun writeAndVerifyEncryptedBackup_nullOutputStream_reportsUnableToOpenDocumentError() = runTest {
        val snapshot = createSampleSnapshot()
        val passphrase = "Pass123!".toCharArray()
        val encryptedBytes = codec.encodeEncryptedBackup(snapshot, passphrase, currentTimeMs = 2000000L)

        val fakeStreamProvider = FakeSafDocumentStreamProvider(
            outputStreamProvider = { null },
            inputStreamProvider = { error("") }
        )
        val customSafWriter = MainEncryptedBackupSafWriter(fakeStreamProvider)

        val outcome = customSafWriter.writeAndVerifyEncryptedBackup(null, dummyUri, encryptedBytes, passphrase, codec)

        assertTrue("Null output stream MUST return DocumentIoAttemptedUnverified", outcome is SafBackupOutcome.DocumentIoAttemptedUnverified)
        val causeMsg = (outcome as SafBackupOutcome.DocumentIoAttemptedUnverified).cause.cause
        assertTrue("Error MUST mention unable to open selected document", causeMsg.contains("Unable to open"))
    }

    @Test
    fun writeAndVerifyEncryptedBackup_nullInputStream_reportsReadbackOpenError() = runTest {
        val snapshot = createSampleSnapshot()
        val passphrase = "Pass123!".toCharArray()
        val encryptedBytes = codec.encodeEncryptedBackup(snapshot, passphrase, currentTimeMs = 2000000L)

        val fakeStreamProvider = FakeSafDocumentStreamProvider(
            outputStreamProvider = { ByteArrayOutputStream() },
            inputStreamProvider = { null }
        )
        val customSafWriter = MainEncryptedBackupSafWriter(fakeStreamProvider)

        val outcome = customSafWriter.writeAndVerifyEncryptedBackup(null, dummyUri, encryptedBytes, passphrase, codec)

        assertTrue("Null input stream MUST return DocumentIoAttemptedUnverified", outcome is SafBackupOutcome.DocumentIoAttemptedUnverified)
        val causeMsg = (outcome as SafBackupOutcome.DocumentIoAttemptedUnverified).cause.cause
        assertTrue("Error MUST mention document could not be opened for readback", causeMsg.contains("could not be opened"))
    }

    @Test
    fun writeAndVerifyEncryptedBackup_inputStreamThrowsOnRead_reportsReadbackError() = runTest {
        val snapshot = createSampleSnapshot()
        val passphrase = "Pass123!".toCharArray()
        val encryptedBytes = codec.encodeEncryptedBackup(snapshot, passphrase, currentTimeMs = 2000000L)

        val failingInputStream = object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("SAF Provider read storage unmounted")
        }

        val fakeStreamProvider = FakeSafDocumentStreamProvider(
            outputStreamProvider = { ByteArrayOutputStream() },
            inputStreamProvider = { failingInputStream }
        )
        val customSafWriter = MainEncryptedBackupSafWriter(fakeStreamProvider)

        val outcome = customSafWriter.writeAndVerifyEncryptedBackup(null, dummyUri, encryptedBytes, passphrase, codec)

        assertTrue("Failing input stream MUST return DocumentIoAttemptedUnverified", outcome is SafBackupOutcome.DocumentIoAttemptedUnverified)
        val causeMsg = (outcome as SafBackupOutcome.DocumentIoAttemptedUnverified).cause.cause
        assertTrue("Error MUST mention readback verification failed", causeMsg.contains("readback verification failed"))
    }

    @Test
    fun writeAndVerifyEncryptedBackup_oversizedReadbackStream_rejectsExceedingLimitError() = runTest {
        val snapshot = createSampleSnapshot()
        val passphrase = "Pass123!".toCharArray()
        val encryptedBytes = codec.encodeEncryptedBackup(snapshot, passphrase, currentTimeMs = 2000000L)

        val oversizedInputStream = object : java.io.InputStream() {
            private var bytesProduced = 0
            override fun read(): Int {
                if (bytesProduced > MAX_ENCRYPTED_CONTAINER_SIZE_BYTES + 100) return -1
                bytesProduced++
                return 0x55
            }
        }

        val fakeStreamProvider = FakeSafDocumentStreamProvider(
            outputStreamProvider = { ByteArrayOutputStream() },
            inputStreamProvider = { oversizedInputStream }
        )
        val customSafWriter = MainEncryptedBackupSafWriter(fakeStreamProvider)

        val outcome = customSafWriter.writeAndVerifyEncryptedBackup(null, dummyUri, encryptedBytes, passphrase, codec)

        assertTrue("Oversized readback stream MUST return DocumentIoAttemptedUnverified", outcome is SafBackupOutcome.DocumentIoAttemptedUnverified)
        val causeMsg = (outcome as SafBackupOutcome.DocumentIoAttemptedUnverified).cause.cause
        assertTrue("Error MUST mention size exceeds maximum allowed limit", causeMsg.contains("exceeds maximum allowed limit"))
    }

    @Test
    fun executeMainBackupSave_preIoFailure_explicitlyIdentifiesNoIoAttempted() = runTest {
        val snapshotBuilder = MainBackupSnapshotBuilder(null, object : DatabaseTransactionRunner {
            override suspend fun <T> runInTransaction(block: suspend () -> T): T = block()
        })
        val roleResolver = FakeAppRoleResolver(isMainRole = false)
        val fakeStreamProvider = FakeSafDocumentStreamProvider(
            outputStreamProvider = { error("") },
            inputStreamProvider = { error("") }
        )
        val safWriter = MainEncryptedBackupSafWriter(fakeStreamProvider)
        val coordinator = MainEncryptedBackupSaveCoordinator(roleResolver, snapshotBuilder, codec, safWriter)

        val outcome = coordinator.executeMainBackupSave(null, dummyUri, "Pass123!".toCharArray())

        assertTrue("Pre-I/O failure MUST return SafBackupOutcome.NoDocumentIoAttempted", outcome is SafBackupOutcome.NoDocumentIoAttempted)
        val causeMsg = (outcome as SafBackupOutcome.NoDocumentIoAttempted).cause.cause
        assertTrue("Cause MUST mention No document I/O attempted", causeMsg.startsWith("No document I/O attempted"))
    }

    @Test
    fun writeAndVerifyEncryptedBackup_postIoFailure_explicitlyIdentifiesSafIoAttemptedUnverified() = runTest {
        val snapshot = createSampleSnapshot()
        val passphrase = "Pass123!".toCharArray()
        val encryptedBytes = codec.encodeEncryptedBackup(snapshot, passphrase, currentTimeMs = 2000000L)

        val fakeStreamProvider = FakeSafDocumentStreamProvider(
            outputStreamProvider = { throw java.io.IOException("Permission denied") },
            inputStreamProvider = { error("") }
        )
        val customSafWriter = MainEncryptedBackupSafWriter(fakeStreamProvider)

        val outcome = customSafWriter.writeAndVerifyEncryptedBackup(null, dummyUri, encryptedBytes, passphrase, codec)

        assertTrue("Post-I/O failure MUST return SafBackupOutcome.DocumentIoAttemptedUnverified", outcome is SafBackupOutcome.DocumentIoAttemptedUnverified)
        val causeMsg = (outcome as SafBackupOutcome.DocumentIoAttemptedUnverified).cause.cause
        assertTrue("Cause MUST mention SAF Document I/O attempted (unverified)", causeMsg.startsWith("SAF Document I/O attempted (unverified)"))
        assertEquals(dummyUri, outcome.destinationUri)
    }
}
