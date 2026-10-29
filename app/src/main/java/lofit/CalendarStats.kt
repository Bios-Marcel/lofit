package lofit

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class TrainingWeek(val startsOn: LocalDate, val days: Int)

data class WeeklyRhythm(
    val weeks: List<TrainingWeek>,
    val thisWeekDays: Int,
    val previousFourWeekAverage: Double,
)

fun List<WorkoutSession>.weeklyRhythm(
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): WeeklyRhythm {
    val trainingDays =
        asSequence()
            .filter { it.completedAt != null }
            .map { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
            .toSet()
    val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weeks =
        (5 downTo 0).map { weeksAgo ->
            val start = thisMonday.minusWeeks(weeksAgo.toLong())
            TrainingWeek(start, (0L..6L).count { start.plusDays(it) in trainingDays })
        }
    val previousFour = weeks.dropLast(1).takeLast(4)
    return WeeklyRhythm(weeks, weeks.last().days, previousFour.map { it.days }.average())
}

fun AppData.recentMuscleWorkload(
    days: Long,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): MuscleWorkload {
    require(days > 0)
    val firstDay = today.minusDays(days - 1)
    val exercisesById = exercises.associateBy { it.id }
    val sets =
        history
            .asSequence()
            .filter { it.completedAt != null }
            .filter {
                Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() in firstDay..today
            }
            .flatMap { it.exercises.asSequence() }
            .mapNotNull { item ->
                exercisesById[item.exerciseId]?.let { exercise -> exercise to item.sets.size }
            }
            .toList()
    return MuscleWorkload(sets)
}
