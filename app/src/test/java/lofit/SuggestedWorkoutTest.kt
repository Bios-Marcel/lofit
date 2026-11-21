package lofit

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SuggestedWorkoutTest {
    @Test
    fun wifiMatchesExactNetworkWithoutCoordinatesAndGpsStillMatchesOlderHistory() {
        val now = 40L * 86400000
        val here = WorkoutLocation(0.0, 0.0)
        val data = AppData(exercises = mutableListOf())
        fun log(group: String, wifi: String?, point: WorkoutLocation?) {
            data.exercises.add(Exercise(group, group, ExerciseType.REPETITIONS, false, group, ""))
            val time = now - 7L * 86400000
            val set = LoggedSet(type = SetType.NORMAL, weightKg = 5.0, completedAt = time)
            data.history.add(
                WorkoutSession(
                        name = group,
                        startedAt = time,
                        exercises =
                            mutableListOf(
                                WorkoutExercise(exerciseId = group, sets = mutableListOf(set))
                            ),
                    )
                    .apply {
                        startWifiName = wifi
                        startLocation = point
                    }
            )
        }
        log("Chest", "Gym", null)
        log("Back", "gym", null)
        log("Glutes", null, here)

        val wifiWorkout = data.suggestWorkoutOnWifi("Gym", now)!!
        assertEquals("Gym", wifiWorkout.startWifiName)
        assertNull(wifiWorkout.startLocation)
        assertEquals(listOf("Chest"), wifiWorkout.exercises.map { it.exerciseId })
        assertNull(data.suggestWorkoutOnWifi("Unknown gym", now))
        assertEquals(
            listOf("Glutes"),
            data.suggestWorkout(here, now)!!.exercises.map { it.exerciseId },
        )
    }

    @Test
    fun lookbackUsesCompletedSetsAndIncludesTheCutoffForWifiAndGps() {
        val zone = ZoneId.systemDefault()
        val end = LocalDateTime.of(2024, 3, 31, 12, 0).atZone(zone)
        val now = end.toInstant().toEpochMilli()
        val cutoff = end.minusMonths(1).toInstant().toEpochMilli()
        val here = WorkoutLocation(0.0, 0.0)
        val data = AppData(exercises = mutableListOf())
        fun log(group: String, time: Long) {
            data.exercises.add(Exercise(group, group, ExerciseType.REPETITIONS, false, group, ""))
            val set = LoggedSet(type = SetType.NORMAL, weightKg = 5.0, completedAt = time)
            data.history.add(
                WorkoutSession(
                        name = group,
                        startedAt = time - 1000,
                        exercises =
                            mutableListOf(
                                WorkoutExercise(exerciseId = group, sets = mutableListOf(set))
                            ),
                    )
                    .apply {
                        startLocation = here
                        startWifiName = "Gym"
                    }
            )
        }
        log("Back", cutoff - 1)
        log("Chest", cutoff)
        log("Glutes", end.minusDays(7).toInstant().toEpochMilli())
        log("Shoulders", now + 1)

        fun checkBoth(vararg groups: String) {
            assertEquals(
                groups.toList(),
                data.suggestWorkoutOnWifi("Gym", now)?.exercises.orEmpty().map { it.exerciseId },
            )
            assertEquals(
                groups.toList(),
                data.suggestWorkout(here, now)?.exercises.orEmpty().map { it.exerciseId },
            )
        }
        checkBoth("Glutes")
        data.suggestedWorkoutLookback = "6d"
        checkBoth()
        data.suggestedWorkoutLookback = "1w"
        checkBoth("Glutes")
        data.suggestedWorkoutLookback = "1m"
        checkBoth("Chest", "Glutes")
        data.suggestedWorkoutLookback = "0m"
        checkBoth()
    }

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
        log("Back", "Biceps", 5, 20, here)
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
        val threeWeeksAgo = now - 21L * 86400000
        log("Chest", 0.0008, threeWeeksAgo, 12)
        log("Shoulders", 0.0, threeWeeksAgo, 12)
        log("Glutes", 0.0, threeWeeksAgo, 12)
        val threeDaysAgo = now - 3L * 86400000
        log("Shoulders", 0.001, threeDaysAgo, 1)
        log("Glutes", 0.001, threeDaysAgo, 2)
        log("Grip", 0.001, threeDaysAgo, 3)
        val plan = data.suggestWorkout(here, now)!!
        assertEquals(listOf("Chest", "Glutes", "Shoulders"), plan.exercises.map { it.exerciseId })
        assertEquals(listOf(12, 12, 6), plan.exercises.map { it.plannedSets.size })
        data.suggestedWorkoutSets = 15
        assertEquals(
            listOf(12, 3),
            data.suggestWorkout(here, now)!!.exercises.map { it.plannedSets.size },
        )
        data.suggestedWorkoutSets = 36
        assertEquals(
            listOf(12, 12, 12),
            data.suggestWorkout(here, now)!!.exercises.map { it.plannedSets.size },
        )
    }
}
