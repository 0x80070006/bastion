package org.bastion.mobile.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat

/** One PCM chunk: 16-bit little-endian, mono, at [sampleRate] Hz. */
class AudioChunkData(val pcm: ByteArray, val sampleRate: Int)

/**
 * Microphone capture for the near-live audio stream: 16 kHz mono 16-bit PCM, read in short
 * chunks. The OS microphone indicator is shown while recording. No file is written.
 */
class AudioCapture(private val context: Context) {
    private var record: AudioRecord? = null
    private val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        .coerceAtLeast(CHUNK_BYTES)

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (!hasPermission()) return false
        val recorder = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "AudioRecord init failed: ${e.javaClass.simpleName}")
            return false
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }
        return try {
            recorder.startRecording()
            record = recorder
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "startRecording failed: ${e.javaClass.simpleName}")
            recorder.release()
            false
        }
    }

    /** Reads one chunk (~[CHUNK_MS] ms), or null when the stream ended or errored. */
    fun read(): AudioChunkData? {
        val recorder = record ?: return null
        val buffer = ByteArray(CHUNK_BYTES)
        var offset = 0
        while (offset < buffer.size) {
            val read = recorder.read(buffer, offset, buffer.size - offset)
            if (read <= 0) return null
            offset += read
        }
        return AudioChunkData(buffer, SAMPLE_RATE)
    }

    fun stop() {
        record?.run {
            runCatching { stop() }
            release()
        }
        record = null
    }

    private companion object {
        const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val CHUNK_MS = 400

        // 16000 samples/s * 2 bytes * 0.4 s = 12800 bytes.
        const val CHUNK_BYTES = SAMPLE_RATE * 2 * CHUNK_MS / 1000
    }
}
