package com.dpjha.picprep.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.dpjha.picprep.image.OutputFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ImageSaver(private val context: Context) {
    suspend fun save(bytes: ByteArray, format: OutputFormat): String = withContext(Dispatchers.IO) {
        val name = "PicPrep_${System.currentTimeMillis()}.${format.extension}"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, format.mime)
            if (Build.VERSION.SDK_INT >= 29) { put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PicPrep"); put(MediaStore.Images.Media.IS_PENDING, 1) }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Could not create the output image.")
        try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Could not write the output image.")
            if (Build.VERSION.SDK_INT >= 29) context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            name
        } catch (t: Throwable) { context.contentResolver.delete(uri, null, null); throw t }
    }
}
