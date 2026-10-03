package com.example.chatgpt_mealplanner.core.barcode.repository

import com.example.chatgpt_mealplanner.core.barcode.model.ProductLookupResult
import com.example.chatgpt_mealplanner.core.barcode.model.ProductMetadata
import com.example.chatgpt_mealplanner.core.model.DomainResult

interface ProductLookupRepository {
    suspend fun lookupBarcode(barcode: String): ProductLookupResult
    suspend fun saveBarcodeMapping(mapping: ProductMetadata): DomainResult<Unit>
    suspend fun getCachedMapping(barcode: String): ProductMetadata?
}
