package com.example.chatgpt_mealplanner.core.barcode.provider

import com.example.chatgpt_mealplanner.core.barcode.model.ProductDataSource
import com.example.chatgpt_mealplanner.core.barcode.model.ProductMetadata
import com.example.chatgpt_mealplanner.core.model.DomainError

sealed interface ProductProviderResult {
    data class Success(val metadata: ProductMetadata) : ProductProviderResult
    object NotFound : ProductProviderResult
    data class Failure(val error: DomainError) : ProductProviderResult
}

interface ProductDataProvider {
    val providerSource: ProductDataSource
    suspend fun lookupBarcode(barcode: String): ProductProviderResult
}
