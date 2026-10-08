package com.example.chatgpt_mealplanner.core.sync.follower.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.example.chatgpt_mealplanner.core.sync.follower.engine.CoordinatorPassOutcome
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerSyncCoordinator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class FollowerOutboxSyncWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val coordinator: FollowerSyncCoordinator,
    private val followerSyncScheduler: FollowerSyncScheduler? = null
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val laneParam = inputData.getString(FollowerSyncScheduler.KEY_WORK_LANE)
        val isManual = when (laneParam) {
            FollowerSyncScheduler.LANE_MANUAL -> true
            FollowerSyncScheduler.LANE_AUTO -> false
            null -> {
                val hasManualTag = runCatching { tags.contains("MANUAL") }.getOrDefault(false)
                println("[SYNC_WORKER] Missing KEY_WORK_LANE in inputData. Falling back to tag check (isManual=$hasManualTag).")
                hasManualTag
            }
            else -> {
                println("[SYNC_WORKER] ERROR: Explicitly invalid KEY_WORK_LANE param '$laneParam'. Failing worker safely.")
                return Result.failure()
            }
        }
        val triggerSource = if (isManual) "MANUAL" else "AUTO"

        println("[SYNC_WORKER] start triggerSource=$triggerSource workerId=$id")

        val syncOutcome = coordinator.executeSyncPass(triggerSource)

        return when (syncOutcome) {
            CoordinatorPassOutcome.SUCCESS -> Result.success()
            CoordinatorPassOutcome.SKIPPED_ACTIVE_PASS -> {
                if (isManual) {
                    Result.success()
                } else {
                    Result.retry()
                }
            }
            CoordinatorPassOutcome.RECOVERABLE_FAILURE -> {
                if (isManual) {
                    val autoResult = followerSyncScheduler?.scheduleSyncWorkAsync(appContext, "OUTBOX_COMMIT")
                    if (autoResult == WorkSchedulingResult.SUBMITTED_AND_CONFIRMED || autoResult == WorkSchedulingResult.INTENT_COALESCED_IN_MEMORY) {
                        println("[SYNC_WORKER] Manual pass failed. AUTO recovery confirmed ($autoResult). Terminating manual lane with Result.failure().")
                        Result.failure()
                    } else {
                        println("[SYNC_WORKER] Manual pass failed and AUTO recovery dispatch failed ($autoResult). Terminating manual lane with Result.failure().")
                        Result.failure()
                    }
                } else {
                    Result.retry()
                }
            }
            CoordinatorPassOutcome.REVOKED -> Result.failure()
        }
    }
}
