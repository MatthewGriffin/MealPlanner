package com.example.chatgpt_mealplanner.core.backup

import android.content.Context
import android.net.Uri
import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class MainEncryptedBackupSaveCoordinator @Inject constructor(
    private val appRoleResolver: AppRoleResolver,
    private val safWriter: MainEncryptedBackupSafWriter,
    private val fullBackupManager: FullBackupManager
) {

    open suspend fun executeMainBackupSave(
        appContext: Context?,
        destinationUri: Uri?,
        passphrase: CharArray,
        currentTimeMs: Long = System.currentTimeMillis()
    ): SafBackupOutcome {
        // 1. Role Guard: Enforce MAIN role BEFORE creating the backup or any SAF I/O
        val isMainRole = try {
            appRoleResolver.isMain()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }

        if (!isMainRole) {
            return SafBackupOutcome.NoDocumentIoAttempted(
                DomainError.TransactionFailed("No document I/O attempted: Backup creation is available on Main device only.")
            )
        }

        // 2. Validate passphrase
        if (passphrase.isEmpty()) {
            return SafBackupOutcome.NoDocumentIoAttempted(
                DomainError.TransactionFailed("No document I/O attempted: Backup passphrase MUST NOT be empty.")
            )
        }

        // 3. Handle null / cancelled document selection
        if (destinationUri == null) {
            return SafBackupOutcome.NoDocumentIoAttempted(
                DomainError.TransactionFailed("No document I/O attempted: Backup cancelled (no document destination selected).")
            )
        }

        val encryptedBackup = try {
            fullBackupManager.createEncryptedBackup(passphrase, currentTimeMs)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return SafBackupOutcome.NoDocumentIoAttempted(
                DomainError.TransactionFailed("No document I/O attempted: Failed creating the household backup: ${e.message}")
            )
        }
        return safWriter.writeAndVerifyFullBackup(appContext, destinationUri, encryptedBackup) { saved ->
            fullBackupManager.verifyEncryptedBackup(saved, passphrase)
        }
    }
}
