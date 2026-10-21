package com.example.chatgpt_mealplanner.feature.settings

import com.example.chatgpt_mealplanner.retail.contract.RetailStoreLocation
import com.example.chatgpt_mealplanner.retail.contract.StoreDirectoryEntry

/**
 * Matches a discovered branch against saved locations without assuming a particular store format.
 * Prefer the source record ID, then fall back to branch name/postcode or normalized address.
 */
internal fun isDirectoryStoreAlreadySaved(
    discovered: StoreDirectoryEntry,
    savedStores: List<RetailStoreLocation>
): Boolean = savedStores.any { saved ->
    if (saved.retailerId != discovered.retailerId) {
        false
    } else {
        val savedPostcode = saved.postcode.filterNot(Char::isWhitespace).uppercase()
        val resultPostcode = discovered.postcode.orEmpty().filterNot(Char::isWhitespace).uppercase()
        val sameSourceRecord = saved.directorySource == discovered.directorySource &&
            !saved.directorySourceId.isNullOrBlank() &&
            saved.directorySourceId == discovered.directorySourceId
        val sameNameAndPostcode = saved.name.trim().equals(discovered.name.trim(), ignoreCase = true) &&
            savedPostcode.isNotBlank() && savedPostcode == resultPostcode
        val savedAddress = saved.address.orEmpty().lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        val resultAddress = discovered.address.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        val sameAddressAndPostcode = savedAddress.isNotBlank() && resultAddress.isNotBlank() &&
            savedAddress == resultAddress &&
            (savedPostcode.isBlank() || resultPostcode.isBlank() || savedPostcode == resultPostcode)
        sameSourceRecord || sameNameAndPostcode || sameAddressAndPostcode
    }
}
