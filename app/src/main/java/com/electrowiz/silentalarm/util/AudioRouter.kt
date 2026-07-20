package com.electrowiz.silentalarm.util

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import com.electrowiz.silentalarm.data.NoEarphoneAction

/**
 * Inspects the audio output device topology and resolves the correct playback
 * action based on user preferences. No Android lifecycle dependencies.
 */
class AudioRouter(private val audioManager: AudioManager) {

    companion object {
        private const val TAG = "AudioRouter"

        /** Device types considered "earphone" for routing purposes. */
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

    /** Query [AudioManager] for the current output topology. */
    fun detectOutputType(): AudioOutputType {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (d in devices) {
            if (d.type in EARPHONE_TYPES) {
                Log.d(TAG, "Earphone found: type=${d.type} name=${d.productName}")
                return AudioOutputType.EARPHONES_AVAILABLE
            }
        }
        return AudioOutputType.SPEAKER_ONLY
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

    /** Find the first earphone-type device among active outputs, or null. */
    fun findEarphoneDevice(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type in EARPHONE_TYPES }

    /** Find the built-in speaker, or null. */
    fun findSpeakerDevice(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
}
