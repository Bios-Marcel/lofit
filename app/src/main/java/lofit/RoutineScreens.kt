package lofit

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import java.util.UUID

internal class RoutineScreens(
    private val activityContext: Context,
    private val ui: LofitViews,
    private val sets: PlannedSetViews,
    private val data: () -> AppData,
    private val muscleMaps: (List<Pair<String, Int>>) -> View,
    private val onEdit: (Routine?) -> Unit,
    private val onStart: (Routine) -> Unit,
    private val onDelete: (String) -> Unit,
    private val onAddExercise: () -> Unit,
    private val onSelectExercise: (String, ExercisePlacement) -> Unit,
    private val onSave: (String?) -> Unit,
    private val onCancel: (String?) -> Unit,
) {
    fun routinesScreen(): View =
        ui.page("Routines", "Prescribe each movement before you train.") { body ->
            body.addView(
                ui.actionButton("Create routine") { onEdit(null) },
                LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    .apply {
                        bottomMargin = ui.dp(16)
                    },
            )
            if (data().routines.isEmpty()) body.addView(ui.emptyText("No routines saved."))
            data().routines.forEach { routine ->
                body.addView(
                    ui.card {
                        addView(ui.titleText(routine.name))
                        addView(ui.metaText("Default rest ${routine.defaultRestSeconds}s"))
                        addView(
                            muscleMaps(
                                routine.exercises.map { it.exerciseId to it.plannedSets.size }
                            )
                        )
                        addView(
                            ui.horizontalActions(
                                ui.actionButton("Start") { onStart(routine) },
                                ui.actionButton("Edit", secondary = true) {
                                    onEdit(routine)
                                },
                                ui.actionButton("Delete", secondary = true) {
                                    onDelete(routine.id)
                                },
                            )
                        )
                    }
                )
            }
        }

    fun routineEditorScreen(draft: Routine?, originalId: String?): View {
        if (draft == null) return routinesScreen()
        return ui.page(
            if (originalId == null) "New routine" else "Edit routine",
            if (originalId == null) null else "Set the exact work you intend to do.",
        ) { body ->
            val name =
                ui.textField("Routine name").apply {
                    setText(draft.name)
                    doAfterTextChanged { draft.name = it.toString() }
                }
            body.addView(ui.fieldLabel("Routine name"))
            body.addView(name)
            body.addView(
                ui.restInput(draft.defaultRestSeconds) { seconds ->
                    draft.defaultRestSeconds = seconds
                }
            )
            body.addView(ui.sectionTitle("Exercises"))
            val exerciseCards =
                LinearLayout(activityContext).apply { orientation = LinearLayout.VERTICAL }
            val cardViews = mutableMapOf<String, View>()
            val reorder =
                ExerciseReorderController(exerciseCards) { from, to ->
                    draft.exercises.add(to, draft.exercises.removeAt(from))
                }
            fun removeExercise(item: RoutineExercise) {
                draft.exercises.remove(item)
                cardViews.remove(item.id)?.let(reorder::remove)
                if (draft.exercises.isEmpty()) {
                    exerciseCards.addView(
                        ui.emptyText(
                            "Add exercises, then specify sets and repetitions or hold time."
                        )
                    )
                }
            }
            if (draft.exercises.isEmpty()) {
                exerciseCards.addView(
                    ui.emptyText("Add exercises, then specify sets and repetitions or hold time.")
                )
            }
            body.addView(exerciseCards)
            draft.exercises.forEach { item ->
                val movement =
                    data().exercises.firstOrNull { it.id == item.exerciseId } ?: return@forEach
                lateinit var handle: View
                val exerciseCard =
                    ui.card(horizontalPadding = 0) {
                        addView(
                            ui.exerciseHeading(
                                movement,
                                large = true,
                                actions =
                                    ui.exerciseActions(
                                        exercise = movement,
                                        onRemove = { removeExercise(item) },
                                        onReorderHandleReady = { handle = it },
                                        onSelectExercise = { placement ->
                                            onSelectExercise(item.id, placement)
                                        },
                                    ),
                            ),
                            ui.insetCardLayoutParams(),
                        )
                        addView(
                            ui.exerciseNote(item.note, movement) { item.note = it },
                            ui.insetCardLayoutParams().apply {
                                topMargin = ui.dp(8)
                                bottomMargin = ui.dp(8)
                            },
                        )
                        if (movement.supportsLiveMeasurement) {
                            addView(
                                ui.choiceGroup(
                                        listOf("use scale", "Manual"),
                                        if (item.manualMeasurement) 1 else 0,
                                    )
                                    .apply {
                                        contentDescription = "${movement.name} measurement mode"
                                        setOnCheckedChangeListener { _, _ ->
                                            item.manualMeasurement = ui.selectedChoice(this) == 1
                                        }
                                    },
                                ui.insetCardLayoutParams().apply { bottomMargin = ui.dp(8) },
                            )
                        }
                        val setRows =
                            LinearLayout(activityContext).apply {
                                orientation = LinearLayout.VERTICAL
                            }
                        val rowViews = mutableMapOf<String, View>()
                        val updateRowIndices = mutableMapOf<String, (Int) -> Unit>()
                        fun removeRoutineSets(fromIndex: Int, allBelow: Boolean) {
                            val endIndex = if (allBelow) item.plannedSets.size else fromIndex + 1
                            val removedSets = item.plannedSets.subList(fromIndex, endIndex).toList()
                            item.plannedSets.subList(fromIndex, endIndex).clear()
                            removedSets.forEach { removedSet ->
                                rowViews.remove(removedSet.id)?.let(setRows::removeView)
                                updateRowIndices.remove(removedSet.id)
                            }
                            item.plannedSets.forEachIndexed { setIndex, set ->
                                updateRowIndices[set.id]?.invoke(setIndex)
                            }
                        }
                        fun addRoutineSetRow(plannedSet: PlannedSet, setIndex: Int) {
                            val row =
                                sets.plannedSetEditor(
                                    movement,
                                    plannedSet,
                                    setIndex,
                                    onRemove = {
                                        item.plannedSets
                                            .indexOf(plannedSet)
                                            .takeIf { it >= 0 }
                                            ?.let { removeRoutineSets(it, false) }
                                    },
                                    onRemoveAndBelow = {
                                        item.plannedSets
                                            .indexOf(plannedSet)
                                            .takeIf { it >= 0 }
                                            ?.let { removeRoutineSets(it, true) }
                                    },
                                    onIndexUpdaterReady = { updater ->
                                        updateRowIndices[plannedSet.id] = updater
                                    },
                                )
                            rowViews[plannedSet.id] = row
                            setRows.addView(row)
                        }
                        setRows.addView(sets.plannedSetHeader(movement))
                        item.plannedSets.forEachIndexed { setIndex, plannedSet ->
                            addRoutineSetRow(plannedSet, setIndex)
                        }
                        addView(setRows)
                        addView(
                            ui.actionButton("Add set", secondary = true) {
                                val next =
                                    item.plannedSets
                                        .lastOrNull()
                                        ?.copy(
                                            id = UUID.randomUUID().toString(),
                                            actualHoldSeconds = null,
                                        ) ?: PlannedSet(holdSeconds = movement.minimumHoldSeconds)
                                item.plannedSets.add(next)
                                addRoutineSetRow(next, item.plannedSets.lastIndex)
                            }
                        )
                        addView(
                            ui.restOverrideInput(item.restSecondsOverride) { seconds ->
                                item.restSecondsOverride = seconds
                            },
                            ui.insetCardLayoutParams(),
                        )
                    }
                cardViews[item.id] = exerciseCard
                exerciseCard.tag = item.id
                exerciseCards.addView(exerciseCard)
                reorder.bind(handle, exerciseCard)
            }
            body.addView(
                ui.actionButton("Add Exercise") {
                    onAddExercise()
                }
            )
            body.addView(ui.divider("Routine actions separator"))
            body.addView(
                ui.actionButton("Save routine") {
                    if (draft.name.isBlank()) {
                        Toast.makeText(
                                activityContext,
                                "Give the routine a name",
                                Toast.LENGTH_SHORT,
                            )
                            .show()
                    } else {
                        onSave(originalId)
                    }
                }
            )
            body.addView(
                ui.actionButton("Cancel", secondary = true) { onCancel(originalId) },
                LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    .apply {
                        topMargin = ui.dp(16)
                    },
            )
        }
    }
}
