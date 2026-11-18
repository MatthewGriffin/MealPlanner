package com.example.chatgpt_mealplanner.sync

import com.example.chatgpt_mealplanner.core.database.entity.PairedDeviceEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.runBlocking

class FollowerLastContactTest {
    @Test
    fun touchLastSync_recordsTheTimeAFollowerGotThrough() = runBlocking {
        val dao = FakePairedDeviceDao()
        dao.insertOrUpdateDevice(PairedDeviceEntity("f1", "Dad's phone", "key", "ACTIVE", 1L, 0L))
        dao.touchLastSync("f1", 12345L)
        assertEquals(12345L, dao.getDeviceById("f1")!!.lastSyncTimestamp)
    }
}
