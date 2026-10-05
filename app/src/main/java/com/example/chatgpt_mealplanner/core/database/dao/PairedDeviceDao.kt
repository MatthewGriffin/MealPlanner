package com.example.chatgpt_mealplanner.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.chatgpt_mealplanner.core.database.entity.PairedDeviceEntity

@Dao
interface PairedDeviceDao {
    @Query("SELECT * FROM paired_devices WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getDeviceById(deviceId: String): PairedDeviceEntity?

    @Query("SELECT * FROM paired_devices ORDER BY pairedAt DESC")
    suspend fun getAllDevices(): List<PairedDeviceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateDevice(device: PairedDeviceEntity)

    @Query("UPDATE paired_devices SET status = :status WHERE deviceId = :deviceId")
    suspend fun updateDeviceStatus(deviceId: String, status: String)

    @Query("UPDATE paired_devices SET status = :newStatus WHERE deviceId = :deviceId AND status = :expectedCurrentStatus")
    suspend fun updateDeviceStatusConditional(deviceId: String, newStatus: String, expectedCurrentStatus: String): Int

    @Query("UPDATE paired_devices SET status = 'REVOKED' WHERE deviceId = :deviceId AND status = 'ACTIVE'")
    suspend fun revokeDeviceIfActive(deviceId: String): Int
}
