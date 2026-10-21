package com.example.chatgpt_mealplanner.feature.settings

import com.example.chatgpt_mealplanner.retail.contract.RetailStoreFormat
import com.example.chatgpt_mealplanner.retail.contract.RetailStoreLocation
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import com.example.chatgpt_mealplanner.retail.contract.StoreDirectoryEntry
import com.example.chatgpt_mealplanner.retail.contract.StoreDirectorySource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreDirectoryUiHelpersTest {

    private fun discovered(
        retailerId: RetailerId = RetailerId.TESCO,
        sourceId: String = "node/123",
        name: String = "Tesco Express",
        address: String = "10 High Street, Exampletown",
        postcode: String? = "AB1 2CD"
    ) = StoreDirectoryEntry(
        directorySource = StoreDirectorySource.OPENSTREETMAP,
        directorySourceId = sourceId,
        retailerId = retailerId,
        name = name,
        address = address,
        postcode = postcode,
        latitude = 51.5,
        longitude = -0.1,
        format = RetailStoreFormat.TESCO_EXPRESS
    )

    private fun saved(
        retailerId: RetailerId = RetailerId.TESCO,
        source: StoreDirectorySource? = StoreDirectorySource.OPENSTREETMAP,
        sourceId: String? = "node/123",
        name: String = "Tesco Express",
        address: String? = "10 High Street, Exampletown",
        postcode: String = "AB1 2CD"
    ) = RetailStoreLocation(
        id = "saved-1",
        retailerId = retailerId,
        name = name,
        postcode = postcode,
        format = RetailStoreFormat.TESCO_EXPRESS,
        directorySource = source,
        directorySourceId = sourceId,
        address = address
    )

    @Test
    fun matchesStableDirectorySourceId() {
        assertTrue(isDirectoryStoreAlreadySaved(discovered(), listOf(saved())))
    }

    @Test
    fun matchesNameAndPostcodeWhenSourceIdChanges() {
        assertTrue(
            isDirectoryStoreAlreadySaved(
                discovered(sourceId = "node/456", address = "Different formatting"),
                listOf(saved(sourceId = "node/old", address = null))
            )
        )
    }

    @Test
    fun matchesNormalizedAddressWhenNameChanges() {
        assertTrue(
            isDirectoryStoreAlreadySaved(
                discovered(sourceId = "node/456", name = "Tesco", address = "10 High Street, Exampletown"),
                listOf(saved(sourceId = "node/old", name = "Tesco Express - Branch", address = "10 HIGH STREET Exampletown"))
            )
        )
    }

    @Test
    fun doesNotMatchDifferentRetailerAtSameAddress() {
        assertFalse(
            isDirectoryStoreAlreadySaved(
                discovered(retailerId = RetailerId.TESCO),
                listOf(saved(retailerId = RetailerId.ASDA))
            )
        )
    }

    @Test
    fun doesNotMatchDifferentBranch() {
        assertFalse(
            isDirectoryStoreAlreadySaved(
                discovered(sourceId = "node/456", name = "Tesco Express", postcode = "XY9 9ZZ", address = "99 Other Road"),
                listOf(saved(sourceId = "node/123", name = "Tesco Express", postcode = "AB1 2CD", address = "10 High Street"))
            )
        )
    }
}
