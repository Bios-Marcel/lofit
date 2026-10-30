package lofit

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun staticMatchesExistInBundledCatalogAndKeepTheirMetrics() {
        val text = File("src/main/assets/hevy_exercise_templates.json").readText()
        val templates =
            Regex("""\{\s*"id": "([^"]+)",\s*"title": "([^"]+)",\s*"type": "([^"]+)"""")
                .findAll(text)
                .map { match ->
                    HevyTemplate(
                        match.groupValues[1],
                        match.groupValues[2],
                        match.groupValues[3],
                    )
                }
                .toList()
        assertTrue(templates.isNotEmpty())
        val exercises = ExerciseCatalog.exercises().associateBy { it.id }
        HevyExerciseMapping.defaultTemplateIds.forEach { (exerciseId, templateId) ->
            val exercise = requireNotNull(exercises[exerciseId])
            assertEquals(
                exerciseId,
                templateId,
                HevyExerciseMapping.find(
                        exercise,
                        listOf(set(if (exercise.weighted) 10.0 else 0.0)),
                        templates,
                    )
                    ?.id,
            )
        }
    }

    @Test
    fun staticMatchesChooseMovementAndEquipmentOverSimilarNames() {
        val templates =
            listOf(
                HevyTemplate("AC5A4C58", "Hack Squat", "weight_reps"),
                HevyTemplate("D04AC939", "Squat (Barbell)", "weight_reps"),
                HevyTemplate("C7973E0E", "Leg Press (Machine)", "weight_reps"),
            )
        val exercises = ExerciseCatalog.exercises().associateBy { it.id }
        assertEquals(
            "D04AC939",
            HevyExerciseMapping.find(
                    exercises.getValue("back-squat"),
                    listOf(set(60.0)),
                    templates,
                )
                ?.id,
        )
        assertEquals(
            "C7973E0E",
            HevyExerciseMapping.find(
                    exercises.getValue("leg-press"),
                    listOf(set(60.0)),
                    templates,
                )
                ?.id,
        )
    }

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
