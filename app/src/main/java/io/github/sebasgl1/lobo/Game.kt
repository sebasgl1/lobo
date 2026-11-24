package io.github.sebasgl1.lobo

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

sealed class Screen {
    data object Home : Screen()
    data object HowTo : Screen()
    data object Setup : Screen()
    data class Reveal(val index: Int) : Screen()
    data class NightPass(val turn: Int) : Screen()
    data class NightAct(val turn: Int) : Screen()
    /** Modo narrador: antes de la primera noche, poner el celular en el centro de la mesa. */
    data object NarratorReady : Screen()
    /** Modo narrador: paso [step] del guion de la noche. */
    data class NarratorNight(val step: Int) : Screen()
    data class Dawn(val deaths: List<Int>, val saved: Boolean) : Screen()
    data object Hunter : Screen()
    data object Day : Screen()
    data object Vote : Screen()
    data object TieSelect : Screen()
    data class TieVote(val candidates: List<Int>) : Screen()
    /** Ronda de acusación: todos señalan y se escogen los dos más señalados. */
    data object Accuse : Screen()
    data class Defense(val index: Int, val candidates: List<Int>) : Screen()
    data class FinalVote(val candidates: List<Int>) : Screen()
    /** Registro opcional de quién votó por quién. */
    data class VoteLog(val eliminated: Int, val voters: List<Int>) : Screen()
    /** player = -1: el pueblo no eliminó a nadie. */
    data class Eliminated(val player: Int, val cause: Cause?) : Screen()
    data object End : Screen()
}

/** En qué momento del ciclo estamos, para saber a dónde seguir después de una muerte. */
enum class Stage { AFTER_DAWN, AFTER_VOTE }

data class PlayerStats(val played: Int, val wins: Int, val wolfGames: Int, val wolfWins: Int)

class Game(context: Context) {
    private val prefs = context.getSharedPreferences("lobo", Context.MODE_PRIVATE)
    private val rng = SecureRandom()

    // Configuración (se guarda entre sesiones)
    val names = mutableStateListOf<String>().apply {
        addAll((prefs.getString("players", "") ?: "").split("\n").filter { it.isNotBlank() })
    }
    var lobos by mutableIntStateOf(prefs.getInt("lobos", 1))
    var vidente by mutableStateOf(prefs.getBoolean("vidente", true))
    var doctor by mutableStateOf(prefs.getBoolean("doctor", true))
    var cazador by mutableStateOf(prefs.getBoolean("cazador", false))
    var revealOnDeath by mutableStateOf(prefs.getBoolean("reveal", true))
    var quietFirstNight by mutableStateOf(prefs.getBoolean("quiet", false))
    var turnSeconds by mutableIntStateOf(prefs.getInt("turnSeconds", 8))
    var bigText by mutableStateOf(prefs.getBoolean("bigText", false))
    var story by mutableStateOf(prefs.getBoolean("story", false))
    var witnessEvery by mutableIntStateOf(prefs.getInt("witnessEvery", 1))
    var corruption by mutableStateOf(prefs.getBoolean("corruption", false))
    var accusation by mutableStateOf(prefs.getBoolean("accusation", true))
    var anonSuspicions by mutableStateOf(prefs.getBoolean("anonSuspicions", false))
    var voteLog by mutableStateOf(prefs.getBoolean("voteLog", false))
    var sound by mutableStateOf(prefs.getBoolean("sound", true))
    var narrator by mutableStateOf(prefs.getBoolean("narrator", false))
    /** Modo Historia: aliado secreto de los lobos (recomendado desde 8 jugadores). */
    var saboteador by mutableStateOf(prefs.getBoolean("saboteador", false))
    /** Rol opcional: gana si el pueblo lo elimina. */
    var bufon by mutableStateOf(prefs.getBoolean("bufon", false))
    /** Solo para las pruebas automáticas: el narrador no espera ni habla. */
    val fastNarrator = prefs.getBoolean("fastNarrator", false)

    // Partida en curso
    var match by mutableStateOf<Match?>(null)
        private set
    var screen by mutableStateOf<Screen>(Screen.Home)
    var order by mutableStateOf(listOf<Int>())
        private set
    var stage by mutableStateOf(Stage.AFTER_DAWN)
        private set
    /** Hora (ms) en que se acaba el temporizador del día; 0 = apagado. */
    var timerEnd by mutableStateOf(0L)
    /** Cambia cada vez que algo de la partida cambia, para que la pantalla se actualice. */
    var version by mutableIntStateOf(0)
        private set
    /** Guion de la noche en curso (modo narrador). */
    var steps by mutableStateOf(listOf<NarratorStep>())
        private set
    /** Cuánto se demoraron los turnos reales, para que los simulados duren parecido. */
    private val realDurations = mutableListOf<Long>()
    private var checkpoint: String? = null

    init {
        // Balance del Modo Historia (v0.4): el testigo pasa a ser noche por medio por defecto
        if (!prefs.getBoolean("balance2", false)) {
            if (witnessEvery == 1) witnessEvery = 2
            prefs.edit().putInt("witnessEvery", witnessEvery).putBoolean("balance2", true).apply()
        }
        restoreState()
    }

    private fun baseSetup() = RoleSetup(lobos, vidente, doctor, cazador, saboteador = story && saboteador, bufon = bufon)

    /** Con narrador y Modo Historia, el testigo es un rol fijo (si quedan al menos 2 aldeanos para no dejar el pueblo sin aldeanos). */
    val testigoActive get() = narrator && story && witnessEvery > 0 && names.size - lobos - baseSetup().specials() >= 2

    fun setup() = baseSetup().copy(testigo = testigoActive)
    fun setupError(): String? = Rules.setupError(names.size, setup())
    fun aldeanos() = names.size - lobos - setup().specials()

    val inGame get() = match != null && screen !is Screen.Home && screen !is Screen.HowTo && screen !is Screen.Setup && screen !is Screen.End

    /** El reparto recomendado cambia según el modo: en Historia hay Saboteador desde 8 y un lobo más desde 12. */
    fun recommended() = if (story) Rules.recommendedStory(names.size) else Rules.recommended(names.size)
    val isRecommended get() = recommended() == baseSetup().copy(bufon = false)

    fun applyRecommended() {
        val r = recommended()
        lobos = r.lobos; vidente = r.vidente; doctor = r.doctor; cazador = r.cazador
        if (story) saboteador = r.saboteador
        saveConfig()
    }

    /** Cambia entre Clásico e Historia; si estaba con el reparto recomendado, lo ajusta al del modo nuevo. */
    fun changeStory(v: Boolean) {
        val wasRec = isRecommended
        story = v
        if (wasRec) applyRecommended() else saveConfig()
    }

    fun addName(raw: String): Boolean {
        val n = raw.trim().replace("\n", " ")
        if (n.isEmpty() || names.any { it.equals(n, ignoreCase = true) }) return false
        names.add(n)
        applyRecommended()
        return true
    }

    fun removeName(n: String) { names.remove(n); applyRecommended() }

    fun moveName(from: Int, to: Int) {
        if (to !in names.indices) return
        val n = names.removeAt(from); names.add(to, n); saveConfig()
    }

    fun saveConfig() {
        prefs.edit()
            .putString("players", names.joinToString("\n"))
            .putInt("lobos", lobos).putBoolean("vidente", vidente).putBoolean("doctor", doctor).putBoolean("cazador", cazador)
            .putBoolean("reveal", revealOnDeath).putBoolean("quiet", quietFirstNight)
            .putInt("turnSeconds", turnSeconds).putBoolean("bigText", bigText)
            .putBoolean("story", story).putInt("witnessEvery", witnessEvery).putBoolean("corruption", corruption)
            .putBoolean("accusation", accusation).putBoolean("anonSuspicions", anonSuspicions).putBoolean("voteLog", voteLog)
            .putBoolean("sound", sound).putBoolean("narrator", narrator)
            .putBoolean("saboteador", saboteador).putBoolean("bufon", bufon)
            .apply()
    }

    private fun options() = Options(
        revealOnDeath = revealOnDeath, quietFirstNight = quietFirstNight,
        story = story, witnessEvery = witnessEvery, corruption = corruption, narrator = narrator,
        // Balance del Modo Historia: testigo desde la 2ª noche, escena de 3 o 4 según el grupo y testigo difícil
        witnessFrom = 2, sceneSize = Rules.sceneSize(names.size), witnessInScene = story,
    )

    // ---------- Flujo de la partida ----------

    fun startOrSetup() {
        if (setupError() != null) screen = Screen.Setup else start()
    }

    fun start() {
        if (setupError() != null) return
        saveConfig()
        val list = names.toList()
        val hist = loadHistory()
        val roles = Rules.assignRoles(list, setup(), hist, rng)
        saveHistory(Rules.pushHistory(hist, list, roles))
        match = Match(list, roles, options(), rng)
        timerEnd = 0L
        go(Screen.Reveal(0))
    }

    fun nextReveal(i: Int) {
        val m = match ?: return
        if (i + 1 < m.size) go(Screen.Reveal(i + 1))
        else if (m.options.narrator) go(Screen.NarratorReady)
        else beginNight()
    }

    fun beginNight() {
        val m = match ?: return
        m.startNight()
        if (m.options.narrator) {
            if (m.night == 1) realDurations.clear()
            steps = m.narratorSteps()
            go(Screen.NarratorNight(0))
        } else {
            order = m.nightOrder()
            go(Screen.NightPass(0))
        }
    }

    // ---------- Modo narrador ----------

    /** Los lobos escogen víctima (salvo noche tranquila), a quién incriminar y si compran al testigo. */
    fun narratorWolves(target: Int, frame: Int, corrupt: Boolean) {
        val m = match ?: return
        val w = m.packActor()
        if (w < 0) return
        if (!m.isQuietNight && target >= 0 && !m.hasActed(w)) m.act(w, target)
        if (frame >= 0 && frame in m.frameTargets(w)) m.frame(w, frame)
        if (corrupt && m.canCorrupt) m.requestCorruption(w)
        version++
    }

    /** Doctor o Vidente con narrador. */
    fun narratorAct(actor: Int, target: Int): SeerResult? {
        val m = match ?: return null
        if (m.hasActed(actor)) return null
        val r = m.act(actor, target)
        version++
        return r
    }

    fun noteRealDuration(ms: Long) { realDurations.add(ms) }

    /** Duración de un turno simulado (rol muerto): parecida a lo que tardan los turnos reales. */
    fun fakeDuration(): Long {
        if (fastNarrator) return 50
        val avg = if (realDurations.isEmpty()) 11_000L else realDurations.average().toLong()
        val jitter = (avg * 0.3).toLong()
        return (avg - jitter + (rng.nextDouble() * 2 * jitter).toLong()).coerceIn(8_000L, 30_000L)
    }

    /** Tiempo mínimo con los ojos abiertos en un turno real, para que no se note quién decidió rápido. */
    fun minHold(): Long = if (fastNarrator) 30 else 7_000L + rng.nextInt(3_000)

    fun pauseAfterClose(): Long = if (fastNarrator) 30 else 1_500L + rng.nextInt(1_500)

    fun narratorNext(step: Int) {
        val m = match ?: return
        // Si mientras hablaba el narrador se salió de la partida, no seguir
        if (screen != Screen.NarratorNight(step)) return
        if (step + 1 < steps.size) {
            go(Screen.NarratorNight(step + 1))
        } else {
            val d = m.resolveDawn()
            stage = Stage.AFTER_DAWN
            go(Screen.Dawn(d.deaths, d.saved))
        }
    }

    private val intros = listOf(
        "La noche vuelve a caer sobre el pueblo. Todos, cierren los ojos.",
        "Se oculta el sol detrás de las montañas. Todos, cierren los ojos.",
        "Otra noche más. El pueblo se va a dormir. Cierren los ojos.",
        "La luna sale sobre el bosque. Todos, cierren los ojos.",
    )

    /** Lo que dice el narrador al llamar a un rol. */
    fun callOpen(c: Call): String {
        val m = match ?: return ""
        val story = m.options.story
        return when (c) {
            Call.INTRO -> if (m.night == 1)
                "Cae la noche sobre el pueblo. Todos, cierren los ojos. Mientras dure la noche, golpeen suavemente la mesa con los dedos."
                else intros[m.night % intros.size]
            Call.LOBOS -> {
                val head = if (m.night == 1) "Lobos, abran los ojos y reconózcanse." else "Lobos, abran los ojos."
                head + " " + when {
                    m.isQuietNight && story -> "Esta noche no atacan. Escojan en silencio a quién incriminar, y uno de ustedes lo toca en el celular."
                    m.isQuietNight -> "Esta noche no atacan. Cuando estén listos, uno de ustedes toca Listo."
                    story -> "Pónganse de acuerdo en silencio, y uno de ustedes toca en el celular a su víctima y a quién quieren incriminar."
                    else -> "Pónganse de acuerdo en silencio, y uno de ustedes toca en el celular a su víctima."
                }
            }
            Call.DOCTOR -> "Doctor, abre los ojos. ¿A quién vas a proteger esta noche?"
            Call.VIDENTE -> "Vidente, abre los ojos. ¿De quién quieres conocer la verdad?"
            Call.TESTIGO -> "Testigo, abre los ojos. Mira el celular: esto es lo que viste esta noche."
            Call.AMANECER -> "El sol empieza a salir sobre el pueblo. Todos, abran los ojos."
        }
    }

    fun callClose(c: Call): String = when (c) {
        Call.LOBOS -> "Lobos, cierren los ojos."
        Call.DOCTOR -> "Doctor, cierra los ojos."
        Call.VIDENTE -> "Vidente, cierra los ojos."
        Call.TESTIGO -> "Testigo, cierra los ojos."
        else -> ""
    }

    /** Lo que lee el narrador en voz alta en las pantallas públicas. */
    fun narration(s: Screen): String? {
        val m = match ?: return null
        if (!m.options.narrator) return null
        return when (s) {
            is Screen.Dawn -> buildString {
                append(dawnText(s.deaths, s.saved))
                s.deaths.forEach { d ->
                    append(if (m.options.revealOnDeath) " ${m.names[d]} era ${m.roles[d].label}." else " Su rol queda en secreto.")
                }
                sceneLines()?.let { (head, who) ->
                    append(" $head ")
                    append(who.joinToString("; "))
                    append(". Al menos uno de ellos es lobo.")
                }
                if (m.pendingHunter < 0 && m.winner == null) append(" Ahora, el pueblo debe discutir.")
            }
            Screen.Hunter -> if (m.pendingHunter >= 0) "${m.names[m.pendingHunter]} era el cazador. Antes de morir, se lleva a alguien con él." else null
            is Screen.Eliminated -> when {
                s.player < 0 -> "El pueblo no eliminó a nadie. Todos a dormir… y que tengan cuidado."
                s.cause == Cause.CAZADOR -> "${m.names[s.player]} cayó por el disparo del cazador." +
                    if (m.options.revealOnDeath) " Era ${m.roles[s.player].label}." else ""
                m.roles[s.player] == Role.BUFON -> "El pueblo eliminó a ${m.names[s.player]}… ¡y era el Bufón! Eso era justo lo que quería."
                else -> "El pueblo eliminó a ${m.names[s.player]}." + if (m.options.revealOnDeath) " Era ${m.roles[s.player].label}." else ""
            }
            Screen.End -> (if (m.winner == Team.LOBOS) "¡Ganan los lobos! El pueblo ha caído." else "¡Gana el pueblo! Ya no quedan lobos.") +
                (if (m.bufonWon) " Y el Bufón también ganó." else "")
            else -> null
        }
    }

    /** Registra la acción del turno y devuelve lo que vio la Vidente, si aplica. */
    fun act(turn: Int, target: Int, frame: Int = -1, corrupt: Boolean = false): SeerResult? {
        val m = match ?: return null
        val p = order[turn]
        val r = m.act(p, target)
        if (frame >= 0) m.frame(p, frame)
        if (corrupt && m.canCorrupt) m.requestCorruption(p)
        version++
        return r
    }

    fun endTurn(turn: Int) {
        val m = match ?: return
        if (turn + 1 < order.size) {
            // Se guarda el avance tras cada turno: si la app se cierra, nadie repite su turno
            go(Screen.NightPass(turn + 1))
        } else {
            val d = m.resolveDawn()
            stage = Stage.AFTER_DAWN
            go(Screen.Dawn(d.deaths, d.saved))
        }
    }

    /** Después de una muerte o del amanecer: cazador, ganador, día o noche. */
    fun next() {
        val m = match ?: return
        when {
            m.pendingHunter >= 0 -> go(Screen.Hunter)
            m.winner != null -> finish()
            stage == Stage.AFTER_DAWN -> { timerEnd = 0L; go(Screen.Day) }
            else -> beginNight()
        }
    }

    fun vote(target: Int?) {
        val m = match ?: return
        val voters = m.aliveList()
        m.vote(target)
        stage = Stage.AFTER_VOTE
        if (voteLog && voters.size > 1) go(Screen.VoteLog(target ?: -1, voters))
        else go(Screen.Eliminated(target ?: -1, if (target == null) null else Cause.VOTO))
    }

    fun saveVotes(votes: Map<Int, Int>, eliminated: Int) {
        val m = match ?: return
        votes.forEach { (v, t) -> m.recordVote(v, t) }
        go(Screen.Eliminated(eliminated, if (eliminated < 0) null else Cause.VOTO))
    }

    fun startAccusation() = go(Screen.Accuse)

    fun accused(candidates: List<Int>) {
        if (candidates.size == 1) go(Screen.FinalVote(candidates)) else go(Screen.Defense(0, candidates))
    }

    fun nextDefense(s: Screen.Defense) {
        timerEnd = 0L
        if (s.index + 1 < s.candidates.size) go(Screen.Defense(s.index + 1, s.candidates)) else go(Screen.FinalVote(s.candidates))
    }

    fun hunterShoot(target: Int) {
        val m = match ?: return
        m.hunterShoot(target)
        go(Screen.Eliminated(target, Cause.CAZADOR))
    }

    private fun finish() {
        val m = match ?: return
        val w = m.winner ?: return
        if (!prefs.getBoolean("recorded", false)) recordStats(m, w)
        prefs.edit().putBoolean("recorded", true).apply()
        go(Screen.End)
    }

    private fun go(s: Screen) {
        screen = s
        version++
        if (s is Screen.Reveal && s.index == 0) prefs.edit().putBoolean("recorded", false).apply()
        checkpoint = snapshot()
    }

    // ---------- Textos ----------

    fun name(p: Int) = match?.names?.getOrNull(p) ?: ""

    fun dawnText(deaths: List<Int>, saved: Boolean): String {
        val m = match ?: return ""
        if (deaths.isEmpty()) {
            return if (saved) "Los lobos atacaron, pero el doctor llegó a tiempo. Esta noche nadie murió."
            else "Fue una noche tranquila. Nadie murió."
        }
        val who = deaths.joinToString(" y ") { m.names[it] }
        val lines = listOf(
            "El pueblo despierta… y $who no volvió a abrir los ojos.",
            "Cantó el gallo, pero $who no salió de su casa.",
            "Amaneció, y faltaba $who.",
            "Los lobos estuvieron de cacería: $who no amaneció.",
        )
        return lines[(m.night + deaths.first()) % lines.size]
    }

    // ---------- Modo Historia ----------

    private val places = listOf("junto al granero", "cerca del pozo", "en la plaza", "a la orilla del río",
        "detrás de la iglesia", "en el camino del molino", "junto al corral", "a la entrada del bosque")
    private val activities = listOf("recogiendo leña", "buscando velas", "cerrando el corral", "paseando al perro",
        "sacando agua del pozo", "llevando pan a su casa", "arreglando una cerca", "buscando a su gato",
        "volviendo de la taberna", "recogiendo hierbas", "apagando una fogata", "guardando las herramientas")

    fun lastScene(): Event.Scene? = match?.log?.lastOrNull { it is Event.Scene } as? Event.Scene

    /** Narración de la escena del crimen; usa la noche como semilla para que no cambie al girar la pantalla. */
    fun sceneLines(): Pair<String, List<String>>? {
        val m = match ?: return null
        val sc = lastScene() ?: return null
        val r = java.util.Random(m.night * 7919L + sc.suspects.sum())
        val place = places[r.nextInt(places.size)]
        val head = when {
            sc.victim >= 0 && !sc.saved -> "Encontraron a ${m.names[sc.victim]} $place."
            sc.victim >= 0 -> "Hubo un ataque $place, pero alguien salvó a la víctima."
            else -> "Anoche hubo ruidos extraños $place."
        }
        val acts = activities.shuffled(r)
        val who = sc.suspects.mapIndexed { i, p -> "${m.names[p]}, ${acts[i]}" }
        return "$head Esa noche estuvieron cerca:" to who
    }

    fun witnessText(p: Int): String? {
        val m = match ?: return null
        val (a, b) = m.witnessFor(p) ?: return null
        return "Anoche viste a ${m.names[a]} y a ${m.names[b]} rondando por el pueblo. Uno de los dos es lobo."
    }

    /** Conteo anónimo de sospechas de la última noche (incluye las pistas falsas de los lobos). */
    fun morningSuspicions(): List<Pair<String, Int>> {
        val m = match ?: return emptyList()
        val start = m.log.indexOfLast { it is Event.Night }
        if (start < 0) return emptyList()
        val seg = m.log.subList(start, m.log.size)
        val targets = seg.filterIsInstance<Event.Suspect>().filter { !m.isWolf(it.player) }.map { it.target } +
            seg.filterIsInstance<Event.FrameVote>().map { it.target }
        return targets.groupingBy { it }.eachCount().entries.filter { m.alive[it.key] }
            .sortedByDescending { it.value }.map { m.names[it.key] to it.value }
    }

    /** Votos registrados en el día anterior. */
    fun yesterdayVotes(): List<String> {
        val m = match ?: return emptyList()
        val lastDay = m.log.indexOfLast { it is Event.Day }
        val prevDay = m.log.subList(0, maxOf(0, lastDay)).indexOfLast { it is Event.Day }
        if (prevDay < 0) return emptyList()
        return m.log.subList(prevDay, lastDay).filterIsInstance<Event.VoteCast>().map { v ->
            // target = -1 cuando votó por nadie
            val wolfTag = if (v.target >= 0 && m.options.revealOnDeath && !m.alive[v.target] && m.isWolf(v.target)) " 🐺" else ""
            "${m.names[v.voter]} → ${if (v.target < 0) "nadie" else m.names[v.target] + wolfTag}"
        }
    }

    val tips = listOf(
        "En la escena siempre hay al menos un lobo… y un inocente incriminado por ellos.",
        "Si alguien dice que fue testigo, cruza su par con la escena: ¿quién aparece en ambos?",
        "Un lobo puede decir que fue testigo e inventar el par. ¿Le creen?",
        "Si \"nadie murió\", el Doctor acertó. ¿A quién protegió y por qué?",
        "Fíjense a quién matan los lobos: suelen ir por quien los está acusando.",
        "Quien nunca acusa a nadie también es sospechoso.",
    )

    fun timeline(): List<String> {
        val m = match ?: return emptyList()
        fun n(p: Int) = m.names[p]
        fun r(p: Int) = m.roles[p].label
        return m.log.mapNotNull { e ->
            when (e) {
                is Event.Night -> "🌙 Noche ${e.n}"
                is Event.WolfChoice -> "🐺 Los lobos atacaron a ${n(e.target)}" + if (e.byChance) " (al azar entre sus propuestas)" else ""
                is Event.Protect -> "🩺 ${n(e.doctor)} protegió a ${n(e.target)}"
                is Event.Seen -> "🔮 ${n(e.seer)} investigó a ${n(e.target)}: ${if (e.isWolf) "lobo" else "no es lobo"}"
                is Event.Saved -> "✨ ${n(e.target)} se salvó gracias al doctor"
                is Event.Died -> when (e.cause) {
                    Cause.LOBOS -> "💀 ${n(e.player)} murió de noche (${r(e.player)})"
                    Cause.VOTO -> "⚖️ El pueblo eliminó a ${n(e.player)} (${r(e.player)})"
                    Cause.CAZADOR -> "🏹 El cazador se llevó a ${n(e.player)} (${r(e.player)})"
                }
                is Event.Day -> "☀️ Día ${e.n}"
                is Event.NobodyVoted -> "⚖️ El pueblo no eliminó a nadie"
                is Event.Win -> "🏆 Ganan ${e.team.label.lowercase()}"
                is Event.Scene -> if (e.suspects.isEmpty()) null else "🔎 Escena: " + e.suspects.joinToString(", ") { n(it) }
                is Event.Witness -> "🕯️ ${n(e.witness)} fue testigo: vio a ${n(e.a)} y ${n(e.b)}" + if (e.corrupted) " (comprado por los lobos)" else ""
                is Event.CorruptionUsed -> "💰 Los lobos compraron al testigo de la noche siguiente"
                is Event.FrameVote -> if (m.options.narrator) "🎭 Los lobos incriminaron a ${n(e.target)}" else "🎭 ${n(e.wolf)} quiso incriminar a ${n(e.target)}"
                is Event.VoteCast -> null
                is Event.Propose, is Event.Suspect -> null
            }
        }
    }

    /** "Lo que pensaba el pueblo": de quién sospecharon más los que no tenían poder. */
    fun suspicions(): List<Pair<String, Int>> {
        val m = match ?: return emptyList()
        // Solo el pueblo: en la primera noche tranquila los lobos también "sospechan" y no deben contar
        return m.log.filterIsInstance<Event.Suspect>().filter { !m.isWolf(it.player) }.groupingBy { it.target }.eachCount()
            .entries.sortedByDescending { it.value }.take(5).map { m.names[it.key] to it.value }
    }

    // ---------- Estadísticas acumuladas ----------

    private fun recordStats(m: Match, w: Team) {
        val all = loadStats().toMutableMap()
        m.names.forEachIndexed { i, n ->
            val s = all[n] ?: PlayerStats(0, 0, 0, 0)
            val won = m.isWinner(i)
            val wolf = m.roles[i] == Role.LOBO
            all[n] = PlayerStats(s.played + 1, s.wins + (if (won) 1 else 0), s.wolfGames + (if (wolf) 1 else 0), s.wolfWins + (if (wolf && won) 1 else 0))
        }
        val o = JSONObject()
        all.forEach { (k, v) -> o.put(k, "${v.played},${v.wins},${v.wolfGames},${v.wolfWins}") }
        prefs.edit().putString("stats", o.toString()).apply()
    }

    fun loadStats(): Map<String, PlayerStats> = try {
        val o = JSONObject(prefs.getString("stats", "{}") ?: "{}")
        val out = mutableMapOf<String, PlayerStats>()
        for (k in o.keys()) {
            val p = o.getString(k).split(",").map { it.toInt() }
            out[k] = PlayerStats(p[0], p[1], p[2], p[3])
        }
        out
    } catch (e: Exception) {
        emptyMap()
    }

    fun resetStats() { prefs.edit().remove("stats").apply(); version++ }

    private fun loadHistory(): Map<String, String> = try {
        val o = JSONObject(prefs.getString("history", "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun saveHistory(h: Map<String, String>) {
        prefs.edit().putString("history", JSONObject(h).toString()).apply()
    }

    // ---------- Guardado de la partida ----------

    /**
     * Foto de la partida en un punto seguro. Durante la noche se usa la foto del inicio de la noche:
     * si Android cierra la app a mitad de la ronda, la noche se vuelve a jugar desde el primer turno.
     */
    private fun snapshot(): String? {
        val m = match ?: return null
        val s = screen
        val o = JSONObject()
        o.put("t", System.currentTimeMillis())
        o.put("names", JSONArray().also { a -> m.names.forEach { a.put(it) } })
        o.put("roles", JSONArray().also { a -> m.roles.forEach { a.put(it.name) } })
        o.put("alive", JSONArray().also { a -> m.alive.forEach { a.put(it) } })
        o.put("night", m.night).put("docLast", m.doctorLast).put("docSelf", m.doctorSelfUsed)
        o.put("hunter", m.pendingHunter).put("winner", m.winner?.name ?: "")
        o.put("reveal", m.options.revealOnDeath).put("quiet", m.options.quietFirstNight)
        o.put("story", m.options.story).put("witnessEvery", m.options.witnessEvery).put("corruption", m.options.corruption)
        o.put("witness", m.witness).put("pairA", m.witnessPair?.first ?: -1).put("pairB", m.witnessPair?.second ?: -1)
        o.put("corruptNext", m.corruptNext).put("corruptUsed", m.corruptionUsed).put("narrator", m.options.narrator)
        o.put("witnessFrom", m.options.witnessFrom).put("sceneSize", m.options.sceneSize)
            .put("witnessInScene", m.options.witnessInScene).put("guest", m.sceneGuest)
        o.put("stage", stage.name)
        o.put("order", JSONArray().also { a -> order.forEach { a.put(it) } })
        o.put("log", JSONArray().also { a -> m.log.forEach { a.put(eventJson(it)) } })
        when (s) {
            is Screen.Reveal -> o.put("screen", "reveal")
            is Screen.NightPass -> {
                o.put("screen", "night").put("turn", s.turn).put("protect", m.nightProtect())
                val pr = JSONObject()
                m.nightProposals().forEach { (k, v) -> pr.put(k.toString(), v) }
                o.put("proposals", pr)
                val fr = JSONObject()
                m.nightFrames().forEach { (k, v) -> fr.put(k.toString(), v) }
                o.put("frames", fr)
                o.put("acted", JSONArray().also { a -> m.nightActed().forEach { a.put(it) } })
            }
            is Screen.NightAct -> return checkpoint
            is Screen.NarratorReady -> o.put("screen", "nready")
            is Screen.NarratorNight -> {
                o.put("screen", "nnight").put("turn", s.step).put("protect", m.nightProtect())
                val pr = JSONObject()
                m.nightProposals().forEach { (k, v) -> pr.put(k.toString(), v) }
                o.put("proposals", pr)
                val fr = JSONObject()
                m.nightFrames().forEach { (k, v) -> fr.put(k.toString(), v) }
                o.put("frames", fr)
                o.put("acted", JSONArray().also { a -> m.nightActed().forEach { a.put(it) } })
            }
            is Screen.Dawn -> o.put("screen", "dawn").put("deaths", JSONArray().also { a -> s.deaths.forEach { a.put(it) } }).put("saved", s.saved)
            is Screen.Hunter -> o.put("screen", "hunter")
            is Screen.Day, is Screen.Vote, is Screen.TieSelect, is Screen.TieVote,
            is Screen.Accuse, is Screen.Defense, is Screen.FinalVote -> o.put("screen", "day")
            is Screen.VoteLog -> o.put("screen", "eliminated").put("player", s.eliminated).put("cause", if (s.eliminated < 0) "" else Cause.VOTO.name)
            is Screen.Eliminated -> o.put("screen", "eliminated").put("player", s.player).put("cause", s.cause?.name ?: "")
            is Screen.End -> o.put("screen", "end")
            else -> return null
        }
        return o.toString()
    }

    fun saveState() {
        val s = screen
        val cp = checkpoint
        if (match == null || cp == null || s is Screen.Home || s is Screen.HowTo || s is Screen.Setup) {
            prefs.edit().remove("state").apply()
        } else {
            prefs.edit().putString("state", cp).apply()
        }
    }

    private fun restoreState() {
        val raw = prefs.getString("state", null) ?: return
        try {
            val o = JSONObject(raw)
            if (System.currentTimeMillis() - o.optLong("t", 0L) > RESUME_MS) {
                prefs.edit().remove("state").apply()
                return
            }
            val nm = o.getJSONArray("names").let { a -> (0 until a.length()).map { a.getString(it) } }
            val rl = o.getJSONArray("roles").let { a -> (0 until a.length()).map { Role.valueOf(a.getString(it)) } }
            val al = o.getJSONArray("alive").let { a -> (0 until a.length()).map { a.getBoolean(it) } }
            val ev = o.getJSONArray("log").let { a -> (0 until a.length()).mapNotNull { eventFrom(a.getJSONObject(it)) } }
            val win = o.getString("winner").let { if (it.isEmpty()) null else Team.valueOf(it) }
            val opts = Options(
                revealOnDeath = o.getBoolean("reveal"), quietFirstNight = o.getBoolean("quiet"),
                story = o.optBoolean("story", false), witnessEvery = o.optInt("witnessEvery", 1), corruption = o.optBoolean("corruption", false),
                narrator = o.optBoolean("narrator", false),
                witnessFrom = o.optInt("witnessFrom", 1), sceneSize = o.optInt("sceneSize", 3),
                witnessInScene = o.optBoolean("witnessInScene", false),
            )
            val m = Match(nm, rl, opts, rng)
            m.restore(al, o.getInt("night"), o.getInt("docLast"), o.getBoolean("docSelf"), o.getInt("hunter"), win, ev)
            val pa = o.optInt("pairA", -1); val pb = o.optInt("pairB", -1)
            m.restoreStory(o.optInt("witness", -1), if (pa >= 0 && pb >= 0) pa to pb else null,
                o.optBoolean("corruptNext", false), o.optBoolean("corruptUsed", false), o.optInt("guest", -1))
            match = m
            stage = Stage.valueOf(o.getString("stage"))
            order = o.getJSONArray("order").let { a -> (0 until a.length()).map { a.getInt(it) } }
            if (o.getString("screen") == "night" || o.getString("screen") == "nnight") {
                val pr = o.optJSONObject("proposals")
                val props = mutableMapOf<Int, Int>()
                if (pr != null) for (k in pr.keys()) props[k.toInt()] = pr.getInt(k)
                val ac = o.optJSONArray("acted")
                val done = if (ac == null) emptySet() else (0 until ac.length()).map { ac.getInt(it) }.toSet()
                val fr = o.optJSONObject("frames")
                val frames = mutableMapOf<Int, Int>()
                if (fr != null) for (k in fr.keys()) frames[k.toInt()] = fr.getInt(k)
                m.restoreNight(props, o.optInt("protect", -1), done, frames)
                if (opts.narrator) steps = m.narratorSteps()
            }
            screen = when (o.getString("screen")) {
                "reveal" -> Screen.Reveal(0)
                "night" -> Screen.NightPass(o.optInt("turn", 0).coerceIn(0, maxOf(0, order.size - 1)))
                "nready" -> Screen.NarratorReady
                "nnight" -> Screen.NarratorNight(o.optInt("turn", 0).coerceIn(0, maxOf(0, steps.size - 1)))
                "dawn" -> Screen.Dawn(o.getJSONArray("deaths").let { a -> (0 until a.length()).map { a.getInt(it) } }, o.getBoolean("saved"))
                "hunter" -> Screen.Hunter
                "day" -> Screen.Day
                "eliminated" -> Screen.Eliminated(o.getInt("player"), o.getString("cause").let { if (it.isEmpty()) null else Cause.valueOf(it) })
                "end" -> Screen.End
                else -> Screen.Home
            }
            checkpoint = raw
        } catch (e: Exception) {
            match = null
            screen = Screen.Home
            prefs.edit().remove("state").apply()
        }
    }

    private fun eventJson(e: Event): JSONObject {
        val o = JSONObject()
        when (e) {
            is Event.Night -> o.put("k", "night").put("n", e.n)
            is Event.Propose -> o.put("k", "prop").put("a", e.wolf).put("b", e.target)
            is Event.WolfChoice -> o.put("k", "wolf").put("b", e.target).put("c", e.byChance)
            is Event.Protect -> o.put("k", "prot").put("a", e.doctor).put("b", e.target)
            is Event.Seen -> o.put("k", "seen").put("a", e.seer).put("b", e.target).put("c", e.isWolf)
            is Event.Suspect -> o.put("k", "susp").put("a", e.player).put("b", e.target)
            is Event.Saved -> o.put("k", "saved").put("b", e.target)
            is Event.Died -> o.put("k", "died").put("b", e.player).put("cause", e.cause.name)
            is Event.Day -> o.put("k", "day").put("n", e.n)
            is Event.NobodyVoted -> o.put("k", "nobody")
            is Event.Win -> o.put("k", "win").put("team", e.team.name)
            is Event.FrameVote -> o.put("k", "frame").put("a", e.wolf).put("b", e.target)
            is Event.Scene -> o.put("k", "scene").put("b", e.victim).put("c", e.saved)
                .put("s", JSONArray().also { a -> e.suspects.forEach { a.put(it) } })
            is Event.Witness -> o.put("k", "wit").put("a", e.witness).put("b", e.a).put("d", e.b).put("c", e.corrupted)
            is Event.CorruptionUsed -> o.put("k", "corrupt")
            is Event.VoteCast -> o.put("k", "votecast").put("a", e.voter).put("b", e.target)
        }
        return o
    }

    private fun eventFrom(o: JSONObject): Event? = when (o.getString("k")) {
        "night" -> Event.Night(o.getInt("n"))
        "prop" -> Event.Propose(o.getInt("a"), o.getInt("b"))
        "wolf" -> Event.WolfChoice(o.getInt("b"), o.getBoolean("c"))
        "prot" -> Event.Protect(o.getInt("a"), o.getInt("b"))
        "seen" -> Event.Seen(o.getInt("a"), o.getInt("b"), o.getBoolean("c"))
        "susp" -> Event.Suspect(o.getInt("a"), o.getInt("b"))
        "saved" -> Event.Saved(o.getInt("b"))
        "died" -> Event.Died(o.getInt("b"), Cause.valueOf(o.getString("cause")))
        "day" -> Event.Day(o.getInt("n"))
        "nobody" -> Event.NobodyVoted
        "win" -> Event.Win(Team.valueOf(o.getString("team")))
        "frame" -> Event.FrameVote(o.getInt("a"), o.getInt("b"))
        "scene" -> Event.Scene(o.getInt("b"), o.getBoolean("c"), o.getJSONArray("s").let { a -> (0 until a.length()).map { a.getInt(it) } })
        "wit" -> Event.Witness(o.getInt("a"), o.getInt("b"), o.getInt("d"), o.getBoolean("c"))
        "corrupt" -> Event.CorruptionUsed
        "votecast" -> Event.VoteCast(o.getInt("a"), o.getInt("b"))
        else -> null
    }

    companion object {
        const val RESUME_MS = 6 * 60 * 60 * 1000L
    }
}
