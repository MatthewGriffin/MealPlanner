package com.example.chatgpt_mealplanner.sync

import android.content.Context
import com.example.chatgpt_mealplanner.core.repository.InventoryRepositoryImpl
import com.example.chatgpt_mealplanner.core.security.CredentialStore
import com.example.chatgpt_mealplanner.core.service.FakeInventoryBarcodeDao
import com.example.chatgpt_mealplanner.core.service.FakeInventoryDao
import com.example.chatgpt_mealplanner.core.service.FakeStapleRuleDao
import com.example.chatgpt_mealplanner.core.service.FakeTransactionDao
import com.example.chatgpt_mealplanner.core.service.FakeTransactionRunner
import com.example.chatgpt_mealplanner.core.service.InventoryDomainServiceImpl
import com.example.chatgpt_mealplanner.core.sync.HouseholdIdentityManager
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerSyncCoordinator
import com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusRepository
import com.example.chatgpt_mealplanner.core.sync.follower.worker.FollowerSyncScheduler
import com.example.chatgpt_mealplanner.core.sync.follower.worker.WorkSchedulingResult
import com.example.chatgpt_mealplanner.core.sync.repository.PairedDeviceRepository
import com.example.chatgpt_mealplanner.feature.household.HouseholdViewModel
import com.example.chatgpt_mealplanner.feature.inventory.InventoryViewModel
import com.example.chatgpt_mealplanner.feature.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class PantrySyncPhase4gManualTapContractTest {

    private val testDispatcher = StandardTestDispatcher()
    private val keyStoreManager = TestEnrollmentKeyStoreManager()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createCoordinator(
        pairingStore: FakePairingStore,
        statusRepo: FollowerSyncStatusRepository,
        passDelayMs: Long = 0L,
        passSuccess: Boolean = true
    ): FollowerSyncCoordinator {
        val fakeClient = PantrySyncTestClient(keyStoreManager)
        val followerDb = PantrySyncPhase4gHostLifecycleTest.TestFakeFollowerDatabase()
        val pullEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(
            followerDb, fakeClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb)
        )
        val bootstrapEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine(
            followerDb, fakeClient, pullEngine, pairingStore
        )
        val uploader = com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploader(
            followerDb, fakeClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb)
        )

        return object : FollowerSyncCoordinator(
            followerDb, uploader, pullEngine, bootstrapEngine, fakeClient, pairingStore, statusRepo
        ) {
            override suspend fun performSingleSyncPass(triggerSource: String): Boolean {
                if (passDelayMs > 0) delay(passDelayMs)
                return passSuccess
            }
        }
    }

    private fun createHouseholdViewModel(
        coordinator: FollowerSyncCoordinator,
        pairingStore: FakePairingStore = FakePairingStore(),
        statusRepo: FollowerSyncStatusRepository? = FollowerSyncStatusRepository(pairingStore),
        followerDatabase: com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase? = null,
        asyncOverride: ((Context?, String, Boolean) -> WorkSchedulingResult)? = null
    ): HouseholdViewModel {
        val identityDao = FakeHouseholdIdentityDao()
        val pairedDeviceDao = TestSyncPairedDeviceDao()
        val identityManager = HouseholdIdentityManager(identityDao)
        val deviceRepo = PairedDeviceRepository(pairedDeviceDao)

        val testScheduler = object : FollowerSyncScheduler(dagger.Lazy { coordinator }) {
            override suspend fun scheduleSyncWorkAsync(
                context: Context?,
                triggerReason: String,
                intentAlreadyRecorded: Boolean
            ): WorkSchedulingResult {
                if (asyncOverride != null) {
                    return asyncOverride(context, triggerReason, intentAlreadyRecorded)
                }
                return super.scheduleSyncWorkAsync(context, triggerReason, intentAlreadyRecorded)
            }
        }

        return HouseholdViewModel(
            identityManager = identityManager,
            pairedDeviceRepository = deviceRepo,
            followerPairingStore = pairingStore,
            syncConflictApprovalRepository = null,
            pantryEnrollmentInvitationManager = null,
            tlsKeyManager = null,
            hostController = null,
            appRoleResolver = null,
            followerSyncStatusRepository = statusRepo,
            followerSyncScheduler = testScheduler,
            followerDatabase = followerDatabase,
            appContext = null
        )
    }

    @Test
    fun test1_generationIncrementsSynchronouslyBeforeCoroutineDispatch() = runTest {
        val pairingStore = FakePairingStore()
        val statusRepo = FollowerSyncStatusRepository(pairingStore)
        val coordinator = createCoordinator(pairingStore, statusRepo)
        val vm = createHouseholdViewModel(coordinator)

        val (reqBefore, _) = coordinator.getGenerations()
        assertEquals("Initial requestedGeneration MUST be 0", 0L, reqBefore)

        // Trigger sync NOW - MUST increment generation synchronously on main thread before coroutine executes
        vm.triggerSyncNow()

        val (reqImmediatelyAfterTap, _) = coordinator.getGenerations()
        assertEquals("Generation MUST increment synchronously on tap thread before coroutine dispatch", 1L, reqImmediatelyAfterTap)

        advanceUntilIdle()
    }

    @Test
    fun test2_exactlyOneIncrementPerTap() = runTest {
        val pairingStore = FakePairingStore()
        val statusRepo = FollowerSyncStatusRepository(pairingStore)
        val coordinator = createCoordinator(pairingStore, statusRepo)

        var scheduleAsyncCalls = 0
        var receivedIntentAlreadyRecorded = false

        val vm = createHouseholdViewModel(coordinator, asyncOverride = { _, _, intentAlreadyRecorded ->
            scheduleAsyncCalls++
            receivedIntentAlreadyRecorded = intentAlreadyRecorded
            WorkSchedulingResult.SUBMITTED_AND_CONFIRMED
        })

        vm.triggerSyncNow()
        advanceUntilIdle()

        val (reqGen, _) = coordinator.getGenerations()
        assertEquals("Single tap MUST produce exactly 1 generation increment", 1L, reqGen)
        assertEquals(1, scheduleAsyncCalls)
        assertTrue("scheduleSyncWorkAsync MUST be invoked with intentAlreadyRecorded = true", receivedIntentAlreadyRecorded)
    }

    @Test
    fun test3_confirmedEnqueueIsNotSyncCompletion() = runTest {
        val pairingStore = FakePairingStore()
        val statusRepo = FollowerSyncStatusRepository(pairingStore)
        val coordinator = createCoordinator(pairingStore, statusRepo)
        val vm = createHouseholdViewModel(coordinator, asyncOverride = { _, _, _ ->
            WorkSchedulingResult.SUBMITTED_AND_CONFIRMED
        })

        vm.triggerSyncNow()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse("isSyncing MUST be false immediately after enqueue confirmation", state.isSyncing)
        assertEquals("statusMessage MUST indicate confirmed enqueue intent ('Sync queued')", "Sync queued", state.statusMessage)
        assertNotEquals("truthfulStatusText MUST NOT display 'Syncing…' upon enqueue confirmation", "Syncing…", state.truthfulStatusText)
        assertNotEquals("truthfulStatusText MUST NOT claim 'Sync completed' upon enqueue confirmation", "Sync completed", state.truthfulStatusText)
    }

    @Test
    fun test4_failedEnqueueProducesVisibleStatus() = runTest {
        val pairingStore = FakePairingStore()
        val statusRepo = FollowerSyncStatusRepository(pairingStore)
        val coordinator = createCoordinator(pairingStore, statusRepo)
        val vm = createHouseholdViewModel(coordinator, asyncOverride = { _, _, _ ->
            WorkSchedulingResult.OPERATION_FAILED
        })

        vm.triggerSyncNow()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("Failed enqueue MUST set visible errorMessage", "Failed to schedule manual sync", state.errorMessage)
        assertNull("Failed enqueue MUST clear statusMessage to prevent misleading permanent 'Sync requested' text", state.statusMessage)
    }

    @Test
    fun test5_cancelledEnqueueProducesVisibleStatus() = runTest {
        val pairingStore = FakePairingStore()
        val statusRepo = FollowerSyncStatusRepository(pairingStore)
        val coordinator = createCoordinator(pairingStore, statusRepo)
        val vm = createHouseholdViewModel(coordinator, asyncOverride = { _, _, _ ->
            WorkSchedulingResult.CANCELLED
        })

        vm.triggerSyncNow()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("Cancelled enqueue MUST set visible errorMessage", "Sync request cancelled", state.errorMessage)
        assertNull("Cancelled enqueue MUST clear statusMessage", state.statusMessage)
    }

    @Test
    fun test6_coalescedTapProducesImmediateFeedback() = runTest {
        val pairingStore = FakePairingStore()
        val statusRepo = FollowerSyncStatusRepository(pairingStore)
        val coordinator = createCoordinator(pairingStore, statusRepo, passDelayMs = 200L)

        val passJob = launch { coordinator.executeSyncPass("AUTO") }
        delay(10)
        assertTrue("Coordinator MUST be running initial pass", coordinator.isRunning())

        var asyncCalledWhileRunning = false
        val vm = createHouseholdViewModel(coordinator, asyncOverride = { _, _, _ ->
            asyncCalledWhileRunning = true
            WorkSchedulingResult.SUBMITTED_AND_CONFIRMED
        })

        // Tap while pass is running
        vm.triggerSyncNow()

        // Immediate feedback MUST be visible right away
        assertEquals("Coalesced tap MUST provide immediate 'Sync requested' feedback", "Sync requested", vm.uiState.value.statusMessage)
        assertFalse("Coalesced tap MUST NOT launch WorkManager enqueue coroutine", asyncCalledWhileRunning)

        val (reqGen, _) = coordinator.getGenerations()
        assertEquals("Coalesced tap MUST increment requestedGeneration in coordinator", 1L, reqGen)

        passJob.join()
        advanceUntilIdle()
    }

    @Test
    fun test7_repeatedTapsDoNotCauseDuplicateWorkerPasses() = runTest {
        val pairingStore = FakePairingStore()
        val statusRepo = FollowerSyncStatusRepository(pairingStore)

        var passExecutions = 0
        val fakeClient = PantrySyncTestClient(keyStoreManager)
        val followerDb = PantrySyncPhase4gHostLifecycleTest.TestFakeFollowerDatabase()
        val pullEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(
            followerDb, fakeClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb)
        )
        val bootstrapEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine(
            followerDb, fakeClient, pullEngine, pairingStore
        )
        val uploader = com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploader(
            followerDb, fakeClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb)
        )

        val coordinator = object : FollowerSyncCoordinator(
            followerDb, uploader, pullEngine, bootstrapEngine, fakeClient, pairingStore, statusRepo
        ) {
            override suspend fun performSingleSyncPass(triggerSource: String): Boolean {
                passExecutions++
                delay(100)
                return true
            }
        }

        val passJob = launch { coordinator.executeSyncPass("AUTO") }
        delay(10)

        val testScheduler = FollowerSyncScheduler(dagger.Lazy { coordinator })

        // 3 rapid taps while pass is active
        val enq1 = testScheduler.recordManualSyncIntent()
        val enq2 = testScheduler.recordManualSyncIntent()
        val enq3 = testScheduler.recordManualSyncIntent()

        assertFalse("Taps during running pass MUST return false (coalesced)", enq1)
        assertFalse(enq2)
        assertFalse(enq3)

        passJob.join()

        assertEquals("Drain loop MUST satisfy all 3 taps in 2 total passes (1 initial + 1 coalesced pass)", 2, passExecutions)
        val (reqGen, compGen) = coordinator.getGenerations()
        assertEquals(3L, reqGen)
        assertEquals(3L, compGen)
    }

    @Test
    fun test8_inventoryAndSettingsManualTriggers_followSingleGenerationContract() = runTest {
        val pairingStore = FakePairingStore()
        val statusRepo = FollowerSyncStatusRepository(pairingStore)
        val coordinator = createCoordinator(pairingStore, statusRepo)

        var asyncCalls = 0
        val testScheduler = object : FollowerSyncScheduler(dagger.Lazy { coordinator }) {
            override suspend fun scheduleSyncWorkAsync(
                context: Context?,
                triggerReason: String,
                intentAlreadyRecorded: Boolean
            ): WorkSchedulingResult {
                asyncCalls++
                assertTrue("intentAlreadyRecorded MUST be true when manual trigger invoked from Inventory/Settings", intentAlreadyRecorded)
                return WorkSchedulingResult.SUBMITTED_AND_CONFIRMED
            }
        }

        val dummyCredentialStore = object : CredentialStore {
            override fun hasKey(): Boolean = false
            override fun saveKey(key: String) {}
            override fun loadKey(): String? = null
            override fun deleteKey() {}
        }

        val dummyAiProvider = object : com.example.chatgpt_mealplanner.core.ai.provider.MealPlannerAiProvider {
            override suspend fun send(request: com.example.chatgpt_mealplanner.contract.AiProviderRequest) =
                com.example.chatgpt_mealplanner.contract.AiProviderResponse.Error("DUMMY", "Dummy")
            override suspend fun continueWithToolResults(request: com.example.chatgpt_mealplanner.contract.AiContinueWithToolsRequest) =
                com.example.chatgpt_mealplanner.contract.AiProviderResponse.Error("DUMMY", "Dummy")
        }

        val fakeSafWriter = com.example.chatgpt_mealplanner.core.backup.MainEncryptedBackupSafWriter(
            object : com.example.chatgpt_mealplanner.core.backup.SafDocumentStreamProvider {
                override fun openOutputStream(appContext: android.content.Context?, uri: android.net.Uri?): java.io.OutputStream? = null
                override fun openInputStream(appContext: android.content.Context?, uri: android.net.Uri?): java.io.InputStream? = null
            }
        )
        val fakeSaveCoordinator = com.example.chatgpt_mealplanner.core.backup.MainEncryptedBackupSaveCoordinator(
            FakeAppRoleResolver(),
            fakeSafWriter,
            org.mockito.kotlin.mock()
        )

        val settingsVm = SettingsViewModel(
            context = null,
            credentialStore = dummyCredentialStore,
            provider = dummyAiProvider,
            chatDao = FakeChatDao(),
            backupManager = FakeBackupManager(),
            followerSyncScheduler = testScheduler,
            appRoleResolver = FakeAppRoleResolver(),
            mainBackupSaveCoordinator = fakeSaveCoordinator
        )

        val (reqBeforeSettings, _) = coordinator.getGenerations()
        assertEquals(0L, reqBeforeSettings)

        // Trigger manual sync from Settings
        settingsVm.triggerManualSync()

        val (reqAfterSettingsTap, _) = coordinator.getGenerations()
        assertEquals("Settings manual tap MUST increment generation synchronously on caller thread", 1L, reqAfterSettingsTap)

        advanceUntilIdle()

        val (reqAfterSettingsIdle, _) = coordinator.getGenerations()
        assertEquals("Settings manual tap MUST NOT double-increment generation", 1L, reqAfterSettingsIdle)
        assertEquals(1, asyncCalls)

        // Trigger manual sync from Inventory
        val fakeInventoryDao = FakeInventoryDao()
        val fakeBarcodeDao = FakeInventoryBarcodeDao()
        val fakeTransactionDao = FakeTransactionDao()
        val fakeStapleRuleDao = FakeStapleRuleDao()
        val fakeRunner = FakeTransactionRunner()
        val inventoryRepo = InventoryRepositoryImpl(fakeInventoryDao)
        val inventoryDomainService = InventoryDomainServiceImpl(fakeInventoryDao, fakeBarcodeDao, fakeTransactionDao, fakeStapleRuleDao, fakeRunner)

        val inventoryVm = InventoryViewModel(
            inventoryRepository = inventoryRepo,
            inventoryDomainService = inventoryDomainService,
            context = null,
            followerPairingStore = pairingStore,
            followerSyncStatusRepository = statusRepo,
            followerSyncScheduler = testScheduler,
            followerDatabase = null
        )

        inventoryVm.triggerManualSync()

        val (reqAfterInventoryTap, _) = coordinator.getGenerations()
        assertEquals("Inventory manual tap MUST increment generation synchronously on caller thread", 2L, reqAfterInventoryTap)

        advanceUntilIdle()

        val (reqAfterInventoryIdle, _) = coordinator.getGenerations()
        assertEquals("Inventory manual tap MUST NOT double-increment generation", 2L, reqAfterInventoryIdle)
        assertEquals(2, asyncCalls)
    }

    @Test
    fun test9_nullContextReturnsOperationFailed() = runTest {
        val pairingStore = FakePairingStore()
        val statusRepo = FollowerSyncStatusRepository(pairingStore)
        val coordinator = createCoordinator(pairingStore, statusRepo)

        val scheduler = FollowerSyncScheduler(dagger.Lazy { coordinator })

        // 1. Uncoalesced manual trigger with null context MUST return OPERATION_FAILED
        val result = scheduler.scheduleSyncWorkAsync(null, "MANUAL_SYNC", intentAlreadyRecorded = false)
        assertEquals("scheduleSyncWorkAsync with null context MUST return OPERATION_FAILED", WorkSchedulingResult.OPERATION_FAILED, result)

        // 2. Auto trigger with null context MUST return OPERATION_FAILED
        val autoResult = scheduler.scheduleSyncWorkAsync(null, "AUTO_SYNC", intentAlreadyRecorded = false)
        assertEquals("scheduleSyncWorkAsync with null context MUST return OPERATION_FAILED", WorkSchedulingResult.OPERATION_FAILED, autoResult)
    }

    @Test
    fun test10_tapRacingWorkerCompletion_isDrainedWithoutLoss() = runTest {
        val pairingStore = FakePairingStore()
        val statusRepo = FollowerSyncStatusRepository(pairingStore)

        var passExecutions = 0
        val fakeClient = PantrySyncTestClient(keyStoreManager)
        val followerDb = PantrySyncPhase4gHostLifecycleTest.TestFakeFollowerDatabase()
        val pullEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine(
            followerDb, fakeClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb)
        )
        val bootstrapEngine = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine(
            followerDb, fakeClient, pullEngine, pairingStore
        )
        val uploader = com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploader(
            followerDb, fakeClient, com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(followerDb)
        )

        val coordinator = object : FollowerSyncCoordinator(
            followerDb, uploader, pullEngine, bootstrapEngine, fakeClient, pairingStore, statusRepo
        ) {
            override suspend fun performSingleSyncPass(triggerSource: String): Boolean {
                passExecutions++
                delay(100)
                return true
            }
        }

        // Tap 1 arrives before pass 1 starts
        val shouldEnqueue1 = coordinator.requestManualSync()
        assertTrue("Tap 1 MUST return shouldEnqueue = true", shouldEnqueue1)

        // Start pass 1 in response to Tap 1
        val passJob = launch { coordinator.executeSyncPass("MANUAL") }
        delay(20) // Enter performSingleSyncPass

        assertTrue("Coordinator MUST be running pass 1", coordinator.isRunning())

        // Tap 2 arrives while pass 1 is executing
        val shouldEnqueue2 = coordinator.requestManualSync()
        assertFalse("Tap 2 during running pass MUST return shouldEnqueue = false (coalesced)", shouldEnqueue2)

        val (reqGenDuringPass, compGenDuringPass) = coordinator.getGenerations()
        assertEquals(2L, reqGenDuringPass)
        assertEquals(0L, compGenDuringPass)

        passJob.join()

        // Drain loop MUST satisfy generation 2 in pass 2 before completing
        val (reqGenFinal, compGenFinal) = coordinator.getGenerations()
        assertEquals("Drain loop MUST reach requestedGeneration = 2", 2L, reqGenFinal)
        assertEquals("Drain loop MUST reach completedGeneration = 2", 2L, compGenFinal)
        assertEquals("Both pass 1 and pass 2 MUST execute", 2, passExecutions)
        assertFalse("Coordinator MUST release ownership after draining all generations", coordinator.isRunning())
    }

    @Test
    fun test11_tapRacingOwnerRelease_acquiresNewWorker() = runTest {
        val pairingStore = FakePairingStore()
        val statusRepo = FollowerSyncStatusRepository(pairingStore)
        val coordinator = createCoordinator(pairingStore, statusRepo)

        // Pass 1 completes
        val pass1Job = launch { coordinator.executeSyncPass("MANUAL") }
        pass1Job.join()

        assertFalse("Worker MUST release owner after pass 1", coordinator.isRunning())
        val (req1, comp1) = coordinator.getGenerations()
        assertEquals(0L, req1)
        assertEquals(0L, comp1)

        // Tap arrives right after owner release
        val shouldEnqueue = coordinator.requestManualSync()
        assertTrue("Tap after owner release MUST return shouldEnqueue = true", shouldEnqueue)

        val (req2, comp2) = coordinator.getGenerations()
        assertEquals(1L, req2)
        assertEquals(0L, comp2)

        // Enqueued worker pass 2 runs
        val pass2Job = launch { coordinator.executeSyncPass("MANUAL") }
        pass2Job.join()

        val (reqFinal, compFinal) = coordinator.getGenerations()
        assertEquals(1L, reqFinal)
        assertEquals(1L, compFinal)
    }

    @Test
    fun test12_staleStatusRefreshVersusSchedulingFailure() = runTest {
        val pairingStore = FakePairingStore()
        pairingStore.savePairingConfig(
            com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
                hostIp = "192.168.1.10", port = 8443, pinnedCertFingerprint = "FINGERPRINT",
                deviceId = "FOLLOWER_1", keyAlias = "ALIAS_1", householdId = "household_1",
                mainDeviceId = "main_1", sessionToken = "token_1"
            )
        )
        val statusRepo = FollowerSyncStatusRepository(pairingStore)
        val fakeDb = PantrySyncPhase4gHostLifecycleTest.TestFakeFollowerDatabase()
        fakeDb.followerDao().updateSyncMetadata(
            com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity(
                singletonId = 1, bootstrapCompleted = true, lastSuccessfulPullAt = 1000L, lastReconciledCanonicalSequence = 5L
            )
        )

        val coordinator = createCoordinator(pairingStore, statusRepo)
        val vm = createHouseholdViewModel(
            coordinator = coordinator,
            pairingStore = pairingStore,
            statusRepo = statusRepo,
            followerDatabase = fakeDb,
            asyncOverride = { _, _, _ -> WorkSchedulingResult.OPERATION_FAILED }
        )

        vm.triggerSyncNow()
        advanceUntilIdle()

        val stateBeforeRefresh = vm.uiState.value
        assertEquals("Failed to schedule manual sync", stateBeforeRefresh.errorMessage)
        assertNull(stateBeforeRefresh.statusMessage)

        // Stale refreshFollowerStatus MUST NOT wipe or overwrite scheduling failure
        vm.refreshFollowerStatus()
        advanceUntilIdle()

        val stateAfterRefresh = vm.uiState.value
        assertEquals("Stale status refresh MUST preserve scheduling failure message", "Failed to schedule manual sync", stateAfterRefresh.errorMessage)
        assertNull("Stale status refresh MUST preserve cleared statusMessage", stateAfterRefresh.statusMessage)
    }

    @Test
    fun test13_cancellationVersusStaleStatusRefresh() = runTest {
        val pairingStore = FakePairingStore()
        pairingStore.savePairingConfig(
            com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
                hostIp = "192.168.1.10", port = 8443, pinnedCertFingerprint = "FINGERPRINT",
                deviceId = "FOLLOWER_1", keyAlias = "ALIAS_1", householdId = "household_1",
                mainDeviceId = "main_1", sessionToken = "token_1"
            )
        )
        val statusRepo = FollowerSyncStatusRepository(pairingStore)
        val fakeDb = PantrySyncPhase4gHostLifecycleTest.TestFakeFollowerDatabase()
        fakeDb.followerDao().updateSyncMetadata(
            com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity(
                singletonId = 1, bootstrapCompleted = true, lastSuccessfulPullAt = 1000L, lastReconciledCanonicalSequence = 5L
            )
        )

        val coordinator = createCoordinator(pairingStore, statusRepo)
        val vm = createHouseholdViewModel(
            coordinator = coordinator,
            pairingStore = pairingStore,
            statusRepo = statusRepo,
            followerDatabase = fakeDb,
            asyncOverride = { _, _, _ -> WorkSchedulingResult.CANCELLED }
        )

        vm.triggerSyncNow()
        advanceUntilIdle()

        val stateBeforeRefresh = vm.uiState.value
        assertEquals("Sync request cancelled", stateBeforeRefresh.errorMessage)
        assertNull(stateBeforeRefresh.statusMessage)

        // Stale refreshFollowerStatus MUST NOT wipe or overwrite cancellation message
        vm.refreshFollowerStatus()
        advanceUntilIdle()

        val stateAfterRefresh = vm.uiState.value
        assertEquals("Stale status refresh MUST preserve cancellation message", "Sync request cancelled", stateAfterRefresh.errorMessage)
        assertNull("Stale status refresh MUST preserve cleared statusMessage", stateAfterRefresh.statusMessage)
    }

    @Test
    fun test14_lifecycle_requested_running_finished() = runTest {
        val pairingStore = FakePairingStore()
        pairingStore.savePairingConfig(
            com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
                hostIp = "192.168.1.10",
                port = 8443,
                pinnedCertFingerprint = "FINGERPRINT",
                deviceId = "FOLLOWER_1",
                keyAlias = "ALIAS_1",
                householdId = "household_1",
                mainDeviceId = "main_1",
                sessionToken = "token_1"
            )
        )
        val statusRepo = FollowerSyncStatusRepository(pairingStore)
        val fakeDb = PantrySyncPhase4gHostLifecycleTest.TestFakeFollowerDatabase()
        fakeDb.followerDao().updateSyncMetadata(
            com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity(
                singletonId = 1,
                bootstrapCompleted = true,
                lastSuccessfulPullAt = 1000L,
                lastReconciledCanonicalSequence = 5L
            )
        )

        val coordinator = createCoordinator(pairingStore, statusRepo)
        val vm = createHouseholdViewModel(
            coordinator = coordinator,
            pairingStore = pairingStore,
            statusRepo = statusRepo,
            followerDatabase = fakeDb,
            asyncOverride = { _, _, _ -> WorkSchedulingResult.SUBMITTED_AND_CONFIRMED }
        )

        // 1. Initial State
        vm.loadHouseholdInfo()
        advanceUntilIdle()
        assertEquals("Healthy", vm.uiState.value.truthfulStatusText)

        // 2. Tap "Sync now" -> Immediately "Sync requested"
        vm.triggerSyncNow()
        assertEquals("Sync requested", vm.uiState.value.statusMessage)
        assertEquals("Sync requested", vm.uiState.value.truthfulStatusText)

        // 3. Worker starts -> Replaced with "Syncing…"
        statusRepo.reportWorkerStarted(1L)
        advanceUntilIdle()
        assertNull("statusMessage MUST be cleared when worker starts", vm.uiState.value.statusMessage)
        assertEquals("Syncing…", vm.uiState.value.truthfulStatusText)
        assertTrue(vm.uiState.value.isSyncing)

        // 4. Worker finishes -> Transitions to "Healthy"
        statusRepo.reportWorkerFinished(1L)
        advanceUntilIdle()
        assertNull(vm.uiState.value.statusMessage)
        assertEquals("Healthy", vm.uiState.value.truthfulStatusText)
        assertFalse(vm.uiState.value.isSyncing)
    }

    @Test
    fun test15_lifecycle_requested_schedulingFailure() = runTest {
        val pairingStore = FakePairingStore()
        pairingStore.savePairingConfig(
            com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
                hostIp = "192.168.1.10", port = 8443, pinnedCertFingerprint = "FINGERPRINT",
                deviceId = "FOLLOWER_1", keyAlias = "ALIAS_1", householdId = "household_1",
                mainDeviceId = "main_1", sessionToken = "token_1"
            )
        )
        val statusRepo = FollowerSyncStatusRepository(pairingStore)
        val fakeDb = PantrySyncPhase4gHostLifecycleTest.TestFakeFollowerDatabase()
        fakeDb.followerDao().updateSyncMetadata(
            com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity(
                singletonId = 1, bootstrapCompleted = true, lastSuccessfulPullAt = 1000L, lastReconciledCanonicalSequence = 5L
            )
        )

        val coordinator = createCoordinator(pairingStore, statusRepo)
        val vm = createHouseholdViewModel(
            coordinator = coordinator,
            pairingStore = pairingStore,
            statusRepo = statusRepo,
            followerDatabase = fakeDb,
            asyncOverride = { _, _, _ -> WorkSchedulingResult.OPERATION_FAILED }
        )

        vm.triggerSyncNow()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("Failed to schedule manual sync", state.errorMessage)
        assertNull(state.statusMessage)

        vm.refreshFollowerStatus()
        advanceUntilIdle()

        assertEquals("Failed to schedule manual sync", vm.uiState.value.errorMessage)
    }

    @Test
    fun test16_lifecycle_requested_cancellation() = runTest {
        val pairingStore = FakePairingStore()
        pairingStore.savePairingConfig(
            com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
                hostIp = "192.168.1.10", port = 8443, pinnedCertFingerprint = "FINGERPRINT",
                deviceId = "FOLLOWER_1", keyAlias = "ALIAS_1", householdId = "household_1",
                mainDeviceId = "main_1", sessionToken = "token_1"
            )
        )
        val statusRepo = FollowerSyncStatusRepository(pairingStore)
        val fakeDb = PantrySyncPhase4gHostLifecycleTest.TestFakeFollowerDatabase()
        fakeDb.followerDao().updateSyncMetadata(
            com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity(
                singletonId = 1, bootstrapCompleted = true, lastSuccessfulPullAt = 1000L, lastReconciledCanonicalSequence = 5L
            )
        )

        val coordinator = createCoordinator(pairingStore, statusRepo)
        val vm = createHouseholdViewModel(
            coordinator = coordinator,
            pairingStore = pairingStore,
            statusRepo = statusRepo,
            followerDatabase = fakeDb,
            asyncOverride = { _, _, _ -> WorkSchedulingResult.CANCELLED }
        )

        vm.triggerSyncNow()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("Sync request cancelled", state.errorMessage)
        assertNull(state.statusMessage)

        vm.refreshFollowerStatus()
        advanceUntilIdle()

        assertEquals("Sync request cancelled", vm.uiState.value.errorMessage)
    }

    @Test
    fun test17_repeatedTapsDuringActivePass_clearsRequestedBackToSyncing() = runTest {
        val pairingStore = FakePairingStore()
        pairingStore.savePairingConfig(
            com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
                hostIp = "192.168.1.10", port = 8443, pinnedCertFingerprint = "FINGERPRINT",
                deviceId = "FOLLOWER_1", keyAlias = "ALIAS_1", householdId = "household_1",
                mainDeviceId = "main_1", sessionToken = "token_1"
            )
        )
        val statusRepo = FollowerSyncStatusRepository(pairingStore)
        val fakeDb = PantrySyncPhase4gHostLifecycleTest.TestFakeFollowerDatabase()
        fakeDb.followerDao().updateSyncMetadata(
            com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity(
                singletonId = 1, bootstrapCompleted = true, lastSuccessfulPullAt = 1000L, lastReconciledCanonicalSequence = 5L
            )
        )

        val coordinator = createCoordinator(pairingStore, statusRepo)
        val vm = createHouseholdViewModel(
            coordinator = coordinator,
            pairingStore = pairingStore,
            statusRepo = statusRepo,
            followerDatabase = fakeDb,
            asyncOverride = { _, _, _ -> WorkSchedulingResult.INTENT_COALESCED_IN_MEMORY }
        )

        // Pass 1 running
        statusRepo.reportWorkerStarted(1L)
        advanceUntilIdle()
        assertEquals("Syncing…", vm.uiState.value.truthfulStatusText)

        // Tap while pass 1 running coalesces into active pass
        vm.triggerSyncNow()
        advanceUntilIdle()

        assertNull("Transient 'Sync requested' MUST be cleared back to null while worker runs", vm.uiState.value.statusMessage)
        assertEquals("Primary status MUST display 'Syncing…'", "Syncing…", vm.uiState.value.truthfulStatusText)
    }

    @Test
    fun test18_staleWorkerFinish_doesNotClearNewerWorkerSyncingStatus() = runTest {
        val pairingStore = FakePairingStore()
        pairingStore.savePairingConfig(
            com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
                hostIp = "192.168.1.10", port = 8443, pinnedCertFingerprint = "FINGERPRINT",
                deviceId = "FOLLOWER_1", keyAlias = "ALIAS_1", householdId = "household_1",
                mainDeviceId = "main_1", sessionToken = "token_1"
            )
        )
        val statusRepo = FollowerSyncStatusRepository(pairingStore)
        val fakeDb = PantrySyncPhase4gHostLifecycleTest.TestFakeFollowerDatabase()
        fakeDb.followerDao().updateSyncMetadata(
            com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity(
                singletonId = 1, bootstrapCompleted = true, lastSuccessfulPullAt = 1000L, lastReconciledCanonicalSequence = 5L
            )
        )

        val coordinator = createCoordinator(pairingStore, statusRepo)
        val vm = createHouseholdViewModel(
            coordinator = coordinator,
            pairingStore = pairingStore,
            statusRepo = statusRepo,
            followerDatabase = fakeDb
        )

        // Worker 1 starts
        statusRepo.reportWorkerStarted(1L)
        advanceUntilIdle()
        assertTrue(statusRepo.isWorkerRunning.value)

        // Worker 2 starts before worker 1 finishes
        statusRepo.reportWorkerStarted(2L)
        advanceUntilIdle()
        assertTrue(statusRepo.isWorkerRunning.value)

        // Worker 1 finishes (stale token)
        statusRepo.reportWorkerFinished(1L)
        advanceUntilIdle()

        assertTrue("Stale worker finish MUST NOT clear newer worker's isWorkerRunning state", statusRepo.isWorkerRunning.value)
        assertEquals("Syncing…", vm.uiState.value.truthfulStatusText)

        // Worker 2 finishes
        statusRepo.reportWorkerFinished(2L)
        advanceUntilIdle()

        assertFalse("Newer worker finish MUST clear isWorkerRunning state", statusRepo.isWorkerRunning.value)
        assertEquals("Healthy", vm.uiState.value.truthfulStatusText)
    }

    @Test
    fun test19_staleRepositoryRefresh_versusVisibleError() = runTest {
        val pairingStore = FakePairingStore()
        pairingStore.savePairingConfig(
            com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
                hostIp = "192.168.1.10", port = 8443, pinnedCertFingerprint = "FINGERPRINT",
                deviceId = "FOLLOWER_1", keyAlias = "ALIAS_1", householdId = "household_1",
                mainDeviceId = "main_1", sessionToken = "token_1"
            )
        )
        val statusRepo = FollowerSyncStatusRepository(pairingStore)
        val fakeDb = PantrySyncPhase4gHostLifecycleTest.TestFakeFollowerDatabase()
        fakeDb.followerDao().updateSyncMetadata(
            com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity(
                singletonId = 1, bootstrapCompleted = true, lastSuccessfulPullAt = 1000L, lastReconciledCanonicalSequence = 5L
            )
        )

        statusRepo.reportTransientSyncError("Connection refused")

        val coordinator = createCoordinator(pairingStore, statusRepo)
        val vm = createHouseholdViewModel(
            coordinator = coordinator,
            pairingStore = pairingStore,
            statusRepo = statusRepo,
            followerDatabase = fakeDb,
            asyncOverride = { _, _, _ -> WorkSchedulingResult.OPERATION_FAILED }
        )

        vm.triggerSyncNow()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("Connection refused", state.truthfulStatusText)
        assertEquals("Failed to schedule manual sync", state.errorMessage)

        vm.refreshFollowerStatus()
        advanceUntilIdle()

        assertEquals("Connection refused", vm.uiState.value.truthfulStatusText)
        assertEquals("Failed to schedule manual sync", vm.uiState.value.errorMessage)
    }

    @Test
    fun test20_confirmedEnqueue_displaysQueuedState_whileWorkerHasNotStarted() = runTest {
        val pairingStore = FakePairingStore()
        pairingStore.savePairingConfig(
            com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
                hostIp = "192.168.1.10", port = 8443, pinnedCertFingerprint = "FINGERPRINT",
                deviceId = "FOLLOWER_1", keyAlias = "ALIAS_1", householdId = "household_1",
                mainDeviceId = "main_1", sessionToken = "token_1"
            )
        )
        val statusRepo = FollowerSyncStatusRepository(pairingStore)
        val fakeDb = PantrySyncPhase4gHostLifecycleTest.TestFakeFollowerDatabase()
        fakeDb.followerDao().updateSyncMetadata(
            com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity(
                singletonId = 1, bootstrapCompleted = true, lastSuccessfulPullAt = 1000L, lastReconciledCanonicalSequence = 5L
            )
        )

        val coordinator = createCoordinator(pairingStore, statusRepo)
        val vm = createHouseholdViewModel(
            coordinator = coordinator,
            pairingStore = pairingStore,
            statusRepo = statusRepo,
            followerDatabase = fakeDb,
            asyncOverride = { _, _, _ -> WorkSchedulingResult.SUBMITTED_AND_CONFIRMED }
        )

        vm.triggerSyncNow()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("Sync queued", state.statusMessage)
        assertEquals("Sync queued", state.truthfulStatusText)
        assertFalse("Worker is not running yet; isSyncing MUST be false", state.isSyncing)
    }

    @Test
    fun test21_queuedWork_withNetworkUnavailable_displaysErrorOrQueuedState() = runTest {
        val pairingStore = FakePairingStore()
        pairingStore.savePairingConfig(
            com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
                hostIp = "192.168.1.10", port = 8443, pinnedCertFingerprint = "FINGERPRINT",
                deviceId = "FOLLOWER_1", keyAlias = "ALIAS_1", householdId = "household_1",
                mainDeviceId = "main_1", sessionToken = "token_1"
            )
        )
        val statusRepo = FollowerSyncStatusRepository(pairingStore)
        val fakeDb = PantrySyncPhase4gHostLifecycleTest.TestFakeFollowerDatabase()
        fakeDb.followerDao().updateSyncMetadata(
            com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity(
                singletonId = 1, bootstrapCompleted = true, lastSuccessfulPullAt = 1000L, lastReconciledCanonicalSequence = 5L
            )
        )

        statusRepo.reportTransientSyncError("Network unavailable")

        val coordinator = createCoordinator(pairingStore, statusRepo)
        val vm = createHouseholdViewModel(
            coordinator = coordinator,
            pairingStore = pairingStore,
            statusRepo = statusRepo,
            followerDatabase = fakeDb,
            asyncOverride = { _, _, _ -> WorkSchedulingResult.SUBMITTED_AND_CONFIRMED }
        )

        vm.triggerSyncNow()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("Sync error takes precedence over queued message", "Network unavailable", state.truthfulStatusText)
    }

    @Test
    fun test22_errorPriority_overRequestedAcknowledgment() = runTest {
        val pairingStore = FakePairingStore()
        pairingStore.savePairingConfig(
            com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
                hostIp = "192.168.1.10", port = 8443, pinnedCertFingerprint = "FINGERPRINT",
                deviceId = "FOLLOWER_1", keyAlias = "ALIAS_1", householdId = "household_1",
                mainDeviceId = "main_1", sessionToken = "token_1"
            )
        )
        val statusRepo = FollowerSyncStatusRepository(pairingStore)
        val fakeDb = PantrySyncPhase4gHostLifecycleTest.TestFakeFollowerDatabase()
        fakeDb.followerDao().updateSyncMetadata(
            com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity(
                singletonId = 1, bootstrapCompleted = true, lastSuccessfulPullAt = 1000L, lastReconciledCanonicalSequence = 5L
            )
        )

        val coordinator = createCoordinator(pairingStore, statusRepo)
        val vm = createHouseholdViewModel(
            coordinator = coordinator,
            pairingStore = pairingStore,
            statusRepo = statusRepo,
            followerDatabase = fakeDb,
            asyncOverride = { _, _, _ -> WorkSchedulingResult.OPERATION_FAILED }
        )

        vm.triggerSyncNow()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("Failed to schedule manual sync", state.truthfulStatusText)
        assertNull("statusMessage MUST be cleared on scheduling failure", state.statusMessage)
    }

    @Test
    fun test23_nullRepositoryStatus_displaysStatusUnavailable() = runTest {
        val pairingStore = FakePairingStore()
        val coordinator = createCoordinator(pairingStore, FollowerSyncStatusRepository(pairingStore))
        val vm = createHouseholdViewModel(
            coordinator = coordinator,
            pairingStore = pairingStore,
            statusRepo = null,
            followerDatabase = null
        )

        vm.loadHouseholdInfo()
        advanceUntilIdle()

        assertEquals("Status unavailable", vm.uiState.value.truthfulStatusText)
    }

    @Test
    fun test24_workerRunningAndFinishedTransitions() = runTest {
        val pairingStore = FakePairingStore()
        pairingStore.savePairingConfig(
            com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
                hostIp = "192.168.1.10", port = 8443, pinnedCertFingerprint = "FINGERPRINT",
                deviceId = "FOLLOWER_1", keyAlias = "ALIAS_1", householdId = "household_1",
                mainDeviceId = "main_1", sessionToken = "token_1"
            )
        )
        val statusRepo = FollowerSyncStatusRepository(pairingStore)
        val fakeDb = PantrySyncPhase4gHostLifecycleTest.TestFakeFollowerDatabase()
        fakeDb.followerDao().updateSyncMetadata(
            com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity(
                singletonId = 1, bootstrapCompleted = true, lastSuccessfulPullAt = 1000L, lastReconciledCanonicalSequence = 5L
            )
        )

        val coordinator = createCoordinator(pairingStore, statusRepo)
        val vm = createHouseholdViewModel(
            coordinator = coordinator,
            pairingStore = pairingStore,
            statusRepo = statusRepo,
            followerDatabase = fakeDb,
            asyncOverride = { _, _, _ -> WorkSchedulingResult.SUBMITTED_AND_CONFIRMED }
        )

        // Initial state
        vm.loadHouseholdInfo()
        advanceUntilIdle()
        assertEquals("Healthy", vm.uiState.value.truthfulStatusText)

        // Confirmed enqueue -> "Sync queued"
        vm.triggerSyncNow()
        advanceUntilIdle()
        assertEquals("Sync queued", vm.uiState.value.truthfulStatusText)

        // Worker starts -> "Syncing…"
        statusRepo.reportWorkerStarted(10L)
        advanceUntilIdle()
        assertEquals("Syncing…", vm.uiState.value.truthfulStatusText)
        assertTrue(vm.uiState.value.isSyncing)

        // Worker finishes -> "Healthy"
        statusRepo.reportWorkerFinished(10L)
        advanceUntilIdle()
        assertEquals("Healthy", vm.uiState.value.truthfulStatusText)
        assertFalse(vm.uiState.value.isSyncing)
    }

    @Test
    fun test25_staleOwnerTokenFinish_notificationIgnored() = runTest {
        val pairingStore = FakePairingStore()
        pairingStore.savePairingConfig(
            com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig(
                hostIp = "192.168.1.10", port = 8443, pinnedCertFingerprint = "FINGERPRINT",
                deviceId = "FOLLOWER_1", keyAlias = "ALIAS_1", householdId = "household_1",
                mainDeviceId = "main_1", sessionToken = "token_1"
            )
        )
        val statusRepo = FollowerSyncStatusRepository(pairingStore)
        val fakeDb = PantrySyncPhase4gHostLifecycleTest.TestFakeFollowerDatabase()
        fakeDb.followerDao().updateSyncMetadata(
            com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity(
                singletonId = 1, bootstrapCompleted = true, lastSuccessfulPullAt = 1000L, lastReconciledCanonicalSequence = 5L
            )
        )

        val coordinator = createCoordinator(pairingStore, statusRepo)
        val vm = createHouseholdViewModel(
            coordinator = coordinator,
            pairingStore = pairingStore,
            statusRepo = statusRepo,
            followerDatabase = fakeDb
        )

        // Worker 1 (token 100) starts
        statusRepo.reportWorkerStarted(100L)
        advanceUntilIdle()
        assertTrue(statusRepo.isWorkerRunning.value)

        // Worker 2 (token 200) starts
        statusRepo.reportWorkerStarted(200L)
        advanceUntilIdle()
        assertTrue(statusRepo.isWorkerRunning.value)

        // Worker 1 (stale token 100) finishes
        statusRepo.reportWorkerFinished(100L)
        advanceUntilIdle()
        assertTrue("Stale owner token finish MUST NOT clear active worker state", statusRepo.isWorkerRunning.value)
        assertEquals("Syncing…", vm.uiState.value.truthfulStatusText)

        // Worker 2 (active token 200) finishes
        statusRepo.reportWorkerFinished(200L)
        advanceUntilIdle()
        assertFalse("Active owner token finish MUST clear active worker state", statusRepo.isWorkerRunning.value)
        assertEquals("Healthy", vm.uiState.value.truthfulStatusText)
    }
}
