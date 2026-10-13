package com.example.chatgpt_mealplanner.core.backup

import android.content.Context
import android.net.Uri
import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.DomainResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SafBackupOutcome {
    data class VerifiedSuccess(val snapshot: MainHouseholdBackupDto) : SafBackupOutcome
    data class NoDocumentIoAttempted(val cause: DomainError.TransactionFailed) : SafBackupOutcome
    data class DocumentIoAttemptedUnverified(val cause: DomainError.TransactionFailed, val destinationUri: Uri?) : SafBackupOutcome

    fun isSuccess(): Boolean = this is VerifiedSuccess
    fun isFailure(): Boolean = !isSuccess()
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

    open suspend fun writeAndVerifyEncryptedBackup(
        appContext: Context?,
        uri: Uri?,
        encryptedBytes: ByteArray,
        passphrase: CharArray,
        encryptionCodec: MainBackupEncryptionCodec
    ): SafBackupOutcome = withContext(Dispatchers.IO) {
        if (uri == null) {
            return@withContext SafBackupOutcome.NoDocumentIoAttempted(
                DomainError.TransactionFailed("No document I/O attempted: Backup cancelled (no document destination selected).")
            )
        }

        // 1. Write encrypted bytes to SAF output stream
        try {
            val outputStream = streamProvider.openOutputStream(appContext, uri)
                ?: return@withContext SafBackupOutcome.DocumentIoAttemptedUnverified(
                    DomainError.TransactionFailed("SAF Document I/O attempted (unverified): Unable to open selected document for writing."),
                    uri
                )

            outputStream.use { stream ->
                stream.write(encryptedBytes)
                stream.flush()
            }
        } catch (e: Exception) {
            return@withContext SafBackupOutcome.DocumentIoAttemptedUnverified(
                DomainError.TransactionFailed("SAF Document I/O attempted (unverified): Failed writing backup file to document URI: ${e.message ?: "Write failed"}"),
                uri
            )
        }

        // 2. Read back saved file for verification with strict bounded buffer limit
        val savedBytes = try {
            val inputStream = streamProvider.openInputStream(appContext, uri)
                ?: return@withContext SafBackupOutcome.DocumentIoAttemptedUnverified(
                    DomainError.TransactionFailed("SAF Document I/O attempted (unverified): Write completed but saved document could not be opened for readback verification."),
                    uri
                )

            inputStream.use { stream ->
                readBoundedBytes(stream, MAX_ENCRYPTED_CONTAINER_SIZE_BYTES)
            }
        } catch (e: Exception) {
            return@withContext SafBackupOutcome.DocumentIoAttemptedUnverified(
                DomainError.TransactionFailed("SAF Document I/O attempted (unverified): Saved document readback verification failed: ${e.message ?: "Readback failed"}"),
                uri
            )
        }

        // 3. Exact-byte readback comparison
        if (!savedBytes.contentEquals(encryptedBytes)) {
            return@withContext SafBackupOutcome.DocumentIoAttemptedUnverified(
                DomainError.TransactionFailed("SAF Document I/O attempted (unverified): Saved backup readback verification failed: exact byte mismatch on disk."),
                uri
            )
        }

        // 4. Authenticated decryption & PANTRYBK container validation
        val result = encryptionCodec.decodeAndValidateEncryptedBackup(savedBytes, passphrase)
        if (result is DomainResult.Failure) {
            return@withContext SafBackupOutcome.DocumentIoAttemptedUnverified(
                DomainError.TransactionFailed("SAF Document I/O attempted (unverified): Saved backup readback verification failed during decryption: ${(result.error as DomainError.TransactionFailed).cause}"),
                uri
            )
        }

        return@withContext SafBackupOutcome.VerifiedSuccess((result as DomainResult.Success).value)
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
