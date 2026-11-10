package com.example.chatgpt_mealplanner.core.ai.packet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import com.example.chatgpt_mealplanner.core.receipt.ReceiptOcrService
import com.example.chatgpt_mealplanner.core.util.rethrowIfCancelled
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Turns a packet photo into text on the phone, and into a small picture only when the text isn't enough. */
interface PacketPhotoSource {
    /** Null if the photo couldn't be opened at all. */
    suspend fun read(uri: Uri): PacketPhoto?
}

@Singleton
class PacketPhotoReader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val ocr: ReceiptOcrService
) : PacketPhotoSource {

    override suspend fun read(uri: Uri): PacketPhoto? = withContext(Dispatchers.IO) {
        val result = try {
            ocr.recognizeTextFromUri(uri)
        } catch (e: Exception) {
            rethrowIfCancelled()
            null
        }
        val text = result?.fullText.orEmpty()
        val confidences = result?.lines?.map { it.confidence }.orEmpty()
        val usable = PacketTextQuality.isUsable(
            text,
            averageConfidence = confidences.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            lowestConfidence = confidences.minOrNull()
        )
        if (usable) return@withContext PacketPhoto(ocrText = text, imageJpegBase64 = null)
        val picture = try {
            jpegFor(uri)
        } catch (e: Exception) {
            rethrowIfCancelled()
            null
        }
        // Neither the text nor the picture could be had: there is nothing to send.
        if (picture == null && text.isBlank()) return@withContext null
        PacketPhoto(ocrText = text, imageJpegBase64 = picture)
    }

    /** The photo scaled to a size Claude reads well and that stays small, with the camera's rotation applied. */
    private fun jpegFor(uri: Uri): String {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > MAX_EDGE_PX) {
                val scale = MAX_EDGE_PX.toFloat() / longest
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private companion object {
        const val MAX_EDGE_PX = 1568
        const val JPEG_QUALITY = 85
    }
}
