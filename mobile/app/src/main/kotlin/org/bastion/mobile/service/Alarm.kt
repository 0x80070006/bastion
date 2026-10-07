package org.bastion.mobile.service

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.getSystemService
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Loud alarm for the Ring command: alarm stream at maximum volume (restored afterwards),
 * vibration and flashlight blinking. Stops after [durationSeconds] or on [stop].
 */
class Alarm(private val context: Context) {
    private var player: MediaPlayer? = null
    private var previousVolume: Int? = null
    private var job: Job? = null

    val ringing: Boolean get() = player != null

    @Synchronized
    fun start(scope: CoroutineScope, durationSeconds: Int, flashlight: Boolean, vibrate: Boolean) {
        stop()
        val audio = context.getSystemService<AudioManager>()
        if (audio != null) {
            previousVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            runCatching {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            }.onFailure { Log.w(TAG, "volume unchanged: ${it.javaClass.simpleName}") }
        }
        player = createPlayer()
        if (vibrate) vibrator()?.vibrate(VibrationEffect.createWaveform(PATTERN, 0))
        val limit = if (durationSeconds <= 0) MAX_SECONDS else durationSeconds
        job = scope.launch {
            val torch = if (flashlight) torchCamera() else null
            var on = false
            val end = System.currentTimeMillis() + limit * MILLIS
            while (isActive && System.currentTimeMillis() < end) {
                if (torch != null) {
                    on = !on
                    setTorch(torch, on)
                }
                delay(BLINK_MS)
            }
            if (torch != null) setTorch(torch, false)
            stop()
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        player?.runCatching {
            stop()
            release()
        }
        player = null
        vibrator()?.cancel()
        torchCamera()?.let { setTorch(it, false) }
        val audio = context.getSystemService<AudioManager>()
        previousVolume?.let { volume -> runCatching { audio?.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0) } }
        previousVolume = null
    }

    private fun createPlayer(): MediaPlayer? {
        val uri = listOf(RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION)
            .firstNotNullOfOrNull { RingtoneManager.getDefaultUri(it) } ?: return null
        return try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: IOException) {
            Log.w(TAG, "alarm sound unavailable: ${e.javaClass.simpleName}")
            null
        } catch (e: IllegalStateException) {
            Log.w(TAG, "alarm sound unavailable: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun vibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService<VibratorManager>()?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService<Vibrator>()
    }

    private fun torchCamera(): String? {
        val manager = context.getSystemService<CameraManager>() ?: return null
        return runCatching {
            manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
    }

    private fun setTorch(id: String, on: Boolean) {
        try {
            context.getSystemService<CameraManager>()?.setTorchMode(id, on)
        } catch (e: CameraAccessException) {
            Log.d(TAG, "torch unavailable: ${e.reason}")
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, "torch unavailable: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "Alarm"
        const val MAX_SECONDS = 600
        const val MILLIS = 1000L
        const val BLINK_MS = 500L
        val PATTERN = longArrayOf(0, 800, 400)
    }
}
