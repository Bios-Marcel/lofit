package lofit

import android.app.Activity
import android.os.Bundle
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** ADB-only debug entry point for adding repeatable calendar demo workouts. */
class DemoHistoryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = LofitRepository(this)
        repository.data.history.removeAll { it.id.startsWith("demo-calendar-") }
        repository.data.history.addAll(demoWorkouts())
        repository.saveHistory()
        finish()
    }

    private fun demoWorkouts(): List<WorkoutSession> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekDays =
            listOf(
                listOf(0, 2),
                listOf(0, 2, 4),
                listOf(1, 3),
                listOf(0, 1, 3, 5),
                listOf(0),
                listOf(0, 2, 4),
            )
        return weekDays.flatMapIndexed { weeksAgo, days ->
            days.mapNotNull { day ->
                val date = monday.minusWeeks(weeksAgo.toLong()).plusDays(day.toLong())
                if (date > today) return@mapNotNull null
                val startedAt = date.atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
                val movements =
                    when (day % 3) {
                        0 ->
                            listOf(
                                "bench-press" to 48.0,
                                "pull-up" to 0.0,
                                "overhead-press" to 28.0,
                            )
                        1 -> listOf("back-squat" to 62.0, "deadlift" to 76.0)
                        else ->
                            listOf("dumbbell-curl" to 12.0, "bench-press" to 42.0, "plank" to 0.0)
                    }
                WorkoutSession(
                    id = "demo-calendar-$date",
                    name = "Demo · ${if (day % 3 == 1) "Lower" else "Upper"}",
                    startedAt = startedAt,
                    completedAt = startedAt + 45 * 60_000L,
                    exercises =
                        movements
                            .mapIndexed { index, (id, weight) ->
                                WorkoutExercise(
                                    exerciseId = id,
                                    sets =
                                        MutableList(3) { setIndex ->
                                            LoggedSet(
                                                type = SetType.NORMAL,
                                                weightKg =
                                                    if (weight == 0.0) 0.0 else weight + weeksAgo,
                                                reps = if (id == "plank") 0 else 8 + setIndex,
                                                holdSeconds = if (id == "plank") 45 else 0,
                                                completedAt =
                                                    startedAt +
                                                        (index * 3 + setIndex + 1) * 5 * 60_000L,
                                            )
                                        },
                                )
                            }
                            .toMutableList(),
                )
            }
        }
    }
}
