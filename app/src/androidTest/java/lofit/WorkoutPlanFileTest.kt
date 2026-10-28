package lofit

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutPlanFileTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val movement =
        Exercise(
            "hold",
            "Partner hold",
            ExerciseType.TIMED,
            true,
            "Core",
            "Shoulders",
            weighted = true,
            custom = true,
        )

    private fun workout() =
        WorkoutSession(
            name = "Partner / workout",
            defaultRestSeconds = 45,
            bodyWeightKg = 80.0,
            exercises =
                mutableListOf(
                    WorkoutExercise(
                        exerciseId = movement.id,
                        restSecondsOverride = 30,
                        plannedSets =
                            MutableList(8) {
                                PlannedSet(
                                    weightKg = 2.5,
                                    holdSeconds = 45,
                                    actualHoldSeconds = 12,
                                    side = Side.LEFT,
                                )
                            },
                    )
                ),
        )

    @Test
    fun fileIsGzipJsonAndPreservesFullPlan() {
        val source = workout()
        val file = WorkoutPlanFile.encode(source, listOf(movement))
        assertArrayEquals(
            SharedWorkoutPlan.encode(source, listOf(movement)),
            GZIPInputStream(ByteArrayInputStream(file)).use { it.readBytes() },
        )
        assertTrue(file.size < SharedWorkoutPlan.encode(source, listOf(movement)).size)
        val received = WorkoutPlanFile.decode(ByteArrayInputStream(file))
        assertEquals(source.name, received.routine.name)
        assertEquals(45, received.routine.defaultRestSeconds)
        assertEquals(8, received.routine.exercises.single().plannedSets.size)
        assertEquals(Side.LEFT, received.routine.exercises.single().plannedSets.first().side)
        assertNull(received.routine.exercises.single().plannedSets.first().actualHoldSeconds)
    }

    @Test
    fun rejectsDamagedFilesAndLimitsCompressedAndExpandedSize() {
        val valid = WorkoutPlanFile.encode(workout(), listOf(movement))
        assertThrows(Exception::class.java) {
            WorkoutPlanFile.decode(ByteArrayInputStream(valid.copyOf(valid.size - 8)))
        }
        assertThrows(Exception::class.java) {
            WorkoutPlanFile.decode(ByteArrayInputStream("{}".toByteArray()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkoutPlanFile.decode(
                ByteArrayInputStream(ByteArray(SharedWorkoutPlan.MAX_BYTES + 1025))
            )
        }
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(ByteArray(SharedWorkoutPlan.MAX_BYTES + 1) { 32 }) }
        assertThrows(IllegalArgumentException::class.java) {
            WorkoutPlanFile.decode(ByteArrayInputStream(output.toByteArray()))
        }
    }

    @Test
    fun shareIntentGrantsReadAccessToNamedLofitAttachment() {
        val share = WorkoutPlanFile.shareIntent(context, workout(), listOf(movement))
        assertEquals(Intent.ACTION_SEND, share.action)
        assertEquals(WorkoutPlanFile.MIME_TYPE, share.type)
        assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, share.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val uri = share.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals("content", uri.scheme)
        assertEquals(uri, share.clipData!!.getItemAt(0).uri)
        assertEquals(WorkoutPlanFile.MIME_TYPE, context.contentResolver.getType(uri))
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!
            .use {
                assertTrue(it.moveToFirst())
                assertEquals("Partner _ workout.lofit", it.getString(0))
            }
        val plan = context.contentResolver.openInputStream(uri)!!.use(WorkoutPlanFile::decode)
        assertEquals(workout().name, plan.routine.name)
        val again = WorkoutPlanFile.shareIntent(context, workout(), listOf(movement))
        assertNotEquals(uri, again.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
    }

    @Test
    fun manifestHandlesCustomMimeAndLofitExtensionWithOrWithoutMime() {
        fun handles(uri: String, mime: String?): Boolean {
            val intent =
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(uri), mime)
                    .addCategory(Intent.CATEGORY_DEFAULT)
            return context.packageManager
                .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo.packageName == context.packageName }
        }
        assertTrue(handles("content://test/document/opaque-id", WorkoutPlanFile.MIME_TYPE))
        assertTrue(
            handles(
                "content://test/document/Download/a.b.workout.lofit",
                "application/octet-stream",
            )
        )
        assertTrue(handles("content://test/document/workout.lofit", "application/gzip"))
        assertTrue(handles("file:///sdcard/Download/workout.lofit", null))
        assertTrue(handles("file:///sdcard/Download/workout.lofit", "application/octet-stream"))
        assertFalse(handles("content://test/document/workout.json", "application/json"))
        assertFalse(handles("content://test/document/workout.zip", "application/octet-stream"))
        assertFalse(
            handles("content://test/document/workout.lofit.exe", "application/octet-stream")
        )
    }
}
