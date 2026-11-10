package com.example.chatgpt_mealplanner.feature.settings

import android.net.Uri
import com.example.chatgpt_mealplanner.core.ai.provider.MealPlannerAiProvider
import com.example.chatgpt_mealplanner.core.backup.BackupManager
import com.example.chatgpt_mealplanner.core.backup.MainEncryptedBackupSaveCoordinator
import com.example.chatgpt_mealplanner.core.backup.SafBackupOutcome
import com.example.chatgpt_mealplanner.core.database.dao.ChatDao
import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.security.CredentialStore
import com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
import com.example.chatgpt_mealplanner.core.sync.follower.worker.FollowerSyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelBackupTest {

    private val testDispatcher = StandardTestDispatcher()
    private val credentialStore: CredentialStore = mock()
    private val provider: MealPlannerAiProvider = mock()
    private val chatDao: ChatDao = mock()
    private val backupManager: BackupManager = BackupManager(null, object : com.example.chatgpt_mealplanner.core.database.DatabaseTransactionRunner {
        override suspend fun <T> runInTransaction(block: suspend () -> T): T = block()
    })
    private val followerSyncScheduler: FollowerSyncScheduler = FollowerSyncScheduler(dagger.Lazy { error("Not called") })
    private class TestAppRoleResolver(var isMainRole: Boolean = true) : AppRoleResolver(
        com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore(null)
    ) {
        override suspend fun isMainAsync(): Boolean = isMainRole
        override fun isMain(): Boolean = isMainRole
        override suspend fun isFollowerAsync(): Boolean = !isMainRole
        override fun isFollower(): Boolean = !isMainRole
    }

    private val appRoleResolver = TestAppRoleResolver(isMainRole = true)

    private lateinit var fakeSaveCoordinator: FakeSaveCoordinator
    private lateinit var viewModel: SettingsViewModel

    private val dummyUri: Uri = android.net.FakeUri()

    private class FakeSaveCoordinator(
        var isMainRole: Boolean = true,
        var outcomeToReturn: SafBackupOutcome = SafBackupOutcome.VerifiedFullBackup
    ) : MainEncryptedBackupSaveCoordinator(
        object : AppRoleResolver(com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore(null)) {
            override fun isMain(): Boolean = isMainRole
            override suspend fun isMainAsync(): Boolean = isMainRole
        },
        com.example.chatgpt_mealplanner.core.backup.MainEncryptedBackupSafWriter(
            object : com.example.chatgpt_mealplanner.core.backup.SafDocumentStreamProvider {
                override fun openOutputStream(appContext: android.content.Context?, uri: Uri?): java.io.OutputStream? = null
                override fun openInputStream(appContext: android.content.Context?, uri: Uri?): java.io.InputStream? = null
            }
        ),
        mock()
    ) {
        fun setOutcome(outcome: SafBackupOutcome) {
            outcomeToReturn = outcome
        }

        override suspend fun executeMainBackupSave(
            appContext: android.content.Context?,
            destinationUri: Uri?,
            passphrase: CharArray,
            currentTimeMs: Long
        ): SafBackupOutcome {
            if (!isMainRole) {
                return SafBackupOutcome.NoDocumentIoAttempted(DomainError.TransactionFailed("No document I/O attempted: Backup creation is available on Main device only."))
            }
            if (passphrase.size < 8) {
                return SafBackupOutcome.NoDocumentIoAttempted(DomainError.TransactionFailed("No document I/O attempted: Backup passphrase MUST be at least 8 characters long."))
            }
            if (destinationUri == null) {
                return SafBackupOutcome.NoDocumentIoAttempted(DomainError.TransactionFailed("No document I/O attempted: Backup cancelled (no document destination selected)."))
            }
            return outcomeToReturn
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        appRoleResolver.isMainRole = true
        fakeSaveCoordinator = FakeSaveCoordinator(isMainRole = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        isMain: Boolean = true,
        fullBackupManager: com.example.chatgpt_mealplanner.core.backup.FullBackupManager? = null,
        dataResetDao: com.example.chatgpt_mealplanner.core.database.dao.AppDataResetDao? = null
    ): SettingsViewModel {
        appRoleResolver.isMainRole = isMain
        fakeSaveCoordinator.isMainRole = isMain
        return SettingsViewModel(
            context = null,
            credentialStore = credentialStore,
            provider = provider,
            chatDao = chatDao,
            backupManager = backupManager,
            followerSyncScheduler = followerSyncScheduler,
            appRoleResolver = appRoleResolver,
            mainBackupSaveCoordinator = fakeSaveCoordinator,
            fullBackupManager = fullBackupManager,
            dataResetDao = dataResetDao
        )
    }

    @Test
    fun restore_success_asksTheAppToRestart() = runTest {
        val manager = mock<com.example.chatgpt_mealplanner.core.backup.FullBackupManager>()
        val uri = mock<android.net.Uri>()
        org.mockito.kotlin.whenever(manager.stageRestore(org.mockito.kotlin.any(), org.mockito.kotlin.any()))
            .thenReturn(com.example.chatgpt_mealplanner.core.model.DomainResult.Success(Unit))
        viewModel = createViewModel(fullBackupManager = manager)
        testScheduler.advanceUntilIdle()

        viewModel.onRestoreFileSelected(uri)
        assertEquals(uri, viewModel.uiState.value.pendingRestoreUri)
        viewModel.executeRestore("passphrase".toCharArray(), ioDispatcher = testDispatcher)
        testScheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.restartToFinishRestore)
        assertNull(viewModel.uiState.value.pendingRestoreUri)
    }

    @Test
    fun restore_failure_showsWhy_andDoesNotRestart() = runTest {
        val manager = mock<com.example.chatgpt_mealplanner.core.backup.FullBackupManager>()
        org.mockito.kotlin.whenever(manager.stageRestore(org.mockito.kotlin.any(), org.mockito.kotlin.any()))
            .thenReturn(
                com.example.chatgpt_mealplanner.core.model.DomainResult.Failure(
                    com.example.chatgpt_mealplanner.core.model.DomainError.TransactionFailed("Wrong passphrase")
                )
            )
        viewModel = createViewModel(fullBackupManager = manager)
        testScheduler.advanceUntilIdle()

        viewModel.onRestoreFileSelected(mock())
        viewModel.executeRestore("passphrase".toCharArray(), ioDispatcher = testDispatcher)
        testScheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.restartToFinishRestore)
        assertFalse(viewModel.uiState.value.isRestoreInProgress)
        assertEquals("Wrong passphrase", viewModel.uiState.value.backupStatusMessage)
    }

    @Test
    fun restore_isBlockedOnJoinedPhones() = runTest {
        viewModel = createViewModel(isMain = false)
        testScheduler.advanceUntilIdle()

        viewModel.onRestoreFileSelected(mock())

        assertNull(viewModel.uiState.value.pendingRestoreUri)
        assertTrue(viewModel.uiState.value.backupStatusMessage!!.contains("Main phone"))
    }

    @Test
    fun onBackupDestinationSelected_validUriMainRole_opensPassphraseDialog() = runTest(testDispatcher) {
        viewModel = createViewModel(isMain = true)
        testScheduler.advanceUntilIdle()

        viewModel.onBackupDestinationSelected(dummyUri)

        val state = viewModel.uiState.value
        assertTrue("Passphrase dialog MUST be visible", state.isPassphraseDialogVisible)
        assertEquals(dummyUri, state.pendingBackupUri)
        assertNull("Status message MUST be null on initial destination selection", state.backupStatusMessage)
    }

    @Test
    fun onBackupDestinationSelected_nullUri_setsCancelledStatus() = runTest(testDispatcher) {
        viewModel = createViewModel(isMain = true)
        testScheduler.advanceUntilIdle()

        viewModel.onBackupDestinationSelected(null)

        val state = viewModel.uiState.value
        assertFalse("Passphrase dialog MUST NOT be visible on cancellation", state.isPassphraseDialogVisible)
        assertNull(state.pendingBackupUri)
        assertTrue("Status message MUST mention backup cancelled", state.backupStatusMessage?.contains("cancelled") == true)
    }

    @Test
    fun onBackupDestinationSelected_followerRole_rejectsWithRoleMessage() = runTest(testDispatcher) {
        viewModel = createViewModel(isMain = false)
        testScheduler.advanceUntilIdle()

        viewModel.onBackupDestinationSelected(dummyUri)

        val state = viewModel.uiState.value
        assertFalse("Passphrase dialog MUST NOT open for Follower role", state.isPassphraseDialogVisible)
        assertTrue("Status message MUST mention Main device only", state.backupStatusMessage?.contains("Main device only") == true)
    }

    @Test
    fun executeEncryptedBackup_shortPassphrase_rejectsWithoutIo() = runTest(testDispatcher) {
        viewModel = createViewModel(isMain = true)
        testScheduler.advanceUntilIdle()

        viewModel.onBackupDestinationSelected(dummyUri)
        val shortPassphrase = "short".toCharArray()

        viewModel.executeEncryptedBackup(null, shortPassphrase, testDispatcher)
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("Status message MUST mention passphrase length", state.backupStatusMessage?.contains("at least 8 characters") == true)
        // Verify passphrase array zeroization
        assertTrue("Passphrase array MUST be zeroed out", shortPassphrase.all { it == ' ' })
    }

    @Test
    fun executeEncryptedBackup_verifiedSuccess_updatesSuccessState() = runTest(testDispatcher) {
        fakeSaveCoordinator.setOutcome(SafBackupOutcome.VerifiedFullBackup)

        viewModel = createViewModel(isMain = true)
        testScheduler.advanceUntilIdle()

        viewModel.onBackupDestinationSelected(dummyUri)
        val validPassphrase = "MasterPassphrase#2026".toCharArray()

        viewModel.executeEncryptedBackup(null, validPassphrase, testDispatcher)
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("Backup MUST no longer be in progress", state.isBackupInProgress)
        assertNull("Pending URI MUST be cleared", state.pendingBackupUri)
        assertTrue("Status message MUST mention the verified backup", state.backupStatusMessage?.contains("verified and saved") == true)
        assertTrue("Passphrase array MUST be zeroed out", validPassphrase.all { it == ' ' })
    }

    @Test
    fun executeEncryptedBackup_unverifiedIoFailure_displaysUnverifiedWarning() = runTest(testDispatcher) {
        viewModel = createViewModel(isMain = true)
        testScheduler.advanceUntilIdle()

        fakeSaveCoordinator.outcomeToReturn = SafBackupOutcome.DocumentIoAttemptedUnverified(
            DomainError.TransactionFailed("SAF Document I/O attempted (unverified): Saved backup readback verification failed: exact byte mismatch on disk."),
            dummyUri
        )
        testScheduler.advanceUntilIdle()

        viewModel.onBackupDestinationSelected(dummyUri)
        val validPassphrase = "MasterPassphrase#2026".toCharArray()

        viewModel.executeEncryptedBackup(null, validPassphrase, testDispatcher)
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("Backup MUST no longer be in progress", state.isBackupInProgress)
        assertTrue("Status message MUST contain unverified warning", state.backupStatusMessage?.contains("Warning: An incomplete or unverified backup document may remain") == true)
        assertTrue("Passphrase array MUST be zeroed out", validPassphrase.all { it == ' ' })
    }

    @Test
    fun dismissPassphraseDialog_clearsPendingUriAndDialogVisibility() = runTest(testDispatcher) {
        viewModel = createViewModel(isMain = true)
        testScheduler.advanceUntilIdle()

        viewModel.onBackupDestinationSelected(dummyUri)
        assertTrue(viewModel.uiState.value.isPassphraseDialogVisible)

        viewModel.dismissPassphraseDialog()

        val state = viewModel.uiState.value
        assertFalse("Passphrase dialog MUST NOT be visible after dismiss", state.isPassphraseDialogVisible)
        assertNull("Pending URI MUST be cleared on dismiss", state.pendingBackupUri)
    }

    @Test
    fun executeEncryptedBackup_noIoFailure_displaysNoIoAttemptedMessage() = runTest(testDispatcher) {
        viewModel = createViewModel(isMain = true)
        testScheduler.advanceUntilIdle()

        fakeSaveCoordinator.outcomeToReturn = SafBackupOutcome.NoDocumentIoAttempted(
            DomainError.TransactionFailed("No document I/O attempted: Failed generating Main household snapshot: Database locked.")
        )
        testScheduler.advanceUntilIdle()

        viewModel.onBackupDestinationSelected(dummyUri)
        val validPassphrase = "MasterPassphrase#2026".toCharArray()

        viewModel.executeEncryptedBackup(null, validPassphrase, testDispatcher)
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("Backup MUST no longer be in progress", state.isBackupInProgress)
        assertTrue("Status message MUST mention No document I/O attempted", state.backupStatusMessage?.contains("No document I/O attempted") == true)
        assertTrue("Passphrase array MUST be zeroed out", validPassphrase.all { it == ' ' })
    }

    private fun resetDao(): com.example.chatgpt_mealplanner.core.database.dao.AppDataResetDao {
        val dao = mock<com.example.chatgpt_mealplanner.core.database.dao.AppDataResetDao>()
        kotlinx.coroutines.runBlocking {
            org.mockito.kotlin.whenever(dao.counts()).thenReturn(com.example.chatgpt_mealplanner.core.database.dao.ClearableDataCounts(2, 15, 40))
        }
        return dao
    }

    @Test
    fun clearAllData_showsWhatWillBeDeleted_thenClearsOnConfirm() = runTest {
        val dao = resetDao()
        viewModel = createViewModel(dataResetDao = dao)
        testScheduler.advanceUntilIdle()

        viewModel.requestClearAllData()
        testScheduler.advanceUntilIdle()
        assertEquals(40, viewModel.uiState.value.clearDataCounts?.inventoryItems)
        org.mockito.kotlin.verify(dao, org.mockito.kotlin.never()).clearHouseholdData()

        viewModel.confirmClearAllData()
        testScheduler.advanceUntilIdle()
        org.mockito.kotlin.verify(dao).clearHouseholdData()
        assertNull(viewModel.uiState.value.clearDataCounts)
        assertFalse(viewModel.uiState.value.isClearingData)
        assertTrue(viewModel.uiState.value.clearDataMessage!!.contains("API key were kept"))
    }

    @Test
    fun clearAllData_isRefusedOnAPairedFollower() = runTest {
        val dao = resetDao()
        viewModel = createViewModel(isMain = false, dataResetDao = dao)
        testScheduler.advanceUntilIdle()

        viewModel.requestClearAllData()
        testScheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.clearDataCounts)
        assertTrue(viewModel.uiState.value.clearDataMessage!!.contains("main phone"))
        org.mockito.kotlin.verify(dao, org.mockito.kotlin.never()).clearHouseholdData()
    }

    @Test
    fun clearAllData_failureSaysNothingWasDeleted() = runTest {
        val dao = resetDao()
        org.mockito.kotlin.whenever(runBlocking { dao.clearHouseholdData() }).thenThrow(RuntimeException("disk full"))
        viewModel = createViewModel(dataResetDao = dao)
        testScheduler.advanceUntilIdle()

        viewModel.confirmClearAllData()
        testScheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.clearDataMessage!!.startsWith("Nothing was deleted"))
        assertFalse(viewModel.uiState.value.isClearingData)
    }
}
