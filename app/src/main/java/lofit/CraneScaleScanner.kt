package lofit

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper

internal interface CraneScaleScanner {
    fun start(
        address: String?,
        onReading: (CraneScaleDevice, Double) -> Unit,
        onError: (String) -> Unit,
    )

    fun stop()
}

/** Reads every matching advertisement. WH-C06 does not expose a GATT measurement service. */
@SuppressLint("MissingPermission") // Activity grants Nearby devices before starting.
internal class BluetoothCraneScaleScanner(context: Context) : CraneScaleScanner {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null
    private var generation = 0

    override fun start(
        address: String?,
        onReading: (CraneScaleDevice, Double) -> Unit,
        onError: (String) -> Unit,
    ) {
        stop()
        val token = generation
        fun fail(message: String) {
            if (token != generation) return
            stop()
            onError(message)
        }
        try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter?.isEnabled != true) return fail("Turn on Bluetooth")
            scanner = adapter.bluetoothLeScanner
            if (scanner == null) return fail("Bluetooth scanning unavailable")
            val listener =
                object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        handler.post {
                            if (token != generation) return@post
                            val record = result.scanRecord ?: return@post
                            val kilograms =
                                WhC06.kilograms(
                                    record.getManufacturerSpecificData(WhC06.MANUFACTURER_ID)
                                ) ?: return@post
                            val deviceAddress = result.device.address
                            if (address != null && deviceAddress != address) return@post
                            onReading(
                                CraneScaleDevice(deviceAddress, record.deviceName ?: "WH-C06"),
                                kilograms,
                            )
                        }
                    }

                    override fun onBatchScanResults(results: MutableList<ScanResult>) {
                        results.forEach { onScanResult(0, it) }
                    }

                    override fun onScanFailed(errorCode: Int) {
                        handler.post { fail("Could not scan for scales ($errorCode). Try again.") }
                    }
                }
            callback = listener
            val filter =
                ScanFilter.Builder()
                    .setManufacturerData(WhC06.MANUFACTURER_ID, byteArrayOf())
                    .apply { if (address != null) setDeviceAddress(address) }
                    .build()
            scanner?.startScan(
                listOf(filter),
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setReportDelay(0)
                    .build(),
                listener,
            )
        } catch (_: RuntimeException) {
            fail("Could not scan for scales. Check Nearby devices access and Bluetooth.")
        }
    }

    override fun stop() {
        generation++
        runCatching { callback?.let { scanner?.stopScan(it) } }
        callback = null
        scanner = null
    }
}
