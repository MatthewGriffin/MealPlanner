package com.example.chatgpt_mealplanner.sync

import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.repository.InventoryRepositoryImpl
import com.example.chatgpt_mealplanner.core.sync.AppRole
import com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerDao
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

class PantrySyncPhase4gFollowerRoomTest {

    private class TestJdbcFollowerDao(private val conn: java.sql.Connection) : FollowerDao {
        init {
            conn.createStatement().use { stmt ->
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS `follower_inventory_items` (
                        `itemId` TEXT PRIMARY KEY NOT NULL,
                        `barcode` TEXT,
                        `canonicalName` TEXT NOT NULL,
                        `confirmedDisplayName` TEXT NOT NULL,
                        `confirmedBrand` TEXT,
                        `confirmedCategory` TEXT NOT NULL,
                        `confirmedStorageLocation` TEXT NOT NULL,
                        `confirmedQuantity` REAL NOT NULL,
                        `confirmedQuantityUnit` TEXT,
                        `confirmedNotes` TEXT,
                        `confirmedStatus` TEXT NOT NULL,
                        `mainConfirmedRevision` INTEGER NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `brand` TEXT,
                        `category` TEXT NOT NULL,
                        `storageLocation` TEXT NOT NULL,
                        `quantity` REAL NOT NULL,
                        `quantityUnit` TEXT,
                        `notes` TEXT,
                        `status` TEXT NOT NULL,
                        `optimisticRevision` INTEGER NOT NULL,
                        `isPendingSync` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        private fun fetchAllItemsSync(): List<FollowerInventoryItemEntity> {
            val list = mutableListOf<FollowerInventoryItemEntity>()
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT itemId, canonicalName, confirmedDisplayName, confirmedCategory, confirmedStorageLocation, confirmedQuantity, confirmedStatus, mainConfirmedRevision, displayName, category, storageLocation, quantity, quantityUnit, status, optimisticRevision, isPendingSync, updatedAt FROM follower_inventory_items")
                while (rs.next()) {
                    list.add(
                        FollowerInventoryItemEntity(
                            itemId = rs.getString("itemId"),
                            barcode = null,
                            canonicalName = rs.getString("canonicalName"),
                            confirmedDisplayName = rs.getString("confirmedDisplayName"),
                            confirmedBrand = null,
                            confirmedCategory = rs.getString("confirmedCategory"),
                            confirmedStorageLocation = rs.getString("confirmedStorageLocation"),
                            confirmedQuantity = rs.getDouble("confirmedQuantity"),
                            confirmedQuantityUnit = rs.getString("quantityUnit"),
                            confirmedNotes = null,
                            confirmedStatus = rs.getString("confirmedStatus"),
                            mainConfirmedRevision = rs.getLong("mainConfirmedRevision"),
                            displayName = rs.getString("displayName"),
                            brand = null,
                            category = rs.getString("category"),
                            storageLocation = rs.getString("storageLocation"),
                            quantity = rs.getDouble("quantity"),
                            quantityUnit = rs.getString("quantityUnit"),
                            notes = null,
                            status = rs.getString("status"),
                            optimisticRevision = rs.getLong("optimisticRevision"),
                            isPendingSync = rs.getInt("isPendingSync") == 1,
                            updatedAt = rs.getLong("updatedAt")
                        )
                    )
                }
            }
            return list
        }

        override suspend fun getSequenceEntity() = null
        override suspend fun updateSequenceEntity(entity: com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerDeviceSequenceEntity) {}
        override suspend fun getSyncMetadata() = null
        override suspend fun updateSyncMetadata(metadata: com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity) {}
        override suspend fun insertOutboxOperation(op: com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity) {}
        override suspend fun updateOutboxStatus(operationId: String, status: String, attemptCount: Int, lastAttemptAt: Long?, lastErrorCode: String?) {}
        override suspend fun reconcileOutboxResult(operationId: String, status: String, canonicalSeq: Long?, resultCode: String?, resultingRev: Long?, conflictId: String?, approvalId: String?, completedAt: Long?) {}
        override suspend fun resetInFlightOpsToPending() {}
        override suspend fun getPendingOperationsInOrder() = emptyList<com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity>()
        override suspend fun getAllOutboxOps() = emptyList<com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity>()
        override suspend fun getUnresolvedOutboxOpsForTargetItem(itemId: String) = emptyList<com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity>()
        override suspend fun getOutboxByOperationId(operationId: String) = null
        override suspend fun getItemById(itemId: String) = fetchAllItemsSync().firstOrNull { it.itemId == itemId }
        override suspend fun getAllItems(): List<FollowerInventoryItemEntity> = fetchAllItemsSync().sortedBy { it.displayName }
        override suspend fun clearAllInventoryItems() { conn.createStatement().use { it.executeUpdate("DELETE FROM follower_inventory_items") } }
        override suspend fun getUsableItemsCount(): Int = fetchAllItemsSync().count { it.status in listOf("AVAILABLE", "LOW", "USE_SOON") }
        override suspend fun getOutItemsCount(): Int = fetchAllItemsSync().count { it.status == "OUT" }
        override fun observeAllItems(): kotlinx.coroutines.flow.Flow<List<FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(fetchAllItemsSync().sortedBy { it.displayName })
        override fun observeUsableItems(): kotlinx.coroutines.flow.Flow<List<FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(fetchAllItemsSync().filter { it.status in listOf("AVAILABLE", "LOW", "USE_SOON") }.sortedBy { it.displayName })
        override fun observeUsableItemsByLocation(location: String): kotlinx.coroutines.flow.Flow<List<FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(fetchAllItemsSync().filter { it.storageLocation == location && it.status in listOf("AVAILABLE", "LOW", "USE_SOON") }.sortedBy { it.displayName })
        override fun observeLowStockItems(): kotlinx.coroutines.flow.Flow<List<FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(fetchAllItemsSync().filter { it.status == "LOW" }.sortedBy { it.displayName })
        override fun observeUseSoonItems(): kotlinx.coroutines.flow.Flow<List<FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(fetchAllItemsSync().filter { it.status == "USE_SOON" }.sortedBy { it.displayName })
        override fun observeOutOfStockItems(): kotlinx.coroutines.flow.Flow<List<FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(fetchAllItemsSync().filter { it.status == "OUT" }.sortedBy { it.displayName })
        override fun observeItemsByLocation(location: String): kotlinx.coroutines.flow.Flow<List<FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(fetchAllItemsSync().filter { it.storageLocation == location }.sortedBy { it.displayName })
        override fun observeItemsByStatus(status: String): kotlinx.coroutines.flow.Flow<List<FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(fetchAllItemsSync().filter { it.status == status }.sortedBy { it.displayName })
        override suspend fun getItemByBarcode(barcode: String): FollowerInventoryItemEntity? = fetchAllItemsSync().firstOrNull { it.barcode == barcode }
        override suspend fun getUsableItemsByCanonicalIngredientId(canonicalName: String): List<FollowerInventoryItemEntity> = fetchAllItemsSync().filter { it.canonicalName == canonicalName && it.status in listOf("AVAILABLE", "LOW", "USE_SOON") }.sortedBy { it.displayName }

        override suspend fun insertOrUpdateItem(item: FollowerInventoryItemEntity) {
            conn.prepareStatement("""
                INSERT OR REPLACE INTO follower_inventory_items (
                    itemId, canonicalName, confirmedDisplayName, confirmedCategory, confirmedStorageLocation, confirmedQuantity, quantityUnit, confirmedStatus, mainConfirmedRevision, displayName, category, storageLocation, quantity, status, optimisticRevision, isPendingSync, updatedAt
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()).use { stmt ->
                stmt.setString(1, item.itemId)
                stmt.setString(2, item.canonicalName)
                stmt.setString(3, item.confirmedDisplayName)
                stmt.setString(4, item.confirmedCategory)
                stmt.setString(5, item.confirmedStorageLocation)
                stmt.setDouble(6, item.confirmedQuantity ?: 0.0)
                stmt.setString(7, item.quantityUnit)
                stmt.setString(8, item.confirmedStatus)
                stmt.setLong(9, item.mainConfirmedRevision)
                stmt.setString(10, item.displayName)
                stmt.setString(11, item.category)
                stmt.setString(12, item.storageLocation)
                stmt.setDouble(13, item.quantity ?: 0.0)
                stmt.setString(14, item.status)
                stmt.setLong(15, item.optimisticRevision)
                stmt.setInt(16, if (item.isPendingSync) 1 else 0)
                stmt.setLong(17, item.updatedAt)
                stmt.executeUpdate()
            }
        }
    }

    private class TestFollowerDatabase(private val conn: java.sql.Connection) : FollowerDatabase() {
        private val dao = TestJdbcFollowerDao(conn)
        override fun followerDao(): FollowerDao = dao
        override fun mealDao(): com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerMealDao = error("Meal DAO not used by this fake")
        override fun mealRequestDao(): com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerMealRequestDao = error("Meal projection not used in this inventory fixture")
        override fun shoppingSourceDao(): com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerShoppingSourceDao = error("Shopping sources not used in this fixture")
        @Deprecated("Deprecated in Room")
        override fun beginTransaction() { conn.setAutoCommit(false) }
        @Deprecated("Deprecated in Room")
        override fun setTransactionSuccessful() {}
        @Deprecated("Deprecated in Room")
        override fun endTransaction() { if (!conn.autoCommit) { conn.commit(); conn.setAutoCommit(true) } }
        @Deprecated("Deprecated in Room")
        override fun clearAllTables() {}
        override fun createInvalidationTracker(): androidx.room.InvalidationTracker = org.mockito.kotlin.mock()
        @Deprecated("Deprecated in Room")
        override fun createOpenHelper(config: androidx.room.DatabaseConfiguration): androidx.sqlite.db.SupportSQLiteOpenHelper = error("")
    }

    @Test
    fun testFollowerRepository_mapsFollowerDatabaseItemsCorrectly() = runTest {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        val db = TestFollowerDatabase(conn)
        val dao = db.followerDao()

        val pairingStore = object : FollowerPairingStore(null) {
            override fun isPaired(): Boolean = true
            override fun getPairingConfig(): FollowerPairingConfig = FollowerPairingConfig(
                hostIp = "192.168.0.126",
                port = 8443,
                pinnedCertFingerprint = "fingerprint",
                deviceId = "follower-device-id"
            )
        }

        val appRoleResolver = object : AppRoleResolver(pairingStore) {
            override fun getAppRole(): AppRole = AppRole.FOLLOWER
            override fun isFollower(): Boolean = true
            override fun isMain(): Boolean = false
        }

        val repo = InventoryRepositoryImpl(
            inventoryDao = org.mockito.kotlin.mock(),
            appRoleResolver = appRoleResolver,
            followerDatabase = db,
            followerLocalSyncEngine = null,
            followerIdentityStore = null
        )

        // 1. Initial state empty
        val initialItems = repo.getAllItems().first()
        assertTrue("Initial repository should be empty", initialItems.isEmpty())

        // 2. Insert item into Follower DB
        val testEntity = FollowerInventoryItemEntity(
            itemId = "7b197c46-780c-424c-8b45-1a9368f94d68",
            barcode = null,
            canonicalName = "fusilli pasta",
            confirmedDisplayName = "fusilli pasta",
            confirmedBrand = null,
            confirmedCategory = "CUPBOARD",
            confirmedStorageLocation = "CUPBOARD",
            confirmedQuantity = 3.0,
            confirmedQuantityUnit = "KILOGRAM",
            confirmedNotes = null,
            confirmedStatus = "AVAILABLE",
            mainConfirmedRevision = 1L,
            displayName = "fusilli pasta",
            brand = null,
            category = "CUPBOARD",
            storageLocation = "CUPBOARD",
            quantity = 3.0,
            quantityUnit = "KILOGRAM",
            notes = null,
            status = "AVAILABLE",
            optimisticRevision = 1L,
            isPendingSync = false,
            updatedAt = System.currentTimeMillis()
        )
        dao.insertOrUpdateItem(testEntity)

        // 3. Verify observeAllItems emits inserted item
        val observedAll = dao.observeAllItems().first()
        assertEquals(1, observedAll.size)
        assertEquals("fusilli pasta", observedAll[0].displayName)

        // 4. Verify observeUsableItems includes item
        val observedUsable = dao.observeUsableItems().first()
        assertEquals(1, observedUsable.size)

        // 5. Verify observeItemsByStatus("AVAILABLE") includes item
        val observedByStatus = dao.observeItemsByStatus("AVAILABLE").first()
        assertEquals(1, observedByStatus.size)

        // 6. Verify repository domain mapping
        val repoItems = repo.getAllItems().first()
        assertEquals(1, repoItems.size)
        val domainItem = repoItems[0]
        assertEquals("7b197c46-780c-424c-8b45-1a9368f94d68", domainItem.id)
        assertEquals("fusilli pasta", domainItem.displayName)
        assertEquals(3.0, domainItem.quantity!!, 0.001)
        assertEquals(QuantityUnit.KILOGRAM, domainItem.quantityUnit)
        assertEquals(StorageLocation.CUPBOARD, domainItem.storageLocation)
        assertEquals(ItemStatus.AVAILABLE, domainItem.status)

        conn.close()
    }
}
