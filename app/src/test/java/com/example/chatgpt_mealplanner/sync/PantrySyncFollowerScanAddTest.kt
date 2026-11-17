package com.example.chatgpt_mealplanner.sync

import com.example.chatgpt_mealplanner.core.barcode.model.ConfirmedProductInput
import com.example.chatgpt_mealplanner.core.database.dao.InventoryDao
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.service.InventoryDomainServiceImpl
import com.example.chatgpt_mealplanner.core.sync.AppRole
import com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
import com.example.chatgpt_mealplanner.core.sync.HouseholdIdentityManager
import com.example.chatgpt_mealplanner.core.sync.enrollment.FollowerIdentityStore
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerDao
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/** A follower's scanned, receipt-imported and hand-added items must be queued for the main phone, not written to its own main database. */
class PantrySyncFollowerScanAddTest {

    private class Pairing : FollowerPairingStore(null) { override fun isPaired(): Boolean = true }

    private val added = mutableListOf<String>()
    private val restocked = mutableListOf<Pair<String, Double>>()
    private val knownByBarcode = mutableMapOf<String, FollowerInventoryItemEntity>()
    private val mainInventoryDao: InventoryDao = mock()

    private fun outbox(op: String) = FollowerSyncOutboxEntity(
        operationId = "op-$op", deviceId = "follower-1", deviceSequence = 1L, protocolVersion = 1, operationType = op,
        targetItemId = "t", baseRevision = 1L, payloadJson = "{}", createdAt = 1L, status = "PENDING", createdLocallyAt = 1L
    )

    private fun service(): InventoryDomainServiceImpl {
        val followerDao: FollowerDao = mock {
            onBlocking { getAllItems() } doAnswer { emptyList() }
            onBlocking { getItemByBarcode(any()) } doAnswer { knownByBarcode[it.getArgument<String>(0)] }
            onBlocking { getItemById(any()) } doAnswer { null }
            onBlocking { getUnresolvedOutboxOpsForTargetItem(any()) } doAnswer { emptyList() }
        }
        val engine = object : FollowerLocalSyncEngine(null) {
            override suspend fun recordLocalAddItem(
                deviceId: String, canonicalName: String, displayName: String, category: String, storageLocation: String,
                quantity: Double?, quantityUnit: String?, barcode: String?, notes: String?, itemId: String
            ): FollowerSyncOutboxEntity { added += displayName; return outbox("ADD_ITEM") }

            override suspend fun recordLocalRestock(deviceId: String, targetItemId: String, addedQuantity: Double): FollowerSyncOutboxEntity {
                restocked += targetItemId to addedQuantity; return outbox("SET_QUANTITY")
            }
        }
        return InventoryDomainServiceImpl(
            inventoryDao = mainInventoryDao,
            inventoryBarcodeDao = mock(),
            transactionDao = mock(),
            stapleRuleDao = mock(),
            transactionRunner = TestTransactionRunner(),
            appRoleResolver = object : AppRoleResolver(Pairing()) {
                override suspend fun getAppRoleAsync(): AppRole = AppRole.FOLLOWER
                override suspend fun isMainAsync(): Boolean = false
                override suspend fun isFollowerAsync(): Boolean = true
            },
            canonicalSyncDao = mock(),
            householdIdentityManager = HouseholdIdentityManager(mock()),
            followerDatabase = object : FollowerDatabase() {
                override fun followerDao(): FollowerDao = followerDao
                override fun mealDao(): com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerMealDao = error("unused")
                override fun mealRequestDao(): com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerMealRequestDao = error("unused")
                override fun shoppingSourceDao(): com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerShoppingSourceDao = error("unused")
                @Deprecated("Deprecated in Room") override fun clearAllTables() {}
                override fun createInvalidationTracker(): androidx.room.InvalidationTracker = mock()
                @Deprecated("Deprecated in Room")
                override fun createOpenHelper(config: androidx.room.DatabaseConfiguration): androidx.sqlite.db.SupportSQLiteOpenHelper = mock()
            },
            followerLocalSyncEngine = engine,
            followerIdentityStore = object : FollowerIdentityStore(null) { override fun getOrCreateDeviceId(): String = "follower-1" }
        )
    }

    private val scanned = ConfirmedProductInput(
        barcode = "5000000000017", displayName = "Baked Beans", category = FoodCategory.CUPBOARD,
        storageLocation = StorageLocation.CUPBOARD, netQuantity = 400.0, netQuantityUnit = QuantityUnit.GRAM
    )

    @Test
    fun aNewScannedProductOnAFollowerIsQueuedForTheMainPhoneNotWrittenLocally() = runTest {
        val result = service().addLotFromProductInput(scanned)
        assertTrue(result is DomainResult.Success)
        assertEquals(listOf("Baked Beans"), added)
        verify(mainInventoryDao, never()).insertItem(any())
    }

    @Test
    fun aScannedProductWhoseBarcodeIsKnownRestocksTheExistingItemOnAFollower() = runTest {
        knownByBarcode["5000000000017"] = FollowerInventoryItemEntity(
            itemId = "beans-1", barcode = "5000000000017", canonicalName = "baked beans", confirmedDisplayName = "Baked Beans",
            confirmedCategory = "CUPBOARD", confirmedStorageLocation = "CUPBOARD", confirmedQuantity = 400.0, confirmedQuantityUnit = "GRAM",
            confirmedStatus = "AVAILABLE", displayName = "Baked Beans", category = "CUPBOARD", storageLocation = "CUPBOARD",
            quantity = 400.0, quantityUnit = "GRAM", status = "AVAILABLE", updatedAt = 1L
        )
        val result = service().addLotFromProductInput(scanned)
        assertTrue(result is DomainResult.Success)
        assertEquals(listOf("beans-1" to 400.0), restocked)
        assertTrue(added.isEmpty())
    }

    @Test
    fun anItemAddedByHandOnAFollowerIsQueuedToo() = runTest {
        val item = InventoryItem(
            id = "new-1", barcode = null, canonicalName = "rice", displayName = "Rice", category = FoodCategory.DRY_GOODS,
            storageLocation = StorageLocation.CUPBOARD, quantity = 1000.0, quantityUnit = QuantityUnit.GRAM
        )
        val result = service().addItem(item)
        assertTrue(result is DomainResult.Success)
        assertEquals(listOf("Rice"), added)
        verify(mainInventoryDao, never()).insertItem(any())
    }
}
