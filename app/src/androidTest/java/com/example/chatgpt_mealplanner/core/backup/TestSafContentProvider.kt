package com.example.chatgpt_mealplanner.core.backup

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

class TestSafContentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection ?: arrayOf("_id", "_display_name", "_size"))
        val file = getFileForUri(uri)
        cursor.newRow()
            .add("_id", 1)
            .add("_display_name", file.name)
            .add("_size", if (file.exists()) file.length() else 0L)
        return cursor
    }

    override fun getType(uri: Uri): String = "application/octet-stream"

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val file = getFileForUri(uri)
        if (!file.exists()) file.createNewFile()
        return uri
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val file = getFileForUri(uri)
        return if (file.exists() && file.delete()) 1 else 0
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = getFileForUri(uri)
        val accessMode = when (mode) {
            "w", "wt" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            else -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
        }
        return ParcelFileDescriptor.open(file, accessMode)
    }

    private fun getFileForUri(uri: Uri): File {
        val fileName = uri.lastPathSegment ?: "test_saf_document.pantrybak"
        val file = File(context!!.cacheDir, fileName)
        if (!file.exists()) file.createNewFile()
        return file
    }

    companion object {
        const val AUTHORITY = "app.pantrypal.mealplanner.test.saf"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/test_saf_document.pantrybak")
    }
}
