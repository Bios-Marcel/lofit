package lofit

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarStatsTest {
    private val today = LocalDate.of(2026, 10, 29)

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
