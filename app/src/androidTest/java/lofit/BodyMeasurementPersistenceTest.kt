package lofit

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BodyMeasurementPersistenceTest {
    private val context = RepositoryTestContext("body-measurements")

    @Before fun clear() = context.clearRepositoryTestData()

    @Test
    fun everyBodyPartAndSideSurvivesSaveEditDeleteAndReset() {
        val repository = LofitRepository(context)
        val entries =
            BodyPart.entries.flatMap { part ->
                part.sides.map { side ->
                    BodyMeasurement(
                        bodyPart = part,
                        side = side,
                        centimeters = 35.75,
                        measuredAt = 123456,
                    )
                }
            }
        repository.data.bodyMeasurements.addAll(entries)
        repository.save()
        val loaded = LofitRepository(context)
        assertEquals(entries, loaded.data.bodyMeasurements)
        loaded.data.bodyMeasurements[0] = entries[0].copy(centimeters = 34.5, measuredAt = 654321)
        loaded.data.bodyMeasurements.removeAt(1)
        loaded.save()
        assertEquals(loaded.data.bodyMeasurements, LofitRepository(context).data.bodyMeasurements)
        loaded.reset()
        assertTrue(LofitRepository(context).data.bodyMeasurements.isEmpty())
    }

    @Test
    fun oldFilesWithoutMeasurementsLoadAsEmpty() {
        LofitRepository(context).save()
        val root = JSONObject(context.repositoryStateFile.readText())
        root.remove("bodyMeasurements")
        context.repositoryStateFile.writeText(root.toString())
        assertTrue(LofitRepository(context).data.bodyMeasurements.isEmpty())
    }
}
