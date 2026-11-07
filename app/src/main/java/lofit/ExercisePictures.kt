package lofit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** Own the selected picture so moving or deleting the source does not break exercises. */
class ExercisePictures(private val context: Context) {
    private val directory = File(context.filesDir, "exercise-pictures")
    private val drawableIds = mutableMapOf<String, Int>()
    private val thumbnails =
        object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }

    fun bundledResource(exercise: Exercise, gender: BiologicalGender): Int? {
        val name =
            exercise.imageResource?.let(context.resources::getResourceEntryName)
                ?: exercise.imageName
                ?: return null
        fun resource(name: String) =
            drawableIds
                .getOrPut(name) {
                    context.resources.getIdentifier(name, "drawable", context.packageName)
                }
                .takeIf { it != 0 }
        return if (gender == BiologicalGender.FEMALE) resource("${name}_female") ?: resource(name)
        else resource(name)
    }

    fun importPicture(uri: Uri): String =
        importPicture(ImageDecoder.createSource(context.contentResolver, uri))

    fun importPicture(bytes: ByteArray): String =
        importPicture(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))

    private fun importPicture(source: ImageDecoder.Source): String {
        val bitmap =
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > 1024)
                    decoder.setTargetSize(
                        (info.size.width * 1024L / longest).toInt().coerceAtLeast(1),
                        (info.size.height * 1024L / longest).toInt().coerceAtLeast(1),
                    )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        val file = File(directory.apply { mkdirs() }, "${UUID.randomUUID()}.webp")
        try {
            file.outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, it))
            }
            return file.name
        } catch (error: Exception) {
            file.delete()
            throw error
        } finally {
            bitmap.recycle()
        }
    }

    // LEGACY: Synchronous decoding remains available to storage clients; views use the worker.
    fun thumbnail(name: String, width: Int, height: Int): Bitmap? =
        thumbnail(name, null, width, height)

    internal fun cachedThumbnail(name: String?, resource: Int?, width: Int, height: Int): Bitmap? =
        thumbnails.get("$name:$resource:$width:$height")

    internal fun thumbnail(name: String?, resource: Int?, width: Int, height: Int): Bitmap? {
        val key = "$name:$resource:$width:$height"
        thumbnails.get(key)?.let {
            return it
        }
        fun decode(source: ImageDecoder.Source): Bitmap =
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val scale =
                    minOf(
                        1.0,
                        max(
                            width.coerceAtLeast(1).toDouble() / info.size.width,
                            height.coerceAtLeast(1).toDouble() / info.size.height,
                        ),
                    )
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1),
                )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        val bitmap =
            name?.let { runCatching { decode(ImageDecoder.createSource(file(it))) }.getOrNull() }
                ?: resource?.let {
                    runCatching { decode(ImageDecoder.createSource(context.resources, it)) }
                        .getOrNull()
                }
        return bitmap?.also { thumbnails.put(key, it) }
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
