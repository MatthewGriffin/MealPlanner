package com.example.chatgpt_mealplanner.core.backup

import android.content.Context
import android.net.Uri
import com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

@RunWith(JUnit4::class)
class PantryPhase6Step6aSafWriterTest {

    private val dummyUri: Uri = android.net.FakeUri()
    private val encryptedBytes = ByteArray(256) { it.toByte() }

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

    private fun writerThatMustNotBeUsed() = MainEncryptedBackupSafWriter(
        FakeSafDocumentStreamProvider(outputStreamProvider = { error("") }, inputStreamProvider = { error("") })
    )

    private fun managerReturning(bytes: ByteArray): FullBackupManager {
        val manager: FullBackupManager = mock()
        whenever(manager.createEncryptedBackup(any(), any())).thenReturn(bytes)
        whenever(manager.verifyEncryptedBackup(any(), any())).thenReturn(true)
        return manager
    }

    private fun unverifiedCause(outcome: SafBackupOutcome): String {
        assertTrue("Outcome MUST be DocumentIoAttemptedUnverified", outcome is SafBackupOutcome.DocumentIoAttemptedUnverified)
        val unverified = outcome as SafBackupOutcome.DocumentIoAttemptedUnverified
        assertEquals(dummyUri, unverified.destinationUri)
        assertTrue(
            "Cause MUST mention SAF Document I/O attempted (unverified)",
            unverified.cause.cause.startsWith("SAF Document I/O attempted (unverified)")
        )
        return unverified.cause.cause
    }

    private fun noIoCause(outcome: SafBackupOutcome): String {
        assertTrue("Outcome MUST be NoDocumentIoAttempted", outcome is SafBackupOutcome.NoDocumentIoAttempted)
        val cause = (outcome as SafBackupOutcome.NoDocumentIoAttempted).cause.cause
        assertTrue("Cause MUST mention No document I/O attempted", cause.startsWith("No document I/O attempted"))
        return cause
    }

    private suspend fun write(provider: SafDocumentStreamProvider, verify: (ByteArray) -> Boolean = { true }) =
        MainEncryptedBackupSafWriter(provider).writeAndVerifyFullBackup(null, dummyUri, encryptedBytes, verify)

    @Test
    fun executeMainBackupSave_mainRoleAndValidUri_writesAndVerifiesFullBackup() = runTest {
        val writtenBytes = ByteArrayOutputStream()
        val writer = MainEncryptedBackupSafWriter(
            FakeSafDocumentStreamProvider(
                outputStreamProvider = { writtenBytes },
                inputStreamProvider = { ByteArrayInputStream(writtenBytes.toByteArray()) }
            )
        )
        val manager = managerReturning(encryptedBytes)
        val coordinator = MainEncryptedBackupSaveCoordinator(FakeAppRoleResolver(isMainRole = true), writer, manager)
        val passphrase = "PantryMasterPassword#2026!".toCharArray()

        val outcome = coordinator.executeMainBackupSave(null, dummyUri, passphrase, currentTimeMs = 2000000L)

        assertEquals(SafBackupOutcome.VerifiedFullBackup, outcome)
        assertArrayEquals(encryptedBytes, writtenBytes.toByteArray())
        verify(manager).verifyEncryptedBackup(encryptedBytes, passphrase)
    }

    @Test
    fun executeMainBackupSave_followerRole_rejectsBeforeBackupOrWrite() = runTest {
        val manager = managerReturning(encryptedBytes)
        val coordinator = MainEncryptedBackupSaveCoordinator(FakeAppRoleResolver(isMainRole = false), writerThatMustNotBeUsed(), manager)

        val outcome = coordinator.executeMainBackupSave(null, dummyUri, "Pass123!".toCharArray())

        assertTrue("Cause MUST mention Main device only", noIoCause(outcome).contains("Main device only"))
        verify(manager, never()).createEncryptedBackup(any(), any())
    }

    @Test
    fun executeMainBackupSave_nullUri_returnsCancelledErrorWithoutWrite() = runTest {
        val coordinator = MainEncryptedBackupSaveCoordinator(
            FakeAppRoleResolver(isMainRole = true), writerThatMustNotBeUsed(), managerReturning(encryptedBytes)
        )

        val outcome = coordinator.executeMainBackupSave(null, null, "Pass123!".toCharArray())

        assertTrue("Cause MUST mention backup cancelled", noIoCause(outcome).contains("cancelled"))
    }

    @Test
    fun executeMainBackupSave_emptyPassphrase_rejectsWithPassphraseError() = runTest {
        val coordinator = MainEncryptedBackupSaveCoordinator(
            FakeAppRoleResolver(isMainRole = true), writerThatMustNotBeUsed(), managerReturning(encryptedBytes)
        )

        val outcome = coordinator.executeMainBackupSave(null, dummyUri, "".toCharArray())

        assertTrue("Cause MUST mention passphrase", noIoCause(outcome).contains("passphrase"))
    }

    @Test
    fun executeMainBackupSave_roleResolverThrowsException_failsClosed() = runTest {
        val failingRoleResolver = object : AppRoleResolver(
            com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore(null)
        ) {
            override fun isMain(): Boolean = throw IllegalStateException("Role store unreadable")
        }
        val coordinator = MainEncryptedBackupSaveCoordinator(
            failingRoleResolver, writerThatMustNotBeUsed(), managerReturning(encryptedBytes)
        )

        val outcome = coordinator.executeMainBackupSave(null, dummyUri, "Pass123!".toCharArray())

        assertTrue("Cause MUST mention Main device only", noIoCause(outcome).contains("Main device only"))
    }

    @Test
    fun executeMainBackupSave_backupCreationFails_reportsNoIoAttempted() = runTest {
        val manager: FullBackupManager = mock()
        whenever(manager.createEncryptedBackup(any(), any())).thenThrow(IllegalStateException("Database unreadable"))
        val coordinator = MainEncryptedBackupSaveCoordinator(FakeAppRoleResolver(isMainRole = true), writerThatMustNotBeUsed(), manager)

        val outcome = coordinator.executeMainBackupSave(null, dummyUri, "Pass123!".toCharArray())

        assertTrue("Cause MUST carry the failure", noIoCause(outcome).contains("Database unreadable"))
    }

    @Test
    fun writeAndVerifyFullBackup_mismatchedReadbackBytes_failsReadbackVerification() = runTest {
        val corrupted = encryptedBytes.clone().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        val outcome = write(
            FakeSafDocumentStreamProvider(
                outputStreamProvider = { ByteArrayOutputStream() },
                inputStreamProvider = { ByteArrayInputStream(corrupted) }
            )
        )

        assertTrue("Cause MUST mention the mismatch", unverifiedCause(outcome).contains("didn't match"))
    }

    @Test
    fun writeAndVerifyFullBackup_verificationRejectsSavedFile_reportsUnverified() = runTest {
        val written = ByteArrayOutputStream()

        val outcome = write(
            FakeSafDocumentStreamProvider(
                outputStreamProvider = { written },
                inputStreamProvider = { ByteArrayInputStream(written.toByteArray()) }
            ),
            verify = { false }
        )

        assertTrue("Cause MUST mention failed verification", unverifiedCause(outcome).contains("failed verification"))
    }

    @Test
    fun writeAndVerifyFullBackup_failingOutputStream_reportsWriteError() = runTest {
        val outcome = write(
            FakeSafDocumentStreamProvider(
                outputStreamProvider = { throw java.io.IOException("SAF Provider write permission denied") },
                inputStreamProvider = { error("") }
            )
        )

        assertTrue("Cause MUST mention write failure", unverifiedCause(outcome).contains("Failed writing"))
    }

    @Test
    fun writeAndVerifyFullBackup_nullOutputStream_reportsUnableToOpenDocumentError() = runTest {
        val outcome = write(FakeSafDocumentStreamProvider(outputStreamProvider = { null }, inputStreamProvider = { error("") }))

        assertTrue("Cause MUST mention unable to open selected document", unverifiedCause(outcome).contains("Unable to open"))
    }

    @Test
    fun writeAndVerifyFullBackup_nullInputStream_reportsReadbackOpenError() = runTest {
        val outcome = write(
            FakeSafDocumentStreamProvider(outputStreamProvider = { ByteArrayOutputStream() }, inputStreamProvider = { null })
        )

        assertTrue("Cause MUST mention the failed read back", unverifiedCause(outcome).contains("couldn't be read back"))
    }

    @Test
    fun writeAndVerifyFullBackup_inputStreamThrowsOnRead_reportsReadbackError() = runTest {
        val failingInputStream = object : InputStream() {
            override fun read(): Int = throw java.io.IOException("SAF Provider read storage unmounted")
        }

        val outcome = write(
            FakeSafDocumentStreamProvider(outputStreamProvider = { ByteArrayOutputStream() }, inputStreamProvider = { failingInputStream })
        )

        assertTrue("Cause MUST report the failure", unverifiedCause(outcome).contains("Failed writing or verifying"))
    }

    @Test
    fun writeAndVerifyFullBackup_oversizedReadbackStream_isRejected() = runTest {
        val oversizedInputStream = object : InputStream() {
            private var bytesProduced = 0
            override fun read(): Int {
                if (bytesProduced > MAX_ENCRYPTED_CONTAINER_SIZE_BYTES + 100) return -1
                bytesProduced++
                return 0x55
            }
        }
        var verifyCalled = false

        val outcome = write(
            FakeSafDocumentStreamProvider(outputStreamProvider = { ByteArrayOutputStream() }, inputStreamProvider = { oversizedInputStream }),
            verify = { verifyCalled = true; true }
        )

        assertTrue("Cause MUST report the failure", unverifiedCause(outcome).contains("Failed writing or verifying"))
        assertTrue("Oversized readback MUST NOT reach verification", !verifyCalled)
    }
}
