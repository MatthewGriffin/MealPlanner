package com.example.chatgpt_mealplanner.sync

import com.example.chatgpt_mealplanner.core.database.dao.HouseholdIdentityDao
import com.example.chatgpt_mealplanner.core.database.dao.PairedDeviceDao
import com.example.chatgpt_mealplanner.core.database.entity.HouseholdIdentityEntity
import com.example.chatgpt_mealplanner.core.database.entity.PairedDeviceEntity
import com.example.chatgpt_mealplanner.core.sync.HouseholdIdentityManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.UUID

class FakeHouseholdIdentityDao : HouseholdIdentityDao {
    private var identity: HouseholdIdentityEntity? = null

    override suspend fun getHouseholdIdentity(): HouseholdIdentityEntity? = identity

    override fun getHouseholdIdentitySync(): HouseholdIdentityEntity? = identity

    override suspend fun insertHouseholdIdentity(identity: HouseholdIdentityEntity) {
        this.identity = identity
    }
}

class FakePairedDeviceDao : PairedDeviceDao {
    private val devicesMap = mutableMapOf<String, PairedDeviceEntity>()

    override suspend fun getDeviceById(deviceId: String): PairedDeviceEntity? = devicesMap[deviceId]

    override suspend fun getAllDevices(): List<PairedDeviceEntity> = devicesMap.values.toList()

    override suspend fun insertOrUpdateDevice(device: PairedDeviceEntity) {
        devicesMap[device.deviceId] = device
    }

    override suspend fun touchLastSync(deviceId: String, timestamp: Long) {
        devicesMap[deviceId]?.let { devicesMap[deviceId] = it.copy(lastSyncTimestamp = timestamp) }
    }

    override suspend fun updateDeviceStatus(deviceId: String, status: String) {
        devicesMap[deviceId]?.let {
            devicesMap[deviceId] = it.copy(status = status)
        }
    }

    override suspend fun updateDeviceStatusConditional(deviceId: String, newStatus: String, expectedCurrentStatus: String): Int {
        val dev = devicesMap[deviceId] ?: return 0
        if (dev.status == expectedCurrentStatus) {
            devicesMap[deviceId] = dev.copy(status = newStatus)
            return 1
        }
        return 0
    }

    override suspend fun revokeDeviceIfActive(deviceId: String): Int {
        val dev = devicesMap[deviceId] ?: return 0
        if (dev.status == "ACTIVE") {
            devicesMap[deviceId] = dev.copy(status = "REVOKED")
            return 1
        }
        return 0
    }
}

@RunWith(JUnit4::class)
class HouseholdIdentityAndDeviceTest {

    private lateinit var householdIdentityDao: FakeHouseholdIdentityDao
    private lateinit var pairedDeviceDao: FakePairedDeviceDao
    private lateinit var identityManager: HouseholdIdentityManager

    @Before
    fun setUp() {
        householdIdentityDao = FakeHouseholdIdentityDao()
        pairedDeviceDao = FakePairedDeviceDao()
        identityManager = HouseholdIdentityManager(householdIdentityDao)
    }

    @Test
    fun householdIdentity_createdOnce_andStableOnRepeatedRetrieval() = runTest {
        val first = identityManager.getOrCreateHouseholdIdentity()
        val second = identityManager.getOrCreateHouseholdIdentity()

        assertEquals("Household ID must remain identical on repeated retrieval", first.householdId, second.householdId)
        assertEquals("Main Device ID must remain identical on repeated retrieval", first.mainDeviceId, second.mainDeviceId)

        val parsedUuid = runCatching { UUID.fromString(first.mainDeviceId) }.getOrNull()
        assertNotNull("mainDeviceId must be a valid UUID string", parsedUuid)
    }

    @Test
    fun concurrentCallers_createSingleAuthoritativeHouseholdIdentity() = runTest {
        val jobs = (1..10).map {
            async { identityManager.getOrCreateHouseholdIdentity() }
        }
        val results = jobs.awaitAll()

        val first = results.first()
        results.forEach { identity ->
            assertEquals(first.householdId, identity.householdId)
            assertEquals(first.mainDeviceId, identity.mainDeviceId)
        }
    }

    @Test
    fun pairedDeviceDao_supportsInsertReadAndRevokeState() = runTest {
        val device = PairedDeviceEntity(
            deviceId = "dev_roommate_1",
            deviceName = "Roommate Pixel 8",
            devicePublicKey = "MCowBQYDK2VwAyEA...",
            status = "ACTIVE",
            pairedAt = System.currentTimeMillis(),
            lastSyncTimestamp = System.currentTimeMillis()
        )

        pairedDeviceDao.insertOrUpdateDevice(device)

        val fetched = pairedDeviceDao.getDeviceById("dev_roommate_1")
        assertNotNull(fetched)
        assertEquals("ACTIVE", fetched?.status)

        // Revoke device
        pairedDeviceDao.updateDeviceStatus("dev_roommate_1", "REVOKED")

        val revoked = pairedDeviceDao.getDeviceById("dev_roommate_1")
        assertEquals("REVOKED", revoked?.status)
    }
}
