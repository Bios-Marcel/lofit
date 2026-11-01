package lofit

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import java.util.UUID

internal class ExerciseScreens(
    private val activityContext: Context,
    private val ui: LofitViews,
    private val data: () -> AppData,
    private val save: () -> Unit,
    private val onEdit: (String?) -> Unit,
    private val onDelete: (String) -> Unit,
    private val onChoosePicture: () -> Unit,
    private val onDeletePicture: (String) -> Unit,
    private val onSaved: () -> Unit,
    private val onCancel: () -> Unit,
) {
    private var updatePicturePreview: (() -> Unit)? = null

    fun clearPicturePreview() {
        updatePicturePreview = null
    }

    fun refreshPicturePreview() {
        updatePicturePreview?.invoke()
    }

    fun createDraft(exerciseId: String?): Bundle {
        val existing = data().exercises.firstOrNull { it.id == exerciseId }
        return Bundle().apply {
            putString("exerciseId", exerciseId)
            putString("name", existing?.name.orEmpty())
            putString("primary", existing?.primaryMuscle.orEmpty())
            putString("secondary", existing?.secondaryMuscles.orEmpty())
            putInt("type", existing?.type?.ordinal ?: 0)
            putInt("weight", if (existing?.weighted == true) 1 else 0)
            putInt("tracking", if (existing?.asymmetric == true) 1 else 0)
            putString("imageFile", existing?.imageFile)
        }
    }

    fun exercisesScreen(): View =
        ui.page("Exercise library", "Search built-in and custom movements.") { body ->
            body.addView(
                ui.actionButton("Create custom exercise") {
                    onEdit(null)
                },
                LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    .apply {
                        bottomMargin = ui.dp(16)
                    },
            )
            val search = ui.textField("Search name or muscle")
            val results =
                LinearLayout(activityContext).apply { orientation = LinearLayout.VERTICAL }
            fun updateResults(query: String) {
                results.removeAllViews()
                val allMatches =
                    data().exercises.filter { movement ->
                        matchesSearchQuery(
                            query,
                            movement.name,
                            movement.primaryMuscle,
                            movement.secondaryMuscles,
                            exerciseSearchTerms(movement),
                        )
                    }
                val matches = allMatches.take(40)
                if (allMatches.size > matches.size)
                    results.addView(
                        ui.metaText("Showing 40 of ${allMatches.size}. Search to narrow the list.")
                    )
                matches.forEach { movement ->
                    results.addView(
                        ui.card {
                            addView(ui.exerciseHeading(movement, large = true))
                            addView(
                                ui.metaText(
                                    listOf(
                                            exerciseTrackingLabel(movement),
                                            if (movement.asymmetric) "left / right" else null,
                                        )
                                        .filterNotNull()
                                        .joinToString(" · ")
                                )
                            )
                            addView(
                                ui.metaText(
                                    "Primary: ${movement.primaryMuscle} · Secondary: ${movement.secondaryMuscles}"
                                )
                            )
                            recordFor(movement)?.let { addView(ui.accentText(it)) }
                            if (movement.custom)
                                addView(
                                    ui.horizontalActions(
                                        ui.actionButton("Edit", secondary = true) {
                                            onEdit(movement.id)
                                        },
                                        ui.actionButton("Delete", secondary = true) {
                                            onDelete(movement.id)
                                        },
                                    )
                                )
                        }
                    )
                }
            }
            search.doAfterTextChanged { updateResults(it.toString()) }
            body.addView(search)
            body.addView(results)
            updateResults("")
        }

    fun exerciseEditorScreen(exerciseId: String?, draft: Bundle): View {
        val existing = data().exercises.firstOrNull { it.id == exerciseId }
        return ui.page(
            if (existing == null) "New exercise" else "Edit exercise",
            "Define how this movement is tracked.",
        ) { body ->
            val name = ui.textField("Exercise name").apply { setText(draft.getString("name")) }
            val primary =
                ui.muscleGroupField("Search primary muscle", draft.getString("primary").orEmpty())
            val secondary =
                ui.muscleGroupField(
                    "Search secondary muscles",
                    draft.getString("secondary").orEmpty(),
                    multiple = true,
                )
            val typeGroup =
                ui.choiceGroup(ExerciseType.entries.map { it.label }, draft.getInt("type"))
            fun exerciseOption(label: String, checked: Boolean): CheckBox =
                CheckBox(activityContext).apply {
                    text = label
                    textSize = 15f
                    setTextColor(ui.color(R.color.cream))
                    buttonTintList = ui.toggleTint()
                    buttonDrawable = ui.sizedButtonDrawable(buttonDrawable, 30)
                    isChecked = checked
                    minHeight = ui.dp(48)
                    setPadding(ui.dp(4), ui.dp(4), ui.dp(10), ui.dp(4))
                }
            val weighted = exerciseOption("Weighted", draft.getInt("weight") == 1)
            val asymmetric = exerciseOption("Asymmetrical", draft.getInt("tracking") == 1)
            name.doAfterTextChanged { draft.putString("name", it.toString()) }
            primary.doAfterTextChanged { draft.putString("primary", it.toString()) }
            secondary.doAfterTextChanged { draft.putString("secondary", it.toString()) }
            typeGroup.setOnCheckedChangeListener { _, _ ->
                draft.putInt("type", ui.selectedChoice(typeGroup))
            }
            weighted.setOnCheckedChangeListener { _, checked ->
                draft.putInt("weight", if (checked) 1 else 0)
            }
            asymmetric.setOnCheckedChangeListener { _, checked ->
                draft.putInt("tracking", if (checked) 1 else 0)
            }
            val preview = FrameLayout(activityContext)
            val choosePicture =
                ui.actionButton("Add picture", secondary = true) {
                    onChoosePicture()
                }
            fun pictureButton(label: String, icon: Int, action: () -> Unit) =
                ui.smallButton("", action).apply {
                    contentDescription = label
                    tooltipText = label
                    setIconResource(icon)
                    iconTint = ColorStateList.valueOf(ui.color(R.color.cream))
                    iconSize = ui.dp(20)
                    iconPadding = 0
                    iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                    setPadding(ui.dp(14), 0, ui.dp(14), 0)
                    cornerRadius = ui.dp(24)
                    elevation = ui.dp(4).toFloat()
                }
            val editPicture =
                pictureButton("Change picture", R.drawable.ic_edit) {
                    onChoosePicture()
                }
            val removePicture =
                pictureButton("Remove picture", R.drawable.ic_delete) {
                    draft.getString("imageFile")?.let(onDeletePicture)
                    draft.remove("imageFile")
                    updatePicturePreview?.invoke()
                }
            val pictureActions =
                LinearLayout(activityContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(editPicture, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
                    addView(
                        removePicture,
                        LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply {
                            marginStart = ui.dp(8)
                        },
                    )
                }
            updatePicturePreview = {
                val picture = draft.getString("imageFile")
                val loading = draft.containsKey("pendingPicture")
                preview.removeAllViews()
                if (picture != null) {
                    val movement =
                        (existing
                                ?: Exercise(
                                    "preview",
                                    "Exercise",
                                    ExerciseType.REPETITIONS,
                                    false,
                                    "",
                                    "",
                                ))
                            .copy(imageFile = picture)
                    preview.addView(
                        ui.exerciseThumbnail(movement, ui.dp(180), ui.dp(128)),
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                    preview.addView(
                        pictureActions,
                        FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                Gravity.TOP or Gravity.END,
                            )
                            .apply {
                                topMargin = ui.dp(8)
                                marginEnd = ui.dp(8)
                            },
                    )
                }
                preview.visibility = if (picture == null) View.GONE else View.VISIBLE
                choosePicture.visibility = if (picture == null) View.VISIBLE else View.GONE
                choosePicture.text = if (loading) "Loading picture…" else "Add picture"
                choosePicture.contentDescription = choosePicture.text
                choosePicture.isEnabled = !loading
                editPicture.isEnabled = !loading
                removePicture.isEnabled = !loading
            }
            body.addView(
                preview,
                LinearLayout.LayoutParams(ui.dp(180), ui.dp(128)).apply {
                    bottomMargin = ui.dp(16)
                },
            )
            body.addView(
                choosePicture,
                LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    .apply { bottomMargin = ui.dp(16) },
            )
            updatePicturePreview?.invoke()
            body.addView(ui.fieldLabel("Exercise name"))
            body.addView(name)
            body.addView(ui.fieldLabel("Exercise type"))
            body.addView(typeGroup)
            body.addView(ui.fieldLabel("Modifiers"))
            body.addView(
                LinearLayout(activityContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(
                        weighted,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                    addView(
                        asymmetric,
                        LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            )
                            .apply { marginStart = ui.dp(16) },
                    )
                }
            )
            body.addView(ui.fieldLabel("Primary muscle"))
            body.addView(primary)
            body.addView(ui.fieldLabel("Secondary muscles"))
            body.addView(secondary)
            body.addView(
                ui.actionButton("Save exercise") {
                    if (draft.containsKey("pendingPicture")) {
                        Toast.makeText(
                                activityContext,
                                "Wait for picture to load",
                                Toast.LENGTH_SHORT,
                            )
                            .show()
                        return@actionButton
                    }
                    val primaryMuscle = MuscleGroups.canonical(primary.text.toString())
                    val secondaryMuscles = MuscleGroups.canonicalList(secondary.text.toString())
                    if (name.text.isBlank() || primaryMuscle == null) {
                        Toast.makeText(
                                activityContext,
                                "Add a name and choose a primary muscle",
                                Toast.LENGTH_SHORT,
                            )
                            .show()
                        return@actionButton
                    }
                    if (secondaryMuscles == null) {
                        Toast.makeText(
                                activityContext,
                                "Choose secondary muscles from the list",
                                Toast.LENGTH_SHORT,
                            )
                            .show()
                        return@actionButton
                    }
                    val movement =
                        existing
                            ?: Exercise(
                                id = UUID.randomUUID().toString(),
                                name = name.text.toString().trim(),
                                type = ExerciseType.REPETITIONS,
                                asymmetric = false,
                                primaryMuscle = primaryMuscle,
                                secondaryMuscles = secondaryMuscles,
                                custom = true,
                            )
                    movement.name = name.text.toString().trim()
                    movement.type = ExerciseType.entries[ui.selectedChoice(typeGroup)]
                    movement.weighted = weighted.isChecked
                    movement.asymmetric = asymmetric.isChecked
                    movement.primaryMuscle = primaryMuscle
                    movement.secondaryMuscles = secondaryMuscles
                    val oldPicture = movement.imageFile
                    movement.imageFile = draft.getString("imageFile")
                    if (existing == null) data().exercises.add(movement)
                    save()
                    oldPicture?.takeIf { it != movement.imageFile }?.let(onDeletePicture)
                    onSaved()
                },
                LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    .apply {
                        topMargin = ui.dp(16)
                    },
            )
            body.addView(
                ui.actionButton("Cancel", secondary = true) { onCancel() },
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

    private fun recordFor(exercise: Exercise): String? {
        val set = data().exerciseRecords[exercise.id]?.lastOrNull()?.set ?: return null
        return when {
            exercise.type.tracksDuration && exercise.weighted ->
                "Best: ${set.holdSeconds}s hold · ${formatWeight(set.weightKg)} kg"
            exercise.type.tracksDuration -> "Best: ${set.holdSeconds}s hold"
            exercise.weighted -> "Best: ${formatWeight(set.weightKg)} kg · ${set.reps} reps"
            else -> "Best: ${set.reps} reps"
        }
    }
}
