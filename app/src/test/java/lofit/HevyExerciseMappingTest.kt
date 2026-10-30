package lofit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HevyExerciseMappingTest {
    private fun exercise(
        id: String,
        name: String,
        type: ExerciseType = ExerciseType.REPETITIONS,
        weighted: Boolean = true,
    ) = Exercise(id, name, type, false, "Other", "", weighted = weighted)

    private fun set(weight: Double = 0.0) =
        LoggedSet(type = SetType.NORMAL, weightKg = weight, reps = 8)

    @Test
    fun knownAliasMapsToCompatibleDefaultOnly() {
        val bench = exercise("bench-press", "Bench press")
        val templates =
            listOf(
                HevyTemplate("wrong", "Bench Press (Barbell)", "duration"),
                HevyTemplate("right", "Bench Press (Barbell)", "weight_reps"),
            )
        assertEquals("right", HevyExerciseMapping.find(bench, listOf(set(60.0)), templates)?.id)
    }

    @Test
    fun equipmentNamesMatchHevySuffixes() {
        val row = exercise("row", "Dumbbell Row")
        assertEquals(
            "row-id",
            HevyExerciseMapping.find(
                    row,
                    listOf(set(20.0)),
                    listOf(HevyTemplate("row-id", "Row (Dumbbell)", "weight_reps")),
                )
                ?.id,
        )
    }

    @Test
    fun newDefaultExercisesMapToHevyTemplates() {
        val templates =
            listOf(
                HevyTemplate("incline", "Incline Bench Press (Dumbbell)", "weight_reps"),
                HevyTemplate("cable", "Triceps Extension (Cable)", "weight_reps"),
            )
        val exercises = ExerciseCatalog.exercises()
        assertEquals(
            "incline",
            HevyExerciseMapping.find(
                    exercises.single { it.id == "incline-dumbbell-bench-press" },
                    listOf(set(20.0)),
                    templates,
                )
                ?.id,
        )
        assertEquals(
            "cable",
            HevyExerciseMapping.find(
                    exercises.single { it.id == "cable-tricep-extension" },
                    listOf(set(20.0)),
                    templates,
                )
                ?.id,
        )
    }

    @Test
    fun bodyweightTemplateCannotLoseAddedWeight() {
        val pushUp = exercise("push-up", "Push-up")
        val templates = listOf(HevyTemplate("push", "Push Up", "bodyweight_reps"))
        assertEquals("push", HevyExerciseMapping.find(pushUp, listOf(set()), templates)?.id)
        assertNull(HevyExerciseMapping.find(pushUp, listOf(set(10.0)), templates))
    }

    @Test
    fun zeroLoadExercisesCanUseHevyUnweightedDefaults() {
        val crunch = exercise("crunch", "Crunch")
        val timed = exercise("plank", "Plank", ExerciseType.TIMED)
        assertEquals(
            "crunch-id",
            HevyExerciseMapping.find(
                    crunch,
                    listOf(set()),
                    listOf(HevyTemplate("crunch-id", "Crunch", "reps_only")),
                )
                ?.id,
        )
        assertNull(
            HevyExerciseMapping.find(
                crunch,
                listOf(set(10.0)),
                listOf(HevyTemplate("crunch-id", "Crunch", "reps_only")),
            )
        )
        assertEquals(
            "plank-id",
            HevyExerciseMapping.find(
                    timed,
                    listOf(set()),
                    listOf(HevyTemplate("plank-id", "Plank", "duration")),
                )
                ?.id,
        )
    }

    @Test
    fun unknownExerciseUsesMetricSpecificPlaceholder() {
        assertEquals(
            "Placeholder Timed",
            HevyExerciseMapping.placeholder(
                HevyExerciseMapping.type(exercise("hold", "Hold", ExerciseType.TIMED, false))
            ),
        )
        assertEquals(
            "Placeholder Weighted Timed",
            HevyExerciseMapping.placeholder(
                HevyExerciseMapping.type(exercise("hold", "Hold", ExerciseType.TIMED, true))
            ),
        )
        assertEquals(
            "Placeholder Weighted",
            HevyExerciseMapping.placeholder(HevyExerciseMapping.type(exercise("lift", "Lift"))),
        )
        assertEquals(
            "Placeholder Reps",
            HevyExerciseMapping.placeholder(
                HevyExerciseMapping.type(exercise("reps", "Reps", weighted = false))
            ),
        )
    }
}
