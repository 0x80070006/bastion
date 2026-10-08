package org.bastion.mobile.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Speaker playback for the intercom: writes the controller's 16-bit mono PCM chunks to an
 * [AudioTrack] in streaming mode. Resamples are not performed; chunks keep their own sample rate,
 * and a rate change re-opens the track. Playback uses the voice-call stream so it is routed to the
 * earpiece/speaker like a call and respected by Do-Not-Disturb.
 */
class AudioPlayback {
    private var track: AudioTrack? = null
    private var currentRate = 0

    /** Plays one PCM chunk, (re)opening the track if the sample rate changed. Best-effort. */
    @Synchronized
    fun play(pcm: ByteArray, sampleRate: Int) {
        if (pcm.isEmpty() || sampleRate !in MIN_RATE..MAX_RATE) return
        val active = ensureTrack(sampleRate) ?: return
        runCatching { active.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING) }
            .onFailure { Log.w(TAG, "audio write failed: ${it.javaClass.simpleName}") }
    }

    @Suppress("ReturnCount")
    private fun ensureTrack(sampleRate: Int): AudioTrack? {
        track?.let { if (currentRate == sampleRate) return it }
        release()
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, CHANNEL, ENCODING)
        if (minBuffer <= 0) return null
        val built = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(CHANNEL)
                        .setEncoding(ENCODING)
                        .build(),
                )
                .setBufferSizeInBytes(minBuffer * BUFFER_FACTOR)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrElse {
            Log.w(TAG, "AudioTrack init failed: ${it.javaClass.simpleName}")
            return null
        }
        if (built.state != AudioTrack.STATE_INITIALIZED) {
            built.release()
            return null
        }
        built.play()
        track = built
        currentRate = sampleRate
        return built
    }

    @Synchronized
    fun release() {
        track?.run {
            runCatching { pause() }
            runCatching { flush() }
            runCatching { stop() }
            release()
        }
        track = null
        currentRate = 0
    }

    private companion object {
        const val TAG = "AudioPlayback"
        const val CHANNEL = AudioFormat.CHANNEL_OUT_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val MIN_RATE = 8_000
        const val MAX_RATE = 48_000
        const val BUFFER_FACTOR = 4
    }
}
