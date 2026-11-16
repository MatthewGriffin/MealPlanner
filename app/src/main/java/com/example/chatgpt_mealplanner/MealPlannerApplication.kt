package com.example.chatgpt_mealplanner

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.chatgpt_mealplanner.core.sync.follower.worker.FollowerNetworkCallbackListener
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

const val PANTRY_DEBUG_BUILD_ID = "SYNC_PHASE4G_SYNC_NOW_V1_1"

@HiltAndroidApp
class MealPlannerApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var followerNetworkCallbackListener: FollowerNetworkCallbackListener

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        // Runs first because super.onCreate() starts dependency injection, which uses the database.
        com.example.chatgpt_mealplanner.core.backup.FullBackupRestoreInstaller.applyPendingRestore(this)
        super.onCreate()
        com.example.chatgpt_mealplanner.core.sync.server.NettyShutdownGuard.install()
        followerNetworkCallbackListener.registerListener()
        com.example.chatgpt_mealplanner.core.worker.IcelandCatalogueRefreshScheduler.schedule(this)
        com.example.chatgpt_mealplanner.core.worker.DataCleanupWorker.schedule(this)
        com.example.chatgpt_mealplanner.core.notification.AppNotificationManager(this).createNotificationChannel()
        val buildLog = """
            [PANTRY_BUILD] buildId=$PANTRY_DEBUG_BUILD_ID
            [PANTRY_BUILD] versionName=${BuildConfig.VERSION_NAME}
            [PANTRY_BUILD] versionCode=${BuildConfig.VERSION_CODE}
            [PANTRY_BUILD] packageName=$packageName
            [PANTRY_BUILD] pid=${android.os.Process.myPid()}
        """.trimIndent()
        println(buildLog)
        Log.i("AI_MEALPLANNER", buildLog)
        Log.i("PANTRY_BUILD", buildLog)
    }
}
