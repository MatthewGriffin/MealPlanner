package com.example.chatgpt_mealplanner.core.service

import android.content.Context
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RetailerPreferenceStore @Inject constructor(
    @param:ApplicationContext private val context: Context?
) {
    fun getEnabledRetailers(): Set<RetailerId> {
        val saved = runCatching {
            context?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                ?.getStringSet(KEY_ENABLED_RETAILERS, null)
        }.getOrNull() ?: return DEFAULT_ENABLED_RETAILERS

        return saved.mapNotNull { name -> runCatching { RetailerId.valueOf(name) }.getOrNull() }
            .ifEmpty { DEFAULT_ENABLED_RETAILERS }
            .toSet()
    }

    fun observeEnabledRetailers(): Flow<Set<RetailerId>> = callbackFlow {
        val prefs = runCatching { context?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE) }.getOrNull()
        if (prefs == null) {
            trySend(getEnabledRetailers())
            close()
            return@callbackFlow
        }
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_ENABLED_RETAILERS) trySend(getEnabledRetailers())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(getEnabledRetailers())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()
    fun setEnabledRetailers(retailers: Set<RetailerId>): Boolean {
        if (retailers.isEmpty()) return false
        return runCatching {
            context?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                ?.edit()
                ?.putStringSet(KEY_ENABLED_RETAILERS, retailers.mapTo(linkedSetOf()) { it.name })
                ?.commit() == true
        }.getOrDefault(false)
    }

    companion object {
        const val PREFERENCES_NAME = "retailer_shopping_preferences"
        const val KEY_ENABLED_RETAILERS = "enabled_retailers"
        val DEFAULT_ENABLED_RETAILERS = RetailerId.entries.toSet()
    }
}
