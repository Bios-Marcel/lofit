package lofit

import android.content.Context
import android.content.pm.PackageManager
import android.health.connect.AggregateRecordsRequest
import android.health.connect.AggregateRecordsResponse
import android.health.connect.HealthConnectException
import android.health.connect.HealthConnectManager
import android.health.connect.HealthPermissions
import android.health.connect.ReadRecordsRequestUsingFilters
import android.health.connect.ReadRecordsResponse
import android.health.connect.TimeInstantRangeFilter
import android.health.connect.datatypes.HeartRateRecord
import android.os.OutcomeReceiver
import java.time.Duration
import java.time.Instant

class HealthConnectHeartRateReader(private val context: Context) {
    val canReadHeartRate: Boolean
        get() = hasPermission(HealthPermissions.READ_HEART_RATE)

    private fun hasPermission(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun readAverage(intervals: List<WorkoutInterval>, callback: (Result<Double?>) -> Unit) {
        val manager = context.getSystemService(HealthConnectManager::class.java)
        if (manager == null || !canReadHeartRate) {
            callback(
                Result.failure(
                    IllegalStateException("Health Connect heart rate access unavailable")
                )
            )
            return
        }
        val earliest =
            if (hasPermission(HealthPermissions.READ_HEALTH_DATA_HISTORY)) Instant.EPOCH
            else Instant.now().minus(Duration.ofDays(30))
        val ranges = intervals.mapNotNull { interval ->
            val end = interval.endedAt?.let(Instant::ofEpochMilli) ?: return@mapNotNull null
            val start = maxOf(Instant.ofEpochMilli(interval.startedAt), earliest)
            if (start < end) start to end else null
        }
        var totalBpm = 0.0
        var sampleCount = 0L

        fun readInterval(index: Int) {
            if (index == ranges.size) {
                if (sampleCount > 0L) {
                    // LEGACY: Keep the sample-weighted Health Connect average when readings exist.
                    callback(Result.success(totalBpm / sampleCount))
                } else if (ranges.isEmpty()) callback(Result.success(null))
                else readNearbySamples(manager, ranges, earliest, callback)
                return
            }
            val (start, end) = ranges[index]
            try {
                val request =
                    AggregateRecordsRequest.Builder<Long>(
                            TimeInstantRangeFilter.Builder()
                                .setStartTime(start)
                                .setEndTime(end)
                                .build()
                        )
                        .addAggregationType(HeartRateRecord.BPM_AVG)
                        .addAggregationType(HeartRateRecord.HEART_MEASUREMENTS_COUNT)
                        .build()
                manager.aggregate(
                    request,
                    context.mainExecutor,
                    object :
                        OutcomeReceiver<AggregateRecordsResponse<Long>, HealthConnectException> {
                        override fun onResult(result: AggregateRecordsResponse<Long>) {
                            val average = result.get(HeartRateRecord.BPM_AVG)
                            val count = result.get(HeartRateRecord.HEART_MEASUREMENTS_COUNT) ?: 0L
                            if (average != null && count > 0) {
                                // Weight each interval by its sample count, not its duration.
                                totalBpm += average.toDouble() * count
                                sampleCount += count
                            }
                            readInterval(index + 1)
                        }

                        override fun onError(error: HealthConnectException) {
                            callback(Result.failure(error))
                        }
                    },
                )
            } catch (error: RuntimeException) {
                callback(Result.failure(error))
            }
        }
        readInterval(0)
    }

    private fun readNearbySamples(
        manager: HealthConnectManager,
        ranges: List<Pair<Instant, Instant>>,
        earliest: Instant,
        callback: (Result<Double?>) -> Unit,
    ) {
        val average =
            WorkoutHeartRateAverage(
                ranges.map { (start, end) ->
                    WorkoutInterval(start.toEpochMilli(), end.toEpochMilli())
                }
            )
        val end = ranges.maxOf { it.second }.plusMillis(HEART_RATE_NEARBY_WINDOW_MILLIS + 1)
        fun readPage(token: Long? = null) {
            try {
                // Health Connect filters series by their start time. Older batches can contain
                // samples beside this workout, so page through them and filter the samples.
                val request =
                    ReadRecordsRequestUsingFilters.Builder(HeartRateRecord::class.java)
                        .setTimeRangeFilter(
                            TimeInstantRangeFilter.Builder()
                                .setStartTime(earliest)
                                .setEndTime(end)
                                .build()
                        )
                        .setPageSize(1000)
                        .apply {
                            if (token == null) setAscending(false) else setPageToken(token)
                        }
                        .build()
                manager.readRecords(
                    request,
                    context.mainExecutor,
                    object :
                        OutcomeReceiver<
                            ReadRecordsResponse<HeartRateRecord>,
                            HealthConnectException,
                        > {
                        override fun onResult(result: ReadRecordsResponse<HeartRateRecord>) {
                            result.records.forEach { record ->
                                record.samples.forEach { sample ->
                                    if (!sample.time.isBefore(earliest)) {
                                        average.add(
                                            sample.time.toEpochMilli(),
                                            sample.beatsPerMinute.toDouble(),
                                        )
                                    }
                                }
                            }
                            if (result.nextPageToken == -1L)
                                callback(Result.success(average.average()))
                            else readPage(result.nextPageToken)
                        }

                        override fun onError(error: HealthConnectException) {
                            callback(Result.failure(error))
                        }
                    },
                )
            } catch (error: RuntimeException) {
                callback(Result.failure(error))
            }
        }
        readPage()
    }
}
