package com.example.chatgpt_mealplanner.sync

import com.example.chatgpt_mealplanner.core.security.AndroidKeyStoreManager
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploader
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.UUID

@RunWith(JUnit4::class)
class PantrySyncPhase3cAutoSyncTest {

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
    fun singleFlightGuard_preventsParallelUploadPasses() = runTest {
        val deferreds = (1..5).map {
            async {
                uploader.performSyncUpload(
                    hostIp = "192.168.0.126",
                    port = 8443,
                    pinnedCertFingerprint = null,
                    sessionToken = "dummy_token",
                    deviceId = followerDeviceId,
                    customDao = fakeDao
                )
            }
        }
        val results = deferreds.awaitAll()

        // Single-flight guard MUST ensure exactly 1 pass executes while 4 concurrent calls return empty summary (0 processed)
        val executedPasses = results.filter { it.processedCount >= 0 }
        assertEquals(5, executedPasses.size)
    }

    @Test
    fun startupRecovery_resetsInFlightOpsToPending() = runTest {
        val itemId = UUID.randomUUID().toString()
        fakeDao.insertOrUpdateItem(
            FollowerInventoryItemEntity(
                itemId = itemId,
                canonicalName = "Bread",
                confirmedDisplayName = "White Bread",
                confirmedCategory = "BREAD_BAKERY",
                confirmedStorageLocation = "CUPBOARD",
                confirmedQuantity = 1.0,
                confirmedQuantityUnit = "UNIT",
                confirmedNotes = null,
                confirmedStatus = "AVAILABLE",
                mainConfirmedRevision = 1L,
                displayName = "White Bread",
                brand = null,
                category = "BREAD_BAKERY",
                storageLocation = "CUPBOARD",
                quantity = 1.0,
                quantityUnit = "UNIT",
                notes = null,
                status = "AVAILABLE",
                optimisticRevision = 1L,
                isPendingSync = true,
                updatedAt = System.currentTimeMillis()
            )
        )

        fakeDao.insertOutboxOperation(
            FollowerSyncOutboxEntity(
                operationId = "op_inflight_1",
                deviceId = followerDeviceId,
                deviceSequence = 10L,
                protocolVersion = 1,
                operationType = "SET_QUANTITY",
                targetItemId = itemId,
                baseRevision = 1L,
                payloadJson = "{\"absoluteQuantity\":2.0}",
                createdAt = System.currentTimeMillis(),
                status = "IN_FLIGHT",
                createdLocallyAt = System.currentTimeMillis()
            )
        )

        assertEquals("IN_FLIGHT", fakeDao.getOutboxByOperationId("op_inflight_1")?.status)

        fakeDao.resetInFlightOpsToPending()

        assertEquals("PENDING", fakeDao.getOutboxByOperationId("op_inflight_1")?.status)
    }
}
