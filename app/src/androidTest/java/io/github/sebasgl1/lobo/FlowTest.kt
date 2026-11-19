package io.github.sebasgl1.lobo

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pruebas de pantalla: juegan partidas completas pasando el celular, como lo haría el grupo. */
@RunWith(AndroidJUnit4::class)
class FlowTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Before fun setUp() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("lobo", Context.MODE_PRIVATE).edit().clear()
            .putString("players", "Ana\nBeto\nCaro\nDani\nEli\nFer")
            .putInt("lobos", 2).putBoolean("vidente", true).putBoolean("doctor", true).putBoolean("cazador", true)
            .putInt("turnSeconds", 1)   // turnos cortos para la prueba
            .putBoolean("accusation", false).putBoolean("sound", false)
            .commit()
    }

    private fun has(t: String) = rule.onAllNodes(hasText(t)).fetchSemanticsNodes().isNotEmpty()
    private fun hasPart(t: String) = rule.onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun tagStarts(prefix: String) = SemanticsMatcher("tag $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    /** Textos visibles, para saber dónde se quedó una prueba. */
    private fun dump(): String = runCatching {
        rule.onAllNodes(SemanticsMatcher("todo") { true }).fetchSemanticsNodes().mapNotNull { n ->
            n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
                ?: n.config.getOrNull(SemanticsProperties.TestTag)?.let { "#$it" }
        }.joinToString(" | ").take(1500)
    }.getOrElse { "sin pantalla: $it" }

    private fun clickFirstTarget() = rule.onAllNodes(tagStarts("target-")).onFirst().performClick()

    private fun startAndReveal(players: Int = 6) {
        rule.onNodeWithText("Jugar").performClick()
        rule.onNodeWithText("Repartir roles").performClick()
        repeat(players) { i ->
            rule.onNodeWithTag("reveal-box").performTouchInput { down(center) }
            rule.waitForIdle()
            assertTrue("no se vio el rol", hasPart("Eres "))
            rule.onNodeWithTag("reveal-box").performTouchInput { up() }
            rule.waitForIdle()
            assertTrue("el rol quedó a la vista", !hasPart("Eres "))
            rule.onNodeWithText(if (i < players - 1) "Listo, pasar al siguiente" else "Listo, empezar la noche").performClick()
        }
    }

    /** Un paso del juego; devuelve false si no reconoce la pantalla. */
    private fun step(voteFirst: Boolean = true): Boolean {
        rule.waitForIdle()
        when {
            hasPart("Soy ") -> rule.onNode(hasText("Soy ", substring = true)).performClick()
            has("Confirmar") -> {
                clickFirstTarget()
                if (rule.onAllNodes(tagStarts("frame-")).fetchSemanticsNodes().isNotEmpty()) {
                    rule.onAllNodes(tagStarts("frame-")).onFirst().performClick()
                }
                rule.onNodeWithText("Confirmar").performClick()
            }
            hasPart("Espera") || has("Listo, pasar") -> {
                rule.waitUntil(6000) { has("Listo, pasar") }
                rule.onNodeWithText("Listo, pasar").performClick()
            }
            has("Disparar") -> { clickFirstTarget(); rule.onNodeWithText("Disparar").performClick() }
            has("Escuchar defensas") -> {
                rule.onAllNodes(tagStarts("acc-"))[0].performClick()
                rule.onAllNodes(tagStarts("acc-"))[1].performClick()
                rule.onNodeWithText("Escuchar defensas").performClick()
            }
            hasPart("Todos señalen") -> rule.waitUntil(6000) { has("Escuchar defensas") }
            has("Siguiente defensa") -> rule.onNodeWithText("Siguiente defensa").performClick()
            has("Ir a la votación") -> rule.onNodeWithText("Ir a la votación").performClick()
            has("Empezar la acusación") -> rule.onNodeWithText("Empezar la acusación").performClick()
            has("Guardar votos") -> {
                rule.onAllNodes(tagStarts("voter-"))[0].performClick()
                rule.onAllNodes(hasText("Nadie")).onFirst().performClick()
                rule.onNodeWithText("Guardar votos").performClick()
            }
            has("Ir a votar") -> rule.onNodeWithText("Ir a votar").performClick()
            has("¿A quién elimina el pueblo?") && voteFirst -> { clickFirstTarget(); rule.onNodeWithText("Eliminar").performClick() }
            has("Continuar") -> rule.onNodeWithText("Continuar").performClick()
            // Modo narrador
            has("Empezar la noche") -> rule.onNodeWithText("Empezar la noche").performClick()
            has("Ya lo vi") -> rule.onNodeWithText("Ya lo vi").performClick()
            has("Listo") -> rule.onNodeWithText("Listo").performClick()
            rule.onAllNodes(tagStarts("narrator-caption")).fetchSemanticsNodes().isNotEmpty() || has("Listo. Cierra los ojos.") ->
                rule.mainClock.advanceTimeBy(300)
            else -> return false
        }
        return true
    }

    @Test fun partidaCompletaPasandoElCelular() {
        ActivityScenario.launch(MainActivity::class.java).use {
            startAndReveal()
            var steps = 0
            while (!has("Otra partida")) {
                if (!step()) throw AssertionError("pantalla desconocida: " + dump())
                if (++steps >= 400) throw AssertionError("la partida no termina: " + dump())
            }
            assertTrue(hasPart("¡Gana") )
            rule.onNodeWithText("Así fue la partida", substring = true, ignoreCase = true).assertExists()
            rule.onNodeWithText("Otra partida").performClick()
            rule.onNodeWithTag("reveal-box").assertExists()
        }
    }

    @Test fun empateDobleNadieMuere() {
        ActivityScenario.launch(MainActivity::class.java).use {
            startAndReveal()
            var steps = 0
            while (!has("¿A quién elimina el pueblo?")) {
                if (has("Otra partida")) return@use   // la noche acabó la partida (muy improbable)
                assertTrue(step(voteFirst = false))
                assertTrue(++steps < 100)
            }
            rule.onNodeWithText("Hubo empate").performClick()
            rule.onAllNodes(tagStarts("tie-"))[0].performClick()
            rule.onAllNodes(tagStarts("tie-"))[1].performClick()
            rule.onNodeWithText("Votar de nuevo entre ellos").performClick()
            rule.onNodeWithText("Empataron otra vez").performClick()
            rule.onNodeWithText("El pueblo no eliminó a nadie").assertExists()
            rule.onNodeWithText("Continuar").performClick()
            assertTrue("no empezó la noche", hasPart("Soy "))
        }
    }

    @Test fun atrasPideConfirmacion() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            startAndReveal()
            assertTrue(hasPart("Soy "))
            sc.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            rule.onNodeWithText("¿Salir de la partida?").assertExists()
            rule.onNodeWithText("Seguir jugando").performClick()
            assertTrue(hasPart("Soy "))
        }
    }

    @Test fun modoHistoriaConAcusacionYRegistroDeVotos() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("lobo", Context.MODE_PRIVATE).edit()
            .putBoolean("story", true).putInt("witnessEvery", 1).putBoolean("corruption", true)
            .putBoolean("anonSuspicions", true).putBoolean("accusation", true).putBoolean("voteLog", true)
            .commit()
        ActivityScenario.launch(MainActivity::class.java).use {
            startAndReveal()
            var steps = 0
            var sawScene = false
            var sawAccusation = false
            while (!has("Otra partida")) {
                if (hasPart("Escena del crimen")) sawScene = true
                if (hasPart("Todos señalen") || has("Escuchar defensas")) sawAccusation = true
                if (!step()) throw AssertionError("pantalla desconocida: " + dump())
                if (++steps >= 500) throw AssertionError("la partida no termina: " + dump())
            }
            assertTrue("no apareció la escena del crimen", sawScene)
            assertTrue("no hubo ronda de acusación", sawAccusation || hasPart("Escena:"))
            rule.onNodeWithText("Así fue la partida", substring = true, ignoreCase = true).assertExists()
        }
    }

    @Test fun modoNarradorConTestigo() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("lobo", Context.MODE_PRIVATE).edit()
            .putString("players", "Ana\nBeto\nCaro\nDani\nEli\nFer\nGabi\nHugo").putBoolean("cazador", false)
            .putBoolean("narrator", true).putBoolean("fastNarrator", true)
            .putBoolean("story", true).putInt("witnessEvery", 1).putBoolean("accusation", true)
            .commit()
        ActivityScenario.launch(MainActivity::class.java).use {
            startAndReveal(8)
            assertTrue("no apareció la preparación del narrador", has("Empezar la noche"))
            var steps = 0
            var sawCaption = false
            var sawWolves = false
            var sawWitness = false
            while (!has("Otra partida")) {
                if (rule.onAllNodes(tagStarts("narrator-caption")).fetchSemanticsNodes().isNotEmpty()) sawCaption = true
                if (has("🐺 Lobos")) sawWolves = true
                if (has("🕯️ Testigo")) sawWitness = true
                if (!step()) throw AssertionError("pantalla desconocida: " + dump())
                if (++steps >= 4000) throw AssertionError("la partida no termina: " + dump())
            }
            assertTrue("el narrador no habló", sawCaption)
            assertTrue("no llamó a los lobos", sawWolves)
            assertTrue("no llamó al testigo", sawWitness || hasPart("Testigo"))
            rule.onNodeWithText("Así fue la partida", substring = true, ignoreCase = true).assertExists()
        }
    }
}
