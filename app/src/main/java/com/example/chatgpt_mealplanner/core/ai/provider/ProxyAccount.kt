package com.example.chatgpt_mealplanner.core.ai.provider

import android.content.Context
import com.example.chatgpt_mealplanner.core.security.AndroidKeystoreSecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** The Cloudflare Worker that holds the Claude key and each install's allowance (see the worker/ folder). */
const val PROXY_BASE_URL = "https://pantry-planner-proxy.demengies.workers.dev"

/** What the Worker says about this install's allowance. [resetsOn] is a YYYY-MM-DD date, blank until the period starts. */
data class UsageReading(val tier: String, val percentLeft: Int, val resetsOn: String?)

/**
 * This install's account on the Worker. The first AI request registers one; the token is kept encrypted on the phone
 * and is sent in place of an API key. Lose it (clear the app's data) and a new account, with a fresh allowance, is made.
 */
@Singleton
open class ProxyAccount @Inject constructor(
    @param:ApplicationContext private val context: Context?
) {
    open val baseUrl: String = PROXY_BASE_URL

    private val prefs by lazy {
        context?.let { runCatching { AndroidKeystoreSecurePreferences(it, "proxy_account", mapOf(KEY to 'S')) }.getOrNull() }
    }
    private val http by lazy { OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build() }

    @Volatile private var cached: String? = null

    /** True when the Worker refused to make an account because Google Play could not vouch for this install. */
    @Volatile open var refusedByIntegrity: Boolean = false
        protected set

    open fun isToken(key: String): Boolean = key == cached

    /** The account token, registering a new account the first time. Null if that is not possible right now. */
    @Synchronized
    open fun token(): String? {
        cached?.let { return it }
        val stored = runCatching { prefs?.getString(KEY) }.getOrNull()?.takeIf { it.isNotBlank() }
        if (stored != null) { cached = stored; return stored }
        val fresh = register() ?: return null
        runCatching { prefs?.putString(KEY, fresh) }
        cached = fresh
        return fresh
    }

    /** Forget a token the Worker no longer accepts, so the next request registers a new account. */
    @Synchronized
    open fun forget() {
        cached = null
        runCatching { prefs?.remove(KEY) }
    }

    open fun fetchUsage(): UsageReading? {
        val token = token() ?: return null
        val request = Request.Builder().url("$baseUrl/v1/usage").header("x-api-key", token).get().build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (response.code == 401) { forget(); return null }
                if (!response.isSuccessful) return null
                val json = Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                readingFrom(json)
            }
        }.getOrNull()
    }

    /**
     * Asks Google Play to vouch for this install (genuine app, from Play, on a real device). The Worker checks the
     * answer when it makes the account. Null when Play cannot answer (no Play services, sideloaded, offline); the
     * Worker decides whether that is acceptable.
     */
    protected open fun integrityToken(nonce: String): String? {
        val appContext = context ?: return null
        // A blocking wait must never happen on the main thread.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return null
        return runCatching {
            val manager = com.google.android.play.core.integrity.IntegrityManagerFactory.create(appContext)
            val request = com.google.android.play.core.integrity.IntegrityTokenRequest.builder().setNonce(nonce).build()
            com.google.android.gms.tasks.Tasks.await(manager.requestIntegrityToken(request), 15, TimeUnit.SECONDS).token()
        }.getOrNull()
    }

    private fun challenge(): String? {
        val request = Request.Builder().url("$baseUrl/v1/register/challenge").post("".toRequestBody()).build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject["nonce"]?.jsonPrimitive?.contentOrNull
            }
        }.getOrNull()
    }

    private fun register(): String? {
        refusedByIntegrity = false
        val nonce = challenge()
        val integrity = nonce?.let { integrityToken(it) }
        val body = if (nonce != null && integrity != null) {
            buildJsonObject { put("nonce", nonce); put("integrityToken", integrity) }.toString()
        } else ""
        val request = Request.Builder().url("$baseUrl/v1/register")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    refusedByIntegrity = response.code == 403 &&
                        runCatching { Json.parseToJsonElement(text).jsonObject["code"]?.jsonPrimitive?.contentOrNull == "integrity" }.getOrDefault(false)
                    return null
                }
                Json.parseToJsonElement(text).jsonObject["token"]?.jsonPrimitive?.contentOrNull
            }
        }.getOrNull()
    }

    private fun readingFrom(json: JsonObject): UsageReading? {
        val tier = json["tier"]?.jsonPrimitive?.contentOrNull ?: return null
        val percent = json["percentLeft"]?.jsonPrimitive?.intOrNull ?: return null
        return UsageReading(tier, percent, json["resetsOn"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() })
    }

    private companion object {
        const val KEY = "KEY_PROXY_TOKEN"
    }
}
