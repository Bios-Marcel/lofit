package lofit

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.Toast
import androidx.core.content.ContextCompat

internal class SettingsScreen(
    private val activityContext: Context,
    private val ui: LofitViews,
    private val data: () -> AppData,
    private val save: () -> Unit,
    private val hevySettings: HevySettings,
    private val onHealthChanged: (Boolean) -> Unit,
    private val onMcpChanged: () -> Unit,
    private val onMcpPortChange: (Int) -> Boolean,
    private val craneScaleSettings: () -> View,
    private val onRefresh: () -> Unit,
) {
    fun genderChoice(
        selected: BiologicalGender,
        onChange: (BiologicalGender) -> Unit,
    ): View = ui.card {
        addView(ui.titleText("Biological gender"))
        addView(ui.metaText("Only changes appearances in the app."))
        addView(
            ui.choiceGroup(BiologicalGender.entries.map { it.label }, selected.ordinal).apply {
                setOnCheckedChangeListener { group, _ ->
                    onChange(BiologicalGender.entries[ui.selectedChoice(group)])
                }
            }
        )
    }

    fun healthIntegrationChoice(enabled: Boolean, onChange: (Boolean) -> Unit): View = ui.card {
        addView(
            LinearLayout(activityContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    ui.titleText("Health Connect"),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(
                    Switch(activityContext).apply {
                        isChecked = enabled
                        showText = false
                        minWidth = ui.dp(72)
                        minHeight = ui.dp(48)
                        thumbTintList = ColorStateList.valueOf(ui.color(R.color.cream))
                        trackDrawable =
                            ContextCompat.getDrawable(
                                activityContext,
                                R.drawable.switch_track,
                            )
                        trackTintList = ui.toggleTint()
                        contentDescription = "Android health integration"
                        setOnCheckedChangeListener { _, checked -> onChange(checked) }
                    }
                )
            }
        )
        addView(ui.metaText("Save weight and average heart rate with workouts."))
    }

    fun voiceCommandsChoice(enabled: Boolean, onChange: (Boolean) -> Unit): View = ui.card {
        addView(
            LinearLayout(activityContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    ui.titleText("Voice commands"),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = ui.dp(12)
                    },
                )
                addView(
                    Switch(activityContext).apply {
                        isChecked = enabled
                        showText = false
                        minWidth = ui.dp(72)
                        minHeight = ui.dp(48)
                        thumbTintList = ColorStateList.valueOf(ui.color(R.color.cream))
                        trackDrawable =
                            ContextCompat.getDrawable(
                                activityContext,
                                R.drawable.switch_track,
                            )
                        trackTintList = ui.toggleTint()
                        contentDescription = "Voice commands"
                        setOnCheckedChangeListener { _, checked -> onChange(checked) }
                    }
                )
            }
        )
        addView(
            ui.metaText(
                "Say “time” for timers, “skip” to skip rest, or “done” to finish the current set. Say “set fifty times eight” for 50 kg and 8 reps or seconds. Without weight, say “set eight”. Works offline, including with the screen locked."
            )
        )
    }

    fun settingsScreen(): View =
        ui.page("Settings", "Control how Lofit behaves.") { body ->
            body.addView(
                genderChoice(data().biologicalGender) {
                    data().biologicalGender = it
                    save()
                }
            )
            body.addView(
                ui.card {
                    addView(
                        LinearLayout(activityContext).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(
                                LinearLayout(activityContext).apply {
                                    orientation = LinearLayout.VERTICAL
                                    addView(ui.titleText("Sound cues"))
                                    addView(
                                        ui.metaText(
                                            "Play rest and hold sounds. Cues always vibrate."
                                        )
                                    )
                                },
                                LinearLayout.LayoutParams(
                                        0,
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        1f,
                                    )
                                    .apply {
                                        marginEnd = ui.dp(12)
                                    },
                            )
                            addView(
                                Switch(activityContext).apply {
                                    isChecked = data().soundCuesEnabled
                                    showText = false
                                    textOn = "On"
                                    textOff = "Off"
                                    isClickable = true
                                    isFocusable = true
                                    minWidth = ui.dp(72)
                                    textSize = 14f
                                    setTextColor(ui.color(R.color.cream))
                                    thumbTintList = ColorStateList.valueOf(ui.color(R.color.cream))
                                    trackDrawable =
                                        ContextCompat.getDrawable(
                                            activityContext,
                                            R.drawable.switch_track,
                                        )
                                    trackTintList = ui.toggleTint()
                                    contentDescription = "Sound cues"
                                    setOnCheckedChangeListener { _, enabled ->
                                        data().soundCuesEnabled = enabled
                                        save()
                                    }
                                }
                            )
                        }
                    )
                }
            )
            body.addView(
                voiceCommandsChoice(data().voiceCommandsEnabled) {
                    data().voiceCommandsEnabled = it
                    save()
                }
            )
            body.addView(craneScaleSettings())
            body.addView(mcpSettings())
            body.addView(hevySettingsCard())
            body.addView(ui.sectionTitle("Android health integration"))
            body.addView(
                healthIntegrationChoice(
                    data().bodyMetricsEnabled == true,
                    onHealthChanged,
                )
            )
            body.addView(
                ui.actionButton("Manage health access", secondary = true) {
                    try {
                        activityContext.startActivity(
                            Intent("android.health.connect.action.HEALTH_HOME_SETTINGS")
                        )
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(
                                activityContext,
                                "Health Connect unavailable",
                                Toast.LENGTH_SHORT,
                            )
                            .show()
                    } catch (_: SecurityException) {
                        Toast.makeText(
                                activityContext,
                                "Health Connect unavailable",
                                Toast.LENGTH_SHORT,
                            )
                            .show()
                    }
                },
                LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    .apply {
                        bottomMargin = ui.dp(8)
                    },
            )
            body.addView(
                ui.actionButton("Health data privacy", secondary = true) {
                    activityContext.startActivity(
                        Intent(activityContext, HealthPrivacyActivity::class.java)
                    )
                }
            )
        }

    private fun hevySettingsCard(): View = ui.card {
        addView(ui.titleText("Hevy export"))
        addView(
            ui.metaText(
                "Use access and refresh tokens from one Hevy session, or a Hevy Pro API key. Refreshing a browser session may sign it out."
            )
        )
        val initialToken = hevySettings.accessToken
        val initialRefresh = hevySettings.refreshToken
        val initialKey = hevySettings.apiKey
        val tokenField =
            ui.textField("Bearer token").apply {
                setText(initialToken)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                contentDescription = "Hevy bearer token"
            }
        val keyField =
            ui.textField("API key (optional)").apply {
                setText(initialKey)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                contentDescription = "Hevy API key"
            }
        val refreshField =
            ui.textField("Refresh token (for auto renewal)").apply {
                setText(initialRefresh)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                contentDescription = "Hevy refresh token"
            }
        listOf(tokenField, refreshField, keyField).forEach { field ->
            addView(
                field,
                LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    .apply { bottomMargin = ui.dp(10) },
            )
        }
        addView(
            ui.actionButton("Save Hevy tokens", secondary = true) {
                val access = tokenField.text.toString().trim()
                val refresh = refreshField.text.toString().trim()
                val accessChanged =
                    access.replace(Regex("^Bearer\\s+", RegexOption.IGNORE_CASE), "") !=
                        initialToken
                val refreshChanged = refresh != initialRefresh
                if (accessChanged || refreshChanged) {
                    hevySettings.saveTokenPair(
                        access,
                        if (accessChanged && !refreshChanged) "" else refresh,
                    )
                    refreshField.setText(hevySettings.refreshToken)
                }
                hevySettings.apiKey =
                    if (accessChanged && keyField.text.toString() == initialKey) ""
                    else keyField.text.toString()
                keyField.setText(hevySettings.apiKey)
                Toast.makeText(activityContext, "Hevy tokens saved", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun mcpSettings(): View = ui.card {
        addView(
            LinearLayout(activityContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    ui.titleText("MCP server"),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = ui.dp(12)
                    },
                )
                addView(
                    Switch(activityContext).apply {
                        isChecked = data().mcpEnabled
                        showText = false
                        minWidth = ui.dp(72)
                        thumbTintList = ColorStateList.valueOf(ui.color(R.color.cream))
                        trackDrawable =
                            ContextCompat.getDrawable(activityContext, R.drawable.switch_track)
                        trackTintList = ui.toggleTint()
                        contentDescription = "MCP server"
                        setOnCheckedChangeListener { _, enabled ->
                            data().mcpEnabled = enabled
                            save()
                            onMcpChanged()
                            onRefresh()
                        }
                    }
                )
            }
        )
        addView(
            LinearLayout(activityContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    ui.titleText("Port"),
                    LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                        .apply { marginEnd = ui.dp(12) },
                )
                val portField =
                    ui.numberField("Port").apply {
                        setText(data().mcpPort.toString())
                        contentDescription = "MCP port"
                    }
                addView(
                    portField,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = ui.dp(12)
                    },
                )
                addView(
                    ui.actionButton("Apply", secondary = true) {
                        val port = portField.text.toString().toIntOrNull()
                        if (port == null || port !in 1..65535) {
                            portField.error = "Use a port from 1 to 65535"
                        } else if (port != data().mcpPort) {
                            if (!onMcpPortChange(port)) {
                                portField.error = "Port unavailable"
                                return@actionButton
                            }
                            data().mcpPort = port
                            save()
                            onRefresh()
                        }
                    }
                )
            },
            LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                .apply {
                    topMargin = ui.dp(12)
                },
        )
        if (data().mcpEnabled) {
            McpHttpServer.urls(data().mcpPort).forEachIndexed { index, url ->
                addView(
                    ui.metaText(url).apply { setTextIsSelectable(true) },
                    LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                        .apply {
                            if (index == 0) topMargin = ui.dp(10)
                        },
                )
            }
        }
    }
}
