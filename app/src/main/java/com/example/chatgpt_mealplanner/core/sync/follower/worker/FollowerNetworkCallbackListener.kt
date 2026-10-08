package com.example.chatgpt_mealplanner.core.sync.follower.worker

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class FollowerNetworkCallbackListener @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val syncScheduler: FollowerSyncScheduler
) {
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    open fun registerListener() {
        if (networkCallback != null) return

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                println("[SYNC_NETWORK] Network onAvailable -> Enqueueing sync work")
                try {
                    android.util.Log.i("AI_MEALPLANNER", "[SYNC_NETWORK] Network onAvailable -> Enqueueing sync work")
                } catch (_: Throwable) {}

                syncScheduler.scheduleSyncWorkNonBlocking(context, "NETWORK_AVAILABLE")
            }
        }

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            println("[SYNC_NETWORK] Failed to register network callback: ${e.message}")
        }
    }

    open fun unregisterListener() {
        if (networkCallback == null) return
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback!!)
        } catch (_: Exception) {}
        networkCallback = null
    }
}
