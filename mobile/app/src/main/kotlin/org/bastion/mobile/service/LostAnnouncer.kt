package org.bastion.mobile.service

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import org.bastion.mobile.R

/**
 * Audible lost-mode alert, distinct from the remote ring: a periodic siren tone plus a spoken
 * message ("this phone is lost, please contact …") repeated aloud. Shown with the full-screen
 * lost-mode screen above the lock screen. Stops when the owner dismisses it.
 */
class LostAnnouncer(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var tone: ToneGenerator? = null
    private var spoken: String = ""
    private var running = false

    fun start(message: String, phone: String) {
        if (running) return
        running = true
        spoken = buildString {
            append(message.ifBlank { context.getString(R.string.lost_default_message) })
            if (phone.isNotBlank()) {
                append(". ")
                append(context.getString(R.string.lost_call, spell(phone)))
            }
        }
        tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, TONE_VOLUME) }.getOrNull()
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = localeOrDefault()
                loop()
            } else {
                Log.w(TAG, "TTS unavailable: $status")
                loop()
            }
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        tts?.run {
            stop()
            shutdown()
        }
        tts = null
        tone?.release()
        tone = null
    }

    private fun loop() {
        if (!running) return
        tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, TONE_MS)
        handler.postDelayed({
            if (running) {
                tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "lost")
                handler.postDelayed({ loop() }, REPEAT_MS)
            }
        }, SPEAK_DELAY_MS)
    }

    private fun localeOrDefault(): Locale {
        val locale = Locale.getDefault()
        val available = tts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
        return if (available >= TextToSpeech.LANG_AVAILABLE) locale else Locale.ENGLISH
    }

    // Spell a phone number digit by digit so synthesis reads it clearly.
    private fun spell(phone: String): String =
        phone.filter { it.isDigit() || it == '+' }.map { if (it == '+') "plus" else it.toString() }.joinToString(" ")

    private companion object {
        const val TAG = "LostAnnouncer"
        const val TONE_VOLUME = 90
        const val TONE_MS = 1500
        const val SPEAK_DELAY_MS = 1600L
        const val REPEAT_MS = 12_000L
    }
}
