package com.example.chatgpt_mealplanner.core.sync.follower.engine

import android.content.Context
import android.net.ConnectivityManager
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds the main phone again when its home-network address has changed (routers hand out new addresses). The follower
 * remembers only the address it was paired at, so without this a changed address meant it could never sync again.
 *
 * It tries every address on the follower's own network, and only accepts one whose TLS certificate matches the one pinned
 * when the phones were paired, so it can't be fooled into talking to another device.
 */
@Singleton
open class FollowerHostLocator @Inject constructor(
    @param:ApplicationContext private val context: Context?,
    private val client: PantrySyncTestClient?
) {
    private var lastScanAt = 0L

    /** The main phone's current address on this network, or null if it isn't found or a scan was done too recently. */
    open suspend fun findHost(port: Int, pinnedCertFingerprint: String, knownIp: String?, nowMs: Long = System.currentTimeMillis()): String? {
        if (nowMs - lastScanAt < MIN_SCAN_GAP_MS) return null
        lastScanAt = nowMs
        val ownIp = localIpv4() ?: return null
        val pinned = client ?: return null
        val http = try {
            pinned.createPinnedOkHttpClient(pinnedCertFingerprint).newBuilder().callTimeout(2_500, TimeUnit.MILLISECONDS).build()
        } catch (_: Exception) {
            return null
        }
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                for (chunk in candidates(ownIp, knownIp).chunked(CONCURRENCY)) {
                    val found = coroutineScope {
                        chunk.map { ip ->
                            async {
                                if (portOpen(ip, port) && isPinnedHost(http, ip, port)) ip else null
                            }
                        }.awaitAll().firstOrNull { it != null }
                    }
                    if (found != null) return@withTimeoutOrNull found
                }
                null
            }
        }
    }

    private fun localIpv4(): String? {
        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val network = cm.activeNetwork ?: return null
        return cm.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull { !it.isLoopbackAddress }?.hostAddress
    }

    private fun portOpen(ip: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), 400); true }
    } catch (_: Exception) {
        false
    }

    private fun isPinnedHost(http: okhttp3.OkHttpClient, ip: String, port: Int): Boolean = try {
        http.newCall(Request.Builder().url("https://$ip:$port/sync/v1/health").get().build()).execute().use { it.isSuccessful }
    } catch (_: Exception) {
        false
    }

    companion object {
        private const val CONCURRENCY = 40
        private const val SCAN_TIMEOUT_MS = 20_000L
        private const val MIN_SCAN_GAP_MS = 10 * 60 * 1000L

        /** Every other address in the same /24 as [ownIp], leaving out our own and the one that already failed. */
        fun candidates(ownIp: String, knownIp: String?): List<String> {
            val parts = ownIp.split('.')
            if (parts.size != 4) return emptyList()
            val prefix = parts.take(3).joinToString(".")
            return (1..254).map { "$prefix.$it" }.filter { it != ownIp && it != knownIp }
        }
    }
}
