package com.example.chatgpt_mealplanner.sync

import com.example.chatgpt_mealplanner.core.security.AndroidKeyStoreManager
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploader
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerDao
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerDeviceSequenceEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.UUID

open class FakeFollowerDao : FollowerDao {
    val outboxMap = mutableMapOf<String, FollowerSyncOutboxEntity>()
    val itemsMap = mutableMapOf<String, FollowerInventoryItemEntity>()
    private var seqEntity: FollowerDeviceSequenceEntity? = null
    private var metaEntity: FollowerSyncMetadataEntity? = null

    override suspend fun getSequenceEntity(): FollowerDeviceSequenceEntity? = seqEntity

    override suspend fun updateSequenceEntity(entity: FollowerDeviceSequenceEntity) {
        seqEntity = entity
    }

    override suspend fun getSyncMetadata(): FollowerSyncMetadataEntity? = metaEntity

    override suspend fun updateSyncMetadata(metadata: FollowerSyncMetadataEntity) {
        metaEntity = metadata
    }

    override suspend fun insertOutboxOperation(op: FollowerSyncOutboxEntity) {
        outboxMap[op.operationId] = op
    }

    override suspend fun updateOutboxStatus(
        operationId: String,
        status: String,
        attemptCount: Int,
        lastAttemptAt: Long?,
        lastErrorCode: String?
    ) {
        outboxMap[operationId]?.let {
            outboxMap[operationId] = it.copy(
                status = status,
                attemptCount = attemptCount,
                lastAttemptAt = lastAttemptAt,
                lastErrorCode = lastErrorCode
            )
        }
    }

    override suspend fun reconcileOutboxResult(
        operationId: String,
        status: String,
        canonicalSeq: Long?,
        resultCode: String?,
        resultingRev: Long?,
        conflictId: String?,
        approvalId: String?,
        completedAt: Long?
    ) {
        outboxMap[operationId]?.let {
            outboxMap[operationId] = it.copy(
                status = status,
                mainCanonicalSequence = canonicalSeq,
                mainResultCode = resultCode,
                mainResultingRevision = resultingRev,
                conflictId = conflictId,
                approvalId = approvalId,
                completedAt = completedAt
            )
        }
    }

    override suspend fun resetInFlightOpsToPending() {
        outboxMap.keys.forEach { opId ->
            val op = outboxMap[opId]
            if (op?.status == "IN_FLIGHT") {
                outboxMap[opId] = op.copy(status = "PENDING")
            }
        }
    }

    override suspend fun getPendingOperationsInOrder(): List<FollowerSyncOutboxEntity> {
        return outboxMap.values
            .filter { it.status == "PENDING" || it.status == "IN_FLIGHT" }
            .sortedBy { it.deviceSequence }
    }

    override suspend fun getAllOutboxOps(): List<FollowerSyncOutboxEntity> {
        return outboxMap.values.sortedBy { it.deviceSequence }
    }

    override suspend fun getUnresolvedOutboxOpsForTargetItem(itemId: String): List<FollowerSyncOutboxEntity> {
        return outboxMap.values
            .filter { it.targetItemId == itemId && (it.status == "PENDING" || it.status == "IN_FLIGHT") }
            .sortedBy { it.deviceSequence }
    }

    override suspend fun getOutboxByOperationId(operationId: String): FollowerSyncOutboxEntity? = outboxMap[operationId]

    override suspend fun getItemById(itemId: String): FollowerInventoryItemEntity? = itemsMap[itemId]

    override suspend fun getAllItems(): List<FollowerInventoryItemEntity> = itemsMap.values.toList()

    override suspend fun insertOrUpdateItem(item: FollowerInventoryItemEntity) {
        itemsMap[item.itemId] = item
    }
}

class TestableFollowerSyncEngine(
    private val fakeDao: FakeFollowerDao
) : FollowerLocalSyncEngine(null) {
    override suspend fun <T> runTransaction(block: suspend (FollowerDao) -> T): T {
        return block(fakeDao)
    }
}

@RunWith(JUnit4::class)
class PantrySyncPhase3bFollowerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fakeDao: FakeFollowerDao
    private lateinit var localEngine: TestableFollowerSyncEngine
    private lateinit var uploader: FollowerSyncUploader
    private lateinit var testClient: PantrySyncTestClient

    private val followerDeviceId = UUID.randomUUID().toString()

    @Before
    fun setUp() {
        fakeDao = FakeFollowerDao()
        localEngine = TestableFollowerSyncEngine(fakeDao)
        testClient = PantrySyncTestClient(AndroidKeyStoreManager())
        uploader = FollowerSyncUploader(null, testClient, localEngine)
    }

    @Test
    fun firstDeviceSequence_isOne_andMonotonicallyIncrements() = runTest {
        val outbox1 = localEngine.recordLocalAddItem(
            deviceId = followerDeviceId,
            canonicalName = "Milk",
            displayName = "Whole Milk",
            category = "FRESH_PANTRY",
            storageLocation = "FRIDGE",
            quantity = 2.0,
            quantityUnit = "LITRE",
            barcode = "5000159461122",
            notes = "Fresh lot"
        )

        assertEquals("First deviceSequence MUST BE 1L", 1L, outbox1.deviceSequence)

        val outbox2 = localEngine.recordLocalEditItem(
            deviceId = followerDeviceId,
            targetItemId = outbox1.targetItemId!!,
            displayName = "Organic Whole Milk",
            category = null,
            storageLocation = null,
            notes = null
        )

        assertEquals("Second deviceSequence MUST BE 2L", 2L, outbox2.deviceSequence)
    }

    @Test
    fun atomicLocalMutation_createsLocalItemAndOutboxRow() = runTest {
        val outbox = localEngine.recordLocalAddItem(
            deviceId = followerDeviceId,
            canonicalName = "Eggs",
            displayName = "Large Eggs",
            category = "FRESH_PANTRY",
            storageLocation = "FRIDGE",
            quantity = 12.0,
            quantityUnit = "UNIT",
            barcode = null,
            notes = null
        )

        val localItem = fakeDao.getItemById(outbox.targetItemId!!)

        assertNotNull(localItem)
        assertEquals("Large Eggs", localItem?.displayName)
        assertTrue(localItem?.isPendingSync == true)

        val outboxRow = fakeDao.getOutboxByOperationId(outbox.operationId)
        assertNotNull(outboxRow)
        assertEquals("PENDING", outboxRow?.status)
    }

    @Test
    fun multipleOfflineEdits_preserveOriginalBaseRevision() = runTest {
        val addOutbox = localEngine.recordLocalAddItem(
            deviceId = followerDeviceId,
            canonicalName = "Juice",
            displayName = "Orange Juice",
            category = "FRESH_PANTRY",
            storageLocation = "FRIDGE",
            quantity = 1.0,
            quantityUnit = "LITRE",
            barcode = null,
            notes = null
        )

        val edit1 = localEngine.recordLocalSetQuantity(
            deviceId = followerDeviceId,
            targetItemId = addOutbox.targetItemId!!,
            absoluteQuantity = 2.0
        )

        val edit2 = localEngine.recordLocalAdjustQuantity(
            deviceId = followerDeviceId,
            targetItemId = addOutbox.targetItemId!!,
            delta = -1.0
        )

        val outbox1 = fakeDao.getOutboxByOperationId(edit1.operationId)
        val outbox2 = fakeDao.getOutboxByOperationId(edit2.operationId)

        assertEquals(1L, outbox1?.baseRevision)
        assertEquals("Second edit MUST preserve original baseRevision = 1L before Main confirmation", 1L, outbox2?.baseRevision)
    }

    @Test
    fun inFlightOps_resetToPendingOnStartupRecovery() = runTest {
        val outbox = localEngine.recordLocalAddItem(
            deviceId = followerDeviceId,
            canonicalName = "Cheese",
            displayName = "Cheddar Cheese",
            category = "FRESH_PANTRY",
            storageLocation = "FRIDGE",
            quantity = 1.0,
            quantityUnit = "UNIT",
            barcode = null,
            notes = null
        )

        fakeDao.updateOutboxStatus(outbox.operationId, "IN_FLIGHT", 1, System.currentTimeMillis(), null)
        assertEquals("IN_FLIGHT", fakeDao.getOutboxByOperationId(outbox.operationId)?.status)

        fakeDao.resetInFlightOpsToPending()
        assertEquals("PENDING", fakeDao.getOutboxByOperationId(outbox.operationId)?.status)
    }

    @Test
    fun recomputeProjection_revertsToConfirmedBase_whenNoPendingOps() = runTest {
        val itemId = UUID.randomUUID().toString()
        val item = FollowerInventoryItemEntity(
            itemId = itemId,
            canonicalName = "Yogurt",
            confirmedDisplayName = "Greek Yogurt",
            confirmedCategory = "FRESH_PANTRY",
            confirmedStorageLocation = "FRIDGE",
            confirmedQuantity = 2.0,
            confirmedQuantityUnit = "UNIT",
            confirmedNotes = null,
            confirmedStatus = "AVAILABLE",
            mainConfirmedRevision = 5L,
            displayName = "Temporary Working Name",
            category = "FRESH_PANTRY",
            storageLocation = "FRIDGE",
            quantity = 99.0,
            quantityUnit = "UNIT",
            notes = null,
            status = "AVAILABLE",
            optimisticRevision = 5L,
            isPendingSync = true,
            updatedAt = System.currentTimeMillis()
        )
        fakeDao.insertOrUpdateItem(item)

        localEngine.recomputeItemWorkingProjection(fakeDao, itemId)

        val recomputed = fakeDao.getItemById(itemId)
        assertEquals("Greek Yogurt", recomputed?.displayName)
        assertEquals(2.0, recomputed?.quantity ?: 0.0, 0.001)
        assertFalse("isPendingSync MUST BE false when no pending ops exist", recomputed?.isPendingSync == true)
    }
}
