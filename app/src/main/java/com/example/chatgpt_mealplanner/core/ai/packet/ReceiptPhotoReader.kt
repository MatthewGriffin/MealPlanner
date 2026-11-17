package com.example.chatgpt_mealplanner.core.ai.packet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import com.example.chatgpt_mealplanner.core.util.rethrowIfCancelled
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Gets a receipt photo ready to send to Claude. */
interface ReceiptPhotoSource {
    /** The receipt as one or more overlapping top-to-bottom JPEG parts in base64, or empty if it couldn't be opened. */
    suspend fun prepare(uri: Uri): List<String>
}

@Singleton
class ReceiptPhotoReader @Inject constructor(
    @param:ApplicationContext private val context: Context
) : ReceiptPhotoSource {

    override suspend fun prepare(uri: Uri): List<String> = withContext(Dispatchers.IO) {
        try {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                // A long receipt needs more pixels than one 1568px picture gives its small print, so keep the width and tile it.
                val width = info.size.width
                if (width > TILE_WIDTH_PX) {
                    val scale = TILE_WIDTH_PX.toFloat() / width
                    decoder.setTargetSize(TILE_WIDTH_PX, (info.size.height * scale).toInt().coerceAtLeast(1))
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            tileRanges(bitmap.height, bitmap.width).map { (top, bottom) ->
                val part = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bottom - top)
                val out = ByteArrayOutputStream()
                part.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            rethrowIfCancelled()
            emptyList()
        }
    }

    companion object {
        const val TILE_WIDTH_PX = 1568
        private const val JPEG_QUALITY = 85
        private const val MAX_TILES = 4

        /** Vertical (top, bottom) pixel ranges of overlapping parts of a picture, each about as tall as it is wide ×1.4. */
        fun tileRanges(height: Int, width: Int): List<Pair<Int, Int>> {
            val tall = (width * 1.4).toInt().coerceAtLeast(1)
            if (height <= (tall * 1.25).toInt()) return listOf(0 to height)
            val overlap = tall / 8
            val step = tall - overlap
            val count = minOf(MAX_TILES, 1 + Math.ceil((height - tall).toDouble() / step).toInt())
            val size = if (count == MAX_TILES) maxOf(tall, (height + overlap * (count - 1)) / count) else tall
            val stride = if (count == 1) 0 else (height - size) / (count - 1)
            return (0 until count).map { i ->
                val top = if (i == count - 1) height - size else i * stride
                top.coerceAtLeast(0) to minOf(height, top.coerceAtLeast(0) + size)
            }
        }
    }
}
