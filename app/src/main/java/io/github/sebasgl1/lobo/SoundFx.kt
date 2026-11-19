package io.github.sebasgl1.lobo

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Sonidos de ambientación (en bucle) y efectos. Todo sale de archivos dentro de la app: no usa internet.
 * La voz de "¡Está vivo!" usa la voz de Android si el celular la tiene; si no, suena solo la campanita.
 */
class SoundFx(private val ctx: Context) {
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build()
    private val ids = HashMap<Int, Int>()
    private var player: MediaPlayer? = null
    private var current = 0
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val main = Handler(Looper.getMainLooper())

    var enabled = true
        set(v) {
            field = v
            if (!v) stopAmbience()
        }

    init {
        listOf(R.raw.aullido, R.raw.manada, R.raw.campana, R.raw.campanas, R.raw.salvado, R.raw.flecha, R.raw.pajaros)
            .forEach { ids[it] = pool.load(ctx, it, 1) }
        runCatching {
            tts = TextToSpeech(ctx) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val t = tts ?: return@TextToSpeech
                    val bad = listOf(TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED)
                    var r = t.setLanguage(Locale("es", "CO"))
                    if (r in bad) r = t.setLanguage(Locale("es"))
                    ttsReady = r !in bad
                }
            }
        }
    }

    /** Cambia el sonido de fondo (0 = silencio). Si ya está sonando el mismo, no lo reinicia. */
    fun ambience(res: Int) {
        if (!enabled || res == 0) { stopAmbience(); return }
        if (res == current && player != null) return
        stopAmbience()
        runCatching {
            player = MediaPlayer.create(ctx, res)?.apply {
                isLooping = true
                setVolume(0.35f, 0.35f)
                start()
            }
            current = res
        }
    }

    fun stopAmbience() {
        runCatching { player?.release() }
        player = null
        current = 0
    }

    fun play(res: Int, volume: Float = 1f) {
        if (!enabled) return
        val id = ids[res] ?: return
        runCatching { pool.play(id, volume, volume, 1, 0, 1f) }
    }

    /** Alguien se salvó: campanita y, si hay voz en español, "¡Está vivo! ¡Está vivo!". */
    fun alive() {
        if (!enabled) return
        play(R.raw.salvado)
        if (ttsReady) main.postDelayed({
            if (enabled) runCatching { tts?.speak("¡Está vivo! ¡Está vivo!", TextToSpeech.QUEUE_FLUSH, null, "vivo") }
        }, 700)
    }

    fun pause() { runCatching { player?.pause() } }
    fun resume() { if (enabled) runCatching { player?.start() } }

    fun release() {
        stopAmbience()
        runCatching { pool.release() }
        runCatching { tts?.shutdown() }
    }
}
