package com.example.chatgpt_mealplanner.core.sync.server

import com.example.chatgpt_mealplanner.core.util.rethrowIfCancelled
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.chatgpt_mealplanner.core.sync.HouseholdIdentityManager
import com.example.chatgpt_mealplanner.core.sync.security.PantryTlsKeyManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class HostServiceState {
    STOPPED,
    STARTING,
    READY,
    FAILED
}

@AndroidEntryPoint
class PantrySyncHostService : Service() {

    @Inject
    lateinit var pantrySyncServer: PantrySyncServer

    @Inject
    lateinit var identityManager: HouseholdIdentityManager

    @Inject
    lateinit var tlsKeyManager: PantryTlsKeyManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channelId = "pantry_sync_host_channel"
    private val notificationId = 8443

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopServerAndService()
            return START_NOT_STICKY
        }

        try {
            startForeground(notificationId, createNotification())
        } catch (e: Throwable) {
            println("[HOST_SERVICE] startForeground failed: ${e.message}")
            try { android.util.Log.e("AI_MEALPLANNER", "[HOST_SERVICE] startForeground failed", e) } catch (_: Throwable) {}
            _serviceState.value = HostServiceState.FAILED
            stopSelf()
            return START_NOT_STICKY
        }

        _serviceState.value = HostServiceState.STARTING

        serviceScope.launch {
            try {
                if (!pantrySyncServer.isRunning()) {
                    val household = identityManager.getOrCreateHouseholdIdentity()
                    val p12Store = tlsKeyManager.getOrCreateMainTlsKeyStore(household.householdId)
                    val certFingerprint = tlsKeyManager.getCertificateFingerprint(p12Store)
                    val password = tlsKeyManager.getTlsPassword()

                    if (com.example.chatgpt_mealplanner.BuildConfig.DEBUG) {
                        println("[TLS_IDENTITY] buildId=SYNC_PHASE2B_HARDENED_V2 householdId=${household.householdId} certFingerprint=$certFingerprint")
                        try { android.util.Log.i("AI_MEALPLANNER", "[TLS_IDENTITY] buildId=SYNC_PHASE2B_HARDENED_V2 householdId=${household.householdId} certFingerprint=$certFingerprint") } catch (_: Throwable) {}
                    }

                    pantrySyncServer.start(
                        keyStore = p12Store,
                        password = password,
                        port = 8443
                    )
                }
                _serviceState.value = HostServiceState.READY
            } catch (e: Exception) {
                _serviceState.value = HostServiceState.FAILED
                rethrowIfCancelled()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stopServerAndService()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopServerAndService() {
        pantrySyncServer.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        _serviceState.value = HostServiceState.STOPPED
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Pantry Household Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Runs local household synchronization host"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Pantry Household Sync")
            .setContentText("Pantry household sync active")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_START = "com.example.chatgpt_mealplanner.sync.START_HOST"
        const val ACTION_STOP = "com.example.chatgpt_mealplanner.sync.STOP_HOST"

        private val _serviceState = kotlinx.coroutines.flow.MutableStateFlow(HostServiceState.STOPPED)
        val serviceState: kotlinx.coroutines.flow.StateFlow<HostServiceState> = _serviceState.asStateFlow()

        fun resetFailedState() {
            if (_serviceState.value == HostServiceState.FAILED) {
                _serviceState.value = HostServiceState.STOPPED
            }
        }

        fun resetStateForTest() {
            _serviceState.value = HostServiceState.STOPPED
        }

        fun setStartingForTest() {
            if (_serviceState.value == HostServiceState.STOPPED) {
                _serviceState.value = HostServiceState.STARTING
            }
        }
    }
}
