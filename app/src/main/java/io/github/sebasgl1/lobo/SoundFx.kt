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

/** Fondos de ambiente: grabaciones largas (2,5 min) que empalman sin corte. */
enum class Amb(val res: Int) {
    NINGUNO(0),
    HOGUERA(R.raw.amb_hoguera),
    NOCHE(R.raw.amb_noche),
    MERCADO(R.raw.amb_mercado),
}

/** Efectos de los momentos importantes. Cada uno tiene varias versiones y suena una al azar. */
enum class Fx(vararg val res: Int) {
    AULLIDO(R.raw.fx_aullido0, R.raw.fx_aullido1, R.raw.fx_aullido2),
    MANADA(R.raw.fx_manada0, R.raw.fx_manada1),
    CAMPANA(R.raw.fx_campana0, R.raw.fx_campana1),
    CAMPANAS(R.raw.fx_campanas0, R.raw.fx_campanas1),
    SALVADO(R.raw.fx_salvado0, R.raw.fx_salvado1),
    FLECHA(R.raw.fx_flecha0, R.raw.fx_flecha1),
    PAJAROS(R.raw.fx_pajaros0, R.raw.fx_pajaros1),
}

/**
 * Sonidos del juego. Todo sale de archivos dentro de la app: no usa internet.
 *
 * - Fondo: un ambiente largo en bucle que empieza en un punto al azar y cambia con un fundido.
 * - Sonidos sueltos: encima del fondo suenan cada tanto, al azar, un búho, una rama, ranas, una ráfaga,
 *   chasquidos de la hoguera, un herrero, un perro… con volumen, lado y tono distintos cada vez, así el
 *   ambiente nunca se repite igual. No dependen de lo que pase en el juego, así que no delatan a nadie.
 * - Efectos: aullido, campanas, flecha… con varias versiones.
 * - Voz: la voz de Android en español (narrador y "¡Está vivo!"). Mientras habla, todo lo demás baja.
 */
class SoundFx(private val ctx: Context) {
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val pool = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(attrs).build()
    private val poolIds = HashMap<Int, Int>()
    private val loaded = HashSet<Int>()
    private val main = Handler(Looper.getMainLooper())
    private val rnd = java.util.Random()

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pending = HashMap<String, CompletableDeferred<Unit>>()
    private var uttCounter = 0

    /** ¿Hay voz en español disponible? */
    val voiceReady get() = ttsReady

    // ---------- estado del sonido de fondo ----------
    private var player: MediaPlayer? = null
    private var current = Amb.NINGUNO
    private val levels = HashMap<MediaPlayer, Float>()   // volumen de cada fondo (0..1) durante los fundidos
    private val fxPlayers = mutableListOf<MediaPlayer>()
    private val lastFx = HashMap<Fx, Int>()
    private var paused = false
    private var speaking = false
    private var lastShot = 0

    var enabled = true
        set(v) {
            field = v
            if (!v) stopEverything()
        }

    /** Un sonido suelto: archivo, cuántas veces más probable que otros, y rango de volumen. */
    private class Shot(val res: Int, val weight: Int, val minVol: Float, val maxVol: Float)

    private val shots: Map<Amb, List<Shot>> = mapOf(
        Amb.NOCHE to listOf(
            Shot(R.raw.s_buho0, 2, 0.35f, 0.7f), Shot(R.raw.s_buho1, 2, 0.35f, 0.7f),
            Shot(R.raw.s_rama0, 2, 0.3f, 0.6f), Shot(R.raw.s_rama1, 2, 0.3f, 0.6f), Shot(R.raw.s_rama2, 1, 0.3f, 0.6f),
            Shot(R.raw.s_ranas0, 2, 0.25f, 0.5f), Shot(R.raw.s_ranas1, 2, 0.25f, 0.5f),
            Shot(R.raw.s_rafaga0, 1, 0.3f, 0.55f), Shot(R.raw.s_rafaga1, 1, 0.3f, 0.55f),
            Shot(R.raw.s_aullidolejos0, 1, 0.3f, 0.55f), Shot(R.raw.s_aullidolejos1, 1, 0.3f, 0.55f),
        ),
        Amb.HOGUERA to listOf(
            Shot(R.raw.s_chasquido0, 4, 0.35f, 0.8f), Shot(R.raw.s_chasquido1, 4, 0.35f, 0.8f),
            Shot(R.raw.s_chasquido2, 4, 0.35f, 0.8f),
            Shot(R.raw.s_leno0, 2, 0.4f, 0.75f), Shot(R.raw.s_leno1, 2, 0.4f, 0.75f),
            Shot(R.raw.s_buho0, 1, 0.2f, 0.4f), Shot(R.raw.s_rama1, 1, 0.2f, 0.4f),
        ),
        Amb.MERCADO to listOf(
            Shot(R.raw.s_yunque0, 2, 0.3f, 0.55f), Shot(R.raw.s_yunque1, 2, 0.3f, 0.55f),
            Shot(R.raw.s_campanita0, 1, 0.25f, 0.45f),
            Shot(R.raw.s_perro0, 2, 0.25f, 0.5f), Shot(R.raw.s_perro1, 2, 0.25f, 0.5f),
            Shot(R.raw.s_carreta0, 1, 0.3f, 0.55f),
            Shot(R.raw.s_pajaro0, 2, 0.25f, 0.5f), Shot(R.raw.s_pajaro1, 2, 0.25f, 0.5f), Shot(R.raw.s_pajaro2, 2, 0.25f, 0.5f),
        ),
    )

    /** Cada cuánto suena un sonido suelto (milisegundos, al azar dentro del rango). */
    private val shotGap: Map<Amb, LongRange> = mapOf(
        Amb.NOCHE to 6_000L..16_000L,
        Amb.HOGUERA to 2_500L..8_000L,
        Amb.MERCADO to 4_000L..11_000L,
    )

    private val shotTask = object : Runnable {
        override fun run() {
            playRandomShot()
            scheduleShot()
        }
    }

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status -> if (status == 0) loaded.add(sampleId) }
        shots.values.flatten().map { it.res }.distinct().forEach { res ->
            runCatching { poolIds[res] = pool.load(ctx, res, 1) }
        }
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
        setSpeaking(true)
        try {
            t.setPitch(0.82f)
            t.setSpeechRate(0.86f)
            val r = t.speak(text, TextToSpeech.QUEUE_ADD, null, id)
            if (r != TextToSpeech.SUCCESS) delay(readTime(text))
            else withTimeoutOrNull(readTime(text) * 3 + 4000) { done.await() }
        } finally {
            synchronized(pending) { pending.remove(id) }
            if (!done.isCompleted) runCatching { t.stop() }
            setSpeaking(false)
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

    // ---------- fondo ----------

    /** Volumen general del fondo, bajándolo mientras habla el narrador o suena un efecto. */
    private fun duckFactor(): Float = (if (speaking) 0.25f else 1f) * (if (fxPlayers.isNotEmpty()) 0.6f else 1f)

    private fun applyVolume(mp: MediaPlayer) {
        val v = BED_VOLUME * (levels[mp] ?: 0f) * duckFactor()
        runCatching { mp.setVolume(v, v) }
    }

    private fun applyAll() { levels.keys.toList().forEach { applyVolume(it) } }

    /** Sube o baja el volumen de un fondo poco a poco. */
    private fun ramp(mp: MediaPlayer, to: Float, ms: Long, onEnd: () -> Unit = {}) {
        val from = levels[mp] ?: 0f
        val steps = (ms / 40).toInt().coerceAtLeast(1)
        var i = 0
        val step = object : Runnable {
            override fun run() {
                if (!levels.containsKey(mp)) return   // ya se liberó
                i++
                levels[mp] = from + (to - from) * i / steps
                applyVolume(mp)
                if (i < steps) main.postDelayed(this, 40) else onEnd()
            }
        }
        main.post(step)
    }

    private fun releasePlayer(mp: MediaPlayer) {
        levels.remove(mp)
        runCatching { mp.stop() }
        runCatching { mp.release() }
    }

    private fun fadeOutCurrent(ms: Long = 1_500) {
        val mp = player ?: return
        player = null
        ramp(mp, 0f, ms) { releasePlayer(mp) }
    }

    /** Cambia el fondo con un fundido. Empieza en un punto al azar para que cada partida suene distinta. */
    fun ambience(a: Amb) {
        if (!enabled || a == Amb.NINGUNO) {
            fadeOutCurrent()
            current = Amb.NINGUNO
            main.removeCallbacks(shotTask)
            return
        }
        if (a == current && player != null) return
        fadeOutCurrent()
        current = a
        val mp = runCatching { MediaPlayer.create(ctx, a.res) }.getOrNull()
        player = mp
        if (mp != null) {
            runCatching {
                mp.isLooping = true
                levels[mp] = 0f
                applyVolume(mp)
                val d = mp.duration
                if (d > 20_000) mp.seekTo(rnd.nextInt(d - 10_000))
                if (!paused) mp.start()
            }
            ramp(mp, 1f, 1_800)
        }
        scheduleShot()
    }

    fun stopAmbience() = ambience(Amb.NINGUNO)

    // ---------- sonidos sueltos al azar ----------

    private fun scheduleShot() {
        main.removeCallbacks(shotTask)
        val gap = shotGap[current] ?: return
        if (!enabled || paused) return
        val wait = gap.first + (rnd.nextDouble() * (gap.last - gap.first)).toLong()
        main.postDelayed(shotTask, wait)
    }

    private fun playRandomShot() {
        if (!enabled || paused || speaking) return
        val list = shots[current]?.filter { it.res != lastShot } ?: return
        if (list.isEmpty()) return
        var x = rnd.nextInt(list.sumOf { it.weight })
        val shot = list.first { x -= it.weight; x < 0 }
        val id = poolIds[shot.res] ?: return
        if (id !in loaded) return
        lastShot = shot.res
        val v = (shot.minVol + rnd.nextFloat() * (shot.maxVol - shot.minVol)) * SHOT_VOLUME * duckFactor()
        val p = rnd.nextFloat() * 1.4f - 0.7f           // de qué lado suena
        val left = v * minOf(1f, 1f - p)
        val right = v * minOf(1f, 1f + p)
        val rate = 0.94f + rnd.nextFloat() * 0.12f      // un poco más grave o agudo cada vez
        runCatching { pool.play(id, left, right, 0, 0, rate) }
    }

    // ---------- efectos ----------

    /** Suena un efecto, escogiendo una versión distinta a la última. Mientras suena, el fondo baja un poco. */
    fun fx(kind: Fx, volume: Float = 0.95f) {
        if (!enabled) return
        val options = kind.res.filter { it != lastFx[kind] }.ifEmpty { kind.res.toList() }
        val res = options[rnd.nextInt(options.size)]
        lastFx[kind] = res
        runCatching {
            val mp = MediaPlayer.create(ctx, res) ?: return
            mp.setVolume(volume, volume)
            mp.setOnCompletionListener {
                fxPlayers.remove(it)
                runCatching { it.release() }
                applyAll()
            }
            fxPlayers.add(mp)
            applyAll()
            if (!paused) mp.start()
        }
    }

    /** Alguien se salvó: campanita mágica y, si hay voz en español, "¡Está vivo! ¡Está vivo!". */
    fun alive() {
        if (!enabled) return
        fx(Fx.SALVADO)
        if (ttsReady) main.postDelayed({
            if (enabled) runCatching {
                tts?.setPitch(1.05f)
                tts?.setSpeechRate(1.0f)
                tts?.speak("¡Está vivo! ¡Está vivo!", TextToSpeech.QUEUE_FLUSH, null, "vivo")
            }
        }, 700)
    }

    private fun setSpeaking(v: Boolean) {
        main.post {
            speaking = v
            applyAll()
        }
    }

    // ---------- ciclo de vida ----------

    private fun stopEverything() {
        main.removeCallbacks(shotTask)
        player = null
        current = Amb.NINGUNO
        levels.keys.toList().forEach { releasePlayer(it) }
        fxPlayers.toList().forEach { runCatching { it.release() } }
        fxPlayers.clear()
        runCatching { pool.autoPause() }
    }

    fun pause() {
        paused = true
        main.removeCallbacks(shotTask)
        levels.keys.forEach { runCatching { it.pause() } }
        fxPlayers.forEach { runCatching { it.pause() } }
        runCatching { pool.autoPause() }
    }

    fun resume() {
        paused = false
        if (!enabled) return
        player?.let { runCatching { it.start() } }
        fxPlayers.forEach { runCatching { it.start() } }
        runCatching { pool.autoResume() }
        scheduleShot()
    }

    fun release() {
        stopEverything()
        runCatching { pool.release() }
        runCatching { tts?.shutdown() }
    }

    private companion object {
        const val BED_VOLUME = 0.55f
        const val SHOT_VOLUME = 0.9f
    }
}
