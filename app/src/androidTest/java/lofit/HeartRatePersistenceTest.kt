package lofit

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeartRatePersistenceTest {
    private val context = RepositoryTestContext("heart-rate")

    @Before fun clear() = context.clearRepositoryTestData()

    @Test
    fun averagesAndMissingDataSurviveReloadAndOldHistoryStillLoads() {
        LofitRepository(context).apply {
            data.history.add(
                WorkoutSession(name = "Measured", completedAt = 100, averageHeartRateBpm = 123.75)
            )
            data.history.add(WorkoutSession(name = "Missing", completedAt = 200))
            save()
            saveHistory()
        }
        val loaded = LofitRepository(context).data.history
        assertEquals(123.75, loaded[0].averageHeartRateBpm!!, 0.0)
        assertNull(loaded[1].averageHeartRateBpm)
        val history = JSONArray(context.repositoryHistoryFile.readText())
        history.getJSONObject(0).remove("averageHeartRateBpm")
        context.repositoryHistoryFile.writeText(history.toString())
        val legacy = LofitRepository(context).data.history
        assertEquals(2, legacy.size)
        assertNull(legacy[0].averageHeartRateBpm)
    }
}
