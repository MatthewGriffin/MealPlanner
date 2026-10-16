package com.example.chatgpt_mealplanner.core.barcode.repository

import com.example.chatgpt_mealplanner.core.barcode.model.ProductLookupResult
import com.example.chatgpt_mealplanner.core.barcode.model.ProductMetadata
import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.DomainResult

sealed interface ProductLookupSearchResult {
    data class Success(val products: List<ProductMetadata>) : ProductLookupSearchResult
    object Empty : ProductLookupSearchResult
    data class Error(val error: DomainError) : ProductLookupSearchResult
}

interface ProductLookupRepository {
    suspend fun lookupBarcode(barcode: String): ProductLookupResult
    suspend fun searchProductsByName(query: String, page: Int = 1, pageSize: Int = 20): ProductLookupSearchResult = ProductLookupSearchResult.Empty
    suspend fun saveBarcodeMapping(mapping: ProductMetadata): DomainResult<Unit>
    suspend fun getCachedMapping(barcode: String): ProductMetadata?
}
