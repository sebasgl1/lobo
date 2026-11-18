package io.github.sebasgl1.lobo

import java.util.Random

enum class Team(val label: String) { PUEBLO("El pueblo"), LOBOS("Los lobos") }

enum class Role(val label: String, val emoji: String, val team: Team) {
    LOBO("Lobo", "🐺", Team.LOBOS),
    ALDEANO("Aldeano", "👨‍🌾", Team.PUEBLO),
    VIDENTE("Vidente", "🔮", Team.PUEBLO),
    DOCTOR("Doctor", "🩺", Team.PUEBLO),
    CAZADOR("Cazador", "🏹", Team.PUEBLO),
}

enum class Cause { LOBOS, VOTO, CAZADOR }

/** Lo que cada jugador hace en su turno de la noche. Todos tocan un nombre, para que nadie se delate. */
enum class Action { ATACAR, PROTEGER, INVESTIGAR, SOSPECHAR }

data class RoleSetup(val lobos: Int, val vidente: Boolean, val doctor: Boolean, val cazador: Boolean) {
    fun specials() = (if (vidente) 1 else 0) + (if (doctor) 1 else 0) + (if (cazador) 1 else 0)
}

data class Options(
    val revealOnDeath: Boolean = true,
    val quietFirstNight: Boolean = false,
    val seerExact: Boolean = false,
    val doctorSelfOnce: Boolean = true,
)

/** Todo lo que pasa en la partida, en orden. De aquí salen la línea de tiempo y el guardado. */
sealed class Event {
    data class Night(val n: Int) : Event()
    data class Propose(val wolf: Int, val target: Int) : Event()
    data class WolfChoice(val target: Int, val byChance: Boolean) : Event()
    data class Protect(val doctor: Int, val target: Int) : Event()
    data class Seen(val seer: Int, val target: Int, val isWolf: Boolean) : Event()
    data class Suspect(val player: Int, val target: Int) : Event()
    data class Saved(val target: Int) : Event()
    data class Died(val player: Int, val cause: Cause) : Event()
    data class Day(val n: Int) : Event()
    data object NobodyVoted : Event()
    data class Win(val team: Team) : Event()
}

data class SeerResult(val target: Int, val isWolf: Boolean, val role: Role)

data class DawnResult(val deaths: List<Int>, val saved: Boolean)

/** Reglas puras, sin nada de Android, para poder probarlas en cada compilación. */
object Rules {
    const val MIN_PLAYERS = 5

    fun recommended(n: Int): RoleSetup = RoleSetup(
        lobos = when { n <= 5 -> 1; n <= 9 -> 2; n <= 15 -> 3; else -> 4 },
        vidente = n >= 5,
        doctor = n >= 6,
        cazador = n >= 8,
    )

    fun setupError(n: Int, s: RoleSetup): String? = when {
        n < MIN_PLAYERS -> "Se necesitan mínimo $MIN_PLAYERS jugadores"
        s.lobos < 1 -> "Debe haber al menos un lobo"
        s.lobos >= n - s.lobos -> "Hay demasiados lobos para $n jugadores"
        s.lobos + s.specials() > n -> "No alcanzan los jugadores para tantos roles"
        else -> null
    }

    /** Peso para volver a ser lobo según el historial reciente (L = fue lobo, P = pueblo). */
    fun wolfWeight(h: String): Double {
        val streak = h.reversed().takeWhile { it == 'L' }.length
        val recent = h.takeLast(3).count { it == 'L' }
        return if (streak >= 2) 0.05 else 1.0 / (1.0 + 1.5 * recent)
    }

    /** Reparto justo: al azar, pero quien fue lobo hace poco tiene menos probabilidad de repetir. */
    fun assignRoles(names: List<String>, s: RoleSetup, hist: Map<String, String>, rng: Random): List<Role> {
        val pool = names.indices.toMutableList()
        val wolves = mutableSetOf<Int>()
        repeat(s.lobos) {
            val w = pool.map { wolfWeight(hist[names[it]] ?: "") }
            var r = rng.nextDouble() * w.sum()
            var pick = pool.last()
            for ((j, i) in pool.withIndex()) {
                r -= w[j]
                if (r <= 0) { pick = i; break }
            }
            wolves.add(pick)
            pool.remove(pick)
        }
        val specials = mutableListOf<Role>()
        if (s.vidente) specials.add(Role.VIDENTE)
        if (s.doctor) specials.add(Role.DOCTOR)
        if (s.cazador) specials.add(Role.CAZADOR)
        pool.shuffle(rng)
        val roles = Array(names.size) { Role.ALDEANO }
        wolves.forEach { roles[it] = Role.LOBO }
        specials.forEachIndexed { k, r -> roles[pool[k]] = r }
        return roles.toList()
    }

    fun pushHistory(hist: Map<String, String>, names: List<String>, roles: List<Role>): Map<String, String> {
        val out = hist.toMutableMap()
        names.forEachIndexed { i, n -> out[n] = ((out[n] ?: "") + if (roles[i] == Role.LOBO) 'L' else 'P').takeLast(6) }
        return out
    }
}

/**
 * Una partida. Se juega pasando el celular: cada noche todos los vivos reciben un turno
 * y tocan un nombre; la app resuelve todo al amanecer.
 */
class Match(
    val names: List<String>,
    val roles: List<Role>,
    val options: Options,
    private val rng: Random,
) {
    val alive = BooleanArray(names.size) { true }
    var night = 0
        private set
    val log = mutableListOf<Event>()

    var doctorLast = -1
        private set
    var doctorSelfUsed = false
        private set
    /** Cazador muerto que todavía debe disparar. */
    var pendingHunter = -1
        private set
    var winner: Team? = null
        private set

    // Acciones de la noche en curso
    private val proposals = LinkedHashMap<Int, Int>()
    private var protectTarget = -1
    private val acted = mutableSetOf<Int>()

    val size get() = names.size
    fun isWolf(p: Int) = roles[p] == Role.LOBO
    fun aliveList() = names.indices.filter { alive[it] }
    fun wolvesAlive() = names.indices.count { alive[it] && isWolf(it) }
    fun othersAlive() = names.indices.count { alive[it] && !isWolf(it) }
    fun wolfMates(p: Int) = names.indices.filter { it != p && isWolf(it) }

    /** Empieza una noche nueva. */
    fun startNight() {
        night++
        proposals.clear()
        protectTarget = -1
        acted.clear()
        log.add(Event.Night(night))
    }

    val isQuietNight get() = options.quietFirstNight && night == 1

    /** Orden de turnos: los vivos en círculo, empezando cada noche por alguien distinto. */
    fun nightOrder(): List<Int> {
        val a = aliveList()
        if (a.isEmpty()) return a
        val start = (night - 1).mod(a.size)
        return a.drop(start) + a.take(start)
    }

    fun actionFor(p: Int): Action = when {
        roles[p] == Role.VIDENTE -> Action.INVESTIGAR
        isQuietNight -> Action.SOSPECHAR
        roles[p] == Role.LOBO -> Action.ATACAR
        roles[p] == Role.DOCTOR -> Action.PROTEGER
        else -> Action.SOSPECHAR
    }

    fun doctorCanSelf(): Boolean = !options.doctorSelfOnce || !doctorSelfUsed

    fun targetsFor(p: Int): List<Int> = aliveList().filter { t ->
        when (actionFor(p)) {
            Action.ATACAR -> !isWolf(t)
            Action.PROTEGER -> t != doctorLast && (t != p || doctorCanSelf())
            Action.INVESTIGAR, Action.SOSPECHAR -> t != p
        }
    }

    /** Propuestas de los lobos que ya jugaron su turno esta noche (las ve el siguiente lobo). */
    fun proposalsSoFar(): Map<Int, Int> = proposals.toMap()

    fun hasActed(p: Int) = p in acted

    /** Registra la acción de un jugador. Para la Vidente devuelve lo que descubrió. */
    fun act(p: Int, target: Int): SeerResult? {
        require(alive[p]) { "Un jugador muerto no puede actuar" }
        require(p !in acted) { "Ya jugó esta noche" }
        require(target in targetsFor(p)) { "Objetivo no permitido" }
        acted.add(p)
        return when (actionFor(p)) {
            Action.ATACAR -> { proposals[p] = target; log.add(Event.Propose(p, target)); null }
            Action.PROTEGER -> {
                protectTarget = target
                if (target == p) doctorSelfUsed = true
                log.add(Event.Protect(p, target))
                null
            }
            Action.INVESTIGAR -> {
                val r = SeerResult(target, isWolf(target), roles[target])
                log.add(Event.Seen(p, target, r.isWolf))
                r
            }
            Action.SOSPECHAR -> { log.add(Event.Suspect(p, target)); null }
        }
    }

    /** Víctima de los lobos: la más propuesta; si empatan, al azar entre las empatadas. */
    private fun wolfTarget(): Int {
        if (proposals.isEmpty()) return -1
        val counts = proposals.values.groupingBy { it }.eachCount()
        val max = counts.values.max()
        val top = counts.filter { it.value == max }.keys.toList()
        val pick = if (top.size == 1) top[0] else top[rng.nextInt(top.size)]
        log.add(Event.WolfChoice(pick, top.size > 1))
        return pick
    }

    /** Resuelve la noche: todo pasa al mismo tiempo. */
    fun resolveDawn(): DawnResult {
        val target = wolfTarget()
        doctorLast = protectTarget
        val deaths = mutableListOf<Int>()
        var saved = false
        if (target >= 0) {
            if (target == protectTarget) {
                saved = true
                log.add(Event.Saved(target))
            } else {
                kill(target, Cause.LOBOS)
                deaths.add(target)
            }
        }
        log.add(Event.Day(night))
        checkWinner()
        return DawnResult(deaths, saved)
    }

    /** Eliminación por votación del pueblo; null = no eliminar a nadie. */
    fun vote(target: Int?) {
        if (target == null) {
            log.add(Event.NobodyVoted)
            return
        }
        require(alive[target]) { "Ya estaba muerto" }
        kill(target, Cause.VOTO)
        checkWinner()
    }

    fun hunterTargets(): List<Int> = aliveList()

    /** El Cazador muerto se lleva a alguien con él. */
    fun hunterShoot(target: Int) {
        require(pendingHunter >= 0) { "No hay cazador pendiente" }
        require(alive[target]) { "Ya estaba muerto" }
        pendingHunter = -1
        kill(target, Cause.CAZADOR)
        checkWinner()
    }

    private fun kill(p: Int, cause: Cause) {
        alive[p] = false
        log.add(Event.Died(p, cause))
        if (roles[p] == Role.CAZADOR) pendingHunter = p
    }

    /** Si hay un cazador por disparar, el ganador se decide después del disparo. */
    private fun checkWinner() {
        if (winner != null || pendingHunter >= 0) return
        val w = when {
            wolvesAlive() == 0 -> Team.PUEBLO
            wolvesAlive() >= othersAlive() -> Team.LOBOS
            else -> null
        }
        if (w != null) {
            winner = w
            log.add(Event.Win(w))
        }
    }

    /** Avance de la noche en curso (para guardarla si Android cierra la app a mitad de la ronda). */
    fun nightProposals(): Map<Int, Int> = proposals.toMap()
    fun nightProtect(): Int = protectTarget
    fun nightActed(): Set<Int> = acted.toSet()

    /** Restaura el avance de la noche: quien ya jugó no vuelve a jugar ni puede cambiar su elección. */
    fun restoreNight(props: Map<Int, Int>, protect: Int, done: Set<Int>) {
        proposals.clear(); proposals.putAll(props)
        protectTarget = protect
        acted.clear(); acted.addAll(done)
    }

    /** Restaura una partida guardada reproduciendo sus datos básicos. */
    fun restore(aliveNow: List<Boolean>, nightNow: Int, docLast: Int, docSelf: Boolean, hunter: Int, win: Team?, events: List<Event>) {
        aliveNow.forEachIndexed { i, v -> alive[i] = v }
        night = nightNow
        doctorLast = docLast
        doctorSelfUsed = docSelf
        pendingHunter = hunter
        winner = win
        log.clear()
        log.addAll(events)
    }
}
