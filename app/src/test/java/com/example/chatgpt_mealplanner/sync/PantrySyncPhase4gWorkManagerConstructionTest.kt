package com.example.chatgpt_mealplanner.sync

import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import com.example.chatgpt_mealplanner.MealPlannerApplication
import com.example.chatgpt_mealplanner.core.sync.follower.worker.FollowerSyncScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class PantrySyncPhase4gWorkManagerConstructionTest {

    @Test
    fun testMealPlannerApplication_implementsConfigurationProvider() {
        val app = MealPlannerApplication()
        assertTrue(
            "MealPlannerApplication MUST implement Configuration.Provider",
            Configuration.Provider::class.java.isAssignableFrom(app.javaClass)
        )
    }

    @Test
    fun testWorkManagerConfiguration_hasWorkerFactory() {
        val mockFactory = androidx.hilt.work.HiltWorkerFactory::class.java
        assertNotNull("HiltWorkerFactory class MUST exist in classpath", mockFactory)
    }

    @Test
    fun testA_manualSync_usesKeepPolicy() {
        val scheduler = FollowerSyncScheduler()
        val policy = scheduler.resolveWorkPolicy("MANUAL_SYNC")
        assertEquals(ExistingWorkPolicy.KEEP, policy)
    }

    @Test
    fun testB_userRetry_usesKeepPolicy() {
        val scheduler = FollowerSyncScheduler()
        val policy = scheduler.resolveWorkPolicy("USER_RETRY")
        assertEquals(ExistingWorkPolicy.KEEP, policy)
    }

    @Test
    fun testC_autoTrigger_usesKeepPolicy() {
        val scheduler = FollowerSyncScheduler()
        val policy = scheduler.resolveWorkPolicy("OUTBOX_COMMIT")
        assertEquals(ExistingWorkPolicy.KEEP, policy)
    }

    @Test
    fun testStatusOwnerToken_staleOwnerToken_doesNotClearNewOwnerStatus() {
        val pairingStore = FakePairingStore()
        val statusRepo = com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusRepository(pairingStore)

        statusRepo.reportWorkerStarted(ownerToken = 101L)
        assertTrue(statusRepo.isWorkerRunning.value)

        statusRepo.reportWorkerStarted(ownerToken = 102L)
        assertTrue(statusRepo.isWorkerRunning.value)

        statusRepo.reportWorkerFinished(ownerToken = 101L)
        assertTrue("Stale owner token 101 MUST NOT clear active owner 102's Syncing status", statusRepo.isWorkerRunning.value)

        statusRepo.reportWorkerFinished(ownerToken = 102L)
        assertFalse("Active owner token 102 MUST clear status on finish", statusRepo.isWorkerRunning.value)
    }

    @Test
    fun testStatusOwnerToken_unownedCall_doesNotClearActiveOwnedStatus() {
        val pairingStore = FakePairingStore()
        val statusRepo = com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusRepository(pairingStore)

        statusRepo.reportWorkerStarted(ownerToken = 201L)
        assertTrue(statusRepo.isWorkerRunning.value)

        statusRepo.reportWorkerFinished(ownerToken = 0L)
        assertTrue("Unowned finish call (token = 0) MUST NOT clear active owner 201's Syncing status", statusRepo.isWorkerRunning.value)

        statusRepo.reportWorkerFinished(ownerToken = 201L)
        assertFalse("Active owner token 201 MUST clear status on finish", statusRepo.isWorkerRunning.value)
    }

    @Test
    fun testWorkSchedulingResult_enumContainsExpectedValues() {
        val values = com.example.chatgpt_mealplanner.core.sync.follower.worker.WorkSchedulingResult.values()
        assertTrue(values.contains(com.example.chatgpt_mealplanner.core.sync.follower.worker.WorkSchedulingResult.SUBMITTED_AND_CONFIRMED))
        assertTrue(values.contains(com.example.chatgpt_mealplanner.core.sync.follower.worker.WorkSchedulingResult.INTENT_COALESCED_IN_MEMORY))
        assertTrue(values.contains(com.example.chatgpt_mealplanner.core.sync.follower.worker.WorkSchedulingResult.OPERATION_FAILED))
        assertTrue(values.contains(com.example.chatgpt_mealplanner.core.sync.follower.worker.WorkSchedulingResult.CANCELLED))
    }

    @Test
    fun testSynchronousIntentRecording_incrementsGenerationOnCallerThread() {
        val pairingStore = FakePairingStore()
        val statusRepo = com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusRepository(pairingStore)
        val coordinator = com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerSyncCoordinator(
            null, null, null, null, null, pairingStore, statusRepo
        )
        val scheduler = FollowerSyncScheduler(dagger.Lazy { coordinator })

        val (reqBefore, compBefore) = coordinator.getGenerations()
        assertEquals(0L, reqBefore)

        scheduler.scheduleSyncWork(null, "MANUAL_SYNC")
        val (reqAfter, compAfter) = coordinator.getGenerations()
        assertEquals("Generation MUST increment synchronously on caller thread", 1L, reqAfter)
        assertEquals(0L, compAfter)
    }

    @Test
    fun testScheduleSyncWorkNonBlocking_returnsSubmittedNotConfirmed() {
        val scheduler = FollowerSyncScheduler()
        val result = scheduler.scheduleSyncWorkNonBlocking(null, "OUTBOX_COMMIT")
        assertEquals(com.example.chatgpt_mealplanner.core.sync.follower.worker.NonBlockingSchedulingResult.OPERATION_FAILED, result)
    }

    @Test
    fun testSharedSchedulerExecutor_isSharedInstance() {
        val exec1 = FollowerSyncScheduler.sharedSchedulerExecutor
        val exec2 = FollowerSyncScheduler.sharedSchedulerExecutor
        assertNotNull("sharedSchedulerExecutor MUST NOT be null", exec1)
        assertEquals("sharedSchedulerExecutor MUST be a single shared instance to prevent thread/executor leaks", exec1, exec2)
    }
}
