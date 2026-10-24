package lofit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import java.io.File
import java.util.UUID
import kotlin.math.max

/** Own the selected picture so moving or deleting the source does not break exercises. */
class ExercisePictures(private val context: Context) {
    private val directory = File(context.filesDir, "exercise-pictures")
    private val thumbnails = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun importPicture(uri: Uri): String {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            if (longest > 1024) decoder.setTargetSize(
                (info.size.width * 1024L / longest).toInt().coerceAtLeast(1),
                (info.size.height * 1024L / longest).toInt().coerceAtLeast(1),
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val file = File(directory.apply { mkdirs() }, "${UUID.randomUUID()}.webp")
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, it)) }
            return file.name
        } catch (error: Exception) {
            file.delete()
            throw error
        } finally {
            bitmap.recycle()
        }
    }

    fun thumbnail(name: String, width: Int, height: Int): Bitmap? {
        val key = "$name:$width:$height"
        thumbnails.get(key)?.let { return it }
        return runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file(name))) { decoder, info, _ ->
                val sample = minOf(info.size.width / width.coerceAtLeast(1), info.size.height / height.coerceAtLeast(1))
                decoder.setTargetSampleSize(sample.coerceAtLeast(1))
            }.also { thumbnails.put(key, it) }
        }.getOrNull()
    }

    fun delete(name: String) {
        file(name).delete()
        thumbnails.evictAll()
    }

    private fun file(name: String): File {
        require(File(name).name == name) { "Invalid picture name" }
        return File(directory, name)
    }
}
