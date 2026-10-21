package com.example.chatgpt_mealplanner.core.receipt

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

data class ReceiptOcrElement(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val centerY: Int
)

data class ReceiptOcrLine(
    val text: String,
    val confidence: Float,
    val lineIndex: Int,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val centerY: Int = 0,
    val elements: List<ReceiptOcrElement> = emptyList()
)

data class ReceiptOcrResult(
    val lines: List<ReceiptOcrLine>,
    val fullText: String
)

interface ReceiptOcrService {
    suspend fun recognizeTextFromUri(imageUri: Uri): ReceiptOcrResult
}

@Singleton
class MlKitReceiptOcrService @Inject constructor(
    @param:ApplicationContext private val context: Context
) : ReceiptOcrService {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognizeTextFromUri(imageUri: Uri): ReceiptOcrResult {
        return try {
            val inputImage = InputImage.fromFilePath(context, imageUri)
            val resultText = recognizer.process(inputImage).await()

            val rawLines = mutableListOf<ReceiptOcrLine>()
            var idx = 0
            for (block in resultText.textBlocks) {
                for (line in block.lines) {
                    val box = line.boundingBox
                    val elemList = line.elements.map { elem ->
                        val eBox = elem.boundingBox
                        ReceiptOcrElement(
                            text = elem.text,
                            left = eBox?.left ?: 0,
                            top = eBox?.top ?: 0,
                            right = eBox?.right ?: 0,
                            bottom = eBox?.bottom ?: 0,
                            centerY = eBox?.centerY() ?: 0
                        )
                    }

                    rawLines.add(
                        ReceiptOcrLine(
                            text = line.text,
                            confidence = line.confidence ?: 0.9f,
                            lineIndex = idx++,
                            left = box?.left ?: 0,
                            top = box?.top ?: 0,
                            right = box?.right ?: 0,
                            bottom = box?.bottom ?: 0,
                            centerY = box?.centerY() ?: 0,
                            elements = elemList
                        )
                    )
                }
            }

            // Globally sort ALL lines across ALL text blocks by centerY (vertical position) then left
            val sortedLines = rawLines.sortedWith(
                compareBy<ReceiptOcrLine> { it.centerY }
                    .thenBy { it.left }
            ).mapIndexed { index, line -> line.copy(lineIndex = index) }

            try {
                val debugLog = buildString {
                    appendLine("=== RECEIPT OCR DIAGNOSTICS DUMP ===")
                    appendLine("Full Text:\n${resultText.text}")
                    appendLine("Total Lines: ${sortedLines.size}")
                    sortedLines.forEachIndexed { i, line ->
                        appendLine("Line $i [confidence=${line.confidence}, elemCount=${line.elements.size}, left=${line.left}, top=${line.top}, right=${line.right}, bottom=${line.bottom}, centerY=${line.centerY}]: \"${line.text}\"")
                        line.elements.forEachIndexed { ei, elem ->
                            appendLine("  Elem $ei [left=${elem.left}, top=${elem.top}, right=${elem.right}, bottom=${elem.bottom}, centerY=${elem.centerY}]: \"${elem.text}\"")
                        }
                    }
                    appendLine("=====================================")
                }
                android.util.Log.d("RECEIPT_OCR_DIAGNOSTICS", debugLog)
            } catch (ignored: Throwable) {}

            ReceiptOcrResult(lines = sortedLines, fullText = resultText.text)
        } catch (e: Exception) {
            ReceiptOcrResult(lines = emptyList(), fullText = "")
        }
    }
}
