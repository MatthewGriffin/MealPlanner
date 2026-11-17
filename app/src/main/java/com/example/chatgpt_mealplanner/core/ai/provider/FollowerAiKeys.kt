package com.example.chatgpt_mealplanner.core.ai.provider

import android.content.Context
import com.example.chatgpt_mealplanner.core.security.AndroidKeystoreSecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Main phone: the AI keys handed to paired follower phones. A follower uses the household's allowance and plan through
 * its own key, so the main phone can switch one follower off without touching the others or its own account.
 * Kept encrypted on this phone only (not part of backups).
 */
@Singleton
open class FollowerAiKeys @Inject constructor(
    @param:ApplicationContext private val context: Context?,
    private val account: ProxyAccount
) {
    @Serializable
    private data class Entry(val keyId: String, val token: String)

    @Serializable
    private data class State(val keys: Map<String, Entry> = emptyMap(), val pending: List<String> = emptyList())

    private val prefs by lazy {
        context?.let { runCatching { AndroidKeystoreSecurePreferences(it, "follower_ai_keys", mapOf(KEY to 'S')) }.getOrNull() }
    }
    private val json = Json { ignoreUnknownKeys = true }

    private fun load(): State =
        runCatching { prefs?.getString(KEY)?.let { json.decodeFromString<State>(it) } }.getOrNull() ?: State()

    private fun save(state: State) {
        runCatching { prefs?.putString(KEY, json.encodeToString(state)) }
    }

    /** The key for this follower phone, asking the Worker for one the first time. Null if that is not possible right now (it is asked again next sync). */
    @Synchronized
    open fun tokenFor(followerDeviceId: String): String? {
        val state = load()
        state.keys[followerDeviceId]?.let { return it.token }
        val made = account.createFollowerKey() ?: return null
        save(state.copy(keys = state.keys + (followerDeviceId to Entry(made.keyId, made.token))))
        return made.token
    }

    /** The follower was removed: switch its key off now, or remember to when the Worker can be reached. */
    @Synchronized
    open fun revoke(followerDeviceId: String) {
        val state = load()
        val entry = state.keys[followerDeviceId] ?: return
        save(state.copy(keys = state.keys - followerDeviceId, pending = state.pending + entry.keyId))
        retryPending()
    }

    /** Tries again for keys that could not be switched off earlier (offline at the time). */
    @Synchronized
    open fun retryPending() {
        val state = load()
        if (state.pending.isEmpty()) return
        val remaining = state.pending.filterNot { account.revokeFollowerKey(it) }
        save(state.copy(pending = remaining))
    }

    private companion object {
        const val KEY = "KEY_FOLLOWER_AI_KEYS"
    }
}
