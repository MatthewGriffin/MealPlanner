package com.example.chatgpt_mealplanner.core.ai.packet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatgpt_mealplanner.core.receipt.MlKitReceiptOcrService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/** The phone's own text reader on a drawn packet, so the real ML Kit model and image decoding are exercised. */
@RunWith(AndroidJUnit4::class)
class PacketPhotoReaderTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val reader = PacketPhotoReader(context, MlKitReceiptOcrService(context))

    private val packetLines = listOf(
        "FAJITA KIT - COOKING INSTRUCTIONS",
        "Contains 8 tortillas, 1 sachet of seasoning and salsa.",
        "You will need 400 g chicken strips, 2 peppers, 1 onion",
        "and 2 tbsp oil.",
        "1. Heat the oil in a large pan, add the chicken and",
        "fry for 5 minutes.",
        "2. Add the vegetables and cook for 3 minutes.",
        "3. Stir in the seasoning with 3 tbsp water and simmer",
        "for 2 minutes. Serve with the warm tortillas and salsa."
    )

    private fun image(lines: List<String>, textSize: Float, background: Int = Color.WHITE): Uri {
        val bitmap = Bitmap.createBitmap(1400, 1000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; this.textSize = textSize }
        lines.forEachIndexed { i, line -> canvas.drawText(line, 40f, 80f + i * (textSize * 1.5f), paint) }
        val file = File.createTempFile("packet_test_", ".jpg", context.cacheDir)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        return Uri.fromFile(file)
    }

    @Test
    fun aClearPacketIsReadAsTextAndThePictureIsNotSent() = runBlocking {
        val photo = reader.read(image(packetLines, textSize = 44f))!!

        android.util.Log.i("PACKET_TEST", "OCR text: ${photo.ocrText.replace('\n', ' ')}")
        assertTrue("clear text should be enough: ${photo.ocrText}", PacketTextQuality.isUsable(photo.ocrText))
        assertNull(photo.imageJpegBase64)
        assertTrue(photo.ocrText.contains("seasoning", ignoreCase = true))
    }

    @Test
    fun aPacketTooSmallToReadFallsBackToSendingThePicture() = runBlocking {
        val photo = reader.read(image(packetLines, textSize = 7f))!!

        android.util.Log.i("PACKET_TEST", "tiny text OCR: ${photo.ocrText.replace('\n', ' ')} image=${photo.imageJpegBase64?.length}")
        // Garbled words are not trusted, however many there are: the picture goes to Claude instead.
        assertNotNull(photo.imageJpegBase64)
        // A real JPEG: base64 of the file header.
        assertTrue(photo.imageJpegBase64!!.startsWith("/9j/"))
        assertEquals(false, photo.imageJpegBase64!!.contains('\n'))
    }

    @Test
    fun aPhotoThatCannotBeOpenedGivesNothing() = runBlocking {
        assertNull(reader.read(Uri.fromFile(File(context.cacheDir, "does_not_exist.jpg"))))
    }
}
