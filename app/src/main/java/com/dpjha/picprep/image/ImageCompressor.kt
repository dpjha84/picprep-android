package com.dpjha.picprep.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import android.os.ParcelFileDescriptor
import kotlin.math.max
import kotlin.math.min

enum class OutputFormat(val label: String, val mime: String, val extension: String) {
    JPEG("JPEG", "image/jpeg", "jpg"), PNG("PNG", "image/png", "png"), WEBP("WebP", "image/webp", "webp")
}

data class ProcessedImage(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val format: OutputFormat,
    val quality: Int,
    val originalBytes: Long,
    val originalWidth: Int,
    val originalHeight: Int,
    val targetReached: Boolean = true
)

class ImageCompressor(private val resolver: ContentResolver) {
    suspend fun process(
        uri: Uri,
        format: OutputFormat = OutputFormat.JPEG,
        quality: Int = 85,
        targetBytes: Int? = null,
        width: Int? = null,
        height: Int? = null,
        maxDimension: Int? = null,
        keepAspect: Boolean = true
    ): ProcessedImage = withContext(Dispatchers.IO) {
        require(quality in 10..100) { "Quality must be between 10 and 100." }
        require(targetBytes == null || targetBytes >= 5 * 1024) { "Target size must be at least 5 KB." }
        // Snapshot the provider stream once; Photo Picker/cloud streams may not reopen reliably.
        val sourceBytes = readImageBytes(uri)
        val sourceSize = sourceBytes.size.toLong()
        val original = decodeOriented(sourceBytes, uri)
        val originalW = original.width; val originalH = original.height
        var working = original
        if (width != null || height != null || maxDimension != null) {
            val targetW: Int
            val targetH: Int
            if (maxDimension != null) {
                val scale = min(1f, maxDimension.toFloat() / max(original.width, original.height))
                targetW = max(1, (original.width * scale).toInt()); targetH = max(1, (original.height * scale).toInt())
            } else if (keepAspect) {
                val boxW = width ?: original.width; val boxH = height ?: original.height
                val scale = min(boxW.toFloat() / original.width, boxH.toFloat() / original.height)
                targetW = max(1, (original.width * scale).toInt()); targetH = max(1, (original.height * scale).toInt())
            } else {
                targetW = width ?: original.width; targetH = height ?: original.height
            }
            require(targetW in 1..12000 && targetH in 1..12000) { "Dimensions must be between 1 and 12,000 pixels." }
            if (targetW != original.width || targetH != original.height) working = Bitmap.createScaledBitmap(original, targetW, targetH, true)
        }

        fun encode(bitmap: Bitmap, q: Int): ByteArray {
            // JPEG has no alpha channel. Composite transparent pixels over white instead of black.
            val jpegBitmap = if (format == OutputFormat.JPEG && bitmap.hasAlpha()) {
                Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also { opaque ->
                    val canvas = Canvas(opaque); canvas.drawColor(Color.WHITE); canvas.drawBitmap(bitmap, 0f, 0f, null)
                }
            } else null
            val source = jpegBitmap ?: bitmap
            val out = ByteArrayOutputStream()
            val compressFormat = when (format) {
                OutputFormat.JPEG -> Bitmap.CompressFormat.JPEG
                OutputFormat.PNG -> Bitmap.CompressFormat.PNG
                OutputFormat.WEBP -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            }
            source.compress(compressFormat, if (format == OutputFormat.PNG) 100 else q, out)
            jpegBitmap?.recycle()
            return out.toByteArray()
        }

        var q = quality
        var bytes = encode(working, q)
        var reached = targetBytes == null || bytes.size <= targetBytes
        // Find the highest JPEG/WebP quality that fits. PNG is lossless; do not pretend quality changes its size.
        if (targetBytes != null && format != OutputFormat.PNG && bytes.size > targetBytes) {
            var low = 10; var high = quality; var best: ByteArray? = null; var bestQ = 10
            while (low <= high) {
                val mid = (low + high) / 2; val candidate = encode(working, mid)
                if (candidate.size <= targetBytes) { best = candidate; bestQ = mid; low = mid + 1 }
                else high = mid - 1
            }
            if (best != null) { bytes = best; q = bestQ; reached = true }
            else {
                var candidate = working
                for (iteration in 0 until 8) {
                    val nw = max(1, (candidate.width * 0.82f).toInt())
                    val nh = max(1, (candidate.height * 0.82f).toInt())
                    if (nw == candidate.width && nh == candidate.height) break
                    val next = Bitmap.createScaledBitmap(candidate, nw, nh, true)
                    if (candidate !== original) candidate.recycle()
                    candidate = next
                    var l = 10; var h = quality; var local: ByteArray? = null; var localQ = 10
                    while (l <= h) {
                        val mid = (l + h) / 2; val b = encode(candidate, mid)
                        if (b.size <= targetBytes) { local = b; localQ = mid; l = mid + 1 } else h = mid - 1
                    }
                    if (local != null) {
                        bytes = local; q = localQ; working = candidate; reached = true
                        break
                    }
                    bytes = encode(candidate, 10)
                }
                if (candidate !== working && candidate !== original) working = candidate
            }
        }
        val outputWidth = working.width; val outputHeight = working.height
        if (working !== original) working.recycle()
        original.recycle()
        ProcessedImage(bytes, outputWidth, outputHeight, format, q, sourceSize, originalW, originalH, reached)
    }

    private fun readImageBytes(uri: Uri): ByteArray {
        var lastFailure: Throwable? = null
        try {
            resolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                if (bytes.isNotEmpty()) return bytes
                lastFailure = IOException("The selected image is empty.")
            }
        } catch (t: Throwable) { lastFailure = t }

        // Some providers expose an AssetFileDescriptor even when openInputStream returns null.
        try {
            resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                val bytes = descriptor.createInputStream().use { it.readBytes() }
                if (bytes.isNotEmpty()) return bytes
                lastFailure = IOException("The selected image is empty.")
            }
        } catch (t: Throwable) { lastFailure = t }

        // Final fallback for providers that expose only a raw file descriptor.
        try {
            resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                val bytes = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                if (bytes.isNotEmpty()) return bytes
                lastFailure = IOException("The selected image is empty.")
            }
        } catch (t: Throwable) { lastFailure = t }

        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        val details = "scheme=${uri.scheme ?: "unknown"}, type=${mime ?: "unknown"}"
        throw IOException(
            "PicPrep could not read the selected image ($details). Please choose it again or select a locally stored copy.",
            lastFailure ?: FileNotFoundException("No readable stream or file descriptor was returned.")
        )
    }

    private fun decodeOriented(sourceBytes: ByteArray, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            val mime = runCatching { resolver.getType(uri) }.getOrNull()
            "Unsupported or invalid image${mime?.let { " ($it)" } ?: ""}."
        }
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > 4096) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, options)
            ?: throw IOException("Android could not decode this image. Try another JPEG, PNG or WebP file.")
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(sourceBytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postScale(-1f, 1f); postRotate(90f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { postScale(-1f, 1f); postRotate(270f) }
            }
        }
        return if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            .also { if (it !== decoded) decoded.recycle() }
    }
}
