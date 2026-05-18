package com.thejas.visionaireader.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Extracts text from a PDF by rendering each page to a Bitmap with PdfRenderer (built into Android),
 * then running ML Kit OCR on each bitmap. Works offline and handles both scanned and text-based PDFs.
 *
 * Slower than direct text extraction libraries (PdfBox / iText) but uses zero extra dependencies.
 */
class PdfTextExtractor(private val context: Context, private val ocrEngine: OcrEngine) {

    /** Render PDF page count for progress reporting. */
    suspend fun pageCount(uri: Uri): Int = withContext(Dispatchers.IO) {
        val tmp = copyToCache(uri) ?: return@withContext 0
        val pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val n = renderer.pageCount
        renderer.close()
        pfd.close()
        n
    }

    /**
     * Extracts text from every page of the PDF.
     * @param uri the content:// or file:// URI
     * @param onProgress optional progress callback (currentPage, totalPages)
     */
    suspend fun extractText(
        uri: Uri,
        maxPages: Int = 50,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): String = withContext(Dispatchers.IO) {
        val tmp = copyToCache(uri) ?: return@withContext ""
        val pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val total = minOf(renderer.pageCount, maxPages)
        val builder = StringBuilder()

        try {
            for (i in 0 until total) {
                val page = renderer.openPage(i)
                // Render at 2x density for better OCR accuracy without blowing up memory
                val scale = 2
                val width = page.width * scale
                val height = page.height * scale
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                val pageText = try { ocrEngine.extractText(bitmap) } catch (e: Exception) { "" }
                bitmap.recycle()

                if (pageText.isNotBlank()) {
                    if (builder.isNotEmpty()) builder.append("\n\n")
                    builder.append(pageText)
                }
                onProgress(i + 1, total)
            }
        } finally {
            renderer.close()
            pfd.close()
            try { tmp.delete() } catch (_: Exception) {}
        }

        Log.d("VisionAgent", "PDF extracted ${builder.length} chars from $total pages")
        builder.toString()
    }

    /** Copy any URI (content://, file://) to a local cache file PdfRenderer can open with ParcelFileDescriptor. */
    private fun copyToCache(uri: Uri): File? {
        return try {
            val out = File(context.cacheDir, "incoming_${System.currentTimeMillis()}.pdf")
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return null
                FileOutputStream(out).use { os ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) os.write(buf, 0, n)
                }
            }
            out
        } catch (e: Exception) {
            Log.e("VisionAgent", "Failed to copy PDF URI to cache", e)
            null
        }
    }
}
