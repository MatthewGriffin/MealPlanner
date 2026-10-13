package com.example.chatgpt_mealplanner.core.barcode

import com.example.chatgpt_mealplanner.core.barcode.model.ProductDataSource
import com.example.chatgpt_mealplanner.core.barcode.model.ProductLookupResult
import com.example.chatgpt_mealplanner.core.barcode.model.ProductMetadata
import com.example.chatgpt_mealplanner.core.barcode.provider.ProductDataProvider
import com.example.chatgpt_mealplanner.core.barcode.provider.ProductProviderResult
import com.example.chatgpt_mealplanner.core.barcode.repository.ProductLookupRepositoryImpl
import com.example.chatgpt_mealplanner.core.database.dao.BarcodeMappingDao
import com.example.chatgpt_mealplanner.core.database.entity.ProductBarcodeMappingEntity
import com.example.chatgpt_mealplanner.core.database.entity.toMappingEntity
import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeBarcodeMappingDao : BarcodeMappingDao {
    val map = mutableMapOf<String, ProductBarcodeMappingEntity>()

    override suspend fun getMappingByBarcode(barcode: String): ProductBarcodeMappingEntity? = map[barcode]

    override suspend fun getAllMappingsSync(): List<ProductBarcodeMappingEntity> = map.values.toList()

    override suspend fun insertMapping(mapping: ProductBarcodeMappingEntity) {
        map[mapping.barcode] = mapping
    }

    override suspend fun deleteMapping(barcode: String) {
        map.remove(barcode)
    }
}

class ProductLookupRepositoryTest {

    private val fakeDao = FakeBarcodeMappingDao()
    private val fakeProvider: ProductDataProvider = org.mockito.kotlin.mock()
    private val fakeVerificationStore = com.example.chatgpt_mealplanner.core.barcode.repository.BarcodeConceptVerificationStore(null)
    private lateinit var repository: ProductLookupRepositoryImpl

    @Before
    fun setup() {
        repository = ProductLookupRepositoryImpl(fakeDao, fakeProvider, fakeVerificationStore)
    }

    @Test
    fun lookupBarcode_whenFreshInCache_returnsCacheWithoutCallingProvider() = runTest {
        val barcode = "5000159461122"
        fakeVerificationStore.markConceptVerified(barcode)
        val cachedMetadata = ProductMetadata(
            barcode = barcode,
            displayName = "Mars Bar",
            category = FoodCategory.SNACKS,
            suggestedStorageLocation = StorageLocation.CUPBOARD,
            source = ProductDataSource.MANUAL
        )
        fakeDao.insertMapping(cachedMetadata.toMappingEntity())

        val result = repository.lookupBarcode(barcode)

        assertTrue(result is ProductLookupResult.Found)
        val found = result as ProductLookupResult.Found
        assertTrue(found.isFromCache)
        assertEquals("Mars Bar", found.metadata.displayName)
        assertEquals(ProductDataSource.MANUAL, found.metadata.source)
        org.mockito.kotlin.verifyNoInteractions(fakeProvider)
    }

    @Test
    fun lookupBarcode_whenCacheMiss_callsProviderAndCachesResult() = runTest {
        val barcode = "5012345678901"
        val fetchedMetadata = ProductMetadata(
            barcode = barcode,
            displayName = "Basmati Rice",
            category = FoodCategory.CUPBOARD,
            suggestedStorageLocation = StorageLocation.CUPBOARD,
            source = ProductDataSource.OPEN_FOOD_FACTS
        )
        org.mockito.kotlin.whenever(fakeProvider.lookupBarcode(barcode))
            .thenReturn(ProductProviderResult.Success(fetchedMetadata))

        val result = repository.lookupBarcode(barcode)

        assertTrue(result is ProductLookupResult.Found)
        val found = result as ProductLookupResult.Found
        org.junit.Assert.assertFalse(found.isFromCache)
        assertEquals("Basmati Rice", found.metadata.displayName)

        // Verify inserted into cache
        val cached = fakeDao.getMappingByBarcode(barcode)
        assertEquals("Basmati Rice", cached?.displayName)
    }

    @Test
    fun lookupBarcode_whenNetworkOfflineAndStaleCacheExists_returnsStaleCacheFallback() = runTest {
        val barcode = "99999999"
        val staleEntity = ProductBarcodeMappingEntity(
            barcode = barcode,
            displayName = "Stale Canned Soup",
            brand = "Heinz",
            canonicalIngredientId = null,
            canonicalName = null,
            category = FoodCategory.CUPBOARD,
            defaultStorageLocation = StorageLocation.CUPBOARD,
            netQuantity = 400.0,
            netQuantityUnit = com.example.chatgpt_mealplanner.core.model.QuantityUnit.GRAM,
            packCount = 1,
            packSize = 400.0,
            packSizeUnit = com.example.chatgpt_mealplanner.core.model.QuantityUnit.GRAM,
            imageUrl = null,
            source = ProductDataSource.OPEN_FOOD_FACTS,
            confidence = com.example.chatgpt_mealplanner.core.barcode.model.ProductMatchConfidence.HIGH,
            updatedAt = System.currentTimeMillis() - (40L * 24 * 60 * 60 * 1000) // 40 days old (stale)
        )
        fakeDao.insertMapping(staleEntity)

        org.mockito.kotlin.whenever(fakeProvider.lookupBarcode(barcode))
            .thenReturn(ProductProviderResult.Failure(DomainError.TransactionFailed("Offline")))

        val result = repository.lookupBarcode(barcode)

        assertTrue(result is ProductLookupResult.Found)
        val found = result as ProductLookupResult.Found
        assertTrue(found.isFromCache)
        assertEquals("Stale Canned Soup", found.metadata.displayName)
    }

    @Test
    fun basmatiRice_canonicalNameEqualsDisplayName_scan3DaysAnd29DaysLater_isCacheHit_zeroProviderCalls() = runTest {
        val barcode = "5012345678901"
        fakeVerificationStore.markConceptVerified(barcode)
        val freshVerifiedEntity = ProductBarcodeMappingEntity(
            barcode = barcode,
            displayName = "Basmati Rice",
            brand = "Tilda",
            canonicalIngredientId = null,
            canonicalName = "Basmati Rice",
            category = FoodCategory.CUPBOARD,
            defaultStorageLocation = StorageLocation.CUPBOARD,
            netQuantity = 1000.0,
            netQuantityUnit = com.example.chatgpt_mealplanner.core.model.QuantityUnit.GRAM,
            packCount = null,
            packSize = null,
            packSizeUnit = null,
            imageUrl = null,
            source = ProductDataSource.OPEN_FOOD_FACTS,
            confidence = com.example.chatgpt_mealplanner.core.barcode.model.ProductMatchConfidence.HIGH,
            lastVerifiedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis() - (3L * 24 * 60 * 60 * 1000)
        )
        fakeDao.insertMapping(freshVerifiedEntity)

        // Scan 3 days later -> Fast cache hit, 0 provider calls
        val result3Days = repository.lookupBarcode(barcode)
        assertTrue(result3Days is ProductLookupResult.Found)
        assertTrue((result3Days as ProductLookupResult.Found).isFromCache)
        assertEquals("Basmati Rice", result3Days.metadata.canonicalName)
        org.mockito.kotlin.verifyNoInteractions(fakeProvider)

        // Update entity to 29 days old
        val entity29Days = freshVerifiedEntity.copy(
            updatedAt = System.currentTimeMillis() - (29L * 24 * 60 * 60 * 1000)
        )
        fakeDao.insertMapping(entity29Days)

        // Scan 29 days later -> Fast cache hit, 0 provider calls
        val result29Days = repository.lookupBarcode(barcode)
        assertTrue(result29Days is ProductLookupResult.Found)
        assertTrue((result29Days as ProductLookupResult.Found).isFromCache)
        assertEquals("Basmati Rice", result29Days.metadata.canonicalName)
        org.mockito.kotlin.verifyNoInteractions(fakeProvider)
    }

    @Test
    fun basmatiRice_scan30DaysLater_triggersNormalProviderRefresh() = runTest {
        val barcode = "5012345678901"
        val staleEntity = ProductBarcodeMappingEntity(
            barcode = barcode,
            displayName = "Basmati Rice",
            brand = "Tilda",
            canonicalIngredientId = null,
            canonicalName = "Basmati Rice",
            category = FoodCategory.CUPBOARD,
            defaultStorageLocation = StorageLocation.CUPBOARD,
            netQuantity = 1000.0,
            netQuantityUnit = com.example.chatgpt_mealplanner.core.model.QuantityUnit.GRAM,
            packCount = null,
            packSize = null,
            packSizeUnit = null,
            imageUrl = null,
            source = ProductDataSource.OPEN_FOOD_FACTS,
            confidence = com.example.chatgpt_mealplanner.core.barcode.model.ProductMatchConfidence.HIGH,
            lastVerifiedAt = System.currentTimeMillis() - (31L * 24 * 60 * 60 * 1000),
            updatedAt = System.currentTimeMillis() - (31L * 24 * 60 * 60 * 1000)
        )
        fakeDao.insertMapping(staleEntity)

        val freshProviderMeta = ProductMetadata(
            barcode = barcode,
            displayName = "Basmati Rice",
            canonicalName = "Basmati Rice",
            brand = "Tilda",
            source = ProductDataSource.OPEN_FOOD_FACTS,
            netQuantity = 1000.0
        )
        org.mockito.kotlin.whenever(fakeProvider.lookupBarcode(barcode))
            .thenReturn(ProductProviderResult.Success(freshProviderMeta))

        val result = repository.lookupBarcode(barcode)
        assertTrue(result is ProductLookupResult.Found)
        org.mockito.kotlin.verify(fakeProvider).lookupBarcode(barcode)
    }

    @Test
    fun physicalHistorySequence_legacyCacheWithUser75ml_repairsToToothpasteAndPreserves75ml() = runTest {
        val barcode = "8700216666367"

        // 1. Old cached mapping created prior to concept deriver (canonicalName == displayName, user confirmed 75 ml)
        val legacyEntity = ProductBarcodeMappingEntity(
            barcode = barcode,
            displayName = "Sensitivy Protection",
            brand = "oral b",
            canonicalIngredientId = null,
            canonicalName = "Sensitivy Protection",
            category = FoodCategory.CUPBOARD,
            defaultStorageLocation = StorageLocation.CUPBOARD,
            netQuantity = 75.0,
            netQuantityUnit = com.example.chatgpt_mealplanner.core.model.QuantityUnit.MILLILITRE,
            packCount = null,
            packSize = null,
            packSizeUnit = null,
            imageUrl = null,
            source = ProductDataSource.OPEN_BEAUTY_FACTS,
            confidence = com.example.chatgpt_mealplanner.core.barcode.model.ProductMatchConfidence.HIGH,
            lastVerifiedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis() - 1000000
        )
        fakeDao.insertMapping(legacyEntity)

        // Verification store initially has NO key for this barcode
        org.junit.Assert.assertFalse(fakeVerificationStore.isConceptVerified(barcode))

        // Mock Open Beauty Facts returning canonicalName = "Toothpaste"
        val freshProviderMeta = ProductMetadata(
            barcode = barcode,
            displayName = "Sensitivy Protection",
            canonicalName = "Toothpaste",
            brand = "oral b",
            source = ProductDataSource.OPEN_BEAUTY_FACTS,
            netQuantity = null
        )
        org.mockito.kotlin.whenever(fakeProvider.lookupBarcode(barcode))
            .thenReturn(ProductProviderResult.Success(freshProviderMeta))

        // First scan with current code
        val result1 = repository.lookupBarcode(barcode)
        assertTrue(result1 is ProductLookupResult.Found)
        val found1 = (result1 as ProductLookupResult.Found).metadata
        assertEquals("Sensitivy Protection", found1.displayName)
        assertEquals("Toothpaste", found1.canonicalName)
        assertEquals(75.0, found1.netQuantity!!, 0.001)
        assertEquals(com.example.chatgpt_mealplanner.core.model.QuantityUnit.MILLILITRE, found1.netQuantityUnit)

        // Verification store is now TRUE
        assertTrue(fakeVerificationStore.isConceptVerified(barcode))
        org.mockito.kotlin.verify(fakeProvider, org.mockito.kotlin.times(1)).lookupBarcode(barcode)

        // Second scan
        val result2 = repository.lookupBarcode(barcode)
        assertTrue(result2 is ProductLookupResult.Found)
        val found2 = (result2 as ProductLookupResult.Found).metadata
        assertEquals("Sensitivy Protection", found2.displayName)
        assertEquals("Toothpaste", found2.canonicalName)
        assertEquals(75.0, found2.netQuantity!!, 0.001)
        assertEquals(com.example.chatgpt_mealplanner.core.model.QuantityUnit.MILLILITRE, found2.netQuantityUnit)

        // ZERO additional provider calls on second scan
        org.mockito.kotlin.verify(fakeProvider, org.mockito.kotlin.times(1)).lookupBarcode(barcode)
    }

    @Test
    fun manualUserEditedName_notOverwrittenByLegacyEnrichment() = runTest {
        val barcode = "8700216666367"
        fakeVerificationStore.markConceptVerified(barcode)
        val userManualEntity = ProductBarcodeMappingEntity(
            barcode = barcode,
            displayName = "Travel Toothpaste",
            brand = "oral b",
            canonicalIngredientId = null,
            canonicalName = "Travel Toothpaste",
            category = FoodCategory.CUPBOARD,
            defaultStorageLocation = StorageLocation.CUPBOARD,
            netQuantity = 75.0,
            netQuantityUnit = com.example.chatgpt_mealplanner.core.model.QuantityUnit.MILLILITRE,
            packCount = null,
            packSize = null,
            packSizeUnit = null,
            imageUrl = null,
            source = ProductDataSource.MANUAL,
            confidence = com.example.chatgpt_mealplanner.core.barcode.model.ProductMatchConfidence.EXACT,
            updatedAt = System.currentTimeMillis()
        )
        fakeDao.insertMapping(userManualEntity)

        val result = repository.lookupBarcode(barcode)

        assertTrue(result is ProductLookupResult.Found)
        val found = result as ProductLookupResult.Found
        assertEquals("Travel Toothpaste", found.metadata.displayName)
        assertEquals("Travel Toothpaste", found.metadata.canonicalName)
        org.mockito.kotlin.verifyNoInteractions(fakeProvider)
    }

    @Test
    fun openBeautyFacts_cacheRoundTrip_preservesSourceProvenance() = runTest {
        val barcode = "8700216666367"
        val beautyMetadata = ProductMetadata(
            barcode = barcode,
            displayName = "Sensitivy Protection",
            brand = "oral b",
            category = FoodCategory.CUPBOARD,
            suggestedStorageLocation = StorageLocation.CUPBOARD,
            source = ProductDataSource.OPEN_BEAUTY_FACTS
        )
        fakeDao.insertMapping(beautyMetadata.toMappingEntity())

        val result = repository.lookupBarcode(barcode)

        assertTrue(result is ProductLookupResult.Found)
        val found = result as ProductLookupResult.Found
        assertTrue(found.isFromCache)
        assertEquals("Sensitivy Protection", found.metadata.displayName)
        assertEquals(ProductDataSource.OPEN_BEAUTY_FACTS, found.metadata.source)
    }

    @Test
    fun lookupBarcode_whenNetworkOfflineAndNoCache_returnsError() = runTest {
        val barcode = "88888888"
        org.mockito.kotlin.whenever(fakeProvider.lookupBarcode(barcode))
            .thenReturn(ProductProviderResult.Failure(DomainError.TransactionFailed("Network Unavailable")))

        val result = repository.lookupBarcode(barcode)

        assertTrue(result is ProductLookupResult.Error)
        val err = result as ProductLookupResult.Error
        assertEquals(barcode, err.barcode)
    }
}
