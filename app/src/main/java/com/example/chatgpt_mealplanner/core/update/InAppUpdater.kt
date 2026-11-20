package com.example.chatgpt_mealplanner.core.update

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Updates the app from inside it, through Google Play, so testers don't have to open the store. A newer version is
 * downloaded in the background after one tap on Play's own prompt, then [readyToInstall] asks to restart into it.
 * Builds not installed from Play (debug, sideloaded) simply report nothing, and nothing happens.
 */
class InAppUpdater(private val activity: ComponentActivity) {
    private val manager = AppUpdateManagerFactory.create(activity)

    /** True once an update has finished downloading and only needs the app to restart. */
    val readyToInstall = mutableStateOf(false)

    private var askedThisSession = false

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        // Declining is fine; the next launch of the app asks again.
        if (result.resultCode != Activity.RESULT_OK) askedThisSession = true
    }

    private val listener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) readyToInstall.value = true
    }

    /** Looks for a newer version; call when the app comes to the front. */
    fun check() {
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                when {
                    info.installStatus() == InstallStatus.DOWNLOADED -> readyToInstall.value = true
                    info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                        info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) && !askedThisSession -> {
                        askedThisSession = true
                        manager.registerListener(listener)
                        runCatching {
                            manager.startUpdateFlowForResult(
                                info,
                                launcher,
                                AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build()
                            )
                        }
                    }
                }
            }
            .addOnFailureListener { /* not installed from Play, or Play unavailable: nothing to do */ }
    }

    /** Restarts the app into the downloaded version. */
    fun install() {
        manager.completeUpdate()
    }

    fun dismissPrompt() {
        readyToInstall.value = false
    }

    fun release() {
        runCatching { manager.unregisterListener(listener) }
    }
}
