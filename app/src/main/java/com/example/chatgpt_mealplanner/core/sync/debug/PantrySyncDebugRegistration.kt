package com.example.chatgpt_mealplanner.core.sync.debug

import com.example.chatgpt_mealplanner.core.database.dao.PairedDeviceDao
import com.example.chatgpt_mealplanner.core.database.entity.PairedDeviceEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class PantrySyncDebugRegistration @Inject constructor(
    private val pairedDeviceDao: PairedDeviceDao
) {
    open suspend fun registerTestDevice(
        deviceId: String,
        deviceName: String,
        devicePublicKeyBase64: String
    ) {
        check(com.example.chatgpt_mealplanner.BuildConfig.DEBUG) {
            "Debug device registration is strictly forbidden in release builds."
        }
        val entity = PairedDeviceEntity(
            deviceId = deviceId,
            deviceName = deviceName,
            devicePublicKey = devicePublicKeyBase64,
            status = "ACTIVE",
            pairedAt = System.currentTimeMillis(),
            lastSyncTimestamp = System.currentTimeMillis()
        )
        pairedDeviceDao.insertOrUpdateDevice(entity)
    }

    open suspend fun revokeDevice(deviceId: String) {
        pairedDeviceDao.updateDeviceStatus(deviceId, "REVOKED")
    }
}
