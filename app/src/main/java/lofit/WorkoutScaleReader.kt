package lofit

import android.os.Handler
import android.os.Looper

/** Owns one foreground scan for all live workout graphs and ignores obsolete callbacks. */
internal class WorkoutScaleReader(
    private val scanner: CraneScaleScanner,
    private val withBluetooth: (() -> Unit) -> Unit,
    private val onReading: (Double) -> Unit,
    private val onInterrupted: () -> Unit,
    private val onStatus: (String?) -> Unit,
    private val staleMillis: Long = 5_000,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var selected: CraneScaleDevice? = null
    private var enabled = false
    private var generation = 0
    private val stale = Runnable {
        onInterrupted()
        onStatus("No signal · waiting for scale…")
    }

    fun update(selected: CraneScaleDevice?, enabled: Boolean) {
        if (this.selected == selected && this.enabled == enabled) return
        this.selected = selected
        this.enabled = enabled
        restart()
    }

    fun retry() = restart()

    private fun restart() {
        generation++
        scanner.stop()
        handler.removeCallbacks(stale)
        onInterrupted()
        if (!enabled) return
        val scale =
            selected
                ?: run {
                    onStatus("No scale selected · select one in Settings")
                    return
                }
        val token = generation
        onStatus("Waiting for scale…")
        withBluetooth {
            if (token != generation || !enabled) return@withBluetooth
            scanner.start(
                scale.address,
                { device, kilograms ->
                    if (
                        token == generation &&
                            device.address == scale.address &&
                            kilograms.isFinite() &&
                            kilograms >= 0
                    ) {
                        handler.removeCallbacks(stale)
                        handler.postDelayed(stale, staleMillis)
                        onStatus(null)
                        onReading(kilograms)
                    }
                },
                { message ->
                    if (token == generation) {
                        generation++
                        scanner.stop()
                        handler.removeCallbacks(stale)
                        onInterrupted()
                        onStatus(message)
                    }
                },
            )
        }
    }
}
