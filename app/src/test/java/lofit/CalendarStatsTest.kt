package lofit

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarStatsTest {
    private val today = LocalDate.of(2026, 10, 29)

    @Test
    fun cardioRequiresLoggedSetsAndKnownCardioExercises() {
        val cardio = Exercise("cardio", "Run", ExerciseType.TIMED, false, "Legs", "", cardio = true)
        val catalog = mapOf(cardio.id to cardio)
        val session = workout(today)
        assertFalse(session.hasCardio(catalog))
        session.exercises.add(
            WorkoutExercise(exerciseId = cardio.id, plannedSets = mutableListOf(PlannedSet()))
        )
        assertFalse(session.hasCardio(catalog))
        session.exercises.single().sets.add(loggedSet())
        assertTrue(session.hasCardio(catalog))
        assertFalse(session.hasCardio(emptyMap()))
        cardio.cardio = false
        assertFalse(session.hasCardio(catalog))
    }

    @Test
    fun cardioIncludesMixedLoggedWorkoutsWithEitherTrackingType() {
        val timed = Exercise("run", "Run", ExerciseType.TIMED, false, "Legs", "", cardio = true)
        val repetitions = timed.copy(id = "jumps", type = ExerciseType.REPETITIONS)
        val strength = timed.copy(id = "squat", cardio = false)
        val catalog = listOf(timed, repetitions, strength).associateBy { it.id }
        val session =
            workout(today)
                .copy(
                    exercises =
                        mutableListOf(
                            WorkoutExercise(
                                exerciseId = timed.id,
                                sets = mutableListOf(loggedSet()),
                            ),
                            WorkoutExercise(
                                exerciseId = repetitions.id,
                                sets = mutableListOf(loggedSet()),
                            ),
                            WorkoutExercise(
                                exerciseId = strength.id,
                                plannedSets = mutableListOf(PlannedSet()),
                            ),
                        )
                )
        assertTrue(session.hasCardio(catalog))
        session.exercises.last().sets.add(loggedSet())
        assertTrue(session.hasCardio(catalog))
        session.exercises.first().sets.clear()
        assertTrue(session.hasCardio(catalog))
        session.exercises[1].sets.clear()
        assertFalse(session.hasCardio(catalog))
    }

    @Test
    fun rhythmCountsDistinctDaysAndUsesFourCompletedWeeksForAverage() {
        val history =
            listOf(
                workout(today.minusDays(1)),
                workout(today.minusDays(1)),
                workout(today.minusDays(2)),
                workout(today.minusDays(7)),
                workout(today.minusDays(8)),
                workout(today.minusDays(14)),
                workout(today.minusDays(28)),
                workout(today, completed = false),
            )

        val rhythm = history.weeklyRhythm(today, ZoneOffset.UTC)

        assertEquals(listOf(0, 1, 0, 1, 2, 2), rhythm.weeks.map { it.days })
        assertEquals(2, rhythm.thisWeekDays)
        assertEquals(1.0, rhythm.previousFourWeekAverage, 0.0)
    }

    @Test
    fun muscleCreditsUseRecentLoggedSetsAndCountSecondaryAsQuarter() {
        val bench = Exercise("bench", "Bench", ExerciseType.REPETITIONS, false, "Chest", "Triceps")
        val plank = Exercise("plank", "Plank", ExerciseType.TIMED, false, "Core", "Shoulders")
        val recent =
            workout(today.minusDays(6))
                .copy(
                    exercises =
                        mutableListOf(
                            WorkoutExercise(
                                exerciseId = bench.id,
                                sets = MutableList(4) { loggedSet() },
                            ),
                            WorkoutExercise(
                                exerciseId = plank.id,
                                sets = MutableList(2) { loggedSet() },
                            ),
                        )
                )
        val old =
            workout(today.minusDays(28))
                .copy(
                    exercises =
                        mutableListOf(
                            WorkoutExercise(
                                exerciseId = bench.id,
                                sets = MutableList(10) { loggedSet() },
                            )
                        )
                )
        val data =
            AppData(exercises = mutableListOf(bench, plank), history = mutableListOf(recent, old))

        val credits = data.recentMuscleWorkload(7, today, ZoneOffset.UTC).regionSetCredits()

        assertEquals(4.0, credits.getValue("Chest"), 0.0)
        assertEquals(1.0, credits.getValue("Arms"), 0.0)
        assertEquals(2.0, credits.getValue("Core"), 0.0)
        assertEquals(0.5, credits.getValue("Shoulders"), 0.0)
        assertEquals(0.0, credits.getValue("Legs"), 0.0)

        val percentages = data.recentMuscleWorkload(7, today, ZoneOffset.UTC).regionPercentages()
        assertEquals(100, percentages.values.sum())
        assertEquals(53, percentages.getValue("Chest"))
        assertEquals(13, percentages.getValue("Arms"))
        assertEquals(27, percentages.getValue("Core"))
        assertEquals(7, percentages.getValue("Shoulders"))
    }

    @Test
    fun emptyMuscleWorkloadHasNoPercentageShare() {
        val percentages = MuscleWorkload(emptyList()).regionPercentages()
        assertEquals(0, percentages.values.sum())
    }

    private fun workout(date: LocalDate, completed: Boolean = true): WorkoutSession {
        val start = date.atTime(18, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        return WorkoutSession(
            name = "Test",
            startedAt = start,
            completedAt = if (completed) start + 1 else null,
        )
    }

    private fun loggedSet() = LoggedSet(type = SetType.NORMAL, weightKg = 10.0, reps = 8)
}
