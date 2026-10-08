package com.example.chatgpt_mealplanner.core.sync.follower.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerSyncCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import javax.inject.Inject
import javax.inject.Singleton

enum class WorkSchedulingResult {
    SUBMITTED_AND_CONFIRMED,
    INTENT_COALESCED_IN_MEMORY,
    OPERATION_FAILED,
    CANCELLED
}

enum class NonBlockingSchedulingResult {
    SUBMITTED_NOT_CONFIRMED,
    OPERATION_FAILED
}

@Singleton
open class FollowerSyncScheduler @Inject constructor(
    private val coordinatorLazy: dagger.Lazy<FollowerSyncCoordinator>
) {
    private val coordinator: FollowerSyncCoordinator?
        get() = try { coordinatorLazy.get() } catch (_: Throwable) { null }

    constructor() : this(
        dagger.Lazy { FollowerSyncCoordinator(null, null, null, null, null, null, null) }
    )

    open fun recordManualSyncIntent(): Boolean {
        val coord = coordinator ?: return true
        return coord.requestManualSync()
    }

    open fun scheduleSyncWork(context: Context?, triggerReason: String): WorkSchedulingResult {
        return scheduleSyncWork(context, triggerReason, intentAlreadyRecorded = false)
    }

    open fun scheduleSyncWork(context: Context?, triggerReason: String, intentAlreadyRecorded: Boolean): WorkSchedulingResult {
        val isManual = triggerReason == "MANUAL_SYNC" || triggerReason == "USER_RETRY" || triggerReason == "USER_MANUAL_TRIGGER"

        val coord = coordinator
        var shouldEnqueueManual = true
        if (isManual && !intentAlreadyRecorded && coord != null) {
            shouldEnqueueManual = coord.requestManualSync()
        }

        if (context == null) {
            return if (isManual && !shouldEnqueueManual) WorkSchedulingResult.INTENT_COALESCED_IN_MEMORY else WorkSchedulingResult.OPERATION_FAILED
        }

        if (isManual && !shouldEnqueueManual) {
            println("[SYNC_SCHEDULE] Manual intent recorded synchronously in coordinator; pass actively running, skipping WorkManager enqueue.")
            return WorkSchedulingResult.INTENT_COALESCED_IN_MEMORY
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workName = if (isManual) MANUAL_WORK_NAME else AUTO_WORK_NAME
        val workTag = if (isManual) "MANUAL" else "AUTO"

        val inputData = androidx.work.Data.Builder()
            .putString(KEY_WORK_LANE, if (isManual) LANE_MANUAL else LANE_AUTO)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<FollowerOutboxSyncWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .addTag(workTag)
            .apply {
                if (!isManual) {
                    setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                }
            }
            .build()

        return try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName,
                ExistingWorkPolicy.KEEP,
                workRequest
            )
            WorkSchedulingResult.SUBMITTED_AND_CONFIRMED
        } catch (e: Throwable) {
            println("[SYNC_SCHEDULE] WorkManager enqueue skipped or failed: ${e.message}")
            WorkSchedulingResult.OPERATION_FAILED
        }
    }

    open fun scheduleSyncWorkNonBlocking(context: Context?, triggerReason: String): NonBlockingSchedulingResult {
        val result = scheduleSyncWork(context, triggerReason)
        return if (result == WorkSchedulingResult.OPERATION_FAILED) NonBlockingSchedulingResult.OPERATION_FAILED else NonBlockingSchedulingResult.SUBMITTED_NOT_CONFIRMED
    }

    open suspend fun scheduleSyncWorkAsync(context: Context?, triggerReason: String): WorkSchedulingResult {
        return scheduleSyncWorkAsync(context, triggerReason, intentAlreadyRecorded = false)
    }

    open suspend fun scheduleSyncWorkAsync(context: Context?, triggerReason: String, intentAlreadyRecorded: Boolean): WorkSchedulingResult {
        val isManual = triggerReason == "MANUAL_SYNC" || triggerReason == "USER_RETRY" || triggerReason == "USER_MANUAL_TRIGGER"

        val coord = coordinator
        var shouldEnqueueManual = true
        if (isManual && !intentAlreadyRecorded && coord != null) {
            shouldEnqueueManual = coord.requestManualSync()
        }

        if (isManual && !shouldEnqueueManual) {
            println("[SYNC_SCHEDULE] Manual intent recorded synchronously in coordinator; pass actively running, skipping WorkManager enqueue.")
            return WorkSchedulingResult.INTENT_COALESCED_IN_MEMORY
        }

        val appContext = context?.applicationContext ?: return WorkSchedulingResult.OPERATION_FAILED
        return scheduleSyncWorkAsyncInternal(appContext, isManual, triggerReason)
    }

    private suspend fun scheduleSyncWorkAsyncInternal(
        context: Context,
        isManual: Boolean,
        triggerReason: String
    ): WorkSchedulingResult {
        println("[SYNC_SCHEDULE] trigger=$triggerReason isManual=$isManual")
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workName = if (isManual) MANUAL_WORK_NAME else AUTO_WORK_NAME
        val workTag = if (isManual) "MANUAL" else "AUTO"

        val inputData = androidx.work.Data.Builder()
            .putString(KEY_WORK_LANE, if (isManual) LANE_MANUAL else LANE_AUTO)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<FollowerOutboxSyncWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .addTag(workTag)
            .apply {
                if (!isManual) {
                    setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                }
            }
            .build()

        return try {
            val operation = WorkManager.getInstance(context).enqueueUniqueWork(
                workName,
                ExistingWorkPolicy.KEEP,
                workRequest
            )
            awaitOperationResult(operation)
        } catch (e: CancellationException) {
            println("[SYNC_SCHEDULE] WorkManager enqueue cancelled: ${e.message}")
            WorkSchedulingResult.CANCELLED
        } catch (e: Throwable) {
            println("[SYNC_SCHEDULE] WorkManager enqueue skipped or failed: ${e.message}")
            WorkSchedulingResult.OPERATION_FAILED
        }
    }

    private suspend fun awaitOperationResult(operation: Operation): WorkSchedulingResult {
        return suspendCancellableCoroutine { continuation ->
            operation.result.addListener({
                try {
                    operation.result.get()
                    continuation.resume(WorkSchedulingResult.SUBMITTED_AND_CONFIRMED)
                } catch (e: java.util.concurrent.ExecutionException) {
                    if (e.cause is CancellationException) {
                        continuation.resume(WorkSchedulingResult.CANCELLED)
                    } else {
                        continuation.resume(WorkSchedulingResult.OPERATION_FAILED)
                    }
                } catch (e: CancellationException) {
                    continuation.resume(WorkSchedulingResult.CANCELLED)
                } catch (e: Throwable) {
                    continuation.resume(WorkSchedulingResult.OPERATION_FAILED)
                }
            }, { command ->
                sharedSchedulerExecutor.execute(command)
            })
        }
    }

    open fun resolveWorkPolicy(triggerReason: String): ExistingWorkPolicy {
        return ExistingWorkPolicy.KEEP
    }

    companion object {
        const val WORK_NAME = "pantry_follower_outbox_sync"
        const val AUTO_WORK_NAME = "pantry_follower_outbox_sync"
        const val MANUAL_WORK_NAME = "pantry_follower_manual_sync"
        const val KEY_WORK_LANE = "KEY_WORK_LANE"
        const val LANE_AUTO = "LANE_AUTO"
        const val LANE_MANUAL = "LANE_MANUAL"

        val sharedSchedulerExecutor = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "pantry-sync-scheduler-worker").apply { isDaemon = true }
        }
    }
}
