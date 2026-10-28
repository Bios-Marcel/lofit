package lofit

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** .lofit files contain the same versioned plan JSON as Bluetooth, compressed with gzip. */
object WorkoutPlanFile {
    const val MIME_TYPE = "application/vnd.lofit.workout"
    private const val MAX_FILE_BYTES = SharedWorkoutPlan.MAX_BYTES + 1024

    fun encode(workout: WorkoutSession, catalog: List<Exercise>): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(SharedWorkoutPlan.encode(workout, catalog)) }
        return output.toByteArray()
    }

    fun decode(input: InputStream): SharedWorkoutPlan {
        val compressed = input.readNBytes(MAX_FILE_BYTES + 1)
        require(compressed.size <= MAX_FILE_BYTES) { "Workout file is too large" }
        val json =
            GZIPInputStream(ByteArrayInputStream(compressed)).use {
                it.readNBytes(SharedWorkoutPlan.MAX_BYTES + 1)
            }
        return SharedWorkoutPlan.decode(json)
    }

    fun shareIntent(context: Context, workout: WorkoutSession, catalog: List<Exercise>): Intent {
        val bytes = encode(workout, catalog)
        val directory =
            File(context.cacheDir, "shared-workouts/${UUID.randomUUID()}").apply { mkdirs() }
        val name =
            workout.name
                .map { if (it.isLetterOrDigit() || it in " -_") it else '_' }
                .joinToString("")
                .trim(' ', '_')
                .take(64)
                .ifBlank { "workout" }
        val file = File(directory, "$name.lofit").apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.workout-files", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, workout.name)
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

class WorkoutFileProvider : FileProvider() {
    override fun getType(uri: Uri): String? =
        if (uri.lastPathSegment?.endsWith(".lofit") == true) WorkoutPlanFile.MIME_TYPE
        else super.getType(uri)
}
