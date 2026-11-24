package io.github.sebasgl1.lobo

import java.util.Random

enum class Team(val label: String) { PUEBLO("El pueblo"), LOBOS("Los lobos") }

enum class Role(val label: String, val emoji: String, val team: Team) {
    LOBO("Lobo", "🐺", Team.LOBOS),
    ALDEANO("Aldeano", "👨‍🌾", Team.PUEBLO),
    VIDENTE("Vidente", "🔮", Team.PUEBLO),
    DOCTOR("Doctor", "🩺", Team.PUEBLO),
    CAZADOR("Cazador", "🏹", Team.PUEBLO),
    /** Solo en Modo Historia con narrador: el aldeano fijo que es testigo en las noches que toca. */
    TESTIGO("Testigo", "🕯️", Team.PUEBLO),
    /** Aliado secreto de los lobos: conoce a la manada, ellos no lo conocen y la Vidente lo ve inocente. */
    SABOTEADOR("Saboteador", "🗡️", Team.LOBOS),
    /** Gana solo si el pueblo lo elimina en una votación (la partida sigue sin él). */
    BUFON("Bufón", "🃏", Team.PUEBLO),
}

enum class Cause { LOBOS, VOTO, CAZADOR }

/** Lo que cada jugador hace en su turno de la noche. Todos tocan un nombre, para que nadie se delate. */
enum class Action { ATACAR, PROTEGER, INVESTIGAR, SOSPECHAR }

data class RoleSetup(
    val lobos: Int, val vidente: Boolean, val doctor: Boolean, val cazador: Boolean, val testigo: Boolean = false,
    val saboteador: Boolean = false, val bufon: Boolean = false,
) {
    fun specials() = listOf(vidente, doctor, cazador, testigo, saboteador, bufon).count { it }
}

data class Options(
    val revealOnDeath: Boolean = true,
    val quietFirstNight: Boolean = false,
    val seerExact: Boolean = false,
    val doctorSelfOnce: Boolean = true,
    /** Modo Historia: escena del crimen, incriminación y testigos. */
    val story: Boolean = false,
    /** Cada cuántas noches hay testigo en Modo Historia: 0 = nunca, 1 = cada noche, 2 = noche por medio. */
    val witnessEvery: Int = 1,
    /** Primera noche con testigo. */
    val witnessFrom: Int = 1,
    /** Cuántas personas aparecen en la escena del crimen (3 o 4). */
    val sceneSize: Int = 3,
    /** Testigo difícil: la mitad de las veces, el inocente que vio también aparece en la escena (así cruzar pistas no delata al lobo). */
    val witnessInScene: Boolean = false,
    /** Los lobos pueden comprar al testigo de la noche siguiente, una vez por partida. */
    val corruption: Boolean = false,
    /** Modo narrador: el celular queda en la mesa y la voz llama a cada rol con los ojos cerrados. */
    val narrator: Boolean = false,
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
    // Modo Historia
    data class FrameVote(val wolf: Int, val target: Int) : Event()
    data class Scene(val victim: Int, val saved: Boolean, val suspects: List<Int>) : Event()
    data class Witness(val witness: Int, val a: Int, val b: Int, val corrupted: Boolean) : Event()
    data object CorruptionUsed : Event()
    data class VoteCast(val voter: Int, val target: Int) : Event()
}

/** A quién llama el narrador, en orden, durante la noche. */
enum class Call { INTRO, LOBOS, DOCTOR, VIDENTE, TESTIGO, AMANECER }

/** Un llamado del narrador. actor = -1: ese rol ya murió y el turno se simula para no delatarlo. */
data class NarratorStep(val call: Call, val actor: Int)

data class SeerResult(val target: Int, val isWolf: Boolean, val role: Role)

data class DawnResult(val deaths: List<Int>, val saved: Boolean, val scene: List<Int> = emptyList())

/** Reglas puras, sin nada de Android, para poder probarlas en cada compilación. */
object Rules {
    const val MIN_PLAYERS = 5

    fun recommended(n: Int): RoleSetup = RoleSetup(
        lobos = when { n <= 5 -> 1; n <= 9 -> 2; n <= 15 -> 3; else -> 4 },
        vidente = n >= 5,
        doctor = n >= 6,
        cazador = n >= 8,
    )

    /**
     * Reparto recomendado para el Modo Historia, para que pueblo y lobos ganen parecido (simulaciones: 44–58%).
     * Desde 8 jugadores hay un Saboteador y desde 12 un lobo más.
     */
    fun recommendedStory(n: Int): RoleSetup = recommended(n).let { r ->
        r.copy(lobos = r.lobos + if (n >= 12) 1 else 0, saboteador = n >= 8)
    }

    /** Sospechosos en la escena del crimen: 3 en grupos pequeños, 4 desde 7 jugadores. */
    fun sceneSize(n: Int) = if (n >= 7) 4 else 3

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
        if (s.testigo) specials.add(Role.TESTIGO)
        if (s.saboteador) specials.add(Role.SABOTEADOR)
        if (s.bufon) specials.add(Role.BUFON)
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

    // Modo Historia
    var witness = -1
        private set
    var witnessPair: Pair<Int, Int>? = null
        private set
    var corruptNext = false
        private set
    var corruptionUsed = false
        private set
    /** Testigo difícil: el inocente que vio el testigo y que también saldrá en la escena (-1 = nadie). */
    var sceneGuest = -1
        private set

    // Acciones de la noche en curso
    private val frames = LinkedHashMap<Int, Int>()
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
        frames.clear()
        protectTarget = -1
        acted.clear()
        log.add(Event.Night(night))
        chooseWitness()
    }

    /** ¿Esta noche hay testigo? (según la frecuencia escogida). */
    val isWitnessNight get() = options.story && options.witnessEvery > 0 && night >= options.witnessFrom &&
        (night - options.witnessFrom) % options.witnessEvery == 0

    /** ¿El Bufón logró que el pueblo lo eliminara? */
    val bufonWon get() = log.any { it is Event.Died && it.cause == Cause.VOTO && roles[it.player] == Role.BUFON }

    /** Quién ganó, jugador por jugador (el Bufón gana aparte; el Saboteador gana con los lobos). */
    fun isWinner(p: Int): Boolean = when {
        roles[p] == Role.BUFON -> bufonWon
        else -> winner != null && roles[p].team == winner
    }

    /**
     * Modo Historia: un aldeano vivo ve a dos personas; una es lobo (salvo que los lobos lo hayan comprado).
     * Pasando el celular es un aldeano al azar cada noche; con narrador es siempre el Testigo.
     */
    private fun chooseWitness() {
        witness = -1
        witnessPair = null
        sceneGuest = -1
        if (!isWitnessNight) return
        val villagers = if (options.narrator) aliveList().filter { roles[it] == Role.TESTIGO }
            else aliveList().filter { roles[it] == Role.ALDEANO }
        if (villagers.isEmpty()) return
        val w = villagers[rng.nextInt(villagers.size)]
        val innocents = aliveList().filter { !isWolf(it) && it != w }
        val wolves = aliveList().filter { isWolf(it) }
        val corrupted = corruptNext && innocents.size >= 2
        val pair: List<Int> = if (corrupted) {
            innocents.shuffled(rng).take(2)
        } else {
            if (wolves.isEmpty() || innocents.isEmpty()) return
            listOf(wolves[rng.nextInt(wolves.size)], innocents[rng.nextInt(innocents.size)]).shuffled(rng)
        }
        if (corrupted) corruptNext = false
        witness = w
        witnessPair = pair[0] to pair[1]
        if (options.witnessInScene && !corrupted && rng.nextBoolean()) sceneGuest = pair.first { !isWolf(it) }
        log.add(Event.Witness(w, pair[0], pair[1], corrupted))
    }

    /** Lo que vio el testigo, solo para él. */
    fun witnessFor(p: Int): Pair<Int, Int>? = if (p == witness) witnessPair else null

    /** Modo Historia: los lobos escogen a un inocente para dejar pistas falsas en la escena. */
    fun frameTargets(p: Int): List<Int> = if (options.story && isWolf(p)) aliveList().filter { !isWolf(it) } else emptyList()

    fun frame(p: Int, target: Int) {
        require(options.story && isWolf(p) && alive[p]) { "Solo los lobos incriminan en Modo Historia" }
        require(target in frameTargets(p)) { "Objetivo no permitido" }
        frames[p] = target
        log.add(Event.FrameVote(p, target))
    }

    val canCorrupt get() = options.story && options.corruption && !corruptionUsed

    /** Los lobos compran al testigo de la noche siguiente (una vez por partida). */
    fun requestCorruption(p: Int) {
        require(isWolf(p) && canCorrupt) { "No se puede comprar al testigo" }
        corruptionUsed = true
        corruptNext = true
        log.add(Event.CorruptionUsed)
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

    /**
     * Guion de la noche con narrador. Se llama a todos los roles que hay en la partida, aunque estén
     * muertos (con turno simulado), para que nadie sepa quién murió por los llamados.
     */
    fun narratorSteps(): List<NarratorStep> {
        fun actorOf(r: Role): Int = holder(r).let { if (it >= 0 && alive[it]) it else -1 }
        val out = mutableListOf(NarratorStep(Call.INTRO, -1))
        out.add(NarratorStep(Call.LOBOS, packActor()))
        if (holder(Role.DOCTOR) >= 0 && !isQuietNight) out.add(NarratorStep(Call.DOCTOR, actorOf(Role.DOCTOR)))
        if (holder(Role.VIDENTE) >= 0) out.add(NarratorStep(Call.VIDENTE, actorOf(Role.VIDENTE)))
        if (holder(Role.TESTIGO) >= 0 && isWitnessNight) out.add(NarratorStep(Call.TESTIGO, if (witness >= 0) witness else -1))
        out.add(NarratorStep(Call.AMANECER, -1))
        return out
    }

    /** Quién tiene un rol (vivo o muerto); -1 si no está en la partida. */
    fun holder(r: Role): Int = roles.indexOf(r)

    /** Con narrador, los lobos escogen juntos y uno toca el celular: se anota a nombre del primer lobo vivo. */
    fun packActor(): Int = names.indices.firstOrNull { alive[it] && isWolf(it) } ?: -1

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
                // El Saboteador engaña a la Vidente: sale como un aldeano cualquiera
                val shown = if (roles[target] == Role.SABOTEADOR) Role.ALDEANO else roles[target]
                val r = SeerResult(target, isWolf(target), shown)
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
        val scene = if (options.story) buildScene(target, saved, deaths) else emptyList()
        log.add(Event.Day(night))
        checkWinner()
        return DawnResult(deaths, saved, scene)
    }

    /**
     * Escena del crimen: un lobo vivo, el inocente incriminado y el resto al azar hasta completar
     * [Options.sceneSize]. Con testigo difícil, a veces uno de esos lugares es el inocente que vio el testigo.
     */
    private fun buildScene(target: Int, saved: Boolean, deaths: List<Int>): List<Int> {
        val wolves = aliveList().filter { isWolf(it) }
        if (wolves.isEmpty()) return emptyList()
        val wolf = wolves[rng.nextInt(wolves.size)]
        val frameOptions = frames.values.filter { alive[it] && !isWolf(it) && it !in deaths }
        val framed = if (frameOptions.isNotEmpty()) {
            val counts = frameOptions.groupingBy { it }.eachCount()
            val max = counts.values.max()
            val top = counts.filter { it.value == max }.keys.toList()
            top[rng.nextInt(top.size)]
        } else {
            val inn = aliveList().filter { !isWolf(it) }
            if (inn.isEmpty()) -1 else inn[rng.nextInt(inn.size)]
        }
        val chosen = mutableListOf(wolf)
        if (framed >= 0) chosen.add(framed)
        val size = options.sceneSize.coerceIn(2, 6)
        if (sceneGuest >= 0 && alive[sceneGuest] && sceneGuest !in chosen && chosen.size < size) chosen.add(sceneGuest)
        val rest = aliveList().filter { it !in chosen }.shuffled(rng)
        chosen.addAll(rest.take((size - chosen.size).coerceAtLeast(0)))
        val suspects = chosen.shuffled(rng)
        log.add(Event.Scene(target, saved, suspects))
        return suspects
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
    fun nightFrames(): Map<Int, Int> = frames.toMap()
    fun nightProtect(): Int = protectTarget
    fun nightActed(): Set<Int> = acted.toSet()

    /** Restaura el avance de la noche: quien ya jugó no vuelve a jugar ni puede cambiar su elección. */
    fun restoreNight(props: Map<Int, Int>, protect: Int, done: Set<Int>, frameProps: Map<Int, Int> = emptyMap()) {
        proposals.clear(); proposals.putAll(props)
        frames.clear(); frames.putAll(frameProps)
        protectTarget = protect
        acted.clear(); acted.addAll(done)
    }

    /** Restaura una partida guardada reproduciendo sus datos básicos. */
    /** Datos del Modo Historia al restaurar una partida guardada. */
    fun restoreStory(w: Int, pair: Pair<Int, Int>?, corruptN: Boolean, corruptUsed: Boolean, guest: Int = -1) {
        witness = w; witnessPair = pair; corruptNext = corruptN; corruptionUsed = corruptUsed; sceneGuest = guest
    }

    /** Registra quién votó por quién en el día (opcional). */
    fun recordVote(voter: Int, target: Int) { log.add(Event.VoteCast(voter, target)) }

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
