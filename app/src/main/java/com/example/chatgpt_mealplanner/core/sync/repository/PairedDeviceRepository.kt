package com.example.chatgpt_mealplanner.core.sync.repository

import com.example.chatgpt_mealplanner.core.database.dao.PairedDeviceDao
import com.example.chatgpt_mealplanner.core.database.entity.PairedDeviceEntity
import com.example.chatgpt_mealplanner.core.database.entity.PairedDeviceStatus
import javax.inject.Inject
import javax.inject.Singleton

sealed interface RevocationResult {
    data class Success(val device: PairedDeviceEntity) : RevocationResult
    data class DeviceNotFound(val deviceId: String) : RevocationResult
    data class AlreadyRevoked(val device: PairedDeviceEntity) : RevocationResult
    data class UnknownStatusFailure(val device: PairedDeviceEntity, val status: String) : RevocationResult
}

@Singleton
open class PairedDeviceRepository @Inject constructor(
    private val pairedDeviceDao: PairedDeviceDao,
    private val followerAiKeys: com.example.chatgpt_mealplanner.core.ai.provider.FollowerAiKeys? = null
) {
    open suspend fun getAllDevices(): List<PairedDeviceEntity> {
        val devices = pairedDeviceDao.getAllDevices()
        // Sort deterministically: ACTIVE devices first, then pairedAt desc, then deviceName asc
        return devices.sortedWith(
            compareBy<PairedDeviceEntity> { if (it.status == PairedDeviceStatus.ACTIVE) 0 else 1 }
                .thenByDescending { it.pairedAt }
                .thenBy { it.deviceName }
        )
    }

    open suspend fun revokeDevice(deviceId: String): RevocationResult {
        val result = revokeInStore(deviceId)
        // The follower's AI key is switched off too (or queued, if the Worker cannot be reached right now).
        val keys = followerAiKeys
        if (keys != null && (result is RevocationResult.Success || result is RevocationResult.AlreadyRevoked)) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { keys.revoke(deviceId) } }
        }
        return result
    }

    private suspend fun revokeInStore(deviceId: String): RevocationResult {
        val affected = pairedDeviceDao.revokeDeviceIfActive(deviceId)
        if (affected == 1) {
            val updated = pairedDeviceDao.getDeviceById(deviceId)
            return if (updated != null) {
                RevocationResult.Success(updated)
            } else {
                RevocationResult.DeviceNotFound(deviceId)
            }
        }

        // affected == 0: inspect existing row to differentiate cause
        val existing = pairedDeviceDao.getDeviceById(deviceId)
            ?: return RevocationResult.DeviceNotFound(deviceId)

        return when (existing.status) {
            PairedDeviceStatus.REVOKED -> RevocationResult.AlreadyRevoked(existing)
            PairedDeviceStatus.ACTIVE -> {
                // Retry conditional update
                val retryAffected = pairedDeviceDao.revokeDeviceIfActive(deviceId)
                if (retryAffected == 1) {
                    val updated = pairedDeviceDao.getDeviceById(deviceId) ?: existing.copy(status = PairedDeviceStatus.REVOKED)
                    RevocationResult.Success(updated)
                } else {
                    RevocationResult.AlreadyRevoked(existing)
                }
            }
            else -> RevocationResult.UnknownStatusFailure(existing, existing.status)
        }
    }
}
