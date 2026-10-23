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
    fun discoversHtmlEscapedIcelandRuntimeConfiguration() {
        val config = IcelandAlgoliaConfigDiscovery.find(
            listOf(
                """
                &quot;algoliaIndexBase&quot;:&quot;r1_iceuk_production&quot;,
                &quot;algoliaAppId&quot;:&quot;FAWURXX413&quot;,
                &quot;algoliaApiKey&quot;:&quot;dd51afec328646fc6b538411032deeb0&quot;,
                const products = client.initIndex("r1_iceuk_production_products");
                """.trimIndent()
            )
        )

        assertEquals("FAWURXX413", config?.applicationId)
        assertEquals("dd51afec328646fc6b538411032deeb0", config?.searchApiKey)
        assertEquals("r1_iceuk_production_products", config?.indexName)
    }

    @Test
    fun doesNotTreatAnIndexBaseAsAnIndexName() {
        assertNull(
            IcelandAlgoliaConfigDiscovery.find(
                listOf(
                    """&quot;algoliaIndexBase&quot;:&quot;r1_iceuk_production&quot;,
                    &quot;algoliaAppId&quot;:&quot;FAWURXX413&quot;,
                    &quot;algoliaApiKey&quot;:&quot;dd51afec328646fc6b538411032deeb0&quot;""")
            )
        )
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
