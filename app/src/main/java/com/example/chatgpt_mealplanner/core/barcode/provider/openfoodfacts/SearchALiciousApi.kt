package com.example.chatgpt_mealplanner.core.barcode.provider.openfoodfacts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Open Food Facts' dedicated product search service. It answers in about a tenth of a second where the
 * older `cgi/search.pl` takes seconds and often returns "temporarily unavailable" (tested 2026-11-10).
 */
interface SearchALiciousApi {

    @GET("search")
    suspend fun search(
        @Query("q") query: String,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 20,
        @Query("fields") fields: String = SEARCH_FIELDS
    ): SearchALiciousResponse

    companion object {
        const val BASE_URL = "https://search.openfoodfacts.org/"
        const val SEARCH_FIELDS = "code,product_name,brands,quantity,categories,categories_tags"
    }
}

@Serializable
data class SearchALiciousResponse(
    val hits: List<SearchALiciousHit>? = emptyList()
)

@Serializable
data class SearchALiciousHit(
    val code: String? = null,
    @SerialName("product_name") val productName: JsonElement? = null,
    /** A list in this service, but a plain string in the older one. */
    val brands: JsonElement? = null,
    val quantity: JsonElement? = null,
    val categories: JsonElement? = null,
    @SerialName("categories_tags") val categoriesTags: List<String>? = null
) {
    /** The same product shape the older search returns, so both are mapped the same way. */
    fun toProductDto() = OpenFoodFactsProductDto(
        code = code,
        productName = productName.text(),
        brands = brands.text(),
        quantity = quantity.text(),
        categories = categories.text(),
        categoriesTags = categoriesTags
    )

    private fun JsonElement?.text(): String? = when (this) {
        is JsonPrimitive -> if (isString) content.takeIf { it.isNotBlank() } else content
        is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { s -> s.isNotBlank() } }.joinToString(", ").ifEmpty { null }
        else -> null
    }?.takeIf { it != "null" }
}
