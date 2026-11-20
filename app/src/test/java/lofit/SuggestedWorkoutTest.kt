package lofit

import org.junit.Assert.assertEquals
import org.junit.Test

class SuggestedWorkoutTest {
    @Test
    fun excludesRecentlyTargetedMusclesEvenWithUnfilledSlots() {
        val day = 86400000L
        val now = 40 * day
        val here = WorkoutLocation(0.0, 0.0)
        val data = AppData(exercises = mutableListOf())
        fun log(
            primary: String,
            secondary: String,
            count: Int,
            daysAgo: Int,
            location: WorkoutLocation,
        ) {
            data.exercises.add(
                Exercise(primary, primary, ExerciseType.REPETITIONS, false, primary, secondary)
            )
            val time = now - daysAgo * day
            val sets =
                MutableList(count) {
                    LoggedSet(type = SetType.NORMAL, weightKg = 5.0, completedAt = time)
                }
            data.history.add(
                WorkoutSession(
                        name = primary,
                        startedAt = time,
                        completedAt = time,
                        exercises =
                            mutableListOf(WorkoutExercise(exerciseId = primary, sets = sets)),
                    )
                    .apply { startLocation = location }
            )
        }
        log("Biceps", "Quadriceps", 1, 1, WorkoutLocation(1.0, 1.0))
        log("Back", "Biceps", 5, 29, here)
        log("Quadriceps", "", 30, 3, here)
        fun plan() =
            data.suggestWorkout(here, now)!!.exercises.map { it.exerciseId to it.plannedSets.size }
        assertEquals(listOf("Quadriceps" to 30), plan())
        data.history.last().exercises.single().sets.removeAt(0)
        assertEquals(listOf("Quadriceps" to 29), plan())
    }

    @Test
    fun prefersThreeSetsThreeWeeksAgoOverTwoThreeDaysAgo() {
        val day = 86400000L
        val now = 40 * day
        val here = WorkoutLocation(0.0, 0.0)
        val data = AppData(exercises = mutableListOf())
        fun log(group: String, count: Int, daysAgo: Int) {
            val time = now - daysAgo * day
            data.exercises.add(Exercise(group, group, ExerciseType.REPETITIONS, false, group, ""))
            val sets =
                MutableList(count) {
                    LoggedSet(type = SetType.NORMAL, weightKg = 5.0, completedAt = time)
                }
            data.history.add(
                WorkoutSession(
                        name = group,
                        startedAt = time,
                        completedAt = time,
                        exercises = mutableListOf(WorkoutExercise(exerciseId = group, sets = sets)),
                    )
                    .apply { startLocation = here }
            )
        }
        log("Biceps", 2, 3)
        log("Quadriceps", 3, 21)
        assertEquals(
            listOf("Quadriceps", "Biceps"),
            data.suggestWorkout(here, now)!!.exercises.map { it.exerciseId },
        )
    }

    @Test
    fun ranksAcrossLocationsAndSeparatesSharedSecondaryMusclesWithinSetLimit() {
        val now = 3000000000L
        val here = WorkoutLocation(0.0, 0.0)
        val catalog =
            listOf("Chest", "Shoulders", "Glutes", "Grip")
                .map {
                    Exercise(
                        it,
                        it,
                        ExerciseType.REPETITIONS,
                        false,
                        it,
                        if (it in listOf("Chest", "Shoulders")) "Triceps" else "",
                    )
                }
                .toMutableList()
        val data = AppData(exercises = catalog)
        fun log(group: String, longitude: Double, time: Long, count: Int) {
            val entry =
                WorkoutExercise(
                    exerciseId = group,
                    sets =
                        MutableList(count) {
                            LoggedSet(type = SetType.NORMAL, weightKg = 5.0, completedAt = time)
                        },
                )
            data.history.add(
                WorkoutSession(
                        name = group,
                        startedAt = time,
                        completedAt = time,
                        exercises = mutableListOf(entry),
                    )
                    .apply { startLocation = WorkoutLocation(0.0, longitude) }
            )
        }
        log("Chest", 0.0008, 1, 12)
        log("Shoulders", 0.0, 1, 12)
        log("Glutes", 0.0, 1, 12)
        val threeDaysAgo = now - 3L * 86400000
        log("Shoulders", 0.001, threeDaysAgo, 1)
        log("Glutes", 0.001, threeDaysAgo, 2)
        log("Grip", 0.001, threeDaysAgo, 3)
        val plan = data.suggestWorkout(here, now)!!
        assertEquals(listOf("Chest", "Glutes", "Shoulders"), plan.exercises.map { it.exerciseId })
        assertEquals(listOf(12, 12, 6), plan.exercises.map { it.plannedSets.size })
    }
}
