package com.example.chatgpt_mealplanner

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

const val PANTRY_DEBUG_BUILD_ID = "SYNC_PHASE3C_AUTO_SYNC_V3"

@HiltAndroidApp
class MealPlannerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
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
