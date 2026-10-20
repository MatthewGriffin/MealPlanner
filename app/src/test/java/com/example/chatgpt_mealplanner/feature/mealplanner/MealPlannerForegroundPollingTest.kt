package com.example.chatgpt_mealplanner.feature.mealplanner

import com.example.chatgpt_mealplanner.core.database.entity.MealChangeRequestEntity
import com.example.chatgpt_mealplanner.core.repository.MealChangeRequestRepository
import com.example.chatgpt_mealplanner.core.repository.MealRepository
import com.example.chatgpt_mealplanner.core.service.MealDomainService
import com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import com.example.chatgpt_mealplanner.core.sync.follower.worker.FollowerSyncScheduler
import com.example.chatgpt_mealplanner.core.sync.follower.worker.NonBlockingSchedulingResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class MealPlannerForegroundPollingTest {

    private lateinit var testDispatcher: TestDispatcher

    @Before
    fun setUp() {
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun eligibleStatuses_containsAwaitingStatuses_andExcludesTerminalStatuses() {
        val awaiting = MealPlannerViewModel.AWAITING_MAIN_STATUSES

        assertTrue("QUEUED must be eligible for polling", awaiting.contains("QUEUED"))
        assertTrue("PENDING must be eligible for polling", awaiting.contains("PENDING"))
        assertTrue("DISCUSSED must be eligible for polling", awaiting.contains("DISCUSSED"))
        assertTrue("SYNC_FAILED must be eligible for polling", awaiting.contains("SYNC_FAILED"))

        assertFalse("ACCEPTED is terminal and must not trigger polling", awaiting.contains("ACCEPTED"))
        assertFalse("DECLINED is terminal and must not trigger polling", awaiting.contains("DECLINED"))
    }

    @Test
    fun pollingStarts_whenFollowerHasEligibleAwaitingRequest() = runTest(testDispatcher) {
        val pendingRequest = createTestRequest(id = "req_1", status = "PENDING")
        val viewModel = createTestViewModel(requests = listOf(pendingRequest), isFollower = true)

        viewModel.setPlannerForeground(true)
        viewModel.updatePollingState(listOf(pendingRequest), isFollower = true)
        assertTrue("Polling job must be active when foreground follower has a PENDING request", viewModel.isPollingActive)

        viewModel.stopPolling()
    }

    @Test
    fun pollingStops_whenRequestsBecomeTerminalOrEmpty() = runTest(testDispatcher) {
        val pendingRequest = createTestRequest(id = "req_1", status = "PENDING")
        val acceptedRequest = createTestRequest(id = "req_2", status = "ACCEPTED")
        val viewModel = createTestViewModel(requests = listOf(pendingRequest), isFollower = true)

        viewModel.setPlannerForeground(true)
        viewModel.updatePollingState(listOf(pendingRequest), isFollower = true)
        assertTrue("Polling job should be active for pending request", viewModel.isPollingActive)

        viewModel.updatePollingState(listOf(acceptedRequest), isFollower = true)
        assertFalse("Polling job must stop promptly when request transitions to ACCEPTED", viewModel.isPollingActive)

        viewModel.updatePollingState(emptyList(), isFollower = true)
        assertFalse("Polling job must remain inactive when requests list is empty", viewModel.isPollingActive)

        viewModel.stopPolling()
    }

    @Test
    fun pollingStops_whenRoleIsNoLongerFollower() = runTest(testDispatcher) {
        val pendingRequest = createTestRequest(id = "req_1", status = "PENDING")
        val viewModel = createTestViewModel(requests = listOf(pendingRequest), isFollower = true)

        viewModel.setPlannerForeground(true)
        viewModel.updatePollingState(listOf(pendingRequest), isFollower = true)
        assertTrue("Polling job should be active for foreground follower", viewModel.isPollingActive)

        viewModel.updatePollingState(listOf(pendingRequest), isFollower = false)
        assertFalse("Polling job must stop promptly when role changes to Main", viewModel.isPollingActive)

        viewModel.stopPolling()
    }

    @Test
    fun lifecycleCleanup_stopPollingCancelsJob() = runTest(testDispatcher) {
        val pendingRequest = createTestRequest(id = "req_1", status = "PENDING")
        val viewModel = createTestViewModel(requests = listOf(pendingRequest), isFollower = true)

        viewModel.setPlannerForeground(true)
        viewModel.updatePollingState(listOf(pendingRequest), isFollower = true)
        assertTrue("Polling job should be active", viewModel.isPollingActive)

        viewModel.stopPolling()
        assertFalse("stopPolling must cancel the active polling job", viewModel.isPollingActive)
    }

    @Test
    fun pollingDoesNotStartWhilePlannerIsBackgrounded() = runTest(testDispatcher) {
        val pendingRequest = createTestRequest(id = "req_1", status = "PENDING")
        val viewModel = createTestViewModel(requests = listOf(pendingRequest), isFollower = true)

        viewModel.updatePollingState(listOf(pendingRequest), isFollower = true)

        assertFalse("Polling must remain stopped until the planner enters foreground", viewModel.isPollingActive)
        viewModel.stopPolling()
    }

    @Test
    fun pollingStopsInBackgroundAndResumesInForeground() = runTest(testDispatcher) {
        val pendingRequest = createTestRequest(id = "req_1", status = "PENDING")
        val viewModel = createTestViewModel(requests = listOf(pendingRequest), isFollower = true)

        viewModel.updatePollingState(listOf(pendingRequest), isFollower = true)
        viewModel.setPlannerForeground(true)
        assertTrue("Polling should start when a pending request exists in foreground", viewModel.isPollingActive)

        viewModel.setPlannerForeground(false)
        assertFalse("Polling must stop when planner leaves foreground", viewModel.isPollingActive)

        viewModel.setPlannerForeground(true)
        assertTrue("Polling should resume when planner returns to foreground", viewModel.isPollingActive)
        viewModel.stopPolling()
    }

    @Test
    fun pollingTriggersSyncAfterFiveSecondDelay() = runTest(testDispatcher) {
        val pendingRequest = createTestRequest(id = "req_1", status = "PENDING")
        var syncTriggerCallCount = 0
        val fakeScheduler = object : FollowerSyncScheduler() {
            override fun scheduleSyncWorkNonBlocking(
                context: android.content.Context?,
                triggerReason: String
            ): NonBlockingSchedulingResult {
                assertEquals("AUTO_SYNC", triggerReason)
                syncTriggerCallCount++
                return NonBlockingSchedulingResult.SUBMITTED_NOT_CONFIRMED
            }
        }
        val viewModel = createTestViewModel(
            requests = listOf(pendingRequest),
            isFollower = true,
            context = mock(android.content.Context::class.java),
            scheduler = fakeScheduler
        )

        viewModel.setPlannerForeground(true)
        viewModel.updatePollingState(listOf(pendingRequest), isFollower = true)
        runCurrent()
        assertEquals("Sync must not trigger before the polling interval", 0, syncTriggerCallCount)

        advanceTimeBy(4_999)
        runCurrent()
        assertEquals("Sync must not trigger before five seconds", 0, syncTriggerCallCount)

        advanceTimeBy(1)
        runCurrent()
        assertEquals("Sync should trigger at the five-second polling interval", 1, syncTriggerCallCount)

        viewModel.stopPolling()
    }

    @Test
    fun safeSyncTrigger_handlesNullAndActiveSchedulerWithoutCrashing() = runTest(testDispatcher) {
        val pendingRequest = createTestRequest(id = "req_1", status = "PENDING")
        var syncTriggerCallCount = 0

        val fakeScheduler = object : FollowerSyncScheduler() {
            override fun scheduleSyncWorkNonBlocking(
                context: android.content.Context?,
                triggerReason: String
            ): NonBlockingSchedulingResult {
                assertEquals("AUTO_SYNC", triggerReason)
                syncTriggerCallCount++
                return NonBlockingSchedulingResult.SUBMITTED_NOT_CONFIRMED
            }
        }

        val mockContext = mock(android.content.Context::class.java)
        val viewModel = createTestViewModel(
            requests = listOf(pendingRequest),
            isFollower = true,
            context = mockContext,
            scheduler = fakeScheduler
        )

        viewModel.triggerFollowerSync()
        assertEquals(1, syncTriggerCallCount)

        viewModel.stopPolling()
    }

    private fun createTestRequest(id: String, status: String): MealChangeRequestEntity {
        return MealChangeRequestEntity(
            id = id,
            mealId = "meal_100",
            authorDeviceId = "follower_dev_1",
            requestText = "Can we swap this?",
            status = status,
            mainResponse = if (status == "ACCEPTED") "Sure" else null,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun createTestViewModel(
        requests: List<MealChangeRequestEntity>,
        isFollower: Boolean,
        context: android.content.Context? = mock(android.content.Context::class.java),
        scheduler: FollowerSyncScheduler? = mock(FollowerSyncScheduler::class.java)
    ): MealPlannerViewModel {
        val mockMealDomainService = mock(MealDomainService::class.java)
        val mockMealRepo = mock(MealRepository::class.java)
        val mockLeftoverRepo = mock(com.example.chatgpt_mealplanner.core.repository.LeftoverRepository::class.java)

        val fakeRequestRepo = object : MealChangeRequestRepository {
            override fun observeRequestsForMeal(mealId: String): Flow<List<MealChangeRequestEntity>> = flowOf(requests)
            override fun observeAllRequests(): Flow<List<MealChangeRequestEntity>> = flowOf(requests)
            override suspend fun submitRequest(mealId: String, requestText: String) {}
            override suspend fun updateRequestStatus(requestId: String, status: String, mainResponse: String?) {}
        }

        val fakePairingStore = FollowerPairingStore(null)
        val fakeRoleResolver = object : AppRoleResolver(fakePairingStore) {
            override fun isFollower(): Boolean = isFollower
            override suspend fun isFollowerAsync(): Boolean = isFollower
        }

        return MealPlannerViewModel(
            mealDomainService = mockMealDomainService,
            mealRepository = mockMealRepo,
            leftoverRepository = mockLeftoverRepo,
            mealChangeRequestRepository = fakeRequestRepo,
            appRoleResolver = fakeRoleResolver,
            context = context,
            followerSyncScheduler = scheduler
        )
    }


}
