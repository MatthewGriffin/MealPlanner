package com.example.chatgpt_mealplanner.core.backup

import android.content.Context
import android.net.Uri
import com.example.chatgpt_mealplanner.core.model.DomainError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SafBackupOutcome {
    data object VerifiedFullBackup : SafBackupOutcome
    data class NoDocumentIoAttempted(val cause: DomainError.TransactionFailed) : SafBackupOutcome
    data class DocumentIoAttemptedUnverified(val cause: DomainError.TransactionFailed, val destinationUri: Uri?) : SafBackupOutcome
}

interface SafDocumentStreamProvider {
    fun openOutputStream(appContext: Context?, uri: Uri?): OutputStream?
    fun openInputStream(appContext: Context?, uri: Uri?): InputStream?
}

@Singleton
open class DefaultSafDocumentStreamProvider @Inject constructor() : SafDocumentStreamProvider {
    override fun openOutputStream(appContext: Context?, uri: Uri?): OutputStream? {
        if (appContext == null || uri == null) return null
        return appContext.contentResolver.openOutputStream(uri, "w")
    }

    override fun openInputStream(appContext: Context?, uri: Uri?): InputStream? {
        if (appContext == null || uri == null) return null
        return appContext.contentResolver.openInputStream(uri)
    }
}

@Singleton
open class MainEncryptedBackupSafWriter @Inject constructor(
    private val streamProvider: SafDocumentStreamProvider
) {

    /** Writes a full backup and confirms the saved file can be read back and decrypted. */
    open suspend fun writeAndVerifyFullBackup(
        appContext: Context?,
        uri: Uri,
        encryptedBytes: ByteArray,
        verify: (ByteArray) -> Boolean
    ): SafBackupOutcome = withContext(Dispatchers.IO) {
        fun unverified(detail: String) = SafBackupOutcome.DocumentIoAttemptedUnverified(
            DomainError.TransactionFailed("SAF Document I/O attempted (unverified): $detail"),
            uri
        )
        try {
            val output = streamProvider.openOutputStream(appContext, uri)
                ?: return@withContext unverified("Unable to open selected document for writing")
            output.use { it.write(encryptedBytes); it.flush() }
            val input = streamProvider.openInputStream(appContext, uri)
                ?: return@withContext unverified("Write completed but the saved document couldn't be read back")
            val saved = input.use { readBoundedBytes(it, MAX_ENCRYPTED_CONTAINER_SIZE_BYTES) }
            if (!saved.contentEquals(encryptedBytes)) return@withContext unverified("Saved backup didn't match what was written")
            if (!verify(saved)) return@withContext unverified("Saved backup failed verification")
            SafBackupOutcome.VerifiedFullBackup
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            unverified("Failed writing or verifying the backup document")
        }
    }

    private fun readBoundedBytes(stream: InputStream, maxBytes: Int): ByteArray {
        val buffer = ByteArray(8192)
        val output = ByteArrayOutputStream()
        var totalRead = 0

        while (true) {
            val read = stream.read(buffer)
            if (read == -1) break
            totalRead += read
            if (totalRead > maxBytes) {
                throw java.io.IOException("Document readback size $totalRead bytes exceeds maximum allowed limit $maxBytes bytes.")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
