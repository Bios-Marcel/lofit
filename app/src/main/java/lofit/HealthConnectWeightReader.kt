package lofit

import android.content.Context
import android.content.pm.PackageManager
import android.health.connect.HealthConnectException
import android.health.connect.HealthConnectManager
import android.health.connect.HealthPermissions
import android.health.connect.ReadRecordsRequestUsingFilters
import android.health.connect.ReadRecordsResponse
import android.health.connect.TimeInstantRangeFilter
import android.health.connect.datatypes.WeightRecord
import android.os.OutcomeReceiver
import java.time.Duration
import java.time.Instant

class HealthConnectWeightReader(private val context: Context) {
    val available: Boolean
        get() = context.getSystemService(HealthConnectManager::class.java) != null

    val canReadWeight: Boolean
        get() = hasPermission(HealthPermissions.READ_WEIGHT)

    private fun hasPermission(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun readAt(time: Long, callback: (Result<Double?>) -> Unit) {
        val instant = Instant.ofEpochMilli(time)
        // Without history access, stay within the default 30-day read window.
        val earliest = if (hasPermission(HealthPermissions.READ_HEALTH_DATA_HISTORY)) {
            Instant.EPOCH
        } else {
            Instant.now().minus(Duration.ofDays(30))
        }
        val split = instant.plusMillis(1)
        readNearest(earliest, split, ascending = false) { before ->
            before.fold(onSuccess = { previous ->
                if (previous?.time == time) {
                    callback(Result.success(previous.kilograms))
                } else {
                    readNearest(split, Instant.ofEpochMilli(Long.MAX_VALUE), ascending = true) { after ->
                        callback(after.map { next -> bodyWeightAt(time, listOfNotNull(previous, next)) })
                    }
                }
            }, onFailure = { callback(Result.failure(it)) })
        }
    }

    private fun readNearest(
        start: Instant,
        end: Instant,
        ascending: Boolean,
        callback: (Result<BodyWeightMeasurement?>) -> Unit,
    ) {
        if (start >= end) {
            callback(Result.success(null))
            return
        }
        try {
            val manager = context.getSystemService(HealthConnectManager::class.java)
            if (manager == null || !canReadWeight) {
                callback(Result.failure(IllegalStateException("Health Connect weight access unavailable")))
                return
            }
            val request = ReadRecordsRequestUsingFilters.Builder(WeightRecord::class.java)
                .setTimeRangeFilter(TimeInstantRangeFilter.Builder().setStartTime(start).setEndTime(end).build())
                .setAscending(ascending)
                .setPageSize(1)
                .build()
            manager.readRecords(request, context.mainExecutor,
                object : OutcomeReceiver<ReadRecordsResponse<WeightRecord>, HealthConnectException> {
                    override fun onResult(result: ReadRecordsResponse<WeightRecord>) {
                        callback(Result.success(result.records.firstOrNull()?.let {
                            BodyWeightMeasurement(it.time.toEpochMilli(), it.weight.inGrams / 1000)
                        }))
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
}
