package io.github.sebasgl1.lobo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class RulesTest {
    private val names = listOf("Ana", "Beto", "Caro", "Dani", "Eli", "Fer", "Gabi", "Hugo")

    /** Partida armada a mano: 0,1 lobos · 2 vidente · 3 doctor · 4 cazador · 5,6,7 aldeanos. */
    private fun match(opts: Options = Options()) = Match(
        names,
        listOf(Role.LOBO, Role.LOBO, Role.VIDENTE, Role.DOCTOR, Role.CAZADOR, Role.ALDEANO, Role.ALDEANO, Role.ALDEANO),
        opts, Random(1),
    )

    /** Juega una noche: los lobos atacan [victim], el doctor protege [protect], los demás sospechan. */
    private fun Match.playNight(victim: Int, protect: Int, seerTarget: Int = 0): DawnResult {
        startNight()
        for (p in nightOrder()) {
            val t = when (actionFor(p)) {
                Action.ATACAR -> victim
                Action.PROTEGER -> protect
                Action.INVESTIGAR -> if (seerTarget in targetsFor(p)) seerTarget else targetsFor(p).first()
                Action.SOSPECHAR -> targetsFor(p).first()
            }
            act(p, t)
        }
        return resolveDawn()
    }

    @Test fun repartoRecomendado() {
        assertEquals(RoleSetup(1, true, false, false), Rules.recommended(5))
        assertEquals(RoleSetup(2, true, true, false), Rules.recommended(7))
        assertEquals(RoleSetup(2, true, true, true), Rules.recommended(8))
        assertEquals(RoleSetup(3, true, true, true), Rules.recommended(12))
        for (n in 5..20) assertNull("n=$n", Rules.setupError(n, Rules.recommended(n)))
        assertTrue(Rules.setupError(4, Rules.recommended(4)) != null)
        assertTrue(Rules.setupError(6, RoleSetup(3, true, false, false)) != null)
    }

    @Test fun repartoTieneLosRolesPedidos() {
        val rng = Random(3)
        repeat(200) {
            val r = Rules.assignRoles(names, RoleSetup(2, true, true, true), emptyMap(), rng)
            assertEquals(2, r.count { it == Role.LOBO })
            assertEquals(1, r.count { it == Role.VIDENTE })
            assertEquals(1, r.count { it == Role.DOCTOR })
            assertEquals(1, r.count { it == Role.CAZADOR })
            assertEquals(3, r.count { it == Role.ALDEANO })
        }
    }

    @Test fun repartoJustoSinRachasLargasDeLobo() {
        val rng = Random(9)
        var hist = mapOf<String, String>()
        var streaks = 0
        val rounds = 4000
        repeat(rounds) {
            val r = Rules.assignRoles(names, RoleSetup(2, true, true, true), hist, rng)
            hist = Rules.pushHistory(hist, names, r)
            names.forEach { n -> if ((hist[n] ?: "").endsWith("LLL")) streaks++ }
        }
        assertTrue("rachas $streaks", streaks < rounds * 0.02)
    }

    @Test fun lobosMatanDeNoche() {
        val m = match()
        val d = m.playNight(victim = 5, protect = 6)
        assertEquals(listOf(5), d.deaths)
        assertFalse(m.alive[5])
        assertNull(m.winner)
    }

    @Test fun doctorSalvaALaVictima() {
        val m = match()
        val d = m.playNight(victim = 5, protect = 5)
        assertTrue(d.saved)
        assertTrue(d.deaths.isEmpty())
        assertTrue(m.alive[5])
    }

    @Test fun doctorNoRepiteNiSeProtegeDosVeces() {
        val m = match()
        m.playNight(victim = 5, protect = 3)          // se protege a sí mismo (1 vez)
        m.startNight()
        val t = m.targetsFor(3)
        assertFalse("no repite al mismo", 3 in t)
        m.nightOrder().forEach { p -> m.act(p, m.targetsFor(p).first { it != 3 || m.actionFor(p) != Action.ATACAR }) }
        m.resolveDawn()
        m.startNight()
        assertFalse("ya usó su protección propia", 3 in m.targetsFor(3))
    }

    @Test fun lobosNoSeAtacanEntreEllos() {
        val m = match()
        m.startNight()
        assertFalse(m.targetsFor(0).any { m.isWolf(it) })
    }

    @Test fun elSegundoLoboVeLaPropuestaYEmpateSeDecideAlAzar() {
        val m = match()
        m.startNight()
        val order = m.nightOrder()
        val firstWolf = order.first { m.isWolf(it) }
        val secondWolf = order.last { m.isWolf(it) }
        m.act(firstWolf, 5)
        assertEquals(mapOf(firstWolf to 5), m.proposalsSoFar())
        m.act(secondWolf, 6)
        order.filter { !m.hasActed(it) }.forEach { p -> m.act(p, m.targetsFor(p).first()) }
        val d = m.resolveDawn()
        assertEquals(1, d.deaths.size)
        assertTrue(d.deaths[0] == 5 || d.deaths[0] == 6)
        assertTrue(m.log.any { it is Event.WolfChoice && it.byChance })
    }

    @Test fun videnteVeSiEsLobo() {
        val m = match()
        m.startNight()
        assertEquals(true, m.act(2, 0)?.isWolf)
        val m2 = match()
        m2.startNight()
        assertEquals(false, m2.act(2, 5)?.isWolf)
    }

    @Test fun todosTocanUnNombreYLosMuertosNoJuegan() {
        val m = match()
        m.playNight(victim = 5, protect = 6)
        m.startNight()
        assertFalse(5 in m.nightOrder())
        assertEquals(7, m.nightOrder().size)
        m.nightOrder().forEach { assertTrue(m.targetsFor(it).isNotEmpty()) }
    }

    @Test fun ordenEmpiezaPorAlguienDistintoCadaNoche() {
        val m = match()
        m.startNight(); val a = m.nightOrder().first()
        m.nightOrder().forEach { m.act(it, m.targetsFor(it).first()) }
        m.resolveDawn()
        m.startNight(); val b = m.nightOrder().first()
        assertTrue(a != b)
    }

    @Test fun cazadorMuertoDeNocheDisparaYDecideElGanador() {
        val m = match()
        m.playNight(victim = 4, protect = 6)          // matan al cazador
        assertEquals(4, m.pendingHunter)
        assertNull(m.winner)
        m.hunterShoot(0)
        assertEquals(-1, m.pendingHunter)
        assertFalse(m.alive[0])
        assertNull(m.winner)
    }

    @Test fun cazadorSeLlevaAlUltimoLoboYGanaElPueblo() {
        val m = match()
        m.vote(0)                                     // cae un lobo
        m.vote(4)                                     // cae el cazador
        assertNull("espera el disparo", m.winner)
        m.hunterShoot(1)                              // se lleva al otro lobo
        assertEquals(Team.PUEBLO, m.winner)
    }

    @Test fun lobosGananConParidadAunqueSeaDeDia() {
        val m = Match(listOf("A", "B", "C", "D", "E"), listOf(Role.LOBO, Role.ALDEANO, Role.ALDEANO, Role.VIDENTE, Role.ALDEANO), Options(), Random(2))
        m.vote(1)
        m.vote(2)
        assertNull(m.winner)                          // 1 lobo vs 2
        m.vote(3)
        assertEquals(Team.LOBOS, m.winner)            // 1 lobo vs 1
    }

    @Test fun noEliminarANadie() {
        val m = match()
        m.vote(null)
        assertEquals(8, m.aliveList().size)
        assertTrue(m.log.last() is Event.NobodyVoted)
    }

    @Test fun primeraNocheTranquila() {
        val m = match(Options(quietFirstNight = true))
        m.startNight()
        assertEquals(Action.SOSPECHAR, m.actionFor(0))
        assertEquals(Action.INVESTIGAR, m.actionFor(2))
        m.nightOrder().forEach { m.act(it, m.targetsFor(it).first()) }
        val d = m.resolveDawn()
        assertTrue(d.deaths.isEmpty() && !d.saved)
        m.startNight()
        assertEquals(Action.ATACAR, m.actionFor(0))
    }

    @Test fun nadieActuaDosVecesNiMuerto() {
        val m = match()
        m.startNight()
        m.act(5, m.targetsFor(5).first())
        assertTrue(runCatching { m.act(5, m.targetsFor(5).first()) }.isFailure)
        m.vote(6)
        assertTrue(runCatching { m.act(6, 0) }.isFailure)
    }

    @Test fun nocheGuardadaNoDejaRepetirTurno() {
        val m = match()
        m.startNight()
        val order = m.nightOrder()
        val firstWolf = order.first { m.isWolf(it) }
        m.act(firstWolf, 5)
        m.act(3, 6)                                   // doctor protege a 6
        val copy = match()
        copy.restore(m.alive.toList(), m.night, m.doctorLast, m.doctorSelfUsed, m.pendingHunter, m.winner, m.log.toList())
        copy.restoreNight(m.nightProposals(), m.nightProtect(), m.nightActed())
        assertTrue(copy.hasActed(firstWolf))
        assertTrue(runCatching { copy.act(firstWolf, 6) }.isFailure)
        copy.nightOrder().filter { !copy.hasActed(it) }.forEach { p ->
            copy.act(p, if (copy.actionFor(p) == Action.ATACAR) 6 else copy.targetsFor(p).first())
        }
        val d = copy.resolveDawn()
        // Lobos propusieron 5 y 6 (empate al azar); si salió 6, el doctor lo salvó
        assertTrue(d.deaths == listOf(5) || d.saved)
    }
}
