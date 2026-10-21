package com.example.chatgpt_mealplanner.core.service

import android.content.Context
import com.example.chatgpt_mealplanner.retail.contract.RetailStoreFormat
import com.example.chatgpt_mealplanner.retail.contract.RetailStoreLocation
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared local storage for preferred physical branches.
 *
 * Branch selections are device preferences, separate from the enabled-retailer list and
 * from retailer-wide online prices. This store intentionally does not imply that a branch
 * has confirmed stock or branch-specific pricing.
 */
@Singleton
class RetailStorePreferenceStore @Inject constructor(
    @param:ApplicationContext private val context: Context?
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun getPreferredStores(): List<RetailStoreLocation> = runCatching {
        val raw = context?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            ?.getString(KEY_PREFERRED_STORES, null) ?: return emptyList()
        json.decodeFromString(ListSerializer(RetailStoreLocation.serializer()), raw)
    }.getOrDefault(emptyList())

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
    }
}
