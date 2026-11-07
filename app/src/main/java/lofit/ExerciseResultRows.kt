package lofit

import android.content.Context
import android.view.View
import android.widget.LinearLayout

/** Retain matching cards while filtering; keep at most the currently displayed rows. */
internal class ExerciseResultRows(context: Context) : LinearLayout(context) {
    private var rows = emptyMap<String, View>()

    init {
        orientation = VERTICAL
    }

    fun update(exercises: List<Exercise>, createRow: (Exercise) -> View) {
        val next = exercises.associate { exercise ->
            exercise.id to (rows[exercise.id] ?: createRow(exercise))
        }
        rows.forEach { (id, view) -> if (id !in next) removeView(view) }
        next.values.forEachIndexed { index, view ->
            if (getChildAt(index) !== view) {
                if (view.parent === this) removeView(view)
                addView(view, index)
            }
        }
        rows = next
    }
}
