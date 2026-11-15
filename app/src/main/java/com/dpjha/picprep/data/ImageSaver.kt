package com.dpjha.picprep.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.dpjha.picprep.image.OutputFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SavedImage(val uri: Uri, val displayName: String)

class ImageSaver(private val context: Context) {
    suspend fun save(bytes: ByteArray, format: OutputFormat): SavedImage = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty()) { "There is no prepared image data to save." }
        val name = "PicPrep_${System.currentTimeMillis()}.${format.extension}"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, format.mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PicPrep")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Android couldn't create the output image.")
        try {
            context.contentResolver.openOutputStream(uri, "w")?.use { stream ->
                stream.write(bytes)
                stream.flush()
            } ?: error("Android couldn't write the prepared image.")
            if (Build.VERSION.SDK_INT >= 29) {
                val updated = context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null
                )
                check(updated > 0) { "Android couldn't finish saving the prepared image." }
            }
            SavedImage(uri, name)
        } catch (failure: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw failure
        }
    }
}
