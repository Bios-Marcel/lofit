package lofit

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test

class WorkoutPlanFileCodecTest {
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
}
