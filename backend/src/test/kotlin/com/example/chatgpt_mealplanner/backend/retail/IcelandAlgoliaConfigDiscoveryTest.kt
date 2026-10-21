package com.example.chatgpt_mealplanner.backend.retail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IcelandAlgoliaConfigDiscoveryTest {

    @Test
    fun discoversOnlyExplicitPublicSearchSettings() {
        val config = IcelandAlgoliaConfigDiscovery.find(
            listOf(
                """
                window.searchConfig = {
                  applicationId: "ABCDEF1234",
                  searchApiKey: "0123456789abcdef0123456789abcdef",
                  indexName: "iceland_products"
                };
                """.trimIndent()
            )
        )

        assertEquals("ABCDEF1234", config?.applicationId)
        assertEquals("0123456789abcdef0123456789abcdef", config?.searchApiKey)
        assertEquals("iceland_products", config?.indexName)
    }

    @Test
    fun discoversStandardAlgoliaClientInitialization() {
        val config = IcelandAlgoliaConfigDiscovery.find(
            listOf(
                """
                const client = algoliasearch("ABCDEF1234", "0123456789abcdef0123456789abcdef");
                const products = client.initIndex("iceland_products");
                """.trimIndent()
            )
        )

        assertEquals("ABCDEF1234", config?.applicationId)
        assertEquals("0123456789abcdef0123456789abcdef", config?.searchApiKey)
        assertEquals("iceland_products", config?.indexName)
    }

    @Test
    fun refusesToGuessMissingIndexOrCredentials() {
        assertNull(
            IcelandAlgoliaConfigDiscovery.find(
                listOf("""{ applicationId: "ABCDEF1234", searchApiKey: "0123456789abcdef0123456789abcdef" }""")
            )
        )
        assertNull(IcelandAlgoliaConfigDiscovery.find(listOf("<html>no search configuration</html>")))
    }
}
