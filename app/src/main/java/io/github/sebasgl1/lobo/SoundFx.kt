package io.github.sebasgl1.lobo

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Sonidos de ambientación (en bucle) y efectos. Todo sale de archivos dentro de la app: no usa internet.
 * La voz (narrador y "¡Está vivo!") es la voz de Android en español: funciona sin internet si el celular
 * tiene descargada la voz. Si no la tiene, suena solo la campanita y el narrador muestra el texto.
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
    private val pending = HashMap<String, CompletableDeferred<Unit>>()
    private var uttCounter = 0

    /** ¿Hay voz en español disponible? */
    val voiceReady get() = ttsReady
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
                    if (r in bad) r = t.setLanguage(Locale("es", "US"))
                    if (r in bad) r = t.setLanguage(Locale("es"))
                    ttsReady = r !in bad
                    if (ttsReady) pickBestVoice(t)
                    t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) { finish(id) }
                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) { finish(id) }
                        override fun onError(id: String?, code: Int) { finish(id) }
                        override fun onStop(id: String?, interrupted: Boolean) { finish(id) }
                    })
                }
            }
        }
    }

    /** La mejor voz en español que funcione sin internet, preferiblemente latinoamericana. */
    private fun pickBestVoice(t: TextToSpeech) {
        runCatching {
            val prefer = listOf("CO", "MX", "US", "419", "AR", "CL", "PE", "ES")
            val best = t.voices
                ?.filter { it.locale.language == "es" && !it.isNetworkConnectionRequired &&
                    it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
                ?.sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }
                    .thenBy { prefer.indexOf(it.locale.country).let { i -> if (i < 0) 99 else i } })
                ?.firstOrNull()
            if (best != null) t.voice = best
        }
    }

    private fun finish(id: String?) {
        val d = synchronized(pending) { pending.remove(id ?: return) } ?: return
        d.complete(Unit)
    }

    /** Tiempo aproximado que toma leer un texto en voz alta, por si no hay voz. */
    private fun readTime(text: String) = 700L + text.length * 70L

    /**
     * El narrador dice algo con voz grave y pausada, y espera a que termine.
     * Mientras habla, baja el sonido de fondo. Si no hay voz, espera lo que tardaría en leerse.
     */
    suspend fun say(text: String, fast: Boolean = false) {
        if (text.isBlank()) return
        if (fast) { delay(60); return }
        val t = tts
        if (!ttsReady || t == null) { delay(readTime(text)); return }
        val id = "n${uttCounter++}"
        val done = CompletableDeferred<Unit>()
        synchronized(pending) { pending[id] = done }
        runCatching { player?.setVolume(0.12f, 0.12f) }
        try {
            t.setPitch(0.82f)
            t.setSpeechRate(0.86f)
            val r = t.speak(text, TextToSpeech.QUEUE_ADD, null, id)
            if (r != TextToSpeech.SUCCESS) delay(readTime(text))
            else withTimeoutOrNull(readTime(text) * 3 + 4000) { done.await() }
        } finally {
            synchronized(pending) { pending.remove(id) }
            if (!done.isCompleted) runCatching { t.stop() }
            runCatching { player?.setVolume(0.35f, 0.35f) }
        }
    }

    /** Dice algo de inmediato, cortando lo que estuviera diciendo (cuentas regresivas). */
    fun sayNow(text: String) {
        if (!ttsReady) return
        runCatching {
            tts?.setPitch(0.82f)
            tts?.setSpeechRate(0.95f)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "now${uttCounter++}")
        }
    }

    fun stopVoice() { runCatching { tts?.stop() } }

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
            if (enabled) runCatching {
                tts?.setPitch(1.05f)
                tts?.setSpeechRate(1.0f)
                tts?.speak("¡Está vivo! ¡Está vivo!", TextToSpeech.QUEUE_FLUSH, null, "vivo")
            }
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
