package lofit

import android.app.DatePickerDialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.card.MaterialCardView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal class BodyMeasurementScreens(
    private val context: Context,
    private val ui: LofitViews,
    private val data: () -> AppData,
    private val save: () -> Unit,
    private val refresh: () -> Unit,
    private val openPart: (BodyPart) -> Unit,
) {
    private val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy")
    private val zone
        get() = ZoneId.systemDefault()

    private fun date(time: Long) = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()

    private var draftPart: BodyPart? = null
    private var draftDate = LocalDate.now()
    private val draftValues = mutableMapOf<Side, String>()

    fun saveDraft() =
        Bundle().apply {
            putString("part", draftPart?.name)
            putString("date", draftDate.toString())
            draftValues.forEach { (side, value) -> putString(side.name, value) }
        }

    fun restoreDraft(bundle: Bundle?) {
        if (bundle == null) return
        draftPart = bundle.getString("part")?.let(BodyPart::valueOf)?.takeIf { it.selectable }
        draftDate = bundle.getString("date")?.let(LocalDate::parse) ?: LocalDate.now()
        Side.entries.forEach { side -> bundle.getString(side.name)?.let { draftValues[side] = it } }
    }

    fun overview(): View =
        ui.page("Body measurements") { body ->
            draftPart = null
            draftValues.clear()
            body.addView(
                measurementCard {
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            MuscleMapSide.entries.forEach { side ->
                                addView(
                                    LinearLayout(context).apply {
                                        orientation = LinearLayout.VERTICAL
                                        addView(
                                            ui.metaText(
                                                    if (side == MuscleMapSide.FRONT) "FRONT"
                                                    else "BACK"
                                                )
                                                .apply {
                                                    textSize = 11f
                                                    letterSpacing = .12f
                                                    gravity = Gravity.CENTER
                                                    setPadding(0, ui.dp(4), 0, ui.dp(8))
                                                }
                                        )
                                        addView(
                                            BodyMeasurementMapView(
                                                context,
                                                side,
                                                data().biologicalGender,
                                            ) { part, _ ->
                                                openPart(part)
                                            },
                                            LinearLayout.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ui.dp(330),
                                            ),
                                        )
                                    },
                                    LinearLayout.LayoutParams(
                                        0,
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        1f,
                                    ),
                                )
                            }
                        }
                    )
                }
            )
            BodyPart.tracked.chunked(2).forEach { parts ->
                body.addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        parts.forEachIndexed { index, part ->
                            val latest =
                                part.sides
                                    .mapNotNull { side ->
                                        data()
                                            .bodyMeasurements
                                            .forBodyPart(part, side)
                                            .lastOrNull()
                                            ?.let {
                                                "${if (part.paired) "${side.marker} " else ""}${formatWeight(it.centimeters)}"
                                            }
                                    }
                                    .joinToString("  ·  ")
                                    .let { if (it.isEmpty()) "—" else "$it cm" }
                            addView(
                                measurementCard {
                                    minimumHeight = ui.dp(72)
                                    gravity = Gravity.CENTER_VERTICAL
                                    addView(ui.titleText(part.label).apply { textSize = 16f })
                                    addView(ui.metaText(latest).apply { textSize = 13f })
                                }
                                    .apply {
                                        contentDescription = "Track ${part.label}"
                                        stateDescription = latest
                                        isFocusable = true
                                        setOnClickListener { openPart(part) }
                                    },
                                LinearLayout.LayoutParams(
                                        0,
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        1f,
                                    )
                                    .apply {
                                        if (index > 0) marginStart = ui.dp(10)
                                        topMargin = ui.dp(2)
                                        bottomMargin = ui.dp(8)
                                    },
                            )
                        }
                        if (parts.size == 1)
                            addView(
                                View(context),
                                LinearLayout.LayoutParams(0, 0, 1f).apply {
                                    marginStart = ui.dp(10)
                                },
                            )
                    }
                )
            }
        }

    private fun measurementCard(build: LinearLayout.() -> Unit): View =
        (ui.card(horizontalPadding = 14, build = build) as MaterialCardView).apply {
            radius = ui.dp(12).toFloat()
        }

    fun detail(part: BodyPart): View =
        if (!part.selectable) overview()
        else
            ui.page(part.label) { body ->
                if (draftPart != part) {
                    draftPart = part
                    draftValues.clear()
                    draftDate = LocalDate.now()
                }
                val measurements = data().bodyMeasurements.forBodyPart(part)
                body.addView(
                    measurementCard {
                        addView(ui.titleText("Circumference · cm"))
                        val fields =
                            part.sides.associateWith { side ->
                                addView(
                                    ui.fieldLabel(
                                        if (part.paired) "${side.label} (cm)"
                                        else "Measurement (cm)"
                                    )
                                )
                                ui.decimalField("cm").apply {
                                    contentDescription =
                                        if (part.paired)
                                            "${side.label} ${part.label} in centimeters"
                                        else "${part.label} in centimeters"
                                    setText(draftValues[side].orEmpty())
                                    doAfterTextChanged { draftValues[side] = it.toString() }
                                    addView(this)
                                }
                            }
                        var measuredOn = draftDate
                        val dateButton = ui.smallButton(dateFormat.format(measuredOn)) {}
                        dateButton.contentDescription = "Measurement date"
                        dateButton.setOnClickListener {
                            chooseDate(measuredOn) {
                                measuredOn = it
                                draftDate = it
                                dateButton.text = dateFormat.format(it)
                            }
                        }
                        addView(dateButton, spaced())
                        addView(
                            ui.actionButton("Save measurement") {
                                if (!validate(fields)) return@actionButton
                                val time = measuredOn.atStartOfDay(zone).toInstant().toEpochMilli()
                                fields.forEach { (side, field) ->
                                    parseBodyMeasurement(field.text.toString())?.let { value ->
                                        data()
                                            .bodyMeasurements
                                            .add(
                                                BodyMeasurement(
                                                    bodyPart = part,
                                                    side = side,
                                                    centimeters = value,
                                                    measuredAt = time,
                                                )
                                            )
                                    }
                                }
                                save()
                                draftValues.clear()
                                draftDate = LocalDate.now()
                                refresh()
                            },
                            spaced(),
                        )
                    }
                )
                body.addView(
                    measurementCard {
                        addView(ui.titleText("History · cm"))
                        if (measurements.isEmpty()) addView(ui.emptyText("No measurements yet."))
                        else addView(BodyMeasurementGraphView(context, part, measurements))
                    }
                )
                measurements.asReversed().forEach { measurement ->
                    body.addView(
                        measurementCard {
                            addView(
                                ui.titleText(
                                    "${if (part.paired) "${measurement.side.label} · " else ""}${formatWeight(measurement.centimeters)} cm"
                                )
                            )
                            addView(ui.metaText(dateFormat.format(date(measurement.measuredAt))))
                            addView(
                                ui.horizontalActions(
                                    ui.smallButton("Edit") { edit(measurement) }
                                        .apply {
                                            contentDescription =
                                                "Edit measurement ${measurement.id}"
                                        },
                                    ui.smallButton("Delete") { delete(measurement) }
                                        .apply {
                                            contentDescription =
                                                "Delete measurement ${measurement.id}"
                                        },
                                )
                            )
                        }
                    )
                }
            }

    private fun spaced() =
        LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            .apply { topMargin = ui.dp(12) }

    private fun validate(fields: Map<Side, EditText>): Boolean {
        var valid = true
        fields.values.forEach { field ->
            field.error = null
            if (field.text.isNotBlank() && parseBodyMeasurement(field.text.toString()) == null) {
                field.error = "Enter a positive number"
                valid = false
            }
        }
        if (fields.values.all { it.text.isBlank() }) {
            fields.values.first().error = "Enter a measurement"
            valid = false
        }
        return valid
    }

    private fun chooseDate(current: LocalDate, selected: (LocalDate) -> Unit) {
        DatePickerDialog(
                context,
                { _, year, month, day -> selected(LocalDate.of(year, month + 1, day)) },
                current.year,
                current.monthValue - 1,
                current.dayOfMonth,
            )
            .apply { datePicker.maxDate = System.currentTimeMillis() }
            .show()
    }

    private fun edit(measurement: BodyMeasurement) {
        val field =
            ui.decimalField("cm").apply {
                setText(formatWeight(measurement.centimeters))
                contentDescription = "Edit circumference in centimeters"
            }
        var measuredOn = date(measurement.measuredAt)
        val dateButton = ui.smallButton(dateFormat.format(measuredOn)) {}
        dateButton.contentDescription = "Edit measurement date"
        dateButton.setOnClickListener {
            chooseDate(measuredOn) {
                measuredOn = it
                dateButton.text = dateFormat.format(it)
            }
        }
        val content =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(24), ui.dp(12), ui.dp(24), ui.dp(12))
                addView(field)
                addView(dateButton, spaced())
            }
        val dialog =
            AlertDialog.Builder(context)
                .setTitle("Edit ${measurement.bodyPart.label}")
                .setView(content)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!validate(mapOf(measurement.side to field))) return@setOnClickListener
                val index = data().bodyMeasurements.indexOfFirst { it.id == measurement.id }
                if (index >= 0) {
                    data().bodyMeasurements[index] =
                        measurement.copy(
                            centimeters = parseBodyMeasurement(field.text.toString())!!,
                            measuredAt =
                                if (measuredOn == date(measurement.measuredAt))
                                    measurement.measuredAt
                                else measuredOn.atStartOfDay(zone).toInstant().toEpochMilli(),
                        )
                    save()
                }
                dialog.dismiss()
                refresh()
            }
        }
        dialog.show()
    }

    private fun delete(measurement: BodyMeasurement) {
        AlertDialog.Builder(context)
            .setTitle("Delete measurement?")
            .setMessage(
                "${formatWeight(measurement.centimeters)} cm · ${dateFormat.format(date(measurement.measuredAt))}"
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                data().bodyMeasurements.removeAll { it.id == measurement.id }
                save()
                refresh()
            }
            .show()
    }
}
