package com.example.chatgpt_mealplanner.core.barcode.provider.openfoodfacts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OpenFoodFactsResponse(
    val code: String? = null,
    val status: Int? = 0,
    @SerialName("status_verbose") val statusVerbose: String? = null,
    val product: OpenFoodFactsProductDto? = null
)

@Serializable
data class OpenFoodFactsProductDto(
    @SerialName("product_name") val productName: String? = null,
    @SerialName("product_name_en") val productNameEn: String? = null,
    @SerialName("generic_name") val genericName: String? = null,
    val brands: String? = null,
    val quantity: String? = null,
    val categories: String? = null,
    @SerialName("categories_tags") val categoriesTags: List<String>? = null,
    @SerialName("image_front_url") val imageFrontUrl: String? = null,
    @SerialName("image_url") val imageUrl: String? = null
)
