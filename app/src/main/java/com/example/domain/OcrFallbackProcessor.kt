package com.example.domain

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrSpatialBlock(
    val lines: List<String>,
    val top: Int,
    val left: Int,
    val bottom: Int,
    val right: Int
)

class OcrFallbackProcessor {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun extractTextFromBitmap(bitmap: Bitmap): String {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        return processImage(inputImage)
    }

    suspend fun extractTextFromUri(context: Context, uri: Uri): String {
        val inputImage = InputImage.fromFilePath(context, uri)
        return processImage(inputImage)
    }

    private suspend fun processImage(image: InputImage): String {
        return try {
            withTimeout(OCR_PROCESS_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    recognizer.process(image)
                        .addOnSuccessListener { visionText ->
                            if (continuation.isActive) {
                                continuation.resume(formatBlocksInReadingOrder(visionText))
                            }
                        }
                        .addOnFailureListener { exception ->
                            if (continuation.isActive) {
                                continuation.resumeWithException(exception)
                            }
                        }
                }
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    private fun formatBlocksInReadingOrder(visionText: Text): String {
        val blocks = visionText.textBlocks
        if (blocks.isEmpty()) return visionText.text

        val spatialBlocks = blocks.mapNotNull { block ->
            val lines = block.lines
                .sortedWith(
                    compareBy<Text.Line>(
                        { it.boundingBox?.top ?: Int.MAX_VALUE },
                        { it.boundingBox?.left ?: Int.MAX_VALUE }
                    )
                )
                .map { it.text.trim() }
                .filter { it.isNotBlank() }

            if (lines.isEmpty()) return@mapNotNull null
            val box = block.boundingBox
            OcrSpatialBlock(
                lines = lines,
                top = box?.top ?: Int.MAX_VALUE,
                left = box?.left ?: Int.MAX_VALUE,
                bottom = box?.bottom ?: Int.MAX_VALUE,
                right = box?.right ?: Int.MAX_VALUE
            )
        }

        val ordered = orderBlocksInReadingOrder(spatialBlocks)
        return ordered.ifBlank { visionText.text }
    }

    companion object {
        private const val OCR_PROCESS_TIMEOUT_MS = 6000L
        private const val ROW_BAND_PX = 28

        fun orderBlocksInReadingOrder(blocks: List<OcrSpatialBlock>): String {
            if (blocks.isEmpty()) return ""
            return blocks
                .sortedWith(
                    compareBy<OcrSpatialBlock>(
                        { it.top / ROW_BAND_PX },
                        { it.left }
                    )
                )
                .flatMap { block ->
                    block.lines
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                }
                .joinToString("\n")
        }
    }
}
