package com.example.chatgpt_mealplanner.core.barcode.provider

import com.example.chatgpt_mealplanner.core.barcode.model.ProductDataSource
import com.example.chatgpt_mealplanner.core.barcode.model.ProductMatchConfidence
import com.example.chatgpt_mealplanner.core.barcode.model.ProductMetadata
import com.example.chatgpt_mealplanner.core.barcode.provider.openfoodfacts.OpenFoodFactsApi
import com.example.chatgpt_mealplanner.core.barcode.provider.openfoodfacts.OpenFoodFactsProductDto
import com.example.chatgpt_mealplanner.core.barcode.provider.openfoodfacts.SearchALiciousApi
import com.example.chatgpt_mealplanner.core.barcode.util.QuantityParser
import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.util.withoutPictographsOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpenFoodFactsProductDataProvider @Inject constructor(
    private val api: OpenFoodFactsApi,
    private val searchApi: SearchALiciousApi? = null
) : ProductDataProvider {

    /** The country whose products are searched first, as Open Food Facts spells it ("en:united-kingdom"), or null for the whole world. */
    internal var countryTag: () -> String? = { defaultCountryTag() }

    override val providerSource: ProductDataSource = ProductDataSource.OPEN_FOOD_FACTS

    private companion object {
        const val MISS_RETRY_DELAY_MS = 800L
    }

    private data class CatalogueEndpoint(
        val name: String,
        val source: ProductDataSource,
        val url: String
    )

    /**
     * The scanner strips leading zeros, so a UPC-A or an EAN that starts with 0 reaches here too short and the
     * catalogue would say "not found". The code as given is tried first, then the usual full lengths.
     */
    override suspend fun lookupBarcode(barcode: String): ProductProviderResult {
        val given = com.example.chatgpt_mealplanner.core.barcode.util.BarcodeNormalizer.formatForExternalLookup(barcode) ?: barcode
        val candidates = buildList {
            add(given)
            if (given.all { it.isDigit() }) {
                listOf(12, 13).filter { it > given.length }.forEach { add(given.padStart(it, '0')) }
            }
        }.distinct()
        var failure: ProductProviderResult.Failure? = null
        for (code in candidates) {
            when (val result = lookupExact(barcode, code)) {
                is ProductProviderResult.Success -> return result
                is ProductProviderResult.Failure -> failure = result
                ProductProviderResult.NotFound -> Unit
            }
        }
        if (failure != null) return failure
        // The catalogue sometimes answers "not found" for a product it has when it is busy, so a miss is asked once more.
        if (retryOnMiss) {
            kotlinx.coroutines.delay(MISS_RETRY_DELAY_MS)
            val again = lookupExact(barcode, candidates.first())
            if (again is ProductProviderResult.Success) return again
        }
        return ProductProviderResult.NotFound
    }

    internal var retryOnMiss = true

    private suspend fun lookupExact(barcode: String, externalBarcode: String): ProductProviderResult {

        val endpoints = listOf(
            CatalogueEndpoint("OPEN_FOOD_FACTS", ProductDataSource.OPEN_FOOD_FACTS, "https://world.openfoodfacts.org/api/v2/product/$externalBarcode.json"),
            CatalogueEndpoint("OPEN_BEAUTY_FACTS", ProductDataSource.OPEN_BEAUTY_FACTS, "https://world.openbeautyfacts.org/api/v2/product/$externalBarcode.json"),
            CatalogueEndpoint("OPEN_PRODUCTS_FACTS", ProductDataSource.OPEN_PRODUCTS_FACTS, "https://world.openproductsfacts.org/api/v2/product/$externalBarcode.json")
        )

        var hadError = false
        var lastErrorMessage: String? = null
        var cleanNotFoundCount = 0

        for (endpoint in endpoints) {
            val result = runCatching {
                api.getProductByUrl(endpoint.url)
            }

            if (result.isSuccess) {
                val response = result.getOrNull()
                if (response?.status == 1 && response.product != null) {
                    val product = response.product
                    val name = (product.productNameEn.takeUnless { it.isNullOrBlank() }
                        ?: product.productName.takeUnless { it.isNullOrBlank() })
                        .withoutPictographsOrNull()

                    val parsedQuantity = QuantityParser.parse(product.quantity)

                    val derivedConcept = com.example.chatgpt_mealplanner.core.barcode.util.HouseholdConceptDeriver.deriveConcept(
                        categoriesTags = product.categoriesTags,
                        categoriesString = product.categories,
                        defaultName = name
                    )

                    val dtoLog = "[BARCODE_METADATA] stage=PROVIDER_DTO provider=${endpoint.name} barcode=$externalBarcode productName=\"${product.productNameEn ?: product.productName ?: ""}\" categories=\"${product.categories ?: ""}\" categoryTags=${product.categoriesTags} rawQty=\"${product.quantity ?: ""}\""
                    logBarcode(dtoLog)

                    val mappedLog = "[BARCODE_METADATA] stage=MAPPED barcode=$barcode displayName=\"${name ?: ""}\" canonicalName=\"${derivedConcept ?: ""}\" netQuantity=${parsedQuantity.netQuantity} netQuantityUnit=${parsedQuantity.netQuantityUnit}"
                    logBarcode(mappedLog)

                    val metadata = ProductMetadata(
                        barcode = barcode,
                        displayName = name,
                        canonicalName = derivedConcept,
                        brand = product.brands.withoutPictographsOrNull(),
                        netQuantity = parsedQuantity.netQuantity,
                        netQuantityUnit = parsedQuantity.netQuantityUnit,
                        packCount = parsedQuantity.packCount,
                        packSize = parsedQuantity.packSize,
                        packSizeUnit = parsedQuantity.packSizeUnit,
                        imageUrl = product.imageFrontUrl ?: product.imageUrl,
                        source = endpoint.source,
                        rawSourceId = response.code ?: barcode,
                        confidence = if (name != null) ProductMatchConfidence.HIGH else ProductMatchConfidence.LOW
                    )
                    return ProductProviderResult.Success(metadata)
                } else {
                    cleanNotFoundCount++
                    val logMsg = "[BARCODE_LOOKUP] barcode=$externalBarcode provider=${endpoint.name} result=NOT_FOUND"
                    logBarcode(logMsg)
                }
            } else {
                hadError = true
                lastErrorMessage = result.exceptionOrNull()?.message ?: "Network error"
                val logMsg = "[BARCODE_LOOKUP] barcode=$externalBarcode provider=${endpoint.name} result=ERROR cause=\"$lastErrorMessage\""
                logBarcode(logMsg)
            }
        }

        return if (!hadError && cleanNotFoundCount == endpoints.size) {
            ProductProviderResult.NotFound
        } else {
            ProductProviderResult.Failure(
                DomainError.TransactionFailed(lastErrorMessage ?: "Barcode lookup incomplete")
            )
        }
    }

    /**
     * Searches the fast Search-a-licious service first. Only when it fails, such as when it is down or
     * unreachable, does the slower legacy search run. An answer of "nothing found" is final.
     */
    override suspend fun searchProductsByName(query: String, page: Int, pageSize: Int): ProductProviderSearchResult {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return ProductProviderSearchResult.Empty

        val fast = searchApi?.let { fastApi ->
            // Products sold in the user's country come first: a world-wide search for "Asda white bread"
            // returned Bulgarian cheese. If nothing is sold there, the whole database is searched.
            val country = countryTag()
            val queries = if (country != null) listOf("$trimmed countries_tags:\"$country\"", trimmed) else listOf(trimmed)
            var outcome: ProductProviderSearchResult? = null
            for (q in queries) {
                val result = runCatching { fastApi.search(q, page, pageSize) }
                val failure = result.exceptionOrNull()
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                if (failure != null) {
                    logBarcode("[BARCODE_LOOKUP] search provider=SEARCH_A_LICIOUS result=ERROR cause=\"${failure.message}\"")
                    outcome = null
                    break
                }
                outcome = mapSearchProducts(result.getOrNull()?.hits.orEmpty().map { it.toProductDto() })
                if (outcome is ProductProviderSearchResult.Success) break
            }
            outcome
        }
        if (fast != null) return fast

        val result = runCatching { api.searchProductsByName(terms = trimmed, page = page, pageSize = pageSize) }
        val failure = result.exceptionOrNull()
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        if (failure != null) {
            return ProductProviderSearchResult.Failure(
                DomainError.TransactionFailed(failure.message ?: "Network error searching OpenFoodFacts")
            )
        }
        return mapSearchProducts(result.getOrNull()?.products.orEmpty())
    }

    private fun mapSearchProducts(products: List<OpenFoodFactsProductDto>): ProductProviderSearchResult {
        if (products.isEmpty()) return ProductProviderSearchResult.Empty

        val mappedList = products.mapNotNull { product ->
            val rawName = (product.productNameEn.takeUnless { it.isNullOrBlank() }
                ?: product.productName.takeUnless { it.isNullOrBlank() })
                .withoutPictographsOrNull()
                ?: return@mapNotNull null

            val parsedTitleInfo = com.example.chatgpt_mealplanner.core.barcode.util.BarcodeNormalizer.parseProductTitle(rawName)
            val parsedQuantity = QuantityParser.parse(product.quantity)

            val derivedConcept = com.example.chatgpt_mealplanner.core.barcode.util.HouseholdConceptDeriver.deriveConcept(
                categoriesTags = product.categoriesTags,
                categoriesString = product.categories,
                defaultName = parsedTitleInfo.cleanName
            )

            val productBarcode = product.code ?: ""

            ProductMetadata(
                barcode = productBarcode,
                displayName = parsedTitleInfo.cleanName,
                canonicalName = derivedConcept,
                brand = product.brands.withoutPictographsOrNull(),
                netQuantity = parsedQuantity.netQuantity,
                netQuantityUnit = parsedQuantity.netQuantityUnit,
                packCount = parsedQuantity.packCount ?: parsedTitleInfo.extractedPackCount,
                packSize = parsedQuantity.packSize ?: parsedTitleInfo.extractedPackSize,
                packSizeUnit = parsedQuantity.packSizeUnit ?: parsedTitleInfo.extractedPackSizeUnit,
                imageUrl = product.imageFrontUrl ?: product.imageUrl,
                source = ProductDataSource.OPEN_FOOD_FACTS,
                rawSourceId = productBarcode,
                confidence = ProductMatchConfidence.HIGH
            )
        }

        return if (mappedList.isEmpty()) {
            ProductProviderSearchResult.Empty
        } else {
            ProductProviderSearchResult.Success(mappedList)
        }
    }
}

private fun logBarcode(message: String) {
    if (!com.example.chatgpt_mealplanner.BuildConfig.DEBUG) return
    println(message)
    runCatching { android.util.Log.i("AI_MEALPLANNER", message) }
}

/** The device's country in Open Food Facts' tag form, such as "en:united-kingdom". */
internal fun defaultCountryTag(locale: java.util.Locale = java.util.Locale.getDefault()): String? =
    locale.getDisplayCountry(java.util.Locale.ENGLISH).takeIf { it.isNotBlank() }
        ?.lowercase()?.replace(Regex("[^a-z0-9]+"), "-")?.trim('-')?.let { "en:$it" }
