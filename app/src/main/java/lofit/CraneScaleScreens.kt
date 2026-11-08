package lofit

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/** Only the selected scale is saved. Readings belong to the currently visible live view. */
internal class CraneScaleScreens(
    private val context: Context,
    private val ui: LofitViews,
    private val data: () -> AppData,
    private val save: () -> Unit,
    private val withBluetooth: (() -> Unit) -> Unit,
    private val onRefresh: () -> Unit,
    private val scanner: CraneScaleScanner = BluetoothCraneScaleScanner(context),
    private val staleMillis: Long = 5_000,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var foreground = false
    private var generation = 0
    private var liveRequested = false
    private var weight: TextView? = null
    private var status: TextView? = null
    private var graph: LiveMeasurementGraphView? = null
    private var picker: AlertDialog? = null
    private var pickerTimeout: Runnable? = null
    private val stale = Runnable {
        weight?.text = "—"
        showStatus("No signal · waiting for scale…")
        graph?.endSegment()
    }

    fun settingsCard(): View = ui.card {
        addView(ui.titleText("Crane scale"))
        val selected = data().craneScale
        addView(ui.metaText(selected?.name ?: "No scale selected"))
        selected?.let { addView(ui.metaText(it.address)) }
        val selectScale =
            ui.actionButton(
                if (selected == null) "Select scale" else "Change scale",
                secondary = true,
            ) {
                chooseScale()
            }
        addView(
            if (selected == null) selectScale
            else
                ui.horizontalActions(
                    selectScale,
                    ui.actionButton("Forget scale", secondary = true) {
                        data().craneScale = null
                        save()
                        onRefresh()
                    },
                ),
            buttonLayout(),
        )
    }

    fun liveScreen(): View =
        ui.page("Live Measurement") { body ->
            val selected = data().craneScale
            if (selected == null) {
                body.addView(ui.metaText("No scale selected"))
                body.addView(ui.actionButton("Select scale") { chooseScale() }, buttonLayout())
            } else {
                body.addView(
                    ui.card {
                        addView(ui.titleText(selected.name))
                        addView(ui.metaText(selected.address))
                        weight =
                            ui.titleText("—").apply {
                                textSize = 64f
                                gravity = Gravity.CENTER
                                contentDescription = "Live scale weight"
                                setPadding(0, ui.dp(32), 0, ui.dp(8))
                                addView(this)
                            }
                        addView(ui.metaText("kg").apply { gravity = Gravity.CENTER })
                        status =
                            ui.metaText("Waiting for scale…").apply {
                                gravity = Gravity.CENTER
                                contentDescription = "Scale status"
                                setPadding(0, ui.dp(16), 0, ui.dp(20))
                                addView(this)
                            }
                    }
                )
                body.addView(
                    ui.card {
                        graph = LiveMeasurementGraphView(context).also { addView(it) }
                    }
                )
                body.addView(
                    ui.actionButton("Retry", secondary = true) {
                        stopScan()
                        startLive()
                    },
                    buttonLayout(),
                )
            }
        }

    fun onStart() {
        foreground = true
        startLive()
    }

    fun onStop() {
        foreground = false
        stopScan()
        picker?.dismiss()
    }

    fun clear() {
        stopScan()
        weight = null
        status = null
        graph = null
        picker?.dismiss()
    }

    fun startLive() {
        val selected = data().craneScale ?: return
        if (!foreground || weight == null || liveRequested || picker != null) return
        liveRequested = true
        val token = generation
        showStatus("Bluetooth access needed")
        withBluetooth {
            if (token != generation || !foreground || weight == null) return@withBluetooth
            showStatus("Waiting for scale…")
            scanner.start(
                selected.address,
                { device, kilograms ->
                    if (token == generation && device.address == selected.address) {
                        weight?.text = formatBodyWeight(kilograms)
                        showStatus(null)
                        graph?.addReading(kilograms)
                        handler.removeCallbacks(stale)
                        handler.postDelayed(stale, staleMillis)
                    }
                },
                { message ->
                    if (token == generation) {
                        stopScan()
                        showStatus(message)
                    }
                },
            )
        }
    }

    private fun showStatus(message: String?) {
        status?.apply {
            text = message.orEmpty()
            visibility = if (message == null) View.GONE else View.VISIBLE
        }
    }

    private fun stopScan() {
        generation++
        liveRequested = false
        scanner.stop()
        handler.removeCallbacks(stale)
        pickerTimeout?.let(handler::removeCallbacks)
        pickerTimeout = null
        weight?.text = "—"
        showStatus("Waiting for scale…")
        graph?.endSegment()
    }

    private fun chooseScale() {
        val token = generation
        withBluetooth {
            if (token == generation && foreground) showPicker()
        }
    }

    private fun showPicker() {
        stopScan()
        val body =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8))
            }
        val scanStatus = ui.metaText("Looking for WH-C06 scales…")
        body.addView(scanStatus)
        val devices = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(devices)
        val dialog =
            AlertDialog.Builder(context)
                .setTitle("Select crane scale")
                .setView(android.widget.ScrollView(context).apply { addView(body) })
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Scan again", null)
                .create()
        var selected = false
        picker = dialog
        dialog.setOnDismissListener {
            picker = null
            stopScan()
            if (!selected) startLive()
        }
        dialog.setOnShowListener {
            fun scan() {
                stopScan()
                val token = generation
                scanStatus.text = "Looking for WH-C06 scales…"
                devices.removeAllViews()
                val seen = mutableSetOf<String>()
                scanner.start(
                    null,
                    { device, _ ->
                        if (token == generation && seen.add(device.address)) {
                            scanStatus.text = "Select your scale"
                            devices.addView(
                                ui.actionButton(
                                    "${device.name}\n${device.address}",
                                    secondary = true,
                                ) {
                                    selected = true
                                    data().craneScale = device
                                    save()
                                    dialog.dismiss()
                                    onRefresh()
                                },
                                buttonLayout(),
                            )
                        }
                    },
                    { message ->
                        if (token == generation) {
                            stopScan()
                            scanStatus.text = message
                        }
                    },
                )
                if (token == generation) {
                    pickerTimeout =
                        Runnable {
                            if (token == generation) {
                                stopScan()
                                scanStatus.text =
                                    if (seen.isEmpty())
                                        "No scales found. Turn on your WH-C06 and scan again."
                                    else "Scan ended · select your scale"
                            }
                        }
                            .also { handler.postDelayed(it, 30_000) }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { scan() }
            scan()
        }
        dialog.show()
    }

    private fun buttonLayout() =
        LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            .apply { topMargin = ui.dp(12) }
}
