package com.example.chatgpt_mealplanner.core.service

import android.content.Context
import com.example.chatgpt_mealplanner.retail.contract.RetailStoreFormat
import com.example.chatgpt_mealplanner.retail.contract.RetailStoreLocation
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared local storage for preferred physical branches.
 *
 * Preferred branches determine which retailers participate in basket comparisons.
 * This store intentionally does not imply that a branch has confirmed stock or
 * branch-specific pricing.
 */
@Singleton
class RetailStorePreferenceStore @Inject constructor(
    @param:ApplicationContext private val context: Context?
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun getBranchSearchRadiusMeters(): Int {
        val stored = context?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            ?.getInt(KEY_BRANCH_SEARCH_RADIUS_METERS, DEFAULT_BRANCH_SEARCH_RADIUS_METERS)
            ?: DEFAULT_BRANCH_SEARCH_RADIUS_METERS
        return stored.takeIf { it in SUPPORTED_BRANCH_SEARCH_RADII_METERS } ?: DEFAULT_BRANCH_SEARCH_RADIUS_METERS
    }

    fun setBranchSearchRadiusMeters(radiusMeters: Int): Boolean {
        if (radiusMeters !in SUPPORTED_BRANCH_SEARCH_RADII_METERS) return false
        return runCatching {
            context?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                ?.edit()
                ?.putInt(KEY_BRANCH_SEARCH_RADIUS_METERS, radiusMeters)
                ?.commit() == true
        }.getOrDefault(false)
    }

    fun getPreferredStores(): List<RetailStoreLocation> = runCatching {
        val raw = context?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            ?.getString(KEY_PREFERRED_STORES, null) ?: return emptyList()
        json.decodeFromString(ListSerializer(RetailStoreLocation.serializer()), raw)
    }.getOrDefault(emptyList())

    fun observePreferredStores(): Flow<List<RetailStoreLocation>> = callbackFlow {
        val prefs = runCatching { context?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE) }.getOrNull()
        if (prefs == null) {
            trySend(getPreferredStores())
            close()
            return@callbackFlow
        }
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_PREFERRED_STORES) trySend(getPreferredStores())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(getPreferredStores())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()
    fun savePreferredStores(stores: List<RetailStoreLocation>): Boolean = runCatching {
        context?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_PREFERRED_STORES, json.encodeToString(ListSerializer(RetailStoreLocation.serializer()), stores))
            ?.commit() == true
    }.getOrDefault(false)

    fun addPreferredStore(
        current: List<RetailStoreLocation>,
        retailerId: RetailerId,
        name: String,
        postcode: String,
        format: RetailStoreFormat
    ): List<RetailStoreLocation> {
        val store = RetailStoreLocation(
            id = UUID.randomUUID().toString(),
            retailerId = retailerId,
            name = name.trim(),
            postcode = postcode.trim().uppercase(),
            format = format
        )
        return (current + store).distinctBy {
            "${it.retailerId}:${it.name.lowercase()}:${it.postcode.replace(" ", "")}"
        }
    }

    fun setPreferred(
        current: List<RetailStoreLocation>,
        storeId: String,
        preferred: Boolean
    ): List<RetailStoreLocation> = current.map {
        if (it.id == storeId) it.copy(isPreferred = preferred) else it
    }

    fun removePreferred(
        current: List<RetailStoreLocation>,
        storeId: String
    ): List<RetailStoreLocation> = current.filterNot { it.id == storeId }

    companion object {
        const val PREFERENCES_NAME = "retail_store_preferences_v1"
        const val KEY_PREFERRED_STORES = "preferred_stores"
        const val KEY_BRANCH_SEARCH_RADIUS_METERS = "branch_search_radius_meters"
        const val DEFAULT_BRANCH_SEARCH_RADIUS_METERS = 5_000
        val SUPPORTED_BRANCH_SEARCH_RADII_METERS = setOf(1_000, 3_000, 5_000, 10_000, 15_000)
    }
}
