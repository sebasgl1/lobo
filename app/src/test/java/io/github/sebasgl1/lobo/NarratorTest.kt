package io.github.sebasgl1.lobo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Modo narrador: guion de la noche, turnos simulados de roles muertos y Testigo fijo. */
class NarratorTest {
    private val names = listOf("Ana", "Beto", "Caro", "Dani", "Eli", "Fer", "Gabi", "Hugo")
    // 0,1 lobos · 2 vidente · 3 doctor · 4 testigo · 5,6,7 aldeanos
    private val roles = listOf(Role.LOBO, Role.LOBO, Role.VIDENTE, Role.DOCTOR, Role.TESTIGO, Role.ALDEANO, Role.ALDEANO, Role.ALDEANO)

    private fun match(opts: Options, seed: Long = 1) = Match(names, roles, opts, Random(seed))
    private fun calls(m: Match) = m.narratorSteps().map { it.call }

    @Test fun guionCompletoEnModoHistoria() {
        val m = match(Options(story = true, narrator = true))
        m.startNight()
        assertEquals(listOf(Call.INTRO, Call.LOBOS, Call.DOCTOR, Call.VIDENTE, Call.TESTIGO, Call.AMANECER), calls(m))
        val st = m.narratorSteps()
        assertEquals(0, st[1].actor)          // el primer lobo vivo anota por la manada
        assertEquals(3, st[2].actor)
        assertEquals(2, st[3].actor)
        assertEquals(4, st[4].actor)          // el testigo es siempre el rol Testigo
    }

    @Test fun clasicoNoLlamaAlTestigo() {
        val m = match(Options(narrator = true))
        m.startNight()
        assertEquals(listOf(Call.INTRO, Call.LOBOS, Call.DOCTOR, Call.VIDENTE, Call.AMANECER), calls(m))
        assertEquals(-1, m.witness)
    }

    @Test fun testigoFijoVeUnLoboYNoSeVeASiMismo() {
        repeat(300) { seed ->
            val m = match(Options(story = true, narrator = true), seed.toLong())
            m.startNight()
            assertEquals(4, m.witness)
            val (a, b) = m.witnessPair!!
            assertTrue(a != 4 && b != 4 && a != b)
            assertEquals(1, listOf(a, b).count { m.isWolf(it) })
        }
    }

    @Test fun testigoNochePorMedio() {
        val m = match(Options(story = true, narrator = true, witnessEvery = 2))
        m.startNight(); assertTrue(Call.TESTIGO in calls(m)); m.resolveDawn(); m.vote(null)
        m.startNight(); assertFalse(Call.TESTIGO in calls(m)); m.resolveDawn(); m.vote(null)
        m.startNight(); assertTrue(Call.TESTIGO in calls(m))
    }

    @Test fun rolesMuertosSeLlamanIgualConTurnoSimulado() {
        val m = match(Options(story = true, narrator = true))
        m.startNight()
        m.act(m.packActor(), 3)                // los lobos matan al doctor
        m.resolveDawn()
        m.vote(4)                               // el pueblo elimina al testigo
        m.startNight()
        val st = m.narratorSteps()
        assertEquals(-1, st.first { it.call == Call.DOCTOR }.actor)
        assertEquals(-1, st.first { it.call == Call.TESTIGO }.actor)
        assertEquals(-1, m.witness)
        assertEquals(2, st.first { it.call == Call.VIDENTE }.actor)
    }

    @Test fun primeraNocheTranquilaNoLlamaAlDoctor() {
        val m = match(Options(narrator = true, quietFirstNight = true))
        m.startNight()
        assertFalse(Call.DOCTOR in calls(m))
        m.resolveDawn(); m.vote(null); m.startNight()
        assertTrue(Call.DOCTOR in calls(m))
    }

    @Test fun repartoConTestigo() {
        val rng = Random(7)
        repeat(500) {
            val n = 7 + rng.nextInt(8)
            val nm = (1..n).map { "J$it" }
            val setup = Rules.recommended(n).copy(testigo = true)
            val r = Rules.assignRoles(nm, setup, emptyMap(), rng)
            assertEquals(1, r.count { it == Role.TESTIGO })
            assertEquals(setup.lobos, r.count { it == Role.LOBO })
        }
    }

    /** 3.000 partidas con narrador siguiendo el guion: siempre terminan y nadie muerto actúa. */
    @Test fun milesDePartidasConNarrador() {
        val rng = Random(99)
        repeat(3_000) { g ->
            val n = 6 + rng.nextInt(10)
            val nm = (1..n).map { "J$it" }
            val story = rng.nextBoolean()
            val opts = Options(story = story, narrator = true, witnessEvery = rng.nextInt(3), corruption = rng.nextBoolean(),
                quietFirstNight = rng.nextBoolean())
            val setup = Rules.recommended(n).copy(testigo = story && opts.witnessEvery > 0)
            val m = Match(nm, Rules.assignRoles(nm, setup, emptyMap(), rng), opts, rng)
            var guard = 0
            while (m.winner == null) {
                assertTrue("partida $g no termina", guard++ < 3 * n)
                m.startNight()
                for (st in m.narratorSteps()) {
                    if (st.actor < 0) continue
                    assertTrue("actúa un muerto", m.alive[st.actor])
                    when (st.call) {
                        Call.LOBOS -> {
                            if (!m.isQuietNight) m.targetsFor(st.actor).let { m.act(st.actor, it[rng.nextInt(it.size)]) }
                            m.frameTargets(st.actor).let { if (it.isNotEmpty()) m.frame(st.actor, it[rng.nextInt(it.size)]) }
                            if (m.canCorrupt && rng.nextInt(4) == 0) m.requestCorruption(st.actor)
                        }
                        Call.DOCTOR, Call.VIDENTE -> m.targetsFor(st.actor).let { m.act(st.actor, it[rng.nextInt(it.size)]) }
                        Call.TESTIGO -> assertTrue(m.witnessFor(st.actor) != null)
                        else -> {}
                    }
                }
                m.resolveDawn()
                while (m.pendingHunter >= 0) m.hunterShoot(m.hunterTargets().let { it[rng.nextInt(it.size)] })
                if (m.winner != null) break
                val alive = m.aliveList()
                m.vote(if (rng.nextInt(10) == 0) null else alive[rng.nextInt(alive.size)])
                while (m.pendingHunter >= 0) m.hunterShoot(m.hunterTargets().let { it[rng.nextInt(it.size)] })
            }
        }
    }
}
