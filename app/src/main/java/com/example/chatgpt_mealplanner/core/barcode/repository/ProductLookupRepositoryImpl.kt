package com.example.chatgpt_mealplanner.core.barcode.repository

import com.example.chatgpt_mealplanner.core.barcode.model.ProductLookupResult
import com.example.chatgpt_mealplanner.core.barcode.model.ProductMetadata
import com.example.chatgpt_mealplanner.core.barcode.provider.ProductDataProvider
import com.example.chatgpt_mealplanner.core.barcode.provider.ProductProviderResult
import com.example.chatgpt_mealplanner.core.database.dao.BarcodeMappingDao
import com.example.chatgpt_mealplanner.core.database.entity.toMappingEntity
import com.example.chatgpt_mealplanner.core.database.entity.toMetadata
import com.example.chatgpt_mealplanner.core.model.DomainResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProductLookupRepositoryImpl @Inject constructor(
    private val barcodeMappingDao: BarcodeMappingDao,
    private val productDataProvider: ProductDataProvider,
    private val verificationStore: BarcodeConceptVerificationStore
) : ProductLookupRepository {

    companion object {
        const val CACHE_REFRESH_THRESHOLD_MS = 30L * 24 * 60 * 60 * 1000 // 30 Days
    }

    override suspend fun lookupBarcode(barcode: String): ProductLookupResult {
        val externalBarcode = com.example.chatgpt_mealplanner.core.barcode.util.BarcodeNormalizer.formatForExternalLookup(barcode) ?: barcode
        val normalizedBarcode = com.example.chatgpt_mealplanner.core.barcode.util.BarcodeNormalizer.normalize(barcode) ?: barcode

        val cachedEntity = barcodeMappingDao.getMappingByBarcode(externalBarcode)
            ?: barcodeMappingDao.getMappingByBarcode(normalizedBarcode)
        val now = System.currentTimeMillis()

        if (cachedEntity != null) {
            val isFresh = (now - cachedEntity.updatedAt) <= CACHE_REFRESH_THRESHOLD_MS
            if (isFresh) {
                val metadata = cachedEntity.toMetadata()

                val isConceptVerified = verificationStore.isConceptVerified(externalBarcode) ||
                        verificationStore.isConceptVerified(normalizedBarcode)

                val needsCompatibilityRepair = !isConceptVerified &&
                        (metadata.canonicalName == null || metadata.canonicalName == metadata.displayName)

                val decisionLog = "[BARCODE_CONCEPT_COMPAT] barcode=$barcode source=${cachedEntity.source} displayName=\"${metadata.displayName ?: ""}\" canonicalName=\"${metadata.canonicalName ?: ""}\" canonicalEqualsDisplay=${metadata.canonicalName == metadata.displayName} isManual=${cachedEntity.source == com.example.chatgpt_mealplanner.core.barcode.model.ProductDataSource.MANUAL} isConceptVerified=$isConceptVerified needsCompatibilityRepair=$needsCompatibilityRepair"
                println(decisionLog)
                runCatching { android.util.Log.i("AI_MEALPLANNER", decisionLog) }

                val finalMetadata = if (needsCompatibilityRepair) {
                    val startLog = "[BARCODE_CONCEPT_COMPAT] barcode=$barcode action=PROVIDER_REFRESH_STARTED"
                    println(startLog)
                    runCatching { android.util.Log.i("AI_MEALPLANNER", startLog) }

                    when (val providerResult = productDataProvider.lookupBarcode(externalBarcode)) {
                        is ProductProviderResult.Success -> {
                            val providerMeta = providerResult.metadata
                            val enrichedCanonicalName = providerMeta.canonicalName ?: metadata.canonicalName ?: metadata.displayName
                            val enriched = metadata.copy(
                                canonicalName = enrichedCanonicalName,
                                displayName = metadata.displayName ?: providerMeta.displayName,
                                brand = metadata.brand ?: providerMeta.brand,
                                netQuantity = metadata.netQuantity ?: providerMeta.netQuantity,
                                netQuantityUnit = metadata.netQuantityUnit ?: providerMeta.netQuantityUnit
                            )
                            val mappingEntity = enriched.toMappingEntity().copy(lastVerifiedAt = now, updatedAt = now)
                            barcodeMappingDao.insertMapping(mappingEntity)

                            verificationStore.markConceptVerified(externalBarcode)
                            verificationStore.markConceptVerified(normalizedBarcode)

                            val resLog = "[BARCODE_CONCEPT_COMPAT] barcode=$barcode action=PROVIDER_REFRESH_RESULT result=SUCCESS providerCanonicalName=\"${providerMeta.canonicalName ?: ""}\""
                            println(resLog)
                            runCatching { android.util.Log.i("AI_MEALPLANNER", resLog) }

                            val writtenLog = "[BARCODE_CONCEPT_COMPAT] barcode=$barcode action=CACHE_REPAIR_WRITTEN canonicalName=\"${enriched.canonicalName ?: ""}\""
                            println(writtenLog)
                            runCatching { android.util.Log.i("AI_MEALPLANNER", writtenLog) }

                            val verifyLog = "[BARCODE_CONCEPT_COMPAT] barcode=$barcode action=MARK_VERIFIED"
                            println(verifyLog)
                            runCatching { android.util.Log.i("AI_MEALPLANNER", verifyLog) }

                            enriched
                        }
                        else -> {
                            val resLog = "[BARCODE_CONCEPT_COMPAT] barcode=$barcode action=PROVIDER_REFRESH_RESULT result=FAILURE providerCanonicalName=null"
                            println(resLog)
                            runCatching { android.util.Log.i("AI_MEALPLANNER", resLog) }
                            metadata
                        }
                    }
                } else {
                    metadata
                }

                val logMsg = "[BARCODE_LOOKUP] raw=$barcode external=$externalBarcode provider=CACHE cache=HIT result=FOUND rawQty=null parsedQty=${finalMetadata.netQuantity} parsedUnit=${finalMetadata.netQuantityUnit} canonicalName=\"${finalMetadata.canonicalName}\""
                println(logMsg)
                runCatching { android.util.Log.i("AI_MEALPLANNER", logMsg) }
                return ProductLookupResult.Found(finalMetadata, isFromCache = true)
            }
        }

        // Cache miss or stale cache -> attempt provider lookup using external barcode format
        when (val providerResult = productDataProvider.lookupBarcode(externalBarcode)) {
            is ProductProviderResult.Success -> {
                barcodeMappingDao.insertMapping(providerResult.metadata.toMappingEntity())
                verificationStore.markConceptVerified(externalBarcode)
                verificationStore.markConceptVerified(normalizedBarcode)
                return ProductLookupResult.Found(providerResult.metadata, isFromCache = false)
            }

            is ProductProviderResult.NotFound -> {
                return if (cachedEntity != null) {
                    // Return stale cached metadata as usable offline fallback
                    ProductLookupResult.Found(cachedEntity.toMetadata(), isFromCache = true)
                } else {
                    ProductLookupResult.NotFound(barcode)
                }
            }

            is ProductProviderResult.Failure -> {
                return if (cachedEntity != null) {
                    // Return stale cached metadata as usable offline fallback when network fails
                    ProductLookupResult.Found(cachedEntity.toMetadata(), isFromCache = true)
                } else {
                    ProductLookupResult.Error(barcode, providerResult.error)
                }
            }
        }
    }

    override suspend fun saveBarcodeMapping(mapping: ProductMetadata): DomainResult<Unit> {
        return runCatching {
            barcodeMappingDao.insertMapping(mapping.toMappingEntity())
        }.fold(
            onSuccess = { DomainResult.Success(Unit) },
            onFailure = { ex -> DomainResult.Failure(com.example.chatgpt_mealplanner.core.model.DomainError.TransactionFailed(ex.message ?: "Failed to save barcode mapping")) }
        )
    }

    override suspend fun getCachedMapping(barcode: String): ProductMetadata? {
        return barcodeMappingDao.getMappingByBarcode(barcode)?.toMetadata()
    }
}
