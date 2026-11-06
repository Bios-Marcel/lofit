package lofit

import android.media.AudioDeviceInfo
import org.junit.Assert.*
import org.junit.Test

class VoiceAudioRouteTest {
    private val phone = VoiceAudioDevice(1, AudioDeviceInfo.TYPE_BUILTIN_MIC, "")
    private val speaker = VoiceAudioDevice(2, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "")
    private val sco = VoiceAudioDevice(3, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "headset")
    private val scoMic = sco.copy(id = 4)
    private val ble = VoiceAudioDevice(5, AudioDeviceInfo.TYPE_BLE_HEADSET, "earbuds")
    private val bleMic = ble.copy(id = 6)

    private inner class Backend : VoiceAudioRouteBackend {
        var microphones = listOf(phone)
        var outputs = listOf(speaker)
        var current: VoiceAudioDevice? = speaker
        var selected: VoiceAudioDevice? = null
        var input: VoiceAudioDevice? = null
        var acceptRoute = true
        var acceptInput = true
        var selects = 0
        var clears = 0
        var removals = 0
        lateinit var changed: () -> Unit

        override fun inputs() = microphones

        override fun communicationDevices() = outputs

        override fun currentCommunicationDevice() = current

        override fun selectCommunicationDevice(device: VoiceAudioDevice): Boolean {
            selects++
            if (acceptRoute) selected = device
            return acceptRoute
        }

        override fun clearCommunicationDevice() {
            clears++
            selected = null
        }

        override fun preferInput(device: VoiceAudioDevice?): Boolean {
            if (acceptInput || device == null) {
                input = device
                return true
            }
            return false
        }

        override fun observe(onChanged: () -> Unit): AutoCloseable {
            changed = onChanged
            return AutoCloseable { removals++ }
        }
    }

    @Test
    fun noHeadsetLeavesExistingAudioRouteAlone() {
        val backend = Backend()
        VoiceAudioRoute(backend).use { it.start() }
        assertEquals(0, backend.selects)
        assertEquals(0, backend.clears)
        assertNull(backend.input)
        assertEquals(1, backend.removals)
    }

    @Test
    fun classicAndLeHeadsetsUseMatchingMicrophoneAndReleaseOnClose() {
        for ((headset, microphone) in listOf(sco to scoMic, ble to bleMic)) {
            val backend =
                Backend().apply {
                    outputs += headset
                    microphones += microphone
                }
            val route = VoiceAudioRoute(backend)
            route.start()
            assertEquals(headset, backend.selected)
            assertEquals(microphone, backend.input)
            route.close()
            route.close()
            assertNull(backend.input)
            assertNull(backend.selected)
            assertEquals(1, backend.clears)
            assertEquals(1, backend.removals)
            backend.changed()
            assertEquals(1, backend.selects)
        }
    }

    @Test
    fun connectingAndDisconnectingHeadsetWhileListeningSwitchesInput() {
        val backend = Backend()
        VoiceAudioRoute(backend).use { route ->
            route.start()
            backend.outputs += sco
            backend.microphones += scoMic
            backend.changed()
            assertEquals(scoMic, backend.input)
            backend.outputs = listOf(speaker)
            backend.microphones = listOf(phone)
            backend.changed()
            assertNull(backend.input)
            assertNull(backend.selected)
            assertEquals(1, backend.clears)
            backend.outputs += ble
            backend.microphones += bleMic
            backend.changed()
            assertEquals(bleMic, backend.input)
        }
    }

    @Test
    fun waitsForMatchingInputAfterAsynchronousBluetoothActivation() {
        val backend = Backend().apply { outputs += sco }
        VoiceAudioRoute(backend).use { route ->
            route.start()
            assertEquals(sco, backend.selected)
            assertNull(backend.input)
            backend.microphones += scoMic.copy(address = "another headset")
            backend.changed()
            assertNull(backend.input)
            backend.microphones += scoMic
            backend.changed()
            assertEquals(scoMic, backend.input)
            assertEquals(1, backend.selects)
        }
    }

    @Test
    fun keepsActiveBluetoothHeadsetAndDoesNotSwitchWhileRouteIsPending() {
        val backend =
            Backend().apply {
                outputs += listOf(sco, ble)
                microphones += listOf(scoMic, bleMic)
                current = sco
            }
        VoiceAudioRoute(backend).use { route ->
            route.start()
            assertEquals(scoMic, backend.input)
            backend.current = speaker
            backend.changed()
            assertEquals(scoMic, backend.input)
            assertEquals(1, backend.selects)
        }
    }

    @Test
    fun ignoresPlaybackOnlyBluetoothDevices() {
        val backend =
            Backend().apply {
                outputs += VoiceAudioDevice(8, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "speaker")
            }
        VoiceAudioRoute(backend).use { it.start() }
        assertEquals(0, backend.selects)
        assertNull(backend.input)
    }

    @Test
    fun rejectedRouteKeepsDefaultMicrophoneAndDoesNotClearAnotherAppsRoute() {
        val backend =
            Backend().apply {
                outputs += sco
                microphones += scoMic
                acceptRoute = false
            }
        VoiceAudioRoute(backend).use { it.start() }
        assertNull(backend.input)
        assertNull(backend.selected)
        assertEquals(0, backend.clears)
    }

    @Test
    fun rejectedMicrophoneReleasesHeadsetRoute() {
        val backend =
            Backend().apply {
                outputs += sco
                microphones += scoMic
                acceptInput = false
            }
        VoiceAudioRoute(backend).use { route ->
            route.start()
            repeat(3) { backend.changed() }
            assertEquals(1, backend.selects)
            assertNull(backend.selected)
            assertNull(backend.input)
            assertEquals(1, backend.clears)
            backend.outputs = listOf(speaker)
            backend.changed()
            backend.outputs += sco
            backend.acceptInput = true
            backend.changed()
            assertEquals(scoMic, backend.input)
        }
    }
}
