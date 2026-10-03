package com.example.chatgpt_mealplanner.core.backup

import android.content.Context
import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.DomainResult
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseRecoveryCoordinator @Inject constructor(
    private val context: Context,
    private val backupManager: BackupManager
) {

    suspend fun recoverFromExportFile(jsonContent: String): DomainResult<Unit> {
        // Bootstrap recovery: Validate export JSON content first
        if (jsonContent.isBlank()) {
            return DomainResult.Failure(DomainError.TransactionFailed("Export file is empty."))
        }

        // Attempt restore via BackupManager
        return backupManager.restoreImportJson(jsonContent)
    }

    fun hasExistingDatabase(): Boolean {
        val dbFile = context.getDatabasePath("pantry_planner.db")
        return dbFile.exists() && dbFile.length() > 0
    }
}
