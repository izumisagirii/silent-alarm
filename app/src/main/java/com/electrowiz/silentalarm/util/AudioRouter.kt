package com.electrowiz.silentalarm.util

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.electrowiz.silentalarm.data.NoEarphoneAction

/**
 * Inspects the audio output device topology and resolves the correct playback
 * action based on user preferences. No Android lifecycle dependencies.
 */
class AudioRouter(private val audioManager: AudioManager) {

    companion object {
        /** Device types considered "earphone" for routing purposes. */
        @SuppressLint("InlinedApi")
        private val EARPHONE_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET
        )
    }

    /** Result of querying output devices. */
    enum class AudioOutputType { EARPHONES_AVAILABLE, SPEAKER_ONLY }

    /** Final action the alarm service should perform. */
    enum class ResolvedAction { PLAY_VIA_EARPHONES, PLAY_VIA_SPEAKER, VIBRATE_ONLY }

    /** All routing information obtained from a single device query. */
    data class AudioRoute(
        val outputType: AudioOutputType,
        val earphoneDevice: AudioDeviceInfo?,
        val speakerDevice: AudioDeviceInfo?
    )

    /** Query [AudioManager] once and return the complete routing picture. */
    fun inspectRoute(): AudioRoute {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val earphoneDevice = devices.firstOrNull { it.type in EARPHONE_TYPES }
        val speakerDevice = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val outputType = if (earphoneDevice != null) {
            AudioOutputType.EARPHONES_AVAILABLE
        } else {
            AudioOutputType.SPEAKER_ONLY
        }
        return AudioRoute(outputType, earphoneDevice, speakerDevice)
    }

    /** Given hardware state and user preference, decide what to do. */
    fun resolveAction(
        outputType: AudioOutputType,
        noEarphoneAction: NoEarphoneAction
    ): ResolvedAction = when (outputType) {
        AudioOutputType.EARPHONES_AVAILABLE -> ResolvedAction.PLAY_VIA_EARPHONES
        AudioOutputType.SPEAKER_ONLY -> when (noEarphoneAction) {
            NoEarphoneAction.VIBRATE_ONLY -> ResolvedAction.VIBRATE_ONLY
            NoEarphoneAction.LOUDSPEAKER -> ResolvedAction.PLAY_VIA_SPEAKER
        }
    }
}
