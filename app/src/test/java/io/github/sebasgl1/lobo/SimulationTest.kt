package io.github.sebasgl1.lobo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** 10.000 partidas al azar: siempre terminan, nadie muerto actúa y el ganador es correcto. */
class SimulationTest {
    @Test fun milesDePartidasAlAzar() {
        val rng = Random(2026)
        var pueblo = 0
        var lobos = 0
        repeat(10_000) { g ->
            val n = 5 + rng.nextInt(12)
            val names = (1..n).map { "J$it" }
            val setup = Rules.recommended(n)
            val opts = Options(quietFirstNight = rng.nextBoolean(), doctorSelfOnce = rng.nextBoolean())
            val m = Match(names, Rules.assignRoles(names, setup, emptyMap(), rng), opts, rng)
            var guard = 0
            while (m.winner == null) {
                assertTrue("partida $g no termina", guard++ < 3 * n)
                m.startNight()
                for (p in m.nightOrder()) {
                    assertTrue(m.alive[p])
                    val t = m.targetsFor(p)
                    assertTrue("sin opciones", t.isNotEmpty())
                    if (m.actionFor(p) == Action.ATACAR) assertTrue(t.none { m.isWolf(it) })
                    m.act(p, t[rng.nextInt(t.size)])
                }
                m.resolveDawn()
                while (m.pendingHunter >= 0) m.hunterShoot(m.hunterTargets().let { it[rng.nextInt(it.size)] })
                if (m.winner != null) break
                val alive = m.aliveList()
                m.vote(if (rng.nextInt(10) == 0) null else alive[rng.nextInt(alive.size)])
                while (m.pendingHunter >= 0) m.hunterShoot(m.hunterTargets().let { it[rng.nextInt(it.size)] })
            }
            val w = m.winner!!
            if (w == Team.PUEBLO) { pueblo++; assertEquals(0, m.wolvesAlive()) }
            else { lobos++; assertTrue(m.wolvesAlive() >= m.othersAlive()) }
            assertTrue(m.log.last() is Event.Win)
        }
        // Con decisiones al azar ambos bandos deben ganar a veces
        assertTrue("pueblo $pueblo lobos $lobos", pueblo > 500 && lobos > 500)
    }
}
