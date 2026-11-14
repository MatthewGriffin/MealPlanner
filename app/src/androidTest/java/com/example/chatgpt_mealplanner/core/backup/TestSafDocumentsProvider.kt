package com.example.chatgpt_mealplanner.core.backup

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class TestSafDocumentsProvider : DocumentsProvider() {

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val flags = DocumentsContract.Root.FLAG_SUPPORTS_CREATE or DocumentsContract.Root.FLAG_SUPPORTS_RECENTS
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        cursor.newRow()
            .add(DocumentsContract.Root.COLUMN_ROOT_ID, "test_root")
            .add(DocumentsContract.Root.COLUMN_FLAGS, flags)
            .add(DocumentsContract.Root.COLUMN_TITLE, "Test Backups")
            .add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "doc_root")
            .add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val file = getFileForDocId(documentId)
        cursor.newRow()
            .add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
            .add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.name)
            .add(DocumentsContract.Document.COLUMN_SIZE, if (file.exists()) file.length() else 0L)
            .add(DocumentsContract.Document.COLUMN_MIME_TYPE, "application/octet-stream")
            .add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE)
        return cursor
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        return MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        // Increment operation counters to PROVE DocumentsProvider.openDocument execution!
        when {
            mode.contains("w") -> writeOpenCount.incrementAndGet()
            mode.contains("r") -> readOpenCount.incrementAndGet()
        }
        totalOpenDocumentCount.incrementAndGet()

        val file = getFileForDocId(documentId)
        val accessMode = when (mode) {
            "w", "wt" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            else -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
        }
        return ParcelFileDescriptor.open(file, accessMode)
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val file = File(context!!.cacheDir, displayName)
        if (!file.exists()) file.createNewFile()
        return file.name
    }

    private fun getFileForDocId(documentId: String): File {
        val fileName = if (documentId == "doc_root") "test_doc_root.pantrybak" else documentId
        val file = File(context!!.cacheDir, fileName)
        if (!file.exists()) file.createNewFile()
        return file
    }

    companion object {
        const val AUTHORITY = "app.pantrypal.mealplanner.test.documents"

        val writeOpenCount = AtomicInteger(0)
        val readOpenCount = AtomicInteger(0)
        val totalOpenDocumentCount = AtomicInteger(0)

        fun resetCounters() {
            writeOpenCount.set(0)
            readOpenCount.set(0)
            totalOpenDocumentCount.set(0)
        }

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_MIME_TYPES
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS
        )
    }
}
