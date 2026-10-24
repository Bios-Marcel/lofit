package lofit

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedWorkoutPlanTest {
    private val movement = Exercise("remote-id", "Partner hold", ExerciseType.TIMED, true,
        "Core", "Shoulders", minimumHoldSeconds = 25, custom = true, weighted = true)

    private fun workout(): WorkoutSession {
        val sets = mutableListOf(
            PlannedSet(type = SetType.WARMUP, weightKg = 2.5, holdSeconds = 25, side = Side.LEFT, actualHoldSeconds = 17),
            PlannedSet(type = SetType.FAILURE, weightKg = 5.0, holdSeconds = 40, side = Side.RIGHT))
        return WorkoutSession(name = "Together 🏋", defaultRestSeconds = 75, bodyWeightKg = 81.0,
            exercises = mutableListOf(WorkoutExercise(exerciseId = movement.id, restSecondsOverride = 45,
                note = "Brace core\nKeep breathing",
                plannedSets = sets, sets = mutableListOf(LoggedSet(type = SetType.WARMUP,
                    weightKg = 2.5, holdSeconds = 17, plannedSetId = sets.first().id)))))
    }

    @Test fun roundTrip_keepsFullPlanAndCustomExerciseWithoutPersonalData() {
        val source = workout()
        source.exercises.single().apply {
            previousNote = note
            note = ""
        }
        val payload = SharedWorkoutPlan.encode(source, listOf(movement))
        val json = JSONObject(payload.toString(Charsets.UTF_8))
        assertFalse(json.has("bodyWeightKg"))
        assertFalse(json.has("intervals"))
        val plan = SharedWorkoutPlan.decode(payload)
        assertEquals(source.name, plan.routine.name)
        assertEquals(75, plan.routine.defaultRestSeconds)
        val exercise = plan.routine.exercises.single()
        assertEquals("Brace core\nKeep breathing", exercise.note)
        assertEquals(45, exercise.restSecondsOverride)
        assertEquals(2, exercise.plannedSets.size)
        assertNull(exercise.plannedSets.first().actualHoldSeconds)
        assertEquals(Side.LEFT, exercise.plannedSets.first().side)
        assertEquals(SetType.FAILURE, exercise.plannedSets.last().type)
        assertEquals(40, exercise.plannedSets.last().holdSeconds)
        assertEquals(movement, plan.exercises.single())
    }

    @Test fun start_usesSharedPrescriptionDespiteReceiverHistoryAndIdCollision() {
        val plan = SharedWorkoutPlan.decode(SharedWorkoutPlan.encode(workout(), listOf(movement)))
        val data = AppData(exercises = mutableListOf(movement.copy(name = "Other movement")))
        data.history.add(WorkoutSession(name = "Old", completedAt = 1,
            exercises = mutableListOf(WorkoutExercise(exerciseId = movement.id,
                sets = mutableListOf(LoggedSet(type = SetType.NORMAL, weightKg = 90.0, reps = 1))))))
        val received = plan.startIn(data)
        assertEquals("Brace core\nKeep breathing", received.exercises.single().note)
        assertEquals("Other movement", data.exercises.first().name)
        assertEquals(2, data.exercises.size)
        assertNotEquals(movement.id, received.exercises.single().exerciseId)
        assertEquals(2, received.exercises.single().plannedSets.size)
        assertEquals(2.5, received.exercises.single().plannedSets.first().weightKg, 0.0)
        assertTrue(received.exercises.single().sets.isEmpty())
        assertNull(received.bodyWeightKg)
        assertNull(received.exercises.single().plannedSets.first().actualHoldSeconds)
    }

    @Test fun start_reusesEquivalentLocalMovementAndMakesFreshSets() {
        val plan = SharedWorkoutPlan.decode(SharedWorkoutPlan.encode(workout(), listOf(movement)))
        val data = AppData(exercises = mutableListOf(movement.copy(id = "local-id")))
        val received = plan.startIn(data)
        assertEquals(1, data.exercises.size)
        assertEquals("local-id", received.exercises.single().exerciseId)
        assertNotEquals(plan.routine.exercises.single().plannedSets.first().id,
            received.exercises.single().plannedSets.first().id)
    }

    @Test fun start_doesNotReplaceActiveWorkoutOrImportExercises() {
        val plan = SharedWorkoutPlan.decode(SharedWorkoutPlan.encode(workout(), listOf(movement)))
        val data = AppData(exercises = mutableListOf(), activeWorkout = WorkoutSession(name = "Mine"))
        assertThrows(IllegalStateException::class.java) { plan.startIn(data) }
        assertEquals("Mine", data.activeWorkout?.name)
        assertTrue(data.exercises.isEmpty())
    }

    @Test fun decode_rejectsInvalidVersionReferencesAndNumbers() {
        val bytes = SharedWorkoutPlan.encode(workout(), listOf(movement))
        fun json() = JSONObject(bytes.toString(Charsets.UTF_8))
        assertThrows(IllegalArgumentException::class.java) {
            SharedWorkoutPlan.decode(json().put("version", 2).toString().toByteArray())
        }
        val badReference = json().apply { getJSONArray("exercises").getJSONObject(0).put("movement", "missing") }
        assertThrows(IllegalArgumentException::class.java) { SharedWorkoutPlan.decode(badReference.toString().toByteArray()) }
        val badWeight = json().apply {
            getJSONArray("exercises").getJSONObject(0).getJSONArray("sets").getJSONObject(0).put("weight", -1)
        }
        assertThrows(IllegalArgumentException::class.java) { SharedWorkoutPlan.decode(badWeight.toString().toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { SharedWorkoutPlan.decode(ByteArray(SharedWorkoutPlan.MAX_BYTES + 1)) }
    }

    @Test fun legacyExerciseWithoutPlan_sharesLoggedSetsAsFreshTargets() {
        val source = workout()
        source.exercises.single().plannedSets.clear()
        val plan = SharedWorkoutPlan.decode(SharedWorkoutPlan.encode(source, listOf(movement)))
        assertEquals(17, plan.routine.exercises.single().plannedSets.single().holdSeconds)
        assertNull(plan.routine.exercises.single().plannedSets.single().actualHoldSeconds)
    }
}
