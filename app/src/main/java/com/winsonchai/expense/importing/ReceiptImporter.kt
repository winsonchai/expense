package com.winsonchai.expense.importing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.roundToInt

const val MAX_RECEIPT_BYTES = 20L * 1024 * 1024
const val MAX_TEXT_CHARS = 100_000

data class ImportedReceipt(val path: String?, val mime: String, val hash: String, val text: String)

class ReceiptImporter(private val context: Context) {
    /** Copy while the calling intent's read grant is valid. Never trust the filename or claimed size. */
    suspend fun copy(uri: Uri): ImportedReceipt = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "只接受系统分享或文件选择器授权的文件。" }
        val directory = File(context.filesDir, "receipts").apply { mkdirs() }
        val temporary = File(directory, "${UUID.randomUUID()}.tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_RECEIPT_BYTES) { "收据超过 20 MiB，请选择较小文件。" }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                    require(total > 0) { "文件为空。" }
                }
            } ?: error("无法读取文件，请重新分享以授予读取权限。")
            val header = temporary.inputStream().use { input -> ByteArray(12).also { input.read(it) } }
            val pdf = String(header.copyOfRange(0, 5), Charsets.US_ASCII) == "%PDF-"
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (!pdf) BitmapFactory.decodeFile(temporary.path, options)
            require(pdf || (options.outWidth > 0 && options.outHeight > 0)) { "不支持该格式或图片已损坏；请选择图片或 PDF。" }
            val destination = File(directory, "${UUID.randomUUID()}.${if (pdf) "pdf" else "img"}")
            check(temporary.renameTo(destination)) { "无法保存收据，请检查手机储存空间。" }
            ImportedReceipt(destination.path, if (pdf) "application/pdf" else "image/*", digest.digest().toHex(), "")
        } finally { temporary.delete() }
    }

    fun text(value: String): ImportedReceipt {
        require(value.length <= MAX_TEXT_CHARS) { "分享文字过长，请分享单份收据。" }
        require(value.isNotBlank()) { "没有收到交易文字。" }
        return ImportedReceipt(null, "text/plain", MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).toHex(), value)
    }

    suspend fun recognize(receipt: ImportedReceipt): String = withContext(Dispatchers.IO) {
        val file = receipt.path?.let(::File) ?: return@withContext receipt.text
        val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val chinese = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        suspend fun read(bitmap: Bitmap): String {
            try {
                val input = InputImage.fromBitmap(bitmap, 0)
                val a = latin.process(input).await().text
                val b = chinese.process(input).await().text
                return listOf(a, b).filter { it.isNotBlank() }.distinct().joinToString("\n")
            } finally { bitmap.recycle() }
        }
        try {
            val result = if (receipt.mime == "application/pdf") {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        require(renderer.pageCount in 1..5) { "PDF 仅支持 1–5 页，请分开分享。" }
                        val pages = mutableListOf<String>()
                        for (index in 0 until renderer.pageCount) {
                            val bitmap = renderer.openPage(index).use(::renderPage)
                            pages += read(bitmap)
                        }
                        pages.joinToString("\n\n")
                    }
                }
            } else read(decodeImage(file))
            result.take(MAX_TEXT_CHARS)
        } finally { latin.close(); chinese.close() }
    }

    suspend fun preview(path: String, mime: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            if (mime == "application/pdf") {
                ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { pdf -> pdf.openPage(0).use(::renderPage) }
                }
            } else decodeImage(File(path))
        }.getOrNull()
    }

    private fun renderPage(page: PdfRenderer.Page): Bitmap {
        val scale = minOf(1600f / maxOf(page.width, page.height), 2f)
        return Bitmap.createBitmap(maxOf(1, (page.width * scale).roundToInt()), maxOf(1, (page.height * scale).roundToInt()), Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.WHITE)
            page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    private fun decodeImage(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法解码图片。" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("图片已损坏。")
        val orientation = runCatching { ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
            }
        }
        if (matrix.isIdentity) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
