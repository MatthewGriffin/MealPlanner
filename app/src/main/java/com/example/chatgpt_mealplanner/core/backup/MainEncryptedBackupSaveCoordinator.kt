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
    private val snapshotBuilder: MainBackupSnapshotBuilder,
    private val encryptionCodec: MainBackupEncryptionCodec,
    private val safWriter: MainEncryptedBackupSafWriter,
    private val fullBackupManager: FullBackupManager? = null
) {

    open suspend fun executeMainBackupSave(
        appContext: Context?,
        destinationUri: Uri?,
        passphrase: CharArray,
        currentTimeMs: Long = System.currentTimeMillis()
    ): SafBackupOutcome {
        // 1. Role Guard: Enforce MAIN role BEFORE snapshot collection or SAF I/O
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

        if (fullBackupManager != null) {
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

        // 4. Collect transactional Main snapshot
        val snapshot = try {
            snapshotBuilder.createMainBackupSnapshot(currentTimeMs)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return SafBackupOutcome.NoDocumentIoAttempted(
                DomainError.TransactionFailed("No document I/O attempted: Failed generating Main household snapshot: ${e.message ?: "Unknown error"}")
            )
        }

        // 5. Encrypt snapshot DTO into PANTRYENC binary container
        val encryptedBytes = try {
            encryptionCodec.encodeEncryptedBackup(snapshot, passphrase, currentTimeMs)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return SafBackupOutcome.NoDocumentIoAttempted(
                DomainError.TransactionFailed("No document I/O attempted: Failed encrypting Main household backup: ${e.message ?: "Unknown error"}")
            )
        }

        // 6. Delegate write, readback, exact-byte check, and decryption verification to SAF Writer
        return safWriter.writeAndVerifyEncryptedBackup(
            appContext = appContext,
            uri = destinationUri,
            encryptedBytes = encryptedBytes,
            passphrase = passphrase,
            encryptionCodec = encryptionCodec
        )
    }
}
