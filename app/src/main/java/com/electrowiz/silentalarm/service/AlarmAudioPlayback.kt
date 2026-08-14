package com.electrowiz.silentalarm.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibrationAttributes
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.electrowiz.silentalarm.R
import com.electrowiz.silentalarm.data.NoEarphoneAction
import com.electrowiz.silentalarm.data.TimeoutAction
import com.electrowiz.silentalarm.util.AudioRouter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

private const val TAG = "AlarmAudioService"

internal suspend fun AlarmAudioService.executeAlarmRoutine() {
    val route = audioRouter.inspectRoute()
    val settings = preferences.snapshot()
    val action = audioRouter.resolveAction(route.outputType, settings.noEarphoneAction)
    val uri = resolveRingtoneUri(settings.globalRingtoneUri)

    Log.i(
        TAG,
        "Routing: output=${route.outputType} action=$action " +
            "earVol=${settings.earphoneVolume} spkVol=${settings.speakerVolume} " +
            "timeout=${settings.timeoutSeconds} s"
    )

    // Keep the ringing notification in sync with the actual playback mode.
    activeAction = action
    postActiveNotification(action)

    when (action) {
        AudioRouter.ResolvedAction.PLAY_VIA_EARPHONES -> {
            playAudio(
                ringtoneUri = uri,
                volumePercent = settings.earphoneVolume,
                preferredDevice = route.earphoneDevice,
                useAlarmAudio = false
            )
            registerAudioBecomingNoisy()
            armAutoStop(settings.timeoutSeconds) {
                Log.i(
                    TAG,
                    "Earphone timeout reached: ${settings.timeoutSeconds} s, " +
                        "action=${settings.timeoutAction}"
                )
                when (settings.timeoutAction) {
                    TimeoutAction.STOP -> handleStop()
                    TimeoutAction.FALLBACK -> {
                        releaseMediaPlayer()
                        fallbackToNoEarphone(
                            uri = uri,
                            speakerVol = settings.speakerVolume,
                            noEarphonePref = settings.noEarphoneAction,
                            timeoutSeconds = settings.timeoutSeconds
                        )
                    }
                }
            }
        }
        AudioRouter.ResolvedAction.PLAY_VIA_SPEAKER -> {
            playAudio(
                ringtoneUri = uri,
                volumePercent = settings.speakerVolume,
                preferredDevice = route.speakerDevice,
                useAlarmAudio = true
            )
            armAutoStop(settings.timeoutSeconds) {
                Log.i(TAG, "Speaker auto-stop after ${settings.timeoutSeconds} s")
                handleStop()
            }
        }
        AudioRouter.ResolvedAction.VIBRATE_ONLY -> {
            startRepeatingVibration()
            armAutoStop(settings.timeoutSeconds) {
                Log.i(TAG, "Vibrate auto-stop after ${settings.timeoutSeconds} s")
                handleStop()
            }
        }
    }
}

internal fun AlarmAudioService.registerAudioBecomingNoisy() {
    if (noisyReceiver != null) return
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            serviceScope.launch {
                playbackMutex.withLock { handleEarphoneDisconnected() }
            }
        }
    }
    noisyReceiver = receiver
    ContextCompat.registerReceiver(
        this,
        receiver,
        IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
        ContextCompat.RECEIVER_EXPORTED
    )
}

internal fun AlarmAudioService.unregisterAudioBecomingNoisy() {
    noisyReceiver?.let { runCatching { unregisterReceiver(it) } }
    noisyReceiver = null
}

internal suspend fun AlarmAudioService.handleEarphoneDisconnected() {
    if (state !is AlarmAudioService.PlaybackState.Ringing) return
    val settings = preferences.snapshot()
    val uri = resolveRingtoneUri(settings.globalRingtoneUri)

    releaseMediaPlayer()
    releaseAudioFocus()
    // Leaving earphone playback: restore the media volume immediately.
    restoreVolume(AudioManager.STREAM_MUSIC)
    val action = when (settings.noEarphoneAction) {
        NoEarphoneAction.VIBRATE_ONLY -> AudioRouter.ResolvedAction.VIBRATE_ONLY
        NoEarphoneAction.LOUDSPEAKER -> AudioRouter.ResolvedAction.PLAY_VIA_SPEAKER
    }
    activeAction = action
    when (settings.noEarphoneAction) {
        NoEarphoneAction.VIBRATE_ONLY -> startRepeatingVibration()
        NoEarphoneAction.LOUDSPEAKER ->
            playAudio(
                ringtoneUri = uri,
                volumePercent = settings.speakerVolume,
                preferredDevice = audioRouter.inspectRoute().speakerDevice,
                useAlarmAudio = true
            )
    }
    postActiveNotification(action)
    // Unplugging starts a fresh no-earphone session: re-arm the timeout so
    // the alarm stops a full timeout after the unplug, and no second
    // fallback stage can trigger (the earphone fallback already happened).
    armAutoStop(settings.timeoutSeconds) {
        Log.i(TAG, "No-earphone session auto-stop after ${settings.timeoutSeconds} s")
        handleStop()
    }
}

internal fun AlarmAudioService.armAutoStop(timeoutSeconds: Int, onTimeout: suspend () -> Unit) {
    autoStopJob?.cancel()
    autoStopJob = serviceScope.launch {
        delay(timeoutSeconds * 1000L)
        playbackMutex.withLock { onTimeout() }
    }
}

internal fun AlarmAudioService.fallbackToNoEarphone(
    uri: Uri,
    speakerVol: Int,
    noEarphonePref: NoEarphoneAction,
    timeoutSeconds: Int
) {
    Log.i(TAG, "Fallback → no-earphone mode: $noEarphonePref")
    // Leaving earphone playback: put the media volume back immediately so it
    // is not left at the alarm level for the rest of the session.
    restoreVolume(AudioManager.STREAM_MUSIC)
    val action = when (noEarphonePref) {
        NoEarphoneAction.VIBRATE_ONLY -> AudioRouter.ResolvedAction.VIBRATE_ONLY
        NoEarphoneAction.LOUDSPEAKER -> AudioRouter.ResolvedAction.PLAY_VIA_SPEAKER
    }
    activeAction = action
    when (noEarphonePref) {
        NoEarphoneAction.VIBRATE_ONLY -> startRepeatingVibration()
        NoEarphoneAction.LOUDSPEAKER -> {
            val speakerDevice = audioRouter.inspectRoute().speakerDevice
            Log.i(
                TAG,
                "Fallback speaker route: deviceFound=${speakerDevice != null} " +
                    "alarmVol=${audioManager.getStreamVolume(AudioManager.STREAM_ALARM)}"
            )
            playAudio(
                ringtoneUri = uri,
                volumePercent = speakerVol,
                preferredDevice = speakerDevice,
                useAlarmAudio = true
            )
        }
    }
    postActiveNotification(action)
    armAutoStop(timeoutSeconds) {
        Log.i(TAG, "No-earphone fallback auto-stop after $timeoutSeconds s")
        handleStop()
    }
}

internal fun AlarmAudioService.playAudio(
    ringtoneUri: Uri,
    volumePercent: Int,
    preferredDevice: AudioDeviceInfo?,
    useAlarmAudio: Boolean
) {
    if (state !is AlarmAudioService.PlaybackState.Ringing) {
        Log.i(TAG, "Not ringing — skipping playback")
        return
    }
    Log.i(
        TAG,
        "playAudio stream=${if (useAlarmAudio) "ALARM" else "MUSIC"} " +
            "volume=$volumePercent% device=${preferredDevice?.productName ?: "default"}"
    )
    setAlarmVolume(volumePercent, useAlarmAudio)
    requestAudioFocus(useAlarmAudio)

    val silentUri = "android.resource://${packageName}/${R.raw.silent_500ms}".toUri()
    val player = mediaPlayer ?: createMediaPlayer(preferredDevice, useAlarmAudio)
    mediaPlayer = player

    player.setOnCompletionListener { completed ->
        // MediaPlayer fires completion on its own thread. Every other player
        // operation (stop/release from stop/snooze/disconnect) runs on the
        // main dispatcher, so hop back to it to keep accesses serialized.
        serviceScope.launch {
            if (state !is AlarmAudioService.PlaybackState.Ringing || mediaPlayer !== completed) {
                releaseMediaPlayer()
                return@launch
            }
            playLoopingOnPlayer(
                player = completed,
                uri = ringtoneUri,
                preferredDevice = preferredDevice,
                allowDefaultFallback = true,
                useAlarmAudio = useAlarmAudio
            )
        }
    }

    player.setOnPreparedListener { prepared ->
        if (state is AlarmAudioService.PlaybackState.Ringing && mediaPlayer === prepared) {
            prepared.start()
        }
    }
    player.setOnErrorListener { _, what, extra ->
        Log.e(TAG, "Silent warm-up failed (what=$what extra=$extra), using default alarm directly")
        serviceScope.launch {
            if (state is AlarmAudioService.PlaybackState.Ringing && mediaPlayer === player) {
                playLoopingOnPlayer(
                    player = player,
                    uri = defaultAlarmUri,
                    preferredDevice = preferredDevice,
                    allowDefaultFallback = false,
                    useAlarmAudio = useAlarmAudio
                )
            }
        }
        true
    }

    try {
        player.reset()
        configurePlayer(player, preferredDevice, useAlarmAudio)
        player.setDataSource(this, silentUri)
        player.prepareAsync()
    } catch (e: Exception) {
        Log.e(TAG, "Silent warm-up failed, using default alarm directly", e)
        playLoopingOnPlayer(
            player = player,
            uri = defaultAlarmUri,
            preferredDevice = preferredDevice,
            allowDefaultFallback = false,
            useAlarmAudio = useAlarmAudio
        )
    }
}

internal fun AlarmAudioService.createMediaPlayer(
    preferredDevice: AudioDeviceInfo?,
    useAlarmAudio: Boolean
): MediaPlayer =
    MediaPlayer().apply { configurePlayer(this, preferredDevice, useAlarmAudio) }

internal fun AlarmAudioService.configurePlayer(
    player: MediaPlayer,
    preferredDevice: AudioDeviceInfo?,
    useAlarmAudio: Boolean
) {
    val usage = if (useAlarmAudio) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA
    val contentType = if (useAlarmAudio) {
        AudioAttributes.CONTENT_TYPE_SONIFICATION
    } else {
        AudioAttributes.CONTENT_TYPE_MUSIC
    }
    player.setAudioAttributes(AudioAttributes.Builder()
        .setUsage(usage)
        .setContentType(contentType)
        .build())
    if (preferredDevice != null) {
        player.setPreferredDevice(preferredDevice)
    }
}

internal fun AlarmAudioService.playLoopingOnPlayer(
    player: MediaPlayer,
    uri: Uri,
    preferredDevice: AudioDeviceInfo?,
    allowDefaultFallback: Boolean,
    useAlarmAudio: Boolean
) {
    if (state !is AlarmAudioService.PlaybackState.Ringing || mediaPlayer !== player) {
        releaseMediaPlayer()
        return
    }

    player.setOnPreparedListener { prepared ->
        if (state is AlarmAudioService.PlaybackState.Ringing && mediaPlayer === prepared) {
            prepared.start()
        }
    }
    player.setOnErrorListener { _, what, extra ->
        Log.e(
            TAG,
            "Failed to play ${if (uri == defaultAlarmUri) "default alarm" else "custom ringtone"} " +
                "(what=$what extra=$extra)"
        )
        serviceScope.launch {
            if (mediaPlayer !== player) return@launch
            if (allowDefaultFallback && uri != defaultAlarmUri) {
                Log.w(TAG, "Custom ringtone unavailable, using default alarm")
                playLoopingOnPlayer(
                    player = player,
                    uri = defaultAlarmUri,
                    preferredDevice = preferredDevice,
                    allowDefaultFallback = false,
                    useAlarmAudio = useAlarmAudio
                )
            } else {
                releaseMediaPlayer()
            }
        }
        true
    }

    try {
        player.reset()
        configurePlayer(player, preferredDevice, useAlarmAudio)
        player.setDataSource(this, uri)
        player.isLooping = true
        player.prepareAsync()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to play ${if (uri == defaultAlarmUri) "default alarm" else "custom ringtone"}", e)
        if (allowDefaultFallback && uri != defaultAlarmUri) {
            Log.w(TAG, "Custom ringtone unavailable, using default alarm")
            playLoopingOnPlayer(
                player = player,
                uri = defaultAlarmUri,
                preferredDevice = preferredDevice,
                allowDefaultFallback = false,
                useAlarmAudio = useAlarmAudio
            )
        } else {
            releaseMediaPlayer()
        }
    }
}

internal fun AlarmAudioService.resolveRingtoneUri(stored: String): Uri =
    stored.takeIf { it.isNotBlank() }?.toUri()
        ?: defaultAlarmUri

internal fun AlarmAudioService.requestAudioFocus(useAlarmAudio: Boolean) {
    audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    val usage = if (useAlarmAudio) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA
    val contentType = if (useAlarmAudio) {
        AudioAttributes.CONTENT_TYPE_SONIFICATION
    } else {
        AudioAttributes.CONTENT_TYPE_MUSIC
    }
    val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(contentType).build())
        .setOnAudioFocusChangeListener { change ->
            serviceScope.launch {
                playbackMutex.withLock {
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS -> handleStop()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                            if (state is AlarmAudioService.PlaybackState.Ringing) mediaPlayer?.pause()
                        AudioManager.AUDIOFOCUS_GAIN ->
                            if (state is AlarmAudioService.PlaybackState.Ringing) mediaPlayer?.start()
                    }
                }
            }
        }.build()
    audioFocusRequest = req
    audioManager.requestAudioFocus(req)
}

internal fun AlarmAudioService.startRepeatingVibration() {
    if (state !is AlarmAudioService.PlaybackState.Ringing) {
        Log.i(TAG, "Not ringing — skipping vibration")
        return
    }
    vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    val effect = VibrationEffect.createWaveform(
        longArrayOf(0, 500, 300),
        intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0),
        0
    )
    // USAGE_ALARM keeps the repeating vibration alive while the app is in the
    // background; Android 12+ cancels default touch-usage vibrations there.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        vibrator?.vibrate(
            effect,
            VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_ALARM)
                .build()
        )
    } else {
        @Suppress("DEPRECATION")
        vibrator?.vibrate(
            effect,
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()
        )
    }
}

internal fun AlarmAudioService.acquireWakeLock() {
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    releaseWakeLock()
    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SilentAlarm::WakeLock").apply {
        acquire(60 * 60 * 1000L)
    }
}

internal fun AlarmAudioService.releaseMediaPlayer() {
    unregisterAudioBecomingNoisy()
    val player = mediaPlayer
    mediaPlayer = null
    if (player != null) {
        runCatching { player.stop() }
        runCatching { player.reset() }
        runCatching { player.release() }
    }
}

internal fun AlarmAudioService.releaseVibrator() {
    vibrator?.cancel()
    vibrator = null
}

internal fun AlarmAudioService.releaseWakeLock() {
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
}

internal fun AlarmAudioService.releaseAudioFocus() {
    audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    audioFocusRequest = null
}

internal fun AlarmAudioService.setAlarmVolume(volumePercent: Int, useAlarmAudio: Boolean) {
    val stream = if (useAlarmAudio) AudioManager.STREAM_ALARM else AudioManager.STREAM_MUSIC
    val max = audioManager.getStreamMaxVolume(stream)
    if (stream !in previousVolumes) {
        previousVolumes[stream] = audioManager.getStreamVolume(stream)
    }
    val target = (max * volumePercent / 100).coerceIn(0, max)
    audioManager.setStreamVolume(stream, target, 0)
    Log.i(TAG, "Stream $stream set to $target/$max ($volumePercent%)")
}

internal fun AlarmAudioService.restoreVolume(stream: Int? = null) {
    if (stream == null) {
        previousVolumes.forEach { (s, volume) ->
            audioManager.setStreamVolume(s, volume, 0)
        }
        previousVolumes.clear()
    } else {
        val volume = previousVolumes.remove(stream) ?: return
        audioManager.setStreamVolume(stream, volume, 0)
    }
}
