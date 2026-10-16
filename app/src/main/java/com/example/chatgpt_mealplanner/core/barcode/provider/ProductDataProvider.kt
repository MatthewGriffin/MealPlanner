package com.example.chatgpt_mealplanner.core.barcode.provider

import com.example.chatgpt_mealplanner.core.barcode.model.ProductDataSource
import com.example.chatgpt_mealplanner.core.barcode.model.ProductMetadata
import com.example.chatgpt_mealplanner.core.model.DomainError

sealed interface ProductProviderResult {
    data class Success(val metadata: ProductMetadata) : ProductProviderResult
    object NotFound : ProductProviderResult
    data class Failure(val error: DomainError) : ProductProviderResult
}

sealed interface ProductProviderSearchResult {
    data class Success(val products: List<ProductMetadata>) : ProductProviderSearchResult
    object Empty : ProductProviderSearchResult
    data class Failure(val error: DomainError) : ProductProviderSearchResult
}

interface ProductDataProvider {
    val providerSource: ProductDataSource
    suspend fun lookupBarcode(barcode: String): ProductProviderResult
    suspend fun searchProductsByName(query: String, page: Int = 1, pageSize: Int = 20): ProductProviderSearchResult = ProductProviderSearchResult.Empty
}
