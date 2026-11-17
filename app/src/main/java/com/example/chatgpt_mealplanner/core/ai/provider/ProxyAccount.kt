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
import kotlinx.serialization.json.booleanOrNull
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

/** A key the Worker issued for one follower phone; [token] is what that phone sends in place of its own account token. */
data class FollowerKey(val keyId: String, val token: String)

/** What the Worker decided about a Google Play purchase. */
data class PurchaseResult(val entitled: Boolean)

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
        context?.let { runCatching { AndroidKeystoreSecurePreferences(it, "proxy_account", mapOf(KEY to 'S', KEY_SHARED to 'S', KEY_BOUND to 'S')) }.getOrNull() }
    }
    private val http by lazy { OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build() }

    @Volatile private var cached: String? = null
    @Volatile private var shared: String? = null
    @Volatile private var bindTried = false

    /** True when the Worker refused to make an account because Google Play could not vouch for this install. */
    @Volatile open var refusedByIntegrity: Boolean = false
        protected set

    open fun isToken(key: String): Boolean = key == cached

    /**
     * The token to use. A paired follower uses the household's shared key; every other phone uses its own account,
     * registering one the first time. Null if that is not possible right now.
     */
    @Synchronized
    open fun token(): String? {
        sharedToken()?.let { cached = it; return it }
        cached?.let { return it }
        val stored = runCatching { prefs?.getString(KEY) }.getOrNull()?.takeIf { it.isNotBlank() }
        if (stored != null) {
            val current = bindDeviceOnce(stored)
            cached = current
            return current
        }
        val fresh = register() ?: return null
        runCatching { prefs?.putString(KEY, fresh) }
        cached = fresh
        return fresh
    }

    /** Forget a token the Worker no longer accepts, so the next request registers a new account (or, for a follower whose key was switched off, uses its own account again). */
    @Synchronized
    open fun forget() {
        cached = null
        if (sharedToken() != null) { clearSharedToken(); return }
        runCatching { prefs?.remove(KEY) }
    }

    private fun sharedToken(): String? {
        shared?.let { return it }
        val stored = runCatching { prefs?.getString(KEY_SHARED) }.getOrNull()?.takeIf { it.isNotBlank() }
        shared = stored
        return stored
    }

    /** True when this phone is a follower using the household's allowance and plan. */
    open fun usingSharedToken(): Boolean = sharedToken() != null

    /** A follower stores the key its household's main phone gave it; from now on its AI use counts against the household. */
    @Synchronized
    open fun setSharedToken(token: String) {
        shared = token
        cached = token
        runCatching { prefs?.putString(KEY_SHARED, token) }
    }

    @Synchronized
    open fun clearSharedToken() {
        shared = null
        cached = null
        runCatching { prefs?.remove(KEY_SHARED) }
    }

    /** The phone's Android ID. It survives uninstalling and reinstalling the app, which is how the Worker recognises the phone. */
    protected open fun deviceId(): String? {
        val id = context?.let { runCatching { android.provider.Settings.Secure.getString(it.contentResolver, android.provider.Settings.Secure.ANDROID_ID) }.getOrNull() }
        return id?.takeIf { it.length >= 8 && it != BROKEN_ANDROID_ID }
    }

    /**
     * Tells the Worker which phone this account is on (once; accounts made before this existed get bound here). If the
     * Worker already knows the phone under another account, that account's token is used instead, so a reinstall
     * keeps its allowance and plan.
     */
    private fun bindDeviceOnce(token: String): String {
        if (bindTried) return token
        if (runCatching { prefs?.getString(KEY_BOUND) }.getOrNull() == "1") return token
        bindTried = true
        val id = deviceId() ?: return token
        val nonce = challenge()
        val integrity = nonce?.let { integrityToken(it) }
        val body = buildJsonObject {
            put("deviceId", id)
            if (nonce != null && integrity != null) { put("nonce", nonce); put("integrityToken", integrity) }
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$baseUrl/v1/device").header("x-api-key", token).post(body).build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use token
                val json = Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                if (json["bound"]?.jsonPrimitive?.booleanOrNull == true) runCatching { prefs?.putString(KEY_BOUND, "1") }
                val switched = json["token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                if (switched != null) { runCatching { prefs?.putString(KEY, switched) }; switched } else token
            }
        }.getOrDefault(token)
    }

    /** Main phone: asks the Worker for a key for one follower. Null if it could not be made right now. */
    open fun createFollowerKey(): FollowerKey? {
        if (usingSharedToken()) return null
        val token = token() ?: return null
        val request = Request.Builder().url("$baseUrl/v1/followers").header("x-api-key", token).post("".toRequestBody()).build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                val keyId = json["keyId"]?.jsonPrimitive?.contentOrNull
                val follower = json["token"]?.jsonPrimitive?.contentOrNull
                if (keyId != null && follower != null) FollowerKey(keyId, follower) else null
            }
        }.getOrNull()
    }

    /** Main phone: switches one follower's key off. True once the Worker has done it. */
    open fun revokeFollowerKey(keyId: String): Boolean {
        val token = token() ?: return false
        val body = buildJsonObject { put("keyId", keyId) }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$baseUrl/v1/followers/revoke").header("x-api-key", token).post(body).build()
        return runCatching { http.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
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

    /**
     * Tells the Worker about a Google Play purchase; the Worker checks it with Google and sets the plan.
     * Null if it could not be checked right now (offline, Google busy): try again later, and do not acknowledge.
     */
    open fun reportPurchase(purchaseToken: String): PurchaseResult? {
        val token = token() ?: return null
        val body = buildJsonObject { put("purchaseToken", purchaseToken) }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$baseUrl/v1/purchase").header("x-api-key", token).post(body).build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (response.code == 401) { forget(); return null }
                if (!response.isSuccessful) return null
                val entitled = Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject["entitled"]?.jsonPrimitive?.booleanOrNull
                entitled?.let { PurchaseResult(it) }
            }
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
        val id = deviceId()
        val body = if (nonce != null && integrity != null || id != null) {
            buildJsonObject {
                if (nonce != null && integrity != null) { put("nonce", nonce); put("integrityToken", integrity) }
                if (id != null) put("deviceId", id)
            }.toString()
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
        const val KEY_SHARED = "KEY_PROXY_SHARED_TOKEN"
        const val KEY_BOUND = "KEY_PROXY_DEVICE_BOUND"
        /** The long-known Android ID that many old or emulated devices share; it identifies nobody. */
        const val BROKEN_ANDROID_ID = "9774d56d682e549c"
    }
}
