package lofit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.util.LruCache
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** Own the selected picture so moving or deleting the source does not break exercises. */
class ExercisePictures(private val context: Context, folder: String = "exercise-pictures") {
    private companion object {
        const val MAX_IMAGE_SIZE = 1024
    }

    private val directory = File(context.filesDir, folder)
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
                if (longest > MAX_IMAGE_SIZE)
                    decoder.setTargetSize(
                        (info.size.width * MAX_IMAGE_SIZE.toLong() / longest)
                            .toInt()
                            .coerceAtLeast(1),
                        (info.size.height * MAX_IMAGE_SIZE.toLong() / longest)
                            .toInt()
                            .coerceAtLeast(1),
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

    internal fun fullPicture(name: String) = thumbnail(name, MAX_IMAGE_SIZE, MAX_IMAGE_SIZE)

    private fun thumbnailKey(name: String?, resource: Int?, width: Int, height: Int, crop: Rect?) =
        "$name:$resource:$width:$height:${crop?.flattenToString()}"

    internal fun cachedThumbnail(
        name: String?,
        resource: Int?,
        width: Int,
        height: Int,
        crop: Rect? = null,
    ): Bitmap? = thumbnails.get(thumbnailKey(name, resource, width, height, crop))

    internal fun thumbnail(
        name: String?,
        resource: Int?,
        width: Int,
        height: Int,
        crop: Rect? = null,
    ): Bitmap? {
        val key = thumbnailKey(name, resource, width, height, crop)
        thumbnails.get(key)?.let {
            return it
        }
        fun decode(source: ImageDecoder.Source): Bitmap {
            val bitmap =
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    if (crop != null) {
                        decoder.crop = crop
                    } else {
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
                    }
                }
            // Sample only pixels inside the selected source square, including at the photo edges.
            val target = maxOf(width, height, 1)
            if (crop == null || bitmap.width <= target) return bitmap
            return Bitmap.createScaledBitmap(bitmap, target, target, true).also { bitmap.recycle() }
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
