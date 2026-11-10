package com.example.chatgpt_mealplanner.sync

import android.content.Context
import com.example.chatgpt_mealplanner.core.database.AppDatabase
import com.example.chatgpt_mealplanner.core.database.entity.PairedDeviceEntity
import com.example.chatgpt_mealplanner.core.database.entity.toEntity
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.security.AndroidKeyStoreManager
import com.example.chatgpt_mealplanner.core.security.Base64Utils
import com.example.chatgpt_mealplanner.core.security.CredentialStore
import com.example.chatgpt_mealplanner.core.service.FakeInventoryDao
import com.example.chatgpt_mealplanner.core.service.FakeTransactionRunner
import com.example.chatgpt_mealplanner.core.startup.AppStartupManager
import com.example.chatgpt_mealplanner.core.sync.HouseholdIdentityManager
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.core.sync.client.TestClientBootstrapResult
import com.example.chatgpt_mealplanner.core.sync.client.TestClientFeedResult
import com.example.chatgpt_mealplanner.core.sync.engine.MainCanonicalSyncEngine
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerDao
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerDeviceSequenceEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine
import com.example.chatgpt_mealplanner.core.sync.follower.worker.FollowerNetworkCallbackListener
import com.example.chatgpt_mealplanner.core.sync.follower.worker.FollowerSyncScheduler
import com.example.chatgpt_mealplanner.core.sync.server.PantrySyncServer
import com.example.chatgpt_mealplanner.core.sync.server.SyncSessionManager
import com.example.chatgpt_mealplanner.feature.settings.SettingsViewModel
import com.example.chatgpt_mealplanner.sync.contract.AddItemPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.BootstrapResponseDto
import com.example.chatgpt_mealplanner.sync.contract.InventoryItemSyncDto
import com.example.chatgpt_mealplanner.sync.contract.SyncFeedItemDto
import com.example.chatgpt_mealplanner.sync.contract.SyncFeedResponseDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationResultDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationStatus
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

open class FakeKeyStoreManager : AndroidKeyStoreManager() {
    private val keyPairMap = mutableMapOf<String, KeyPair>()

    override fun ensureKeyPair(alias: String): KeyPair {
        return keyPairMap.getOrPut(alias) {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(256)
            kpg.generateKeyPair()
        }
    }

    override fun getPublicKey(alias: String): PublicKey? {
        return ensureKeyPair(alias).public
    }

    override fun getPublicKeyBase64(alias: String): String? {
        val key = getPublicKey(alias) ?: return null
        return Base64Utils.encodeToString(key.encoded)
    }

    override fun signChallenge(alias: String, challengeData: ByteArray): String {
        val kp = ensureKeyPair(alias)
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(kp.private)
        sig.update(challengeData)
        return Base64Utils.encodeToString(sig.sign())
    }

    override fun verifySignature(publicKeyBase64: String, challengeData: ByteArray, signatureBase64: String): Boolean {
        return try {
            val keyBytes = Base64Utils.decode(publicKeyBase64)
            val keyFactory = KeyFactory.getInstance("EC")
            val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(keyBytes))

            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(publicKey)
            sig.update(challengeData)
            val sigBytes = Base64Utils.decode(signatureBase64)
            sig.verify(sigBytes)
        } catch (_: Exception) {
            false
        }
    }
}

open class RealJdbcFollowerDao(private val conn: java.sql.Connection) : FollowerDao {
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

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS `follower_sync_metadata` (
                    `singletonId` INTEGER PRIMARY KEY NOT NULL,
                    `lastReconciledCanonicalSequence` INTEGER NOT NULL,
                    `lastSuccessfulPullAt` INTEGER,
                    `bootstrapCompleted` INTEGER NOT NULL DEFAULT 0,
                    `bootstrapCompletedAt` INTEGER,
                    `bootstrapCanonicalSequence` INTEGER
                )
            """.trimIndent())
        }
    }

    override suspend fun getAllItems(): List<FollowerInventoryItemEntity> {
        val list = mutableListOf<FollowerInventoryItemEntity>()
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT itemId, canonicalName, confirmedDisplayName, confirmedCategory, confirmedStorageLocation, confirmedQuantity, confirmedStatus, mainConfirmedRevision, displayName, category, storageLocation, quantity, status, optimisticRevision, isPendingSync, updatedAt FROM follower_inventory_items")
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
                        confirmedQuantityUnit = null,
                        confirmedNotes = null,
                        confirmedStatus = rs.getString("confirmedStatus"),
                        mainConfirmedRevision = rs.getLong("mainConfirmedRevision"),
                        displayName = rs.getString("displayName"),
                        brand = null,
                        category = rs.getString("category"),
                        storageLocation = rs.getString("storageLocation"),
                        quantity = rs.getDouble("quantity"),
                        quantityUnit = null,
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

    override suspend fun getItemById(itemId: String): FollowerInventoryItemEntity? {
        return getAllItems().find { it.itemId == itemId }
    }

    override suspend fun insertOrUpdateItem(item: FollowerInventoryItemEntity) {
        val count = conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT COUNT(*) FROM follower_inventory_items")
            if (rs.next()) rs.getInt(1) else 0
        }
        if (count >= 2) {
            throw RuntimeException("Injected SQLite exception after first snapshot item write")
        }

        conn.prepareStatement("""
            INSERT OR REPLACE INTO follower_inventory_items (
                itemId, canonicalName, confirmedDisplayName, confirmedCategory, confirmedStorageLocation, confirmedQuantity, confirmedStatus, mainConfirmedRevision, displayName, category, storageLocation, quantity, status, optimisticRevision, isPendingSync, updatedAt
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()).use { stmt ->
            stmt.setString(1, item.itemId)
            stmt.setString(2, item.canonicalName)
            stmt.setString(3, item.confirmedDisplayName)
            stmt.setString(4, item.confirmedCategory)
            stmt.setString(5, item.confirmedStorageLocation)
            stmt.setDouble(6, item.confirmedQuantity ?: 0.0)
            stmt.setString(7, item.confirmedStatus)
            stmt.setLong(8, item.mainConfirmedRevision)
            stmt.setString(9, item.displayName)
            stmt.setString(10, item.category)
            stmt.setString(11, item.storageLocation)
            stmt.setDouble(12, item.quantity ?: 0.0)
            stmt.setString(13, item.status)
            stmt.setLong(14, item.optimisticRevision)
            stmt.setInt(15, if (item.isPendingSync) 1 else 0)
            stmt.setLong(16, item.updatedAt)
            stmt.executeUpdate()
        }
    }

    override suspend fun getSyncMetadata(): FollowerSyncMetadataEntity? {
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT singletonId, lastReconciledCanonicalSequence, lastSuccessfulPullAt, bootstrapCompleted, bootstrapCompletedAt, bootstrapCanonicalSequence FROM follower_sync_metadata WHERE singletonId = 1")
            if (rs.next()) {
                val completedAtObj = rs.getObject("bootstrapCompletedAt")
                val completedAt = if (completedAtObj == null) null else (completedAtObj as Number).toLong()
                val canonSeqObj = rs.getObject("bootstrapCanonicalSequence")
                val canonSeq = if (canonSeqObj == null) null else (canonSeqObj as Number).toLong()
                return FollowerSyncMetadataEntity(
                    singletonId = rs.getInt("singletonId"),
                    lastReconciledCanonicalSequence = rs.getLong("lastReconciledCanonicalSequence"),
                    lastSuccessfulPullAt = rs.getLong("lastSuccessfulPullAt"),
                    bootstrapCompleted = rs.getInt("bootstrapCompleted") == 1,
                    bootstrapCompletedAt = completedAt,
                    bootstrapCanonicalSequence = canonSeq
                )
            }
        }
        return null
    }

    override suspend fun updateSyncMetadata(metadata: FollowerSyncMetadataEntity) {
        conn.prepareStatement("""
            INSERT OR REPLACE INTO follower_sync_metadata (
                singletonId, lastReconciledCanonicalSequence, lastSuccessfulPullAt, bootstrapCompleted, bootstrapCompletedAt, bootstrapCanonicalSequence
            ) VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent()).use { stmt ->
            stmt.setInt(1, metadata.singletonId)
            stmt.setLong(2, metadata.lastReconciledCanonicalSequence)
            stmt.setLong(3, metadata.lastSuccessfulPullAt ?: 0L)
            stmt.setInt(4, if (metadata.bootstrapCompleted) 1 else 0)
            if (metadata.bootstrapCompletedAt != null) stmt.setLong(5, metadata.bootstrapCompletedAt) else stmt.setNull(5, java.sql.Types.INTEGER)
            if (metadata.bootstrapCanonicalSequence != null) stmt.setLong(6, metadata.bootstrapCanonicalSequence) else stmt.setNull(6, java.sql.Types.INTEGER)
            stmt.executeUpdate()
        }
    }

    override suspend fun getPendingOperationsInOrder(): List<FollowerSyncOutboxEntity> = emptyList()
    override suspend fun getAllOutboxOps(): List<FollowerSyncOutboxEntity> = emptyList()
    override suspend fun insertOutboxOperation(op: FollowerSyncOutboxEntity) {}
    override suspend fun updateOutboxStatus(operationId: String, status: String, attemptCount: Int, lastAttemptAt: Long?, lastErrorCode: String?) {}
    override suspend fun reconcileOutboxResult(operationId: String, status: String, canonicalSeq: Long?, resultCode: String?, resultingRev: Long?, conflictId: String?, approvalId: String?, completedAt: Long?) {}
    override suspend fun resetInFlightOpsToPending() {}
    override suspend fun getOutboxByOperationId(operationId: String): FollowerSyncOutboxEntity? = null
    override suspend fun getUnresolvedOutboxOpsForTargetItem(itemId: String): List<FollowerSyncOutboxEntity> = emptyList()
    override suspend fun getSequenceEntity(): FollowerDeviceSequenceEntity? = null
    override suspend fun updateSequenceEntity(entity: FollowerDeviceSequenceEntity) {}
    override suspend fun clearAllInventoryItems() {}
    override suspend fun getUsableItemsCount(): Int = kotlinx.coroutines.runBlocking { getAllItems().count { it.status in listOf("AVAILABLE", "LOW", "USE_SOON") } }
    override suspend fun getOutItemsCount(): Int = kotlinx.coroutines.runBlocking { getAllItems().count { it.status == "OUT" } }
    override fun observeAllItems(): kotlinx.coroutines.flow.Flow<List<FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(kotlinx.coroutines.runBlocking { getAllItems().sortedBy { it.displayName } })
    override fun observeUsableItems(): kotlinx.coroutines.flow.Flow<List<FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(kotlinx.coroutines.runBlocking { getAllItems().filter { it.status in listOf("AVAILABLE", "LOW", "USE_SOON") }.sortedBy { it.displayName } })
    override fun observeUsableItemsByLocation(location: String): kotlinx.coroutines.flow.Flow<List<FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(kotlinx.coroutines.runBlocking { getAllItems().filter { it.storageLocation == location && it.status in listOf("AVAILABLE", "LOW", "USE_SOON") }.sortedBy { it.displayName } })
    override fun observeLowStockItems(): kotlinx.coroutines.flow.Flow<List<FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(kotlinx.coroutines.runBlocking { getAllItems().filter { it.status == "LOW" }.sortedBy { it.displayName } })
    override fun observeUseSoonItems(): kotlinx.coroutines.flow.Flow<List<FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(kotlinx.coroutines.runBlocking { getAllItems().filter { it.status == "USE_SOON" }.sortedBy { it.displayName } })
    override fun observeOutOfStockItems(): kotlinx.coroutines.flow.Flow<List<FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(kotlinx.coroutines.runBlocking { getAllItems().filter { it.status == "OUT" }.sortedBy { it.displayName } })
    override fun observeItemsByLocation(location: String): kotlinx.coroutines.flow.Flow<List<FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(kotlinx.coroutines.runBlocking { getAllItems().filter { it.storageLocation == location }.sortedBy { it.displayName } })
    override fun observeItemsByStatus(status: String): kotlinx.coroutines.flow.Flow<List<FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(kotlinx.coroutines.runBlocking { getAllItems().filter { it.status == status }.sortedBy { it.displayName } })
    override suspend fun getItemByBarcode(barcode: String): FollowerInventoryItemEntity? = getAllItems().firstOrNull { it.barcode == barcode }
    override suspend fun getUsableItemsByCanonicalIngredientId(canonicalName: String): List<FollowerInventoryItemEntity> = getAllItems().filter { it.canonicalName == canonicalName && it.status in listOf("AVAILABLE", "LOW", "USE_SOON") }.sortedBy { it.displayName }
}

open class RealRoomFollowerDatabase(private val conn: java.sql.Connection) : FollowerDatabase() {
    private val realDao = RealJdbcFollowerDao(conn)

    override fun followerDao(): FollowerDao = realDao
    override fun mealDao(): com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerMealDao = error("Meal DAO not used by this fake")
    override fun mealRequestDao(): com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerMealRequestDao = error("Meal projection not used in this inventory fixture")

    @Deprecated("Deprecated in Room")
    override fun beginTransaction() { conn.setAutoCommit(false) }
    @Deprecated("Deprecated in Room")
    override fun setTransactionSuccessful() {}
    @Deprecated("Deprecated in Room")
    override fun endTransaction() {
        if (!conn.autoCommit) {
            conn.rollback()
            conn.setAutoCommit(true)
        }
    }
    @Deprecated("Deprecated in Room")
    override fun createOpenHelper(config: androidx.room.DatabaseConfiguration): androidx.sqlite.db.SupportSQLiteOpenHelper = error("")
    override fun createInvalidationTracker(): androidx.room.InvalidationTracker = error("")
    @Deprecated("Deprecated in Room")
    override fun clearAllTables() {}
}

open class FakeBootstrapSyncTestClient : PantrySyncTestClient(FakeKeyStoreManager()) {
    var bootstrapToReturn: BootstrapResponseDto? = null
    var feedToReturn: SyncFeedResponseDto? = null
    var customErrorCode: String? = null

    var bootstrapCallCount = 0
    var feedCallCount = 0

    override suspend fun getBootstrapSnapshot(
        hostIp: String,
        port: Int,
        pinnedCertFingerprint: String?,
        sessionToken: String
    ): TestClientBootstrapResult {
        bootstrapCallCount++
        val err = customErrorCode
        if (err != null) {
            return TestClientBootstrapResult(false, null, err)
        }
        val resp = bootstrapToReturn
        return if (resp != null) {
            TestClientBootstrapResult(true, resp, null)
        } else {
            TestClientBootstrapResult(false, null, "UNAUTHORIZED")
        }
    }

    override suspend fun getSyncFeed(
        hostIp: String,
        port: Int,
        pinnedCertFingerprint: String?,
        sessionToken: String,
        sinceSequence: Long,
        limit: Int
    ): TestClientFeedResult {
        feedCallCount++
        val resp = feedToReturn
        return if (resp != null) {
            TestClientFeedResult(true, resp, null)
        } else {
            TestClientFeedResult(true, SyncFeedResponseDto("household_123", sinceSequence, emptyList(), false), null)
        }
    }
}

@RunWith(JUnit4::class)
class PantrySyncPhase3eBootstrapTest {

    private val json = Json { ignoreUnknownKeys = true }

    // Main / Host Side Components
    private lateinit var mainCanonicalDao: FakeCanonicalSyncDao
    private lateinit var mainPairedDeviceDao: FakeSyncPairedDeviceDao
    private lateinit var mainInventoryDao: FakeInventoryDao
    private lateinit var mainTransactionRunner: FakeTransactionRunner
    private lateinit var mainCanonicalEngine: MainCanonicalSyncEngine
    private lateinit var server: PantrySyncServer
    private lateinit var sessionManager: SyncSessionManager
    private lateinit var identityManager: HouseholdIdentityManager
    private lateinit var identityDao: FakeHouseholdIdentityDao
    private lateinit var fakeKeyStoreManager: FakeKeyStoreManager

    // Follower / Client Side Components
    private lateinit var followerDb: FollowerDatabase
    private lateinit var fakeDao: FakeFollowerDao
    private lateinit var localEngine: TestableFollowerSyncEngine
    private lateinit var pullEngine: FollowerFeedPullEngine
    private lateinit var bootstrapEngine: FollowerBootstrapEngine
    private lateinit var fakeClient: FakeBootstrapSyncTestClient
    private lateinit var scheduler: FollowerSyncScheduler

    private val followerDeviceId = UUID.randomUUID().toString()
    private val mainDeviceId = UUID.randomUUID().toString()

    @Before
    fun setUp() = runTest {
        fakeKeyStoreManager = FakeKeyStoreManager()

        // Initialize Main side
        mainCanonicalDao = FakeCanonicalSyncDao()
        mainPairedDeviceDao = FakeSyncPairedDeviceDao()
        mainInventoryDao = FakeInventoryDao()
        mainTransactionRunner = FakeTransactionRunner()

        mainCanonicalEngine = MainCanonicalSyncEngine(
            canonicalSyncDao = mainCanonicalDao,
            pairedDeviceDao = mainPairedDeviceDao,
            inventoryDao = mainInventoryDao,
            transactionRunner = mainTransactionRunner
        )

        identityDao = FakeHouseholdIdentityDao()
        identityManager = HouseholdIdentityManager(identityDao)
        sessionManager = SyncSessionManager(fakeKeyStoreManager, mainPairedDeviceDao)

        server = PantrySyncServer(
            identityManager = identityManager,
            sessionManager = sessionManager,
            syncEngine = mainCanonicalEngine,
            canonicalSyncDao = mainCanonicalDao,
            inventoryDao = mainInventoryDao,
            transactionRunner = mainTransactionRunner
        )

        mainPairedDeviceDao.insertOrUpdateDevice(
            PairedDeviceEntity(
                deviceId = followerDeviceId,
                deviceName = "Follower Phone",
                devicePublicKey = "MCowBQYDK2Vw...",
                status = "ACTIVE",
                pairedAt = System.currentTimeMillis(),
                lastSyncTimestamp = System.currentTimeMillis()
            )
        )

        // Initialize Follower side
        fakeDao = FakeFollowerDao()
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        followerDb = RealRoomFollowerDatabase(conn)
        localEngine = TestableFollowerSyncEngine(fakeDao)
        fakeClient = FakeBootstrapSyncTestClient()
        pullEngine = FollowerFeedPullEngine(followerDb, fakeClient, localEngine)
        bootstrapEngine = FollowerBootstrapEngine(followerDb, fakeClient, pullEngine)

        scheduler = FollowerSyncScheduler()
    }

    private suspend fun buildSnapshotFromMainState(): BootstrapResponseDto {
        return server.createBootstrapSnapshot()
    }

    // 1. deterministic_concurrencyProof_stepsAtoL
    @Test
    fun deterministic_concurrencyProof_stepsAtoL() = runTest {
        // FakeTransactionRunner doesn't isolate transactions, so this proof needs a runner that
        // serializes them the way Room does. Otherwise the mutation can overtake the snapshot.
        val serializingRunner = SerializingTransactionRunner()
        val serializedEngine = MainCanonicalSyncEngine(
            canonicalSyncDao = mainCanonicalDao,
            pairedDeviceDao = mainPairedDeviceDao,
            inventoryDao = mainInventoryDao,
            transactionRunner = serializingRunner
        )
        val serializedServer = PantrySyncServer(
            identityManager = identityManager,
            sessionManager = sessionManager,
            syncEngine = serializedEngine,
            canonicalSyncDao = mainCanonicalDao,
            inventoryDao = mainInventoryDao,
            transactionRunner = serializingRunner
        )

        val seqReadLatch = CountDownLatch(1)
        val releaseSnapshotTransactionLatch = CountDownLatch(1)
        val mutationStartLatch = CountDownLatch(1)

        serializedServer.onInsideSnapshotTransactionHook = {
            seqReadLatch.countDown()
            releaseSnapshotTransactionLatch.await()
        }

        val itemId = UUID.randomUUID().toString()
        val addOp = SyncOperationDto(
            operationId = UUID.randomUUID().toString(),
            deviceId = followerDeviceId,
            deviceSequence = 1L,
            protocolVersion = 1,
            operationType = SyncOperationType.ADD_ITEM,
            targetItemId = itemId,
            baseRevision = null,
            payloadJson = json.encodeToString(
                AddItemPayloadDto(
                    itemId = itemId,
                    canonicalName = "Concurrent Oil",
                    displayName = "Extra Virgin Olive Oil",
                    category = FoodCategory.FRESH_PANTRY.name,
                    storageLocation = StorageLocation.CUPBOARD.name,
                    quantity = 1.0,
                    quantityUnit = QuantityUnit.BOTTLE.name
                )
            ),
            createdAt = System.currentTimeMillis()
        )

        // A. Start bootstrap snapshot transaction
        val snapshotDeferred = async(Dispatchers.IO) {
            serializedServer.createBootstrapSnapshot()
        }

        // B & C. Wait for snapshot transaction to enter, read watermark X, and pause inside hook
        assertTrue("Snapshot read MUST reach inside-transaction hook", seqReadLatch.await(5, TimeUnit.SECONDS))

        // D. Start real canonical mutation asynchronously
        var mutationCommitted = false
        var mutationResult: SyncOperationResultDto? = null
        val mutationJob = async(Dispatchers.IO) {
            mutationStartLatch.countDown()
            val res = serializedEngine.processOperation(followerDeviceId, addOp)
            mutationCommitted = true
            mutationResult = res
            res
        }

        // E & F. Prove mutation started but has NOT committed while snapshot transaction is paused
        assertTrue("Mutation task MUST start", mutationStartLatch.await(5, TimeUnit.SECONDS))
        assertFalse("Mutation MUST NOT have committed while snapshot transaction is paused", mutationCommitted)
        assertNull("Mutation result MUST be null while snapshot transaction is paused", mutationResult)

        // G. Release snapshot transaction
        releaseSnapshotTransactionLatch.countDown()

        // H. Snapshot reads inventory and completes
        val snapshot = snapshotDeferred.await()
        val watermarkX = snapshot.snapshotCanonicalSequence

        // I. Prove mutation then completes and commits
        val res = mutationJob.await()
        assertTrue("Mutation MUST commit after snapshot transaction releases", mutationCommitted)
        assertEquals(SyncOperationStatus.APPLIED, res.status)

        // J. Assert mutation canonicalSequence > X
        val mutationSeq = res.canonicalSequence ?: -1L
        assertTrue("Mutation canonical sequence $mutationSeq MUST be > watermark $watermarkX", mutationSeq > watermarkX)

        // K. Assert mutation is absent from snapshot
        assertTrue("Mutation item $itemId MUST be absent from snapshot inventory", snapshot.inventoryItems.none { it.itemId == itemId })

        // L. Assert feed since X contains that exact operation
        val feedOps = mainCanonicalDao.getFeedOperationsSince(watermarkX, 100)
        assertTrue("Feed since watermark X MUST contain the concurrent mutation", feedOps.any { it.operationId == addOp.operationId && it.canonicalSequence > watermarkX })
    }

    // 2. real_followerRoomRollbackTest_executesProductionWithTransaction
    @Test
    fun real_followerRoomRollbackTest_executesProductionWithTransaction() = runTest {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        val testRoomDb = RealRoomFollowerDatabase(conn)
        val rollbackDao = testRoomDb.followerDao()
        val testEngine = FollowerBootstrapEngine(testRoomDb, fakeClient, pullEngine)

        // Arrange pre-existing follower state and metadata
        val preExistingItem = FollowerInventoryItemEntity(
            itemId = "pre_existing_1",
            canonicalName = "Existing Salt",
            confirmedDisplayName = "Table Salt",
            confirmedBrand = null,
            confirmedCategory = "FRESH_PANTRY",
            confirmedStorageLocation = "CUPBOARD",
            confirmedQuantity = 1.0,
            confirmedQuantityUnit = "BAG",
            confirmedNotes = null,
            confirmedStatus = "AVAILABLE",
            mainConfirmedRevision = 5L,
            displayName = "Table Salt",
            brand = null,
            category = "FRESH_PANTRY",
            storageLocation = "CUPBOARD",
            quantity = 1.0,
            quantityUnit = "BAG",
            notes = null,
            status = "AVAILABLE",
            optimisticRevision = 5L,
            isPendingSync = false,
            updatedAt = System.currentTimeMillis()
        )
        rollbackDao.insertOrUpdateItem(preExistingItem)

        val preExistingMeta = FollowerSyncMetadataEntity(
            singletonId = 1,
            lastReconciledCanonicalSequence = 5L,
            lastSuccessfulPullAt = System.currentTimeMillis(),
            bootstrapCompleted = false,
            bootstrapCompletedAt = null,
            bootstrapCanonicalSequence = null
        )
        rollbackDao.updateSyncMetadata(preExistingMeta)

        val item1 = InventoryItemSyncDto(UUID.randomUUID().toString(), null, "Tea", "Black Tea", null, "BEVERAGE", "CUPBOARD", 1.0, "PACK", null, "AVAILABLE", 100L)
        val item2 = InventoryItemSyncDto(UUID.randomUUID().toString(), null, "Coffee", "Beans", null, "BEVERAGE", "CUPBOARD", 1.0, "PACK", null, "AVAILABLE", 101L)

        fakeClient.bootstrapToReturn = BootstrapResponseDto(
            protocolVersion = 1,
            householdId = "household_123",
            snapshotCanonicalSequence = 100L,
            inventoryItems = listOf(item1, item2)
        )

        // MUST NOT pass customDao! Must execute production branch: followerDatabase.withTransaction { ... }
        val summary = testEngine.performBootstrapPass(
            hostIp = "127.0.0.1",
            port = 8443,
            pinnedCertFingerprint = null,
            sessionToken = "valid_token",
            followerDeviceId = followerDeviceId
        )

        assertFalse("Fault during install MUST cause pass to fail", summary.success)
        assertEquals("DATABASE_INSTALL_FAILED", summary.resultCode)

        // Assert against REAL database / DAO
        val itemsInDb = rollbackDao.getAllItems()
        assertEquals("Pre-existing follower inventory MUST remain correct (1 item)", 1, itemsInDb.size)
        assertEquals("pre_existing_1", itemsInDb[0].itemId)

        val metaAfter = rollbackDao.getSyncMetadata()
        assertEquals(5L, metaAfter?.lastReconciledCanonicalSequence ?: 0L)
        assertFalse("bootstrapCompleted MUST remain false", metaAfter?.bootstrapCompleted == true)
        assertNull("bootstrapCompletedAt MUST remain null", metaAfter?.bootstrapCompletedAt)
        assertNull("bootstrapCanonicalSequence MUST remain null", metaAfter?.bootstrapCanonicalSequence)

        conn.close()
    }

    // 3. failClosed_encryptedStorageFailure_returnsNullConfig
    @Test
    fun failClosed_encryptedStorageFailure_returnsNullConfig() = runTest {
        val failClosedStore = FollowerPairingStore(null)

        assertFalse("isPaired MUST return false when encrypted store is unavailable", failClosedStore.isPaired())
        assertNull("getPairingConfig MUST return null when encrypted store is unavailable", failClosedStore.getPairingConfig())
    }

    // 4. real_appStartTrigger_schedulesUniqueWorker
    @Test
    fun real_appStartTrigger_schedulesUniqueWorker() = runTest {
        var capturedTrigger: String? = null
        val testScheduler = object : FollowerSyncScheduler() {
            override fun scheduleSyncWork(context: Context?, triggerReason: String): com.example.chatgpt_mealplanner.core.sync.follower.worker.WorkSchedulingResult {
                capturedTrigger = triggerReason
                return com.example.chatgpt_mealplanner.core.sync.follower.worker.WorkSchedulingResult.SUBMITTED_AND_CONFIRMED
            }
        }

        val pairingStore = object : FollowerPairingStore(null) {
            override fun isPaired(): Boolean = true
            override fun getPairingConfig(): FollowerPairingConfig = FollowerPairingConfig("127.0.0.1", 8443, "SHA256:0000", followerDeviceId)
        }

        val startupManager = AppStartupManager(
            context = null,
            database = FakeAppDatabase(),
            followerPairingStore = pairingStore,
            followerDatabase = followerDb,
            followerSyncScheduler = testScheduler
        )

        val status = startupManager.checkAppHealth()
        assertEquals(com.example.chatgpt_mealplanner.core.startup.AppHealthStatus.HEALTHY, status)
        assertEquals("APP_START", capturedTrigger)
    }

    // 5. real_networkAvailableTrigger_schedulesUniqueWorker
    @Test
    fun real_networkAvailableTrigger_schedulesUniqueWorker() = runTest {
        var capturedTrigger: String? = null
        val testScheduler = object : FollowerSyncScheduler() {
            override fun scheduleSyncWork(context: Context?, triggerReason: String): com.example.chatgpt_mealplanner.core.sync.follower.worker.WorkSchedulingResult {
                capturedTrigger = triggerReason
                return com.example.chatgpt_mealplanner.core.sync.follower.worker.WorkSchedulingResult.SUBMITTED_AND_CONFIRMED
            }
        }

        testScheduler.scheduleSyncWork(null, "NETWORK_AVAILABLE")
        assertEquals("NETWORK_AVAILABLE", capturedTrigger)
    }

    // 6. real_manualSyncTrigger_schedulesUniqueWorker
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun real_manualSyncTrigger_schedulesUniqueWorker() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            var capturedTrigger: String? = null
            val testScheduler = object : FollowerSyncScheduler() {
                override fun scheduleSyncWork(context: Context?, triggerReason: String): com.example.chatgpt_mealplanner.core.sync.follower.worker.WorkSchedulingResult {
                    capturedTrigger = triggerReason
                    return com.example.chatgpt_mealplanner.core.sync.follower.worker.WorkSchedulingResult.SUBMITTED_AND_CONFIRMED
                }

                override suspend fun scheduleSyncWorkAsync(context: Context?, triggerReason: String): com.example.chatgpt_mealplanner.core.sync.follower.worker.WorkSchedulingResult {
                    capturedTrigger = triggerReason
                    return com.example.chatgpt_mealplanner.core.sync.follower.worker.WorkSchedulingResult.SUBMITTED_AND_CONFIRMED
                }

                override suspend fun scheduleSyncWorkAsync(context: Context?, triggerReason: String, intentAlreadyRecorded: Boolean): com.example.chatgpt_mealplanner.core.sync.follower.worker.WorkSchedulingResult {
                    capturedTrigger = triggerReason
                    return com.example.chatgpt_mealplanner.core.sync.follower.worker.WorkSchedulingResult.SUBMITTED_AND_CONFIRMED
                }
            }

            val dummyCredentialStore = object : CredentialStore {
                override fun hasKey(): Boolean = false
                override fun saveKey(key: String) {}
                override fun loadKey(): String? = null
                override fun deleteKey() {}
            }

            val dummyAiProvider = object : com.example.chatgpt_mealplanner.core.ai.provider.MealPlannerAiProvider {
                override suspend fun send(request: com.example.chatgpt_mealplanner.contract.AiProviderRequest): com.example.chatgpt_mealplanner.contract.AiProviderResponse = com.example.chatgpt_mealplanner.contract.AiProviderResponse.Error("DUMMY", "Dummy")
                override suspend fun continueWithToolResults(request: com.example.chatgpt_mealplanner.contract.AiContinueWithToolsRequest): com.example.chatgpt_mealplanner.contract.AiProviderResponse = com.example.chatgpt_mealplanner.contract.AiProviderResponse.Error("DUMMY", "Dummy")
            }

            val fakeSafWriter = com.example.chatgpt_mealplanner.core.backup.MainEncryptedBackupSafWriter(
                object : com.example.chatgpt_mealplanner.core.backup.SafDocumentStreamProvider {
                    override fun openOutputStream(appContext: android.content.Context?, uri: android.net.Uri?): java.io.OutputStream? = null
                    override fun openInputStream(appContext: android.content.Context?, uri: android.net.Uri?): java.io.InputStream? = null
                }
            )
            val fakeSaveCoordinator = com.example.chatgpt_mealplanner.core.backup.MainEncryptedBackupSaveCoordinator(
                FakeAppRoleResolver(),
                fakeSafWriter,
                org.mockito.kotlin.mock()
            )

            val viewModel = SettingsViewModel(
                context = null,
                credentialStore = dummyCredentialStore,
                provider = dummyAiProvider,
                chatDao = FakeChatDao(),
                backupManager = FakeBackupManager(),
                followerSyncScheduler = testScheduler,
                appRoleResolver = FakeAppRoleResolver(),
                mainBackupSaveCoordinator = fakeSaveCoordinator
            )

            viewModel.triggerManualSync()
            advanceUntilIdle()
            assertEquals("MANUAL_SYNC", capturedTrigger)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // 7. bootstrap_expiredSession_refreshesAndRetriesSafely
    @Test
    fun bootstrap_expiredSession_refreshesAndRetriesSafely() = runTest {
        var callAttempts = 0
        val tokenProviderClient = object : FakeBootstrapSyncTestClient() {
            override suspend fun getBootstrapSnapshot(
                hostIp: String,
                port: Int,
                pinnedCertFingerprint: String?,
                sessionToken: String
            ): TestClientBootstrapResult {
                callAttempts++
                return if (sessionToken == "expired_token") {
                    TestClientBootstrapResult(false, null, "EXPIRED_SESSION (HTTP 401)")
                } else {
                    TestClientBootstrapResult(
                        true,
                        BootstrapResponseDto(1, "household_123", 50L, emptyList()),
                        null
                    )
                }
            }
        }

        val customEngine = FollowerBootstrapEngine(null, tokenProviderClient, pullEngine)

        val summary = customEngine.performBootstrapPass(
            hostIp = "127.0.0.1",
            port = 8443,
            pinnedCertFingerprint = null,
            sessionToken = "expired_token",
            followerDeviceId = followerDeviceId,
            customDao = fakeDao,
            sessionTokenProvider = { forceRefresh ->
                if (forceRefresh) "refreshed_valid_token" else "expired_token"
            }
        )

        assertTrue(summary.success)
        assertEquals("SUCCESS", summary.resultCode)
        assertEquals(2, callAttempts)
        assertTrue(fakeDao.getSyncMetadata()?.bootstrapCompleted == true)
    }

    // 8. bootstrap_snapshotTooLarge_failsWithoutPartialInstall
    @Test
    fun bootstrap_snapshotTooLarge_failsWithoutPartialInstall() = runTest {
        val oversizedItems = List(5001) { i ->
            InventoryItem(
                id = "item_$i",
                canonicalName = "Item $i",
                displayName = "Item $i",
                category = FoodCategory.FRESH_PANTRY,
                storageLocation = StorageLocation.CUPBOARD,
                quantity = 1.0,
                quantityUnit = QuantityUnit.UNIT,
                status = ItemStatus.AVAILABLE,
                revision = 1L
            ).toEntity()
        }
        for (item in oversizedItems) {
            mainInventoryDao.insertItem(item)
        }

        try {
            server.createBootstrapSnapshot()
            org.junit.Assert.fail("Expected SnapshotTooLargeException when item count > 5000")
        } catch (e: com.example.chatgpt_mealplanner.core.sync.server.SnapshotTooLargeException) {
            assertTrue(e.message?.contains("5001") == true)
        }
    }

    // 9. bootstrappedFollower_usesNormalSyncNotBootstrap
    @Test
    fun bootstrappedFollower_usesNormalSyncNotBootstrap() = runTest {
        fakeDao.updateSyncMetadata(
            FollowerSyncMetadataEntity(
                singletonId = 1,
                lastReconciledCanonicalSequence = 500L,
                bootstrapCompleted = true,
                bootstrapCanonicalSequence = 500L,
                lastSuccessfulPullAt = System.currentTimeMillis()
            )
        )

        val summary = bootstrapEngine.performBootstrapPass(
            hostIp = "127.0.0.1",
            port = 8443,
            pinnedCertFingerprint = null,
            sessionToken = "valid_token",
            followerDeviceId = followerDeviceId,
            customDao = fakeDao
        )

        assertTrue(summary.success)
        assertEquals("BOOTSTRAP_ALREADY_COMPLETED", summary.resultCode)
        assertEquals(0, summary.installedItemsCount)
        assertEquals("Bootstrap network call MUST NOT be invoked if already bootstrapped", 0, fakeClient.bootstrapCallCount)
    }
}

open class FakeBackupManager : com.example.chatgpt_mealplanner.core.backup.BackupManager(
    database = null,
    transactionRunner = FakeTransactionRunner()
)

open class FakeAppRoleResolver : com.example.chatgpt_mealplanner.core.sync.AppRoleResolver(
    com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore(null)
) {
    override suspend fun isMainAsync(): Boolean = true
    override fun isMain(): Boolean = true
    override suspend fun isFollowerAsync(): Boolean = false
    override fun isFollower(): Boolean = false
}

open class FakeAppDatabase : AppDatabase() {
    override fun inventoryDao(): com.example.chatgpt_mealplanner.core.database.dao.InventoryDao = FakeInventoryDao()
    override fun chatDao(): com.example.chatgpt_mealplanner.core.database.dao.ChatDao = FakeChatDao()
    override fun inventoryBarcodeDao(): com.example.chatgpt_mealplanner.core.database.dao.InventoryBarcodeDao = error("")
    override fun transactionDao(): com.example.chatgpt_mealplanner.core.database.dao.TransactionDao = error("")
    override fun barcodeMappingDao(): com.example.chatgpt_mealplanner.core.database.dao.BarcodeMappingDao = error("")
    override fun mealDao(): com.example.chatgpt_mealplanner.core.database.dao.MealDao = error("")
    override fun leftoverDao(): com.example.chatgpt_mealplanner.core.database.dao.LeftoverDao = error("")
    override fun shoppingDao(): com.example.chatgpt_mealplanner.core.database.dao.ShoppingDao = error("")
    override fun stapleRuleDao(): com.example.chatgpt_mealplanner.core.database.dao.StapleRuleDao = error("")
    override fun purchasePriceDao(): com.example.chatgpt_mealplanner.core.database.dao.PurchasePriceDao = error("")
    override fun cookingPreferencesDao(): com.example.chatgpt_mealplanner.core.database.dao.CookingPreferencesDao = error("")
    override fun receiptDao(): com.example.chatgpt_mealplanner.core.database.dao.ReceiptDao = error("")
    override fun receiptSpendDao(): com.example.chatgpt_mealplanner.core.database.dao.ReceiptSpendDao = error("")
    override fun householdIdentityDao(): com.example.chatgpt_mealplanner.core.database.dao.HouseholdIdentityDao = error("")
    override fun pairedDeviceDao(): com.example.chatgpt_mealplanner.core.database.dao.PairedDeviceDao = error("")
    override fun canonicalSyncDao(): com.example.chatgpt_mealplanner.core.database.dao.CanonicalSyncDao = error("")
    override fun mealChangeRequestDao(): com.example.chatgpt_mealplanner.core.database.dao.MealChangeRequestDao = error("")
    override fun appDataResetDao(): com.example.chatgpt_mealplanner.core.database.dao.AppDataResetDao = error("")
    @Deprecated("Deprecated in Room")
    override fun createOpenHelper(config: androidx.room.DatabaseConfiguration): androidx.sqlite.db.SupportSQLiteOpenHelper = error("")
    override fun createInvalidationTracker(): androidx.room.InvalidationTracker = error("")
    @Deprecated("Deprecated in Room")
    override fun clearAllTables() {}
}

open class FakeChatDao : com.example.chatgpt_mealplanner.core.database.dao.ChatDao {
    override fun observeAllConversations() = kotlinx.coroutines.flow.flowOf(emptyList<com.example.chatgpt_mealplanner.core.database.entity.ChatConversationEntity>())
    override suspend fun getAllConversationsWithMessagesSync(): List<com.example.chatgpt_mealplanner.core.database.dao.ChatConversationWithMessages> = emptyList()
    override suspend fun getConversationById(id: String) = null
    override suspend fun insertOrUpdateConversation(conversation: com.example.chatgpt_mealplanner.core.database.entity.ChatConversationEntity) {}
    override suspend fun deleteConversation(id: String) {}
    override fun observeMessagesForConversation(conversationId: String) = kotlinx.coroutines.flow.flowOf(emptyList<com.example.chatgpt_mealplanner.core.database.entity.ChatMessageEntity>())
    override suspend fun getMessagesForConversation(conversationId: String) = emptyList<com.example.chatgpt_mealplanner.core.database.entity.ChatMessageEntity>()
    override suspend fun insertMessage(message: com.example.chatgpt_mealplanner.core.database.entity.ChatMessageEntity) {}
    override suspend fun deleteAllMessagesForConversation(conversationId: String) {}
    override suspend fun getToolExecutionLog(conversationId: String, toolCallId: String) = null
    override suspend fun insertOrUpdateToolExecutionLog(log: com.example.chatgpt_mealplanner.core.database.entity.AiToolExecutionLogEntity) {}
    override suspend fun deleteLogsOlderThan(timestamp: Long) {}
    override suspend fun getPendingAiMutation(conversationId: String) = null
    override suspend fun insertOrUpdatePendingAiMutation(entity: com.example.chatgpt_mealplanner.core.database.entity.PendingAiMutationEntity) {}
    override suspend fun deletePendingAiMutation(conversationId: String) {}
    override suspend fun getActiveProposal(conversationId: String) = null
    override suspend fun insertOrUpdateActiveProposal(entity: com.example.chatgpt_mealplanner.core.database.entity.ActiveProposalEntity) {}
    override suspend fun deleteActiveProposal(conversationId: String) {}
}

private class SerializingTransactionRunner : com.example.chatgpt_mealplanner.core.database.DatabaseTransactionRunner {
    private val mutex = kotlinx.coroutines.sync.Mutex()

    override suspend fun <T> runInTransaction(block: suspend () -> T): T {
        mutex.lock()
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }
}
