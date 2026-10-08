package com.example.chatgpt_mealplanner.sync

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.chatgpt_mealplanner.MealPlannerApplication
import com.example.chatgpt_mealplanner.core.sync.follower.worker.FollowerOutboxSyncWorker
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FollowerOutboxSyncWorkerInstrumentedTest {

    @Test
    fun testInstrumentation_workManagerSuccessfullyConstructsFollowerOutboxSyncWorker() {
        val app = ApplicationProvider.getApplicationContext<MealPlannerApplication>()
        assertNotNull("Application must exist", app)

        val config = app.workManagerConfiguration
        assertNotNull("workManagerConfiguration must be non-null", config)

        try {
            WorkManagerTestInitHelper.initializeTestWorkManager(app, config)
        } catch (_: Exception) {}

        val workManager = WorkManager.getInstance(app)
        assertNotNull(workManager)

        val request = OneTimeWorkRequestBuilder<FollowerOutboxSyncWorker>().build()
        workManager.enqueue(request)

        val workInfo = workManager.getWorkInfoById(request.id).get()
        assertNotNull(workInfo)
        assertTrue(
            "FollowerOutboxSyncWorker construction MUST succeed without NoSuchMethodException",
            workInfo.state != null
        )
    }
}
