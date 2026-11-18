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
    data class Dawn(val deaths: List<Int>, val saved: Boolean) : Screen()
    data object Hunter : Screen()
    data object Day : Screen()
    data object Vote : Screen()
    data object TieSelect : Screen()
    data class TieVote(val candidates: List<Int>) : Screen()
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
    private var checkpoint: String? = null

    init {
        restoreState()
    }

    fun setup() = RoleSetup(lobos, vidente, doctor, cazador)
    fun setupError(): String? = Rules.setupError(names.size, setup())
    fun aldeanos() = names.size - lobos - setup().specials()

    val inGame get() = match != null && screen !is Screen.Home && screen !is Screen.HowTo && screen !is Screen.Setup && screen !is Screen.End

    fun recommended() = Rules.recommended(names.size)
    val isRecommended get() = recommended() == setup()

    fun applyRecommended() {
        val r = recommended()
        lobos = r.lobos; vidente = r.vidente; doctor = r.doctor; cazador = r.cazador
        saveConfig()
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
            .apply()
    }

    private fun options() = Options(revealOnDeath = revealOnDeath, quietFirstNight = quietFirstNight)

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
        if (i + 1 < m.size) go(Screen.Reveal(i + 1)) else beginNight()
    }

    private fun beginNight() {
        val m = match ?: return
        m.startNight()
        order = m.nightOrder()
        go(Screen.NightPass(0))
    }

    /** Registra la acción del turno y devuelve lo que vio la Vidente, si aplica. */
    fun act(turn: Int, target: Int): SeerResult? {
        val m = match ?: return null
        val r = m.act(order[turn], target)
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
        m.vote(target)
        stage = Stage.AFTER_VOTE
        go(Screen.Eliminated(target ?: -1, if (target == null) null else Cause.VOTO))
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
            val won = m.roles[i].team == w
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
                o.put("acted", JSONArray().also { a -> m.nightActed().forEach { a.put(it) } })
            }
            is Screen.NightAct -> return checkpoint
            is Screen.Dawn -> o.put("screen", "dawn").put("deaths", JSONArray().also { a -> s.deaths.forEach { a.put(it) } }).put("saved", s.saved)
            is Screen.Hunter -> o.put("screen", "hunter")
            is Screen.Day, is Screen.Vote, is Screen.TieSelect, is Screen.TieVote -> o.put("screen", "day")
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
            val m = Match(nm, rl, Options(revealOnDeath = o.getBoolean("reveal"), quietFirstNight = o.getBoolean("quiet")), rng)
            m.restore(al, o.getInt("night"), o.getInt("docLast"), o.getBoolean("docSelf"), o.getInt("hunter"), win, ev)
            match = m
            stage = Stage.valueOf(o.getString("stage"))
            order = o.getJSONArray("order").let { a -> (0 until a.length()).map { a.getInt(it) } }
            if (o.getString("screen") == "night") {
                val pr = o.optJSONObject("proposals")
                val props = mutableMapOf<Int, Int>()
                if (pr != null) for (k in pr.keys()) props[k.toInt()] = pr.getInt(k)
                val ac = o.optJSONArray("acted")
                val done = if (ac == null) emptySet() else (0 until ac.length()).map { ac.getInt(it) }.toSet()
                m.restoreNight(props, o.optInt("protect", -1), done)
            }
            screen = when (o.getString("screen")) {
                "reveal" -> Screen.Reveal(0)
                "night" -> Screen.NightPass(o.optInt("turn", 0).coerceIn(0, maxOf(0, order.size - 1)))
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
        else -> null
    }

    companion object {
        const val RESUME_MS = 6 * 60 * 60 * 1000L
    }
}
