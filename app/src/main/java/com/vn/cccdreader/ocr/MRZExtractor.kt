package com.vn.cccdreader.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.vn.cccdreader.nfc.MRZInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object MRZExtractor {

    suspend fun extractFromUri(context: Context, uri: Uri): MRZInfo? {
        val image = try {
            InputImage.fromFilePath(context, uri)
        } catch (e: Exception) {
            return null
        }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val rawText = suspendCancellableCoroutine<String?> { cont ->
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it.text) }
                .addOnFailureListener { cont.resume(null) }
            cont.invokeOnCancellation { recognizer.close() }
        } ?: return null
        return parseMrzFromText(rawText)
    }

    /**
     * Attempts to find and parse MRZ lines from raw OCR text.
     * Supports TD1 (3×30, Vietnamese CCCD) and TD3 (2×44, passport).
     */
    fun parseMrzFromText(rawText: String): MRZInfo? {
        val lines = rawText.lines()
            .map { normalizeLine(it) }
            .filter { line -> line.length >= 28 && line.all { it.isLetterOrDigit() || it == '<' } }

        // TD1: 3 consecutive lines of ~30 chars (Vietnamese CCCD)
        for (i in 0..lines.size - 3) {
            val l1 = lines[i].padEnd(30, '<').take(30)
            val l2 = lines[i + 1].padEnd(30, '<').take(30)
            val l3 = lines[i + 2].padEnd(30, '<').take(30)
            MRZInfo.fromTD1(l1, l2, l3)?.takeIf { it.isValid() }?.let { return it }
        }

        // TD3: 2 consecutive lines of ~44 chars (passport)
        val longLines = lines.filter { it.length >= 42 }
        for (i in 0..longLines.size - 2) {
            val l1 = longLines[i].padEnd(44, '<').take(44)
            val l2 = longLines[i + 1].padEnd(44, '<').take(44)
            MRZInfo.fromTD3(l1, l2)?.takeIf { it.isValid() }?.let { return it }
        }

        return null
    }

    private fun normalizeLine(line: String): String =
        line.uppercase().trim()
            .replace(" ", "")   // OCR sometimes inserts spaces between chars
            .replace("«", "<")  // OCR mistake for '<'
            .replace("»", "<")
            .replace("-", "<")  // OCR commonly reads '<' as '-'
            .replace("_", "<")  // OCR commonly reads '<' as '_'
}
