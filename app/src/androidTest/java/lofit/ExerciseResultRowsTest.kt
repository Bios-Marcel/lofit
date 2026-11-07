package lofit

import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExerciseResultRowsTest {
    @Test
    fun matchingRowsKeepTheirViewsAndActionsWhileOrderChanges() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val rows = ExerciseResultRows(context)
            val exercises = DefaultData.exercises().take(3)
            var created = 0
            var clicked: String? = null
            val create: (Exercise) -> TextView = { exercise ->
                created++
                TextView(context).apply {
                    text = exercise.name
                    setOnClickListener { clicked = exercise.id }
                }
            }
            rows.update(exercises, create)
            val retained = rows.getChildAt(1)
            rows.update(listOf(exercises[1], exercises[0]), create)
            assertSame(retained, rows.getChildAt(0))
            assertEquals(3, created)
            assertEquals(2, rows.childCount)
            rows.getChildAt(0).performClick()
            assertEquals(exercises[1].id, clicked)
            rows.update(emptyList(), create)
            assertEquals(0, rows.childCount)
            rows.update(listOf(exercises[2]), create)
            assertEquals(4, created)
            assertEquals(exercises[2].name, (rows.getChildAt(0) as TextView).text)
        }
    }
}
