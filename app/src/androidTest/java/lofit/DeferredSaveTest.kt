package lofit

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeferredSaveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = RepositoryTestContext("deferred-save")

    @Before fun clearData() = context.clearRepositoryTestData()

    @Test
    fun editsCoalesceAndFlushPersistsLatestNestedValues() {
        val repository = LofitRepository(context)
        val set = PlannedSet(weightKg = 10.0)
        repository.data.activeWorkout =
            WorkoutSession(
                name = "Initial",
                exercises =
                    mutableListOf(
                        WorkoutExercise(
                            exerciseId = "bench-press",
                            plannedSets = mutableListOf(set),
                        )
                    ),
            )
        repository.save()
        val original = context.repositoryStateFile.readText()
        instrumentation.runOnMainSync {
            repository.data.activeWorkout!!.name = "First edit"
            repository.saveDeferred()
            repository.data.activeWorkout!!.name = "Latest edit"
            set.weightKg = 25.0
            repository.saveDeferred()
            assertEquals(original, context.repositoryStateFile.readText())
            repository.flushPendingSave()
        }
        val loaded = LofitRepository(context).data.activeWorkout!!
        assertEquals("Latest edit", loaded.name)
        assertEquals(25.0, loaded.exercises.single().plannedSets.single().weightKg, 0.0)
    }

    @Test
    fun immediateSaveAndResetSupersedePendingEdits() {
        val repository = LofitRepository(context)
        instrumentation.runOnMainSync {
            repository.data.activeWorkout = WorkoutSession(name = "Pending")
            repository.saveDeferred()
            repository.data.activeWorkout = null
            repository.save()
            repository.flushPendingSave()
        }
        assertTrue(JSONObject(context.repositoryStateFile.readText()).isNull("activeWorkout"))
        instrumentation.runOnMainSync {
            repository.data.activeWorkout = WorkoutSession(name = "Discarded")
            repository.saveDeferred()
            repository.reset()
            repository.flushPendingSave()
        }
        assertNull(LofitRepository(context).data.activeWorkout)
    }

    @Test
    fun quietEditsPersistWithoutLeavingScreen() {
        val repository = LofitRepository(context)
        val saved = CountDownLatch(1)
        val handler = Handler(Looper.getMainLooper())
        val checkSaved =
            object : Runnable {
                override fun run() {
                    val name = runCatching {
                        JSONObject(context.repositoryStateFile.readText())
                            .getJSONObject("activeWorkout")
                            .getString("name")
                    }
                        .getOrNull()
                    if (name == "Quiet edit") saved.countDown() else handler.postDelayed(this, 16)
                }
            }
        try {
            instrumentation.runOnMainSync {
                repository.data.activeWorkout = WorkoutSession(name = "Quiet edit")
                repository.saveDeferred()
                handler.post(checkSaved)
            }
            assertTrue("Deferred edit reached disk", saved.await(5, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync {
                handler.removeCallbacks(checkSaved)
                repository.flushPendingSave()
            }
        }
    }
}
