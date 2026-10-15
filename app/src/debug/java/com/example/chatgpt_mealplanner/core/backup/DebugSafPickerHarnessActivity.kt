package com.example.chatgpt_mealplanner.core.backup

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.util.concurrent.atomic.AtomicBoolean

class DebugSafPickerHarnessActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val targetUri = intent.getParcelableExtra<Uri>("target_uri")
        if (targetUri != null) {
            val resultIntent = Intent().apply {
                data = targetUri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            setResult(RESULT_OK, resultIntent)
            capturedContentUri = targetUri
            activityLaunched.set(true)
        } else {
            setResult(RESULT_CANCELED)
        }
        finish()
    }

    companion object {
        val activityLaunched = AtomicBoolean(false)
        var capturedContentUri: Uri? = null
    }
}
