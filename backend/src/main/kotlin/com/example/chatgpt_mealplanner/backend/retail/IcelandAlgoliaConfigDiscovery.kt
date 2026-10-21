package com.example.chatgpt_mealplanner.backend.retail

/**
 * Extracts public browser-side Algolia search configuration from Iceland's own page
 * assets. It intentionally refuses to guess credentials or an index name.
 */
internal object IcelandAlgoliaConfigDiscovery {
    data class Config(
        val applicationId: String,
        val searchApiKey: String,
        val indexName: String
    )

    private val appIdPatterns = listOf(
        Regex("""(?i)(?:applicationId|applicationID|appId|ALGOLIA_APP_ID)\s*["']?\s*[:=]\s*["']([A-Z0-9]{8,})["']"""),
        Regex("""(?i)["']x-algolia-application-id["']\s*[,=:]\s*["']([A-Z0-9]{8,})["']"""),
        Regex("""(?i)algoliasearch\s*\(\s*["']([A-Z0-9]{8,})["']""")
    )
    private val apiKeyPatterns = listOf(
        Regex("""(?i)(?:searchApiKey|algoliaApiKey|apiKey|ALGOLIA_SEARCH_API_KEY)\s*["']?\s*[:=]\s*["']([a-f0-9]{20,})["']"""),
        Regex("""(?i)["']x-algolia-api-key["']\s*[,=:]\s*["']([a-f0-9]{20,})["']"""),
        Regex("""(?i)algoliasearch\s*\(\s*["'][A-Z0-9]{8,}["']\s*,\s*["']([a-f0-9]{20,})["']""")
    )
    private val indexPatterns = listOf(
        Regex("""(?i)(?:indexName|productIndexName|productsIndex|ALGOLIA_INDEX_NAME)\s*["']?\s*[:=]\s*["']([A-Za-z0-9_./-]{2,})["']"""),
        Regex("""(?i)initIndex\s*\(\s*["']([A-Za-z0-9_./-]{2,})["']\s*\)""")
    )

    fun find(sources: List<String>): Config? {
        val combined = sources.joinToString("\n")
        val appId = firstMatch(combined, appIdPatterns) ?: return null
        val apiKey = firstMatch(combined, apiKeyPatterns) ?: return null
        val indexName = firstMatch(combined, indexPatterns) ?: return null
        if (!appId.matches(Regex("[A-Z0-9]{8,}"))) return null
        if (!apiKey.matches(Regex("[a-fA-F0-9]{20,}"))) return null
        if (!indexName.matches(Regex("[A-Za-z0-9_./-]{2,}"))) return null
        return Config(appId, apiKey, indexName)
    }

    private fun firstMatch(source: String, patterns: List<Regex>): String? =
        patterns.firstNotNullOfOrNull { pattern -> pattern.find(source)?.groupValues?.getOrNull(1) }
}
