package io.github.sebasgl1.lobo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Modo Historia: escena, incriminación, testigos y compra del testigo. */
class StoryTest {
    private val names = listOf("Ana", "Beto", "Caro", "Dani", "Eli", "Fer", "Gabi")
    // 0,1 lobos · 2 vidente · 3 doctor · 4,5,6 aldeanos
    private val roles = listOf(Role.LOBO, Role.LOBO, Role.VIDENTE, Role.DOCTOR, Role.ALDEANO, Role.ALDEANO, Role.ALDEANO)

    private fun match(seed: Long, opts: Options = Options(story = true)) = Match(names, roles, opts, Random(seed))

    private fun Match.playNight(victim: Int, frameTarget: Int, protect: Int = -1): DawnResult {
        startNight()
        for (p in nightOrder()) {
            val t = when (actionFor(p)) {
                Action.ATACAR -> victim
                Action.PROTEGER -> if (protect in targetsFor(p)) protect else targetsFor(p).first()
                else -> targetsFor(p).first()
            }
            act(p, t)
            if (isWolf(p) && frameTarget in frameTargets(p)) frame(p, frameTarget)
        }
        return resolveDawn()
    }

    @Test fun escenaTieneUnLoboYAlIncriminado() {
        repeat(300) { seed ->
            val m = match(seed.toLong())
            val d = m.playNight(victim = 4, frameTarget = 5, protect = 6)
            assertEquals(3, d.scene.size)
            assertEquals(3, d.scene.toSet().size)
            assertTrue("sin lobo en la escena", d.scene.any { m.isWolf(it) })
            assertTrue("falta el incriminado", 5 in d.scene)
            assertTrue("un muerto en la escena", d.scene.all { m.alive[it] })
        }
    }

    @Test fun incriminadoNoPuedeSerLaVictimaMuerta() {
        repeat(100) { seed ->
            val m = match(seed.toLong())
            val d = m.playNight(victim = 4, frameTarget = 4, protect = 6)  // proponen incriminar a quien matan
            assertFalse(4 in d.scene)
            assertTrue(d.scene.any { m.isWolf(it) })
        }
    }

    @Test fun losLobosNoSeIncriminanEntreEllos() {
        val m = match(1)
        m.startNight()
        assertFalse(m.frameTargets(0).any { m.isWolf(it) })
        assertTrue(m.frameTargets(2).isEmpty())       // la vidente no incrimina
    }

    @Test fun elModoClasicoNoTieneEscenaNiTestigos() {
        val m = match(3, Options(story = false))
        val d = m.playNight(victim = 4, frameTarget = 5)
        assertTrue(d.scene.isEmpty())
        assertTrue(m.log.none { it is Event.Witness || it is Event.Scene })
    }

    @Test fun testigoEsAldeanoYVeUnLoboYUnInocente() {
        repeat(300) { seed ->
            val m = match(seed.toLong())
            m.startNight()
            assertTrue(m.witness >= 0)
            assertEquals(Role.ALDEANO, m.roles[m.witness])
            val (a, b) = m.witnessPair!!
            assertTrue(a != b && a != m.witness && b != m.witness)
            assertEquals(1, listOf(a, b).count { m.isWolf(it) })
            assertEquals(m.witnessPair, m.witnessFor(m.witness))
            assertNull(m.witnessFor(if (m.witness == 4) 5 else 4))
        }
    }

    @Test fun testigoNochePorMedio() {
        val m = match(5, Options(story = true, witnessEvery = 2))
        m.startNight(); assertTrue(m.witness >= 0)
        m.nightOrder().forEach { m.act(it, m.targetsFor(it).first()) }; m.resolveDawn()
        m.vote(null)
        m.startNight(); assertEquals(-1, m.witness)
    }

    @Test fun comprarAlTestigoUnaVezYParaLaNocheSiguiente() {
        val m = match(9, Options(story = true, corruption = true))
        m.startNight()
        assertTrue(m.canCorrupt)
        m.requestCorruption(0)
        assertFalse(m.canCorrupt)
        assertTrue(runCatching { m.requestCorruption(1) }.isFailure)
        m.nightOrder().forEach { p -> m.act(p, if (m.actionFor(p) == Action.ATACAR) 4 else m.targetsFor(p).first()) }
        m.resolveDawn()
        m.vote(null)
        m.startNight()                                // noche 2: el testigo ve a dos inocentes
        val (a, b) = m.witnessPair!!
        assertFalse(m.isWolf(a) || m.isWolf(b))
        assertTrue(m.log.any { it is Event.Witness && it.corrupted })
        m.nightOrder().forEach { p -> m.act(p, m.targetsFor(p).first()) }
        m.resolveDawn(); m.vote(null)
        m.startNight()                                // noche 3: vuelve a ser verdad
        m.witnessPair?.let { (x, y) -> assertEquals(1, listOf(x, y).count { m.isWolf(it) }) }
    }

    @Test fun nocheTranquilaTambienTieneEscena() {
        val m = match(4, Options(story = true, quietFirstNight = true))
        val d = m.playNight(victim = 4, frameTarget = 5)
        assertTrue(d.deaths.isEmpty())
        assertEquals(3, d.scene.size)
        assertTrue(d.scene.any { m.isWolf(it) })
    }

    @Test fun milesDePartidasEnModoHistoria() {
        val rng = Random(77)
        repeat(3000) {
            val n = 5 + rng.nextInt(10)
            val nm = (1..n).map { "J$it" }
            val opts = Options(story = true, quietFirstNight = rng.nextBoolean(), witnessEvery = rng.nextInt(3), corruption = rng.nextBoolean())
            val m = Match(nm, Rules.assignRoles(nm, Rules.recommended(n), emptyMap(), rng), opts, rng)
            var guard = 0
            while (m.winner == null) {
                assertTrue(guard++ < 3 * n)
                m.startNight()
                m.witnessPair?.let { (a, b) -> assertTrue(m.alive[a] && m.alive[b]) }
                for (p in m.nightOrder()) {
                    val t = m.targetsFor(p); m.act(p, t[rng.nextInt(t.size)])
                    val f = m.frameTargets(p); if (f.isNotEmpty()) m.frame(p, f[rng.nextInt(f.size)])
                    if (m.isWolf(p) && m.canCorrupt && rng.nextInt(4) == 0) m.requestCorruption(p)
                }
                val d = m.resolveDawn()
                if (m.wolvesAlive() > 0) assertTrue(d.scene.any { m.isWolf(it) })
                assertTrue(d.scene.all { m.alive[it] })
                while (m.pendingHunter >= 0) m.hunterShoot(m.hunterTargets().let { it[rng.nextInt(it.size)] })
                if (m.winner != null) break
                val a = m.aliveList(); m.vote(a[rng.nextInt(a.size)])
                while (m.pendingHunter >= 0) m.hunterShoot(m.hunterTargets().let { it[rng.nextInt(it.size)] })
            }
        }
    }
}
