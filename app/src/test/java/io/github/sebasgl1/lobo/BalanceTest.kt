package io.github.sebasgl1.lobo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Balance del Modo Historia: escena de 3/4, testigo difícil, Saboteador y Bufón. */
class BalanceTest {
    private val names = (1..8).map { "J$it" }

    @Test fun repartoRecomendadoDelModoHistoria() {
        assertFalse(Rules.recommendedStory(6).saboteador)
        assertFalse(Rules.recommendedStory(7).saboteador)
        assertTrue(Rules.recommendedStory(8).saboteador)
        assertEquals(Rules.recommended(10).lobos, Rules.recommendedStory(10).lobos)
        assertEquals(Rules.recommended(12).lobos + 1, Rules.recommendedStory(12).lobos)
        assertEquals(3, Rules.sceneSize(6)); assertEquals(4, Rules.sceneSize(7)); assertEquals(4, Rules.sceneSize(10))
    }

    @Test fun escenaDeCuatroSospechosos() {
        repeat(300) { seed ->
            val roles = listOf(Role.LOBO, Role.LOBO, Role.VIDENTE, Role.DOCTOR, Role.ALDEANO, Role.ALDEANO, Role.ALDEANO, Role.ALDEANO)
            val m = Match(names, roles, Options(story = true, sceneSize = 4), Random(seed.toLong()))
            m.startNight(); m.act(0, 4); m.act(1, 4)
            val d = m.resolveDawn()
            assertEquals(4, d.scene.toSet().size)
            assertTrue(d.scene.all { m.alive[it] })
            assertTrue(d.scene.any { m.isWolf(it) })
        }
    }

    @Test fun testigoDesdeLaSegundaNocheYNochePorMedio() {
        val roles = listOf(Role.LOBO, Role.LOBO, Role.VIDENTE, Role.DOCTOR, Role.ALDEANO, Role.ALDEANO, Role.ALDEANO, Role.ALDEANO)
        val m = Match(names, roles, Options(story = true, witnessEvery = 2, witnessFrom = 2), Random(3))
        val witnessNights = mutableListOf<Int>()
        repeat(5) {
            m.startNight()
            if (m.witness >= 0) witnessNights.add(m.night)
            m.resolveDawn(); m.vote(null)
            if (m.winner != null) return@repeat
        }
        assertEquals(listOf(2, 4), witnessNights.filter { it <= 5 })
    }

    @Test fun testigoDificilMeteASuInocenteEnLaEscenaLaMitadDeLasVeces() {
        var guests = 0; var inScene = 0; val total = 2000
        repeat(total) { seed ->
            val roles = listOf(Role.LOBO, Role.LOBO, Role.VIDENTE, Role.DOCTOR, Role.ALDEANO, Role.ALDEANO, Role.ALDEANO, Role.ALDEANO)
            val m = Match(names, roles, Options(story = true, sceneSize = 4, witnessInScene = true), Random(seed.toLong()))
            m.startNight()
            val g = m.sceneGuest
            if (g >= 0) {
                guests++
                assertFalse(m.isWolf(g))
                val pair = m.witnessPair!!
                assertTrue(g == pair.first || g == pair.second)
            }
            val d = m.resolveDawn()
            if (g >= 0 && m.alive[g] && g in d.scene) inScene++
        }
        assertTrue("invitados: $guests", guests in 850..1150)
        assertTrue("en la escena: $inScene de $guests", inScene >= guests * 0.95)
    }

    @Test fun saboteadorEngañaALaVidenteYGanaConLosLobos() {
        val roles = listOf(Role.LOBO, Role.VIDENTE, Role.SABOTEADOR, Role.ALDEANO, Role.ALDEANO, Role.ALDEANO)
        val m = Match(names.take(6), roles, Options(), Random(1))
        m.startNight()
        val seen = m.act(1, 2)!!
        assertFalse(seen.isWolf)
        assertEquals(Role.ALDEANO, seen.role)
        assertFalse(m.wolfMates(0).contains(2))           // los lobos no saben quién es
        m.act(0, 3); m.resolveDawn()                         // muere un aldeano: 1 lobo vs 4
        m.vote(4); assertNull(m.winner)                      // 1 lobo vs 3 (el saboteador cuenta como "los demás")
        m.startNight(); m.act(0, 5); m.resolveDawn()         // 1 lobo vs 2
        m.vote(1)                                            // 1 lobo vs 1 → ganan los lobos
        assertEquals(Team.LOBOS, m.winner)
        assertTrue(m.isWinner(0)); assertTrue(m.isWinner(2)); assertFalse(m.isWinner(3))
    }

    @Test fun bufonGanaSiLoEliminaElPueblo() {
        val roles = listOf(Role.LOBO, Role.BUFON, Role.ALDEANO, Role.ALDEANO, Role.ALDEANO, Role.ALDEANO)
        val m = Match(names.take(6), roles, Options(), Random(1))
        m.startNight(); m.act(0, 2); m.resolveDawn()
        m.vote(1)
        assertTrue(m.bufonWon)
        assertNull(m.winner)                                  // la partida sigue
        m.startNight(); m.act(0, 3); m.resolveDawn()
        m.vote(0)
        assertEquals(Team.PUEBLO, m.winner)
        assertTrue(m.isWinner(1)); assertTrue(m.isWinner(4)); assertFalse(m.isWinner(0))
    }

    @Test fun bufonMuertoDeNocheNoGana() {
        val roles = listOf(Role.LOBO, Role.BUFON, Role.ALDEANO, Role.ALDEANO, Role.ALDEANO, Role.ALDEANO)
        val m = Match(names.take(6), roles, Options(), Random(1))
        m.startNight(); m.act(0, 1); m.resolveDawn()
        assertFalse(m.bufonWon)
    }

    @Test fun repartoConSaboteadorYBufon() {
        val rng = Random(5)
        repeat(500) {
            val n = 8 + rng.nextInt(8)
            val nm = (1..n).map { "J$it" }
            val s = Rules.recommendedStory(n).copy(bufon = true)
            assertNull(Rules.setupError(n, s))
            val r = Rules.assignRoles(nm, s, emptyMap(), rng)
            assertEquals(1, r.count { it == Role.SABOTEADOR }); assertEquals(1, r.count { it == Role.BUFON })
            assertEquals(s.lobos, r.count { it == Role.LOBO })
        }
    }

    /** 3.000 partidas del Modo Historia con la configuración recomendada: siempre terminan. */
    @Test fun milesDePartidasBalanceadas() {
        val rng = Random(77)
        repeat(3_000) { g ->
            val n = 6 + rng.nextInt(10)
            val nm = (1..n).map { "J$it" }
            val s = Rules.recommendedStory(n).copy(bufon = rng.nextBoolean())
            val opts = Options(story = true, witnessEvery = 2, witnessFrom = 2, sceneSize = Rules.sceneSize(n), witnessInScene = true,
                corruption = rng.nextBoolean())
            val m = Match(nm, Rules.assignRoles(nm, s, emptyMap(), rng), opts, rng)
            var guard = 0
            while (m.winner == null) {
                assertTrue("partida $g no termina", guard++ < 3 * n)
                m.startNight()
                for (p in m.nightOrder()) {
                    val t = m.targetsFor(p); if (t.isEmpty()) continue
                    m.act(p, t[rng.nextInt(t.size)])
                    m.frameTargets(p).let { if (it.isNotEmpty()) m.frame(p, it[rng.nextInt(it.size)]) }
                }
                val d = m.resolveDawn()
                if (d.scene.isNotEmpty()) assertTrue(d.scene.size <= opts.sceneSize && d.scene.toSet().size == d.scene.size)
                while (m.pendingHunter >= 0) m.hunterShoot(m.hunterTargets().let { it[rng.nextInt(it.size)] })
                if (m.winner != null) break
                val alive = m.aliveList()
                m.vote(if (rng.nextInt(8) == 0) null else alive[rng.nextInt(alive.size)])
                while (m.pendingHunter >= 0) m.hunterShoot(m.hunterTargets().let { it[rng.nextInt(it.size)] })
            }
        }
    }
}
