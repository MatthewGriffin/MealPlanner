package com.example.chatgpt_mealplanner.core.receipt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MlKitReceiptOcrServiceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val service = MlKitReceiptOcrService(context)

    @Test
    fun readsTextFromImage_withBundledModel() = runBlocking {
        val image = File.createTempFile("ocr_test_", ".png", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(1000, 400, Bitmap.Config.ARGB_8888)
            val paint = Paint().apply {
                color = Color.BLACK
                textSize = 64f
                isAntiAlias = true
            }
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                drawText("MILK 2.00", 80f, 120f, paint)
                drawText("TOTAL 2.00", 80f, 280f, paint)
            }
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

            val result = service.recognizeTextFromUri(Uri.fromFile(image))

            assertTrue("OCR text was: ${result.fullText}", result.fullText.contains("MILK"))
            assertTrue("OCR text was: ${result.fullText}", result.fullText.contains("TOTAL"))
        } finally {
            image.delete()
        }
    }

    @Test
    fun missingImage_throwsInsteadOfReturningAnEmptyResult() = runBlocking {
        val missing = Uri.fromFile(File(context.cacheDir, "does_not_exist.jpg"))

        try {
            service.recognizeTextFromUri(missing)
            fail("Expected ReceiptOcrException")
        } catch (_: ReceiptOcrException) {
            // Expected: the user is told the image failed, not to take a clearer photo.
        }
    }
}
