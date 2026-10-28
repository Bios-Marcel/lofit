package lofit

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID

/** Foreground BLE discovery and chunked GATT transfer. State stays on the main thread. */
@SuppressLint("MissingPermission") // Activity grants Nearby devices before starting.
class WorkoutBluetooth(private val context: Context) {
    companion object {
        private val SERVICE = UUID.fromString("c2862b80-a1a4-4ff4-8f0c-29a774df1951")
        private val SIZE = UUID.fromString("c2862b81-a1a4-4ff4-8f0c-29a774df1951")
        private val DATA = UUID.fromString("c2862b82-a1a4-4ff4-8f0c-29a774df1951")
        private const val CHUNK = 256
    }

    private val handler = Handler(Looper.getMainLooper())
    private val adapter
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private var generation = 0
    private var server: BluetoothGattServer? = null
    private var client: BluetoothGatt? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertisement: AdvertiseCallback? = null
    private var scanner: BluetoothLeScanner? = null
    private var scan: ScanCallback? = null
    private var timeout: Runnable? = null
    private var onError: (String) -> Unit = {}

    fun stop() {
        generation++
        timeout?.let(handler::removeCallbacks)
        timeout = null
        runCatching { scan?.let { scanner?.stopScan(it) } }
        runCatching { advertisement?.let { advertiser?.stopAdvertising(it) } }
        runCatching { client?.disconnect() }
        runCatching { client?.close() }
        runCatching { server?.close() }
        scan = null
        scanner = null
        advertisement = null
        advertiser = null
        client = null
        server = null
        onError = {}
    }

    private fun fail(message: String) {
        val callback = onError
        stop()
        callback(message)
    }

    private fun dispatch(token: Int, action: () -> Unit) {
        handler.post {
            if (generation == token) {
                try {
                    action()
                } catch (_: RuntimeException) {
                    fail("Bluetooth failed. Try again.")
                }
            }
        }
    }

    private fun deadline(millis: Long, message: String) {
        timeout?.let(handler::removeCallbacks)
        timeout = Runnable { fail(message) }.also { handler.postDelayed(it, millis) }
    }

    fun share(payload: ByteArray, onReady: () -> Unit, error: (String) -> Unit) {
        stop()
        onError = error
        val token = generation
        try {
            val transfer = SharedWorkoutPlan.compressForTransfer(payload)
            val radio = adapter
            if (radio?.isEnabled != true) return fail("Turn on Bluetooth to share")
            advertiser = radio.bluetoothLeAdvertiser
            if (advertiser == null)
                return fail("Bluetooth broadcast is not supported on this device")
            val cursors = mutableMapOf<String, Int>()
            val callback =
                object : BluetoothGattServerCallback() {
                    override fun onServiceAdded(status: Int, service: BluetoothGattService) =
                        dispatch(token) {
                            if (status != BluetoothGatt.GATT_SUCCESS) {
                                fail("Could not share workout")
                            } else {
                                val advertising =
                                    object : AdvertiseCallback() {
                                        override fun onStartSuccess(
                                            settingsInEffect: AdvertiseSettings
                                        ) =
                                            dispatch(token) {
                                                deadline(5 * 60_000L, "Workout sharing ended")
                                                onReady()
                                            }

                                        override fun onStartFailure(errorCode: Int) =
                                            dispatch(token) {
                                                fail("Could not broadcast workout ($errorCode)")
                                            }
                                    }
                                advertisement = advertising
                                advertiser?.startAdvertising(
                                    AdvertiseSettings.Builder()
                                        .setAdvertiseMode(
                                            AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                                        )
                                        .setConnectable(true)
                                        .setTimeout(0)
                                        .build(),
                                    AdvertiseData.Builder()
                                        .addServiceUuid(ParcelUuid(SERVICE))
                                        .build(),
                                    advertising,
                                )
                            }
                        }

                    override fun onConnectionStateChange(
                        device: BluetoothDevice,
                        status: Int,
                        newState: Int,
                    ) =
                        dispatch(token) {
                            if (newState == BluetoothProfile.STATE_DISCONNECTED)
                                cursors.remove(device.address)
                        }

                    override fun onCharacteristicWriteRequest(
                        device: BluetoothDevice,
                        requestId: Int,
                        characteristic: BluetoothGattCharacteristic,
                        preparedWrite: Boolean,
                        responseNeeded: Boolean,
                        offset: Int,
                        value: ByteArray,
                    ) =
                        dispatch(token) {
                            val cursor = if (value.size == 4) ByteBuffer.wrap(value).int else -1
                            val valid =
                                characteristic.uuid == DATA &&
                                    !preparedWrite &&
                                    offset == 0 &&
                                    cursor in 0 until transfer.size
                            if (valid) cursors[device.address] = cursor
                            if (responseNeeded)
                                server?.sendResponse(
                                    device,
                                    requestId,
                                    if (valid) BluetoothGatt.GATT_SUCCESS
                                    else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                                    0,
                                    null,
                                )
                        }

                    override fun onCharacteristicReadRequest(
                        device: BluetoothDevice,
                        requestId: Int,
                        offset: Int,
                        characteristic: BluetoothGattCharacteristic,
                    ) =
                        dispatch(token) {
                            val bytes =
                                when (characteristic.uuid) {
                                    SIZE -> ByteBuffer.allocate(4).putInt(transfer.size).array()
                                    DATA -> {
                                        val cursor = cursors[device.address] ?: 0
                                        transfer.copyOfRange(
                                            cursor,
                                            minOf(cursor + CHUNK, transfer.size),
                                        )
                                    }
                                    else -> null
                                }
                            val valid = bytes != null && offset in 0..bytes.size
                            server?.sendResponse(
                                device,
                                requestId,
                                if (valid) BluetoothGatt.GATT_SUCCESS
                                else BluetoothGatt.GATT_INVALID_OFFSET,
                                offset,
                                if (valid) bytes!!.copyOfRange(offset, bytes.size) else null,
                            )
                        }
                }
            server =
                context
                    .getSystemService(BluetoothManager::class.java)
                    .openGattServer(context, callback)
            val service =
                BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
                    addCharacteristic(
                        BluetoothGattCharacteristic(
                            SIZE,
                            BluetoothGattCharacteristic.PROPERTY_READ,
                            BluetoothGattCharacteristic.PERMISSION_READ,
                        )
                    )
                    addCharacteristic(
                        BluetoothGattCharacteristic(
                            DATA,
                            BluetoothGattCharacteristic.PROPERTY_READ or
                                BluetoothGattCharacteristic.PROPERTY_WRITE,
                            BluetoothGattCharacteristic.PERMISSION_READ or
                                BluetoothGattCharacteristic.PERMISSION_WRITE,
                        )
                    )
                }
            deadline(15_000, "Could not start Bluetooth sharing")
            if (server?.addService(service) != true) fail("Could not start Bluetooth sharing")
        } catch (_: RuntimeException) {
            fail("Could not start Bluetooth sharing")
        }
    }

    fun scan(found: (BluetoothDevice) -> Unit, error: (String) -> Unit) {
        stop()
        onError = error
        val token = generation
        try {
            if (adapter?.isEnabled != true) return fail("Turn on Bluetooth to find workouts")
            scanner = adapter?.bluetoothLeScanner
            if (scanner == null) return fail("Bluetooth scanning is not supported on this device")
            val seen = mutableSetOf<String>()
            val callback =
                object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) =
                        dispatch(token) {
                            if (seen.add(result.device.address)) found(result.device)
                        }

                    override fun onBatchScanResults(results: MutableList<ScanResult>) {
                        results.forEach { onScanResult(0, it) }
                    }

                    override fun onScanFailed(errorCode: Int) =
                        dispatch(token) {
                            fail("Could not scan for workouts ($errorCode)")
                        }
                }
            scan = callback
            deadline(60_000, "Scan ended. Try again to find workouts.")
            scanner?.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback,
            )
        } catch (_: RuntimeException) {
            fail("Could not scan for workouts")
        }
    }

    fun receive(
        device: BluetoothDevice,
        received: (SharedWorkoutPlan) -> Unit,
        error: (String) -> Unit,
    ) {
        stop()
        onError = error
        val token = generation
        var total = 0
        val output = ByteArrayOutputStream()
        try {
            deadline(60_000, "Workout transfer timed out. Try again.")
            val callback =
                object : BluetoothGattCallback() {
                    private fun next(gatt: BluetoothGatt) {
                        val data =
                            gatt.getService(SERVICE)?.getCharacteristic(DATA)
                                ?: return fail("Workout service is missing")
                        val result =
                            gatt.writeCharacteristic(
                                data,
                                ByteBuffer.allocate(4).putInt(output.size()).array(),
                                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                            )
                        if (result != BluetoothStatusCodes.SUCCESS)
                            fail("Could not receive workout")
                    }

                    override fun onConnectionStateChange(
                        gatt: BluetoothGatt,
                        status: Int,
                        newState: Int,
                    ) =
                        dispatch(token) {
                            if (
                                status != BluetoothGatt.GATT_SUCCESS ||
                                    newState == BluetoothProfile.STATE_DISCONNECTED
                            ) {
                                fail("Workout host disconnected. Try again.")
                            } else if (
                                newState == BluetoothProfile.STATE_CONNECTED &&
                                    !gatt.discoverServices()
                            ) {
                                fail("Could not read workout service")
                            }
                        }

                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) =
                        dispatch(token) {
                            val size = gatt.getService(SERVICE)?.getCharacteristic(SIZE)
                            if (
                                status != BluetoothGatt.GATT_SUCCESS ||
                                    size == null ||
                                    !gatt.readCharacteristic(size)
                            ) {
                                fail("Could not read workout service")
                            }
                        }

                    override fun onCharacteristicWrite(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int,
                    ) =
                        dispatch(token) {
                            if (
                                status != BluetoothGatt.GATT_SUCCESS ||
                                    !gatt.readCharacteristic(characteristic)
                            ) {
                                fail("Could not receive workout")
                            }
                        }

                    override fun onCharacteristicRead(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        value: ByteArray,
                        status: Int,
                    ) =
                        dispatch(token) {
                            if (status != BluetoothGatt.GATT_SUCCESS)
                                return@dispatch fail("Could not receive workout")
                            if (characteristic.uuid == SIZE) {
                                if (value.size != 4) return@dispatch fail("Invalid workout plan")
                                total = ByteBuffer.wrap(value).int
                                if (total !in 1..SharedWorkoutPlan.MAX_TRANSFER_BYTES)
                                    return@dispatch fail("Invalid workout plan size")
                                next(gatt)
                            } else if (characteristic.uuid == DATA) {
                                if (value.size != minOf(CHUNK, total - output.size())) {
                                    return@dispatch fail("Incomplete workout plan. Try again.")
                                }
                                output.write(value)
                                if (output.size() == total) {
                                    val plan =
                                        runCatching {
                                            SharedWorkoutPlan.decodeTransfer(output.toByteArray())
                                        }
                                            .getOrNull()
                                            ?: return@dispatch fail("Invalid workout plan")
                                    stop()
                                    received(plan)
                                } else next(gatt)
                            }
                        }
                }
            client = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (client == null) fail("Could not connect to workout host")
        } catch (_: RuntimeException) {
            fail("Could not connect to workout host")
        }
    }
}
