package lofit

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RetiredExercisePersistenceTest {
    private val context = RepositoryTestContext("retired-exercises")

    @Before
    fun clearData() {
        context.clearRepositoryTestData()
    }

    @Test
    fun removedBuiltInsKeepSavedWorkAndRecordsButLeaveSelection() {
        val repository = LofitRepository(context)
        val obsolete =
            Exercise(
                id = "obsolete-builtin",
                name = "Old built-in movement",
                type = ExerciseType.REPETITIONS,
                asymmetric = true,
                primaryMuscle = "Biceps",
                secondaryMuscles = "Forearms",
                imageResource = Int.MAX_VALUE,
                imageName = "removed_exercise_image",
            )
        val custom =
            obsolete.copy(
                id = "custom-movement",
                name = "My movement",
                custom = true,
                imageResource = null,
                imageName = null,
                imageFile = "custom.webp",
            )
        repository.data.exercises.addAll(listOf(obsolete, custom))
        val set = LoggedSet(type = SetType.NORMAL, weightKg = 12.0, reps = 8, completedAt = 200)
        val item =
            WorkoutExercise(
                exerciseId = obsolete.id,
                sets = mutableListOf(set),
                plannedSets = mutableListOf(PlannedSet(weightKg = 12.0, reps = 8)),
                note = "Keep this note",
            )
        val history =
            WorkoutSession(
                name = "Past workout",
                startedAt = 100,
                completedAt = 200,
                exercises = mutableListOf(item),
            )
        val routine = history.toRoutine()
        repository.data.routines.add(routine)
        repository.data.history.add(history.copy(sourceRoutine = routine))
        repository.data.activeWorkout =
            WorkoutSession(
                name = "Current workout",
                startedAt = 300,
                sourceRoutine = routine,
                exercises = mutableListOf(item.copy()),
            )
        repository.data.exerciseRecords[obsolete.id] =
            mutableListOf(ExerciseRecord(obsolete, set, set))
        repository.data.exerciseLastSelectedAt[obsolete.id] = 200L
        repository.save()
        repository.saveHistory()

        val loaded = LofitRepository(context)
        val data = loaded.data
        val retired = data.exercises.single { it.id == obsolete.id }
        assertTrue(retired.retired)
        assertFalse(retired.custom)
        assertEquals(obsolete.name, retired.name)
        assertTrue(retired.asymmetric)
        assertTrue(retired.weighted)
        assertNull(retired.imageResource)
        assertNull(retired.imageName)
        assertNull(ExercisePictures(context).bundledResource(retired, BiologicalGender.MALE))
        assertNull(ExercisePictures(context).bundledResource(retired, BiologicalGender.FEMALE))
        assertEquals(custom, data.exercises.single { it.id == custom.id })
        assertFalse(data.exercises.single { it.id == custom.id }.retired)
        val defaultIds = DefaultData.exercises().map { it.id }.toSet()
        assertTrue(data.exercises.filter { it.id in defaultIds }.none { it.retired })
        assertEquals(obsolete.id, data.routines.single().exercises.single().exerciseId)
        val active = requireNotNull(data.activeWorkout)
        assertEquals(obsolete.id, active.exercises.single().exerciseId)
        assertEquals(obsolete.id, active.sourceRoutine!!.exercises.single().exerciseId)
        assertEquals(item.plannedSets, active.exercises.single().plannedSets)
        assertEquals(item.sets, active.exercises.single().sets)
        val past = data.history.single()
        assertEquals(obsolete.id, past.exercises.single().exerciseId)
        assertEquals(obsolete.id, past.sourceRoutine!!.exercises.single().exerciseId)
        assertEquals("Keep this note", past.exercises.single().note)
        assertEquals(item.sets, past.exercises.single().sets)
        assertEquals(192.0, past.totalVolume(data.exercises), 0.0)
        val record = data.exerciseRecords.getValue(obsolete.id).single()
        assertEquals(retired, record.exercise)
        assertEquals(set, record.set)
        assertEquals(set, record.volumeSet)
        assertEquals(200L, data.exerciseLastSelectedAt[obsolete.id])
        assertEquals(12.0, data.lastKnownSets(obsolete.id)!!.single().weightKg, 0.0)
        assertFalse(data.exercisesByRecency().any { it.id == obsolete.id })

        val protocol = McpProtocol(loaded, ExercisePictures(context))
        val response =
            protocol.handle(
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", "retired-selection")
                    .put("method", "tools/call")
                    .put(
                        "params",
                        JSONObject()
                            .put("name", "get_exercises")
                            .put("arguments", JSONObject().put("offset", defaultIds.size)),
                    )
                    .toString()
            )!!
        val page = response.getJSONObject("result").getJSONObject("structuredContent")
        assertEquals(defaultIds.size + 1, page.getInt("total"))
        assertEquals(1, page.getJSONArray("exercises").length())
        assertEquals(custom.id, page.getJSONArray("exercises").getJSONObject(0).getString("id"))

        loaded.save()
        loaded.saveHistory()
        val serialized =
            JSONObject(context.repositoryStateFile.readText()).getJSONArray("exercises")
        for (index in 0 until serialized.length()) {
            assertFalse(serialized.getJSONObject(index).has("retired"))
        }
        assertEquals(data, LofitRepository(context).data)
    }
}
