package lofit

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Handler
import android.os.Looper
import android.util.Log

internal data class VoiceAudioDevice(val id: Int, val type: Int, val address: String)

internal interface VoiceAudioRouteBackend {
    fun inputs(): List<VoiceAudioDevice>

    fun communicationDevices(): List<VoiceAudioDevice>

    fun currentCommunicationDevice(): VoiceAudioDevice?

    fun selectCommunicationDevice(device: VoiceAudioDevice): Boolean

    fun clearCommunicationDevice()

    fun preferInput(device: VoiceAudioDevice?): Boolean

    fun observe(onChanged: () -> Unit): AutoCloseable
}

/** Owns the headset route only while this voice recording is alive. */
internal class VoiceAudioRoute(private val backend: VoiceAudioRouteBackend) : AutoCloseable {
    constructor(
        context: Context,
        recorder: AudioRecord,
    ) : this(AndroidVoiceAudioRoute(context, recorder))

    private var observation: AutoCloseable? = null
    private var requested: VoiceAudioDevice? = null
    private var preferred: VoiceAudioDevice? = null
    private val rejected = mutableSetOf<Int>()
    private var closed = false

    @Synchronized
    fun start() {
        if (closed || observation != null) return
        try {
            observation = backend.observe(::refresh)
            refresh()
        } catch (error: RuntimeException) {
            Log.w("TimerVoice", "Headset routing unavailable; using default microphone", error)
            useDefaultInput()
        }
    }

    @Synchronized
    private fun refresh() {
        if (closed) return
        try {
            val connected = backend.communicationDevices().filter { it.isBluetoothHeadset() }
            rejected.retainAll(connected.map { it.id }.toSet())
            val available = connected.filter { it.id !in rejected }
            val current = backend.currentCommunicationDevice()
            val headset =
                available.firstOrNull { it.id == requested?.id }
                    ?: available.firstOrNull { it.id == current?.id }
                    ?: available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                    ?: available.firstOrNull()
            if (headset == null) {
                useDefaultInput()
                return
            }
            if (requested?.id != headset.id) {
                useDefaultInput()
                if (!backend.selectCommunicationDevice(headset)) {
                    rejected.add(headset.id)
                    refresh()
                    return
                }
                requested = headset
            }
            // SCO activation is asynchronous. Device callbacks pick up the input when it appears.
            val input =
                backend.inputs().firstOrNull {
                    it.type == headset.type && it.address == headset.address
                }
            if (input != preferred) {
                if (backend.preferInput(input)) preferred = input
                else {
                    // Avoid a select/clear callback loop for an unusable headset.
                    rejected.add(headset.id)
                    useDefaultInput()
                }
            }
        } catch (error: RuntimeException) {
            Log.w("TimerVoice", "Headset routing failed; using default microphone", error)
            requested?.let { rejected.add(it.id) }
            useDefaultInput()
        }
    }

    private fun useDefaultInput() {
        // LEGACY: Keep the system microphone route when no usable Bluetooth headset is present.
        if (preferred != null) {
            runCatching { backend.preferInput(null) }
            preferred = null
        }
        if (requested != null) {
            requested = null
            runCatching { backend.clearCommunicationDevice() }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { observation?.close() }
        observation = null
        useDefaultInput()
    }

    private fun VoiceAudioDevice.isBluetoothHeadset() =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET
}

private class AndroidVoiceAudioRoute(context: Context, private val recorder: AudioRecord) :
    VoiceAudioRouteBackend {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private fun AudioDeviceInfo.device() = VoiceAudioDevice(id, type, address)

    override fun inputs() = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).map { it.device() }

    override fun communicationDevices() = audio.availableCommunicationDevices.map { it.device() }

    override fun currentCommunicationDevice() = audio.communicationDevice?.device()

    override fun selectCommunicationDevice(device: VoiceAudioDevice): Boolean {
        val output =
            audio.availableCommunicationDevices.firstOrNull { it.id == device.id } ?: return false
        return audio.setCommunicationDevice(output)
    }

    override fun clearCommunicationDevice() = audio.clearCommunicationDevice()

    override fun preferInput(device: VoiceAudioDevice?): Boolean {
        val input = device?.let { wanted ->
            audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == wanted.id }
                ?: return false
        }
        return recorder.setPreferredDevice(input)
    }

    override fun observe(onChanged: () -> Unit): AutoCloseable {
        val devices =
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) = onChanged()

                override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) =
                    onChanged()
            }
        val communication = AudioManager.OnCommunicationDeviceChangedListener { onChanged() }
        audio.registerAudioDeviceCallback(devices, main)
        try {
            audio.addOnCommunicationDeviceChangedListener({ main.post(it) }, communication)
        } catch (error: RuntimeException) {
            audio.unregisterAudioDeviceCallback(devices)
            throw error
        }
        return AutoCloseable {
            try {
                audio.unregisterAudioDeviceCallback(devices)
            } finally {
                audio.removeOnCommunicationDeviceChangedListener(communication)
            }
        }
    }
}
