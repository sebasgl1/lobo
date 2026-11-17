package io.github.sebasgl1.lobo

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.draw.clip

class MainActivity : ComponentActivity() {
    private lateinit var game: Game

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        game = Game(applicationContext)
        setContent { LoboTheme { App(game) } }
    }

    // Guarda la partida por si Android cierra la app en segundo plano
    override fun onStop() {
        super.onStop()
        game.saveState()
    }
}

/** Durante la partida: oculta los botones de Android y mantiene la pantalla encendida. */
@Composable
fun GameMode(active: Boolean) {
    val view = LocalView.current
    LaunchedEffect(active) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        val bars = WindowCompat.getInsetsController(window, view)
        if (active) {
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
        view.keepScreenOn = active
    }
}

/** Vibración y sonido cuando se acaba el tiempo de discusión. */
fun buzz(ctx: Context) {
    runCatching {
        ctx.getSystemService(Vibrator::class.java)
            ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300, 150, 500), -1))
    }
    runCatching {
        val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
        tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900)
        Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 1300)
    }
}

@OptIn(ExperimentalLayoutApi::class)
val StableInsets: WindowInsets
    @Composable get() = WindowInsets.systemBarsIgnoringVisibility
        .union(WindowInsets.displayCutout)
        .union(WindowInsets.ime)

@Composable
fun App(game: Game) {
    var askQuit by remember { mutableStateOf(false) }
    val s = game.screen
    GameMode(active = s !is Screen.Home && s !is Screen.HowTo && s !is Screen.Setup)

    BackHandler(enabled = s !is Screen.Home) {
        when {
            game.inGame -> askQuit = true
            else -> game.screen = Screen.Home
        }
    }

    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * if (game.bigText) 1.2f else 1f)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(StableInsets)) {
            AnimatedContent(targetState = s, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { screen ->
                when (screen) {
                    Screen.Home -> HomeScreen(game)
                    Screen.HowTo -> HowToScreen(game)
                    Screen.Setup -> SetupScreen(game)
                    is Screen.Reveal -> RevealScreen(game, screen)
                    is Screen.NightPass -> NightPassScreen(game, screen.turn)
                    is Screen.NightAct -> NightActScreen(game, screen.turn)
                    is Screen.Dawn -> DawnScreen(game, screen)
                    Screen.Hunter -> HunterScreen(game)
                    Screen.Day -> DayScreen(game)
                    Screen.Vote -> VoteScreen(game)
                    Screen.TieSelect -> TieSelectScreen(game)
                    is Screen.TieVote -> TieVoteScreen(game, screen.candidates)
                    is Screen.Eliminated -> EliminatedScreen(game, screen)
                    Screen.End -> EndScreen(game)
                }
            }
        }
    }

    if (askQuit) {
        AlertDialog(
            onDismissRequest = { askQuit = false },
            title = { Text("¿Salir de la partida?") },
            text = { Text("Se pierde la partida en curso.") },
            confirmButton = { TextButton(onClick = { askQuit = false; game.screen = Screen.Setup }) { Text("Salir") } },
            dismissButton = { TextButton(onClick = { askQuit = false }) { Text("Seguir jugando") } },
        )
    }
}

// ---------- Piezas comunes ----------

@Composable
fun Page(
    modifier: Modifier = Modifier,
    scroll: Boolean = true,
    bottom: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth()
                .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 22.dp, vertical = 18.dp),
            content = content,
        )
        if (bottom != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), content = bottom)
        }
    }
}

@Composable
fun BigButton(text: String, enabled: Boolean = true, color: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(58.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Ink),
    ) { Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) { Text(text, fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground) }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text.uppercase(), fontSize = 12.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp),
    )
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, textAlign = align, modifier = modifier)
}

@Composable
fun SwitchRow(title: String, desc: String, checked: Boolean, tag: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            if (desc.isNotEmpty()) Muted(desc, Modifier.padding(top = 2.dp))
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
    }
}

/** Tarjeta con el nombre de un jugador para escoger. */
@Composable
fun PlayerCard(name: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
            .background(if (selected) Moon else MaterialTheme.colorScheme.surface)
            .testTag(tag).clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(name, fontSize = 19.sp, color = if (selected) Ink else MaterialTheme.colorScheme.onBackground,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun RoleChip(r: Role) {
    Box(Modifier.background(roleColor(r).copy(alpha = 0.18f), RoundedCornerShape(99.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text("${r.emoji} ${r.label}", color = roleColor(r), fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

fun roleDescription(r: Role) = when (r) {
    Role.LOBO -> "Cada noche, con tu manada, escoges a quién atacar. De día, disimula."
    Role.ALDEANO -> "No tienes poderes. Escucha, sospecha y vota bien de día."
    Role.VIDENTE -> "Cada noche descubres si alguien es lobo o no."
    Role.DOCTOR -> "Cada noche proteges a alguien. No puedes repetir dos noches seguidas."
    Role.CAZADOR -> "Si mueres, te llevas a alguien contigo."
}

// ---------- Inicio ----------

@Composable
fun HomeScreen(game: Game) {
    Page(scroll = false, bottom = {
        BigButton("Jugar") { game.screen = Screen.Setup }
        Spacer(Modifier.height(10.dp))
        GhostButton("Cómo jugar") { game.screen = Screen.HowTo }
    }) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🌕", fontSize = 80.sp)
            Text("🐺", fontSize = 56.sp)
            Spacer(Modifier.height(8.dp))
            Text("Lobo", fontSize = 48.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp)
            Spacer(Modifier.height(6.dp))
            Muted("El pueblo duerme… pero no todos.", align = TextAlign.Center)
        }
    }
}

@Composable
fun HowToScreen(game: Game) {
    Page(bottom = { BigButton("Entendido") { game.screen = Screen.Home } }) {
        Text("Cómo jugar", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        val rules = listOf(
            "🎯 El objetivo" to "El pueblo gana si elimina a todos los lobos. Los lobos ganan si llegan a ser tantos como los demás.",
            "1. Repartir" to "Cada uno mantiene presionado para ver su rol en secreto. Los lobos ven quién es su manada.",
            "2. La noche" to "El celular pasa de mano en mano. TODOS tocan un nombre, así nadie sabe quién tiene poder. Cada turno dura lo mismo.",
            "🐺 Lobos" to "Escogen a quién atacar. El siguiente lobo ve lo que propuso su compañero. Si no coinciden, la app decide al azar entre las propuestas.",
            "🩺 Doctor" to "Protege a alguien. Si es la víctima, se salva. No puede repetir dos noches seguidas y solo puede protegerse a sí mismo una vez.",
            "🔮 Vidente" to "Descubre si alguien es lobo o no.",
            "👨‍🌾 Aldeanos" to "Tocan de quién sospechan. No cambia nada, pero al final se ve lo que pensaba el pueblo.",
            "3. El día" to "La app cuenta qué pasó. Discutan y voten a quién eliminar. Si empatan, votan de nuevo entre los empatados; si vuelven a empatar, nadie muere.",
            "🏹 Cazador" to "Si muere, de noche o por votación, se lleva a alguien con él.",
        )
        rules.forEach { (t, d) ->
            Card(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(t, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Spacer(Modifier.height(4.dp))
                    Muted(d)
                }
            }
        }
    }
}

// ---------- Configuración ----------

@Composable
fun SetupScreen(game: Game) {
    var input by remember { mutableStateOf("") }
    val error = game.setupError()
    val add = { if (game.addName(input)) input = "" }
    val n = game.names.size

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("setup-list"), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 18.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Nueva partida", fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { game.screen = Screen.Home }) { Text("Inicio") }
                }
                SectionTitle("Jugadores · $n")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input, onValueChange = { input = it.take(20) }, placeholder = { Text("Nombre") }, singleLine = true,
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { add() }),
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(onClick = add, enabled = input.isNotBlank(), modifier = Modifier.height(56.dp), shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(contentColor = Ink)) { Text("Agregar") }
                }
                Spacer(Modifier.height(8.dp))
            }
            itemsIndexed(game.names, key = { _, nm -> nm }) { i, nm ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                        .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(26.dp))
                    Text(nm, fontSize = 17.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { game.moveName(i, i - 1) }, enabled = i > 0) { Text("▲") }
                    TextButton(onClick = { game.removeName(nm) }) { Text("✕", color = WolfColor) }
                }
            }
            item {
                SectionTitle("Roles")
                Stepper("🐺 Lobos", game.lobos, canDec = game.lobos > 1, canInc = Rules.setupError(n, game.setup().copy(lobos = game.lobos + 1)) == null) {
                    game.lobos += it; game.saveConfig()
                }
                Spacer(Modifier.height(6.dp))
                SwitchRow("🔮 Vidente", "Descubre si alguien es lobo", game.vidente) { game.vidente = it; game.saveConfig() }
                SwitchRow("🩺 Doctor", "Protege a alguien cada noche", game.doctor) { game.doctor = it; game.saveConfig() }
                SwitchRow("🏹 Cazador", "Al morir se lleva a alguien", game.cazador) { game.cazador = it; game.saveConfig() }
                Spacer(Modifier.height(6.dp))
                Muted("👨‍🌾 Aldeanos: ${maxOf(0, game.aldeanos())}", Modifier.padding(start = 4.dp))
                if (n >= Rules.MIN_PLAYERS) {
                    val r = game.recommended()
                    val extras = listOfNotNull(if (r.vidente) "Vidente" else null, if (r.doctor) "Doctor" else null, if (r.cazador) "Cazador" else null)
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(14.dp))
                            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text("Recomendado para $n jugadores", fontSize = 13.sp, color = Gold)
                            Text(
                                "${r.lobos} ${if (r.lobos == 1) "lobo" else "lobos"}" + (if (extras.isEmpty()) "" else " · " + extras.joinToString(", ")) +
                                    if (game.isRecommended) "  ✓" else "",
                                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        if (!game.isRecommended) TextButton(onClick = { game.applyRecommended() }) { Text("Usar", color = Gold) }
                    }
                }

                SectionTitle("Opciones")
                SwitchRow("Revelar el rol al morir", "Si se apaga, el juego es más difícil", game.revealOnDeath) { game.revealOnDeath = it; game.saveConfig() }
                SwitchRow("Primera noche tranquila", "La primera noche nadie muere; solo actúa la Vidente", game.quietFirstNight) { game.quietFirstNight = it; game.saveConfig() }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Tiempo por turno", fontSize = 16.sp)
                        Muted("Igual para todos de noche")
                    }
                    listOf(5, 8, 12).forEach { sec ->
                        FilterChip(selected = game.turnSeconds == sec, onClick = { game.turnSeconds = sec; game.saveConfig() },
                            label = { Text("$sec s") }, modifier = Modifier.padding(start = 6.dp))
                    }
                }

                SectionTitle("Pantalla")
                SwitchRow("Letra grande", "Textos más grandes en toda la app", game.bigText, tag = "big-text") { game.bigText = it; game.saveConfig() }

                val stats = game.loadStats()
                if (stats.isNotEmpty()) {
                    SectionTitle("Estadísticas")
                    stats.entries.sortedByDescending { it.value.wins }.forEach { (nm, st) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(nm, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Muted("${st.wins}/${st.played} ganadas · 🐺 ${st.wolfWins}/${st.wolfGames}")
                        }
                    }
                    TextButton(onClick = { game.resetStats() }) { Text("Reiniciar estadísticas") }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp)) {
            if (error != null) Muted(error, Modifier.fillMaxWidth().padding(bottom = 8.dp), align = TextAlign.Center)
            BigButton("Repartir roles", enabled = error == null) { game.start() }
        }
    }
}

@Composable
fun Stepper(label: String, value: Int, canDec: Boolean, canInc: Boolean, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 16.sp, modifier = Modifier.weight(1f))
        RoundBtn("−", canDec) { onChange(-1) }
        Text("$value", fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(40.dp))
        RoundBtn("+", canInc) { onChange(1) }
    }
}

@Composable
fun RoundBtn(t: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp)
            .background(if (enabled) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(t, fontSize = 22.sp, color = if (enabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.outline)
    }
}

// ---------- Repartir ----------

@Composable
fun RevealScreen(game: Game, s: Screen.Reveal) {
    val m = game.match ?: return
    val p = s.index
    var pressed by remember(p) { mutableStateOf(false) }
    var seen by remember(p) { mutableStateOf(false) }
    Page(scroll = false, bottom = {
        BigButton(if (p + 1 < m.size) "Listo, pasar al siguiente" else "Listo, empezar la noche", enabled = seen && !pressed) { game.nextReveal(p) }
    }) {
        Muted("${p + 1} de ${m.size}")
        Spacer(Modifier.height(18.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Muted("Pásale el celular a", align = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(m.names[p], fontSize = 38.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(22.dp))
        Box(
            Modifier.fillMaxWidth().weight(1f).testTag("reveal-box")
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(28.dp))
                .pointerInput(p) {
                    detectTapGestures(onPress = {
                        pressed = true
                        seen = true
                        tryAwaitRelease()
                        pressed = false
                    })
                }
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (!pressed) {
                    Text("👆", fontSize = 56.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(if (seen) "Mantén presionado para verlo otra vez" else "Mantén presionado para ver tu rol",
                        fontSize = 19.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Muted("Se oculta al soltar. Que nadie más mire 👀", align = TextAlign.Center)
                } else {
                    val r = m.roles[p]
                    Text(r.emoji, fontSize = 64.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Eres ${r.label}", fontSize = 34.sp, fontWeight = FontWeight.Black, color = roleColor(r), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Muted(roleDescription(r), align = TextAlign.Center)
                    if (r == Role.LOBO) {
                        val mates = m.wolfMates(p)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (mates.isEmpty()) "Eres el único lobo" else "Tu manada: " + mates.joinToString(", ") { m.names[it] },
                            fontSize = 17.sp, fontWeight = FontWeight.Bold, color = WolfColor, textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

// ---------- Noche ----------

@Composable
fun NightPassScreen(game: Game, turn: Int) {
    val m = game.match ?: return
    val p = game.order.getOrNull(turn) ?: return
    Page(scroll = false, bottom = { BigButton("Soy ${m.names[p]}") { game.screen = Screen.NightAct(turn) } }) {
        Muted("🌙 Noche ${m.night} · turno ${turn + 1} de ${game.order.size}")
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🌙", fontSize = 64.sp)
            Spacer(Modifier.height(12.dp))
            Muted("Pásale el celular a", align = TextAlign.Center)
            Text(m.names[p], fontSize = 40.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Muted("Inclina el celular para que nadie más vea.", align = TextAlign.Center)
        }
    }
}

@Composable
fun NightActScreen(game: Game, turn: Int) {
    val m = game.match ?: return
    val p = game.order.getOrNull(turn) ?: return
    val action = m.actionFor(p)
    val targets = remember(turn) { m.targetsFor(p) }
    val proposals = remember(turn) { m.proposalsSoFar() }
    var selected by remember(turn) { mutableIntStateOf(-1) }
    var confirmed by remember(turn) { mutableStateOf(m.hasActed(p)) }
    var seer by remember(turn) { mutableStateOf<SeerResult?>(null) }
    val start = remember(turn) { System.currentTimeMillis() }
    var now by remember(turn) { mutableStateOf(System.currentTimeMillis()) }
    val total = game.turnSeconds * 1000L
    LaunchedEffect(turn) {
        while (System.currentTimeMillis() - start < total) { delay(200); now = System.currentTimeMillis() }
        now = System.currentTimeMillis()
    }
    val left = ((total - (now - start)) / 1000.0).let { Math.ceil(it).toInt() }.coerceAtLeast(0)
    val prompt = when (action) {
        Action.ATACAR -> "¿A quién atacan esta noche?"
        Action.PROTEGER -> "¿A quién proteges esta noche?"
        Action.INVESTIGAR -> "¿A quién investigas?"
        Action.SOSPECHAR -> "¿De quién sospechas?"
    }
    Page(bottom = {
        if (!confirmed) {
            BigButton("Confirmar", enabled = selected >= 0) {
                seer = game.act(turn, selected)
                confirmed = true
            }
        } else {
            BigButton(if (left > 0) "Espera $left s…" else "Listo, pasar", enabled = left == 0) { game.endTurn(turn) }
        }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.names[p], fontSize = 24.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            RoleChip(m.roles[p])
        }
        Spacer(Modifier.height(14.dp))
        if (m.roles[p] == Role.LOBO) {
            val mates = m.wolfMates(p).filter { m.alive[it] }
            Text(if (mates.isEmpty()) "Estás solo en la manada" else "Tu manada: " + mates.joinToString(", ") { m.names[it] },
                color = WolfColor, fontWeight = FontWeight.SemiBold)
            if (m.isQuietNight) Muted("Primera noche tranquila: esta noche los lobos no atacan.")
            proposals.forEach { (w, t) -> Text("🐺 ${m.names[w]} propone a ${m.names[t]}", color = WolfColor, fontSize = 15.sp) }
            Spacer(Modifier.height(8.dp))
        }
        if (action == Action.PROTEGER && m.doctorLast >= 0) Muted("No puedes repetir a ${m.names[m.doctorLast]}.")
        Text(prompt, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
        if (!confirmed) {
            targets.forEach { t -> PlayerCard(m.names[t], selected == t, "target-${m.names[t]}") { selected = t } }
        } else {
            val r = seer
            Box(
                Modifier.fillMaxWidth().padding(top = 8.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp)).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (r != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (r.isWolf) "🐺" else "🙂", fontSize = 56.sp)
                        Text(m.names[r.target], fontSize = 28.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                        Text(if (r.isWolf) "ES LOBO" else "no es lobo", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                            color = if (r.isWolf) WolfColor else VillageColor)
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("✓", fontSize = 56.sp, color = Moon)
                        Text("Anotado: ${if (selected >= 0) m.names[selected] else ""}", fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Muted("Cuando se acabe el tiempo, toca Listo y pasa el celular.", Modifier.fillMaxWidth(), align = TextAlign.Center)
        }
    }
}

// ---------- Amanecer y día ----------

@Composable
fun DawnScreen(game: Game, s: Screen.Dawn) {
    val m = game.match ?: return
    Page(bottom = { BigButton("Continuar") { game.next() } }) {
        Spacer(Modifier.height(20.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("☀️", fontSize = 64.sp)
            Text("Día ${m.night}", fontSize = 32.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(14.dp))
            Text(game.dawnText(s.deaths, s.saved), fontSize = 21.sp, textAlign = TextAlign.Center, lineHeight = 29.sp)
            s.deaths.forEach { d ->
                Spacer(Modifier.height(14.dp))
                if (m.options.revealOnDeath) {
                    Muted("${m.names[d]} era", align = TextAlign.Center)
                    Spacer(Modifier.height(4.dp))
                    RoleChip(m.roles[d])
                } else {
                    Muted("El rol de ${m.names[d]} queda en secreto 🤫", align = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(18.dp))
            Muted("Alguien lea esto en voz alta para todos.", align = TextAlign.Center)
        }
    }
}

@Composable
fun HunterScreen(game: Game) {
    val m = game.match ?: return
    val h = m.pendingHunter
    if (h < 0) return
    var pick by remember { mutableIntStateOf(-1) }
    Page(bottom = { BigButton("Disparar", enabled = pick >= 0, color = HunterColor) { game.hunterShoot(pick) } }) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🏹", fontSize = 60.sp)
            Text("${m.names[h]} era el Cazador", fontSize = 26.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Muted("Antes de morir, se lleva a alguien con él. ${m.names[h]}, escoge:", align = TextAlign.Center)
        }
        Spacer(Modifier.height(12.dp))
        m.hunterTargets().forEach { t -> PlayerCard(m.names[t], pick == t, "target-${m.names[t]}") { pick = t } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DayScreen(game: Game) {
    val m = game.match ?: return
    Page(bottom = { BigButton("Ir a votar") { game.screen = Screen.Vote } }) {
        Text("☀️ Día ${m.night}", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Muted("Discutan quién puede ser lobo.")
        SectionTitle("Vivos · ${m.aliveList().size}")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            m.aliveList().forEach { a ->
                Box(Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(99.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text(m.names[a])
                }
            }
        }
        val dead = m.log.filterIsInstance<Event.Died>()
        if (dead.isNotEmpty()) {
            SectionTitle("Eliminados")
            dead.forEach { d ->
                val role = if (m.options.revealOnDeath) " · ${m.roles[d.player].emoji} ${m.roles[d.player].label}" else ""
                Muted("${m.names[d.player]}$role", Modifier.padding(vertical = 2.dp))
            }
        }
        SectionTitle("Temporizador de discusión")
        DiscussionTimer(game)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiscussionTimer(game: Game) {
    val ctx = LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val end = game.timerEnd
    LaunchedEffect(end) {
        while (end > 0) {
            now = System.currentTimeMillis()
            if (now >= end) {
                game.timerEnd = 0L
                buzz(ctx)
                break
            }
            delay(250)
        }
    }
    val left = if (end > 0) ((end - now + 999) / 1000).toInt().coerceAtLeast(0) else 0
    val running = end > 0
    Column {
        Text(
            "%d:%02d".format(left / 60, left % 60), fontSize = 28.sp, fontWeight = FontWeight.Bold,
            color = if (running && left <= 10) WolfColor else MaterialTheme.colorScheme.onBackground,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(60, 120, 180, 300).forEach { secs ->
                FilterChip(selected = false, onClick = { game.timerEnd = System.currentTimeMillis() + secs * 1000L }, label = { Text("${secs / 60} min") })
            }
            if (running) TextButton(onClick = { game.timerEnd = 0L }) { Text("Parar") }
        }
    }
}

// ---------- Votación ----------

@Composable
fun VoteScreen(game: Game) {
    val m = game.match ?: return
    var pick by remember { mutableIntStateOf(-1) }
    var askNobody by remember { mutableStateOf(false) }
    Page(bottom = {
        GhostButton("Hubo empate") { game.screen = Screen.TieSelect }
        Spacer(Modifier.height(8.dp))
        GhostButton("No eliminar a nadie") { askNobody = true }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { game.screen = Screen.Day }, modifier = Modifier.fillMaxWidth()) { Text("Volver al día") }
    }) {
        Text("¿A quién elimina el pueblo?", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Muted("Voten en voz alta o señalando, y toquen al más votado.")
        Spacer(Modifier.height(12.dp))
        m.aliveList().forEach { a -> PlayerCard(m.names[a], false, "target-${m.names[a]}") { pick = a } }
    }
    if (pick >= 0) {
        AlertDialog(
            onDismissRequest = { pick = -1 },
            title = { Text("¿Eliminar a ${m.names[pick]}?") },
            confirmButton = { TextButton(onClick = { val p = pick; pick = -1; game.vote(p) }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { pick = -1 }) { Text("Cancelar") } },
        )
    }
    if (askNobody) {
        AlertDialog(
            onDismissRequest = { askNobody = false },
            title = { Text("¿No eliminar a nadie hoy?") },
            confirmButton = { TextButton(onClick = { askNobody = false; game.vote(null) }) { Text("Sí, a dormir") } },
            dismissButton = { TextButton(onClick = { askNobody = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
fun TieSelectScreen(game: Game) {
    val m = game.match ?: return
    val chosen = remember { mutableStateListOf<Int>() }
    Page(bottom = {
        BigButton("Votar de nuevo entre ellos", enabled = chosen.size >= 2) { game.screen = Screen.TieVote(chosen.toList().sorted()) }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { game.screen = Screen.Vote }, modifier = Modifier.fillMaxWidth()) { Text("Volver") }
    }) {
        Text("¿Entre quiénes fue el empate?", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Muted("Toca a los empatados.")
        Spacer(Modifier.height(12.dp))
        m.aliveList().forEach { a ->
            PlayerCard(m.names[a], a in chosen, "tie-${m.names[a]}") { if (a in chosen) chosen.remove(a) else chosen.add(a) }
        }
    }
}

@Composable
fun TieVoteScreen(game: Game, candidates: List<Int>) {
    val m = game.match ?: return
    var pick by remember { mutableIntStateOf(-1) }
    Page(bottom = {
        GhostButton("Empataron otra vez") { game.vote(null) }
    }) {
        Text("Desempate", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Muted("Voten otra vez solo entre ellos. Si vuelven a empatar, hoy nadie muere.")
        Spacer(Modifier.height(12.dp))
        candidates.forEach { a -> PlayerCard(m.names[a], false, "target-${m.names[a]}") { pick = a } }
    }
    if (pick >= 0) {
        AlertDialog(
            onDismissRequest = { pick = -1 },
            title = { Text("¿Eliminar a ${m.names[pick]}?") },
            confirmButton = { TextButton(onClick = { val p = pick; pick = -1; game.vote(p) }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { pick = -1 }) { Text("Cancelar") } },
        )
    }
}

@Composable
fun EliminatedScreen(game: Game, s: Screen.Eliminated) {
    val m = game.match ?: return
    Page(scroll = false, bottom = { BigButton("Continuar") { game.next() } }) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            if (s.player < 0) {
                Text("⚖️", fontSize = 64.sp)
                Spacer(Modifier.height(12.dp))
                Text("El pueblo no eliminó a nadie", fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Muted("Todos a dormir… y que tengan cuidado.", align = TextAlign.Center)
            } else {
                Text(if (s.cause == Cause.CAZADOR) "🏹" else "⚖️", fontSize = 64.sp)
                Spacer(Modifier.height(12.dp))
                Text(m.names[s.player], fontSize = 34.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                Muted(if (s.cause == Cause.CAZADOR) "cayó por el disparo del cazador" else "fue eliminado por el pueblo", align = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                if (m.options.revealOnDeath) {
                    Muted("Era", align = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    RoleChip(m.roles[s.player])
                } else {
                    Muted("Su rol queda en secreto 🤫", align = TextAlign.Center)
                }
            }
        }
    }
}

// ---------- Final ----------

@Composable
fun EndScreen(game: Game) {
    val m = game.match ?: return
    val w = m.winner ?: return
    Page(bottom = {
        BigButton("Otra partida") { game.startOrSetup() }
        Spacer(Modifier.height(10.dp))
        GhostButton("Cambiar jugadores") { game.screen = Screen.Setup }
    }) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (w == Team.LOBOS) "🐺" else "👨‍🌾", fontSize = 64.sp)
            Text(if (w == Team.LOBOS) "¡Ganan los lobos!" else "¡Gana el pueblo!", fontSize = 30.sp, fontWeight = FontWeight.Black,
                color = if (w == Team.LOBOS) WolfColor else VillageColor, textAlign = TextAlign.Center)
        }
        SectionTitle("Roles")
        m.names.indices.forEach { i ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(m.names[i] + if (!m.alive[i]) " ✝" else "", fontSize = 17.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${m.roles[i].emoji} ${m.roles[i].label}", color = roleColor(m.roles[i]), fontWeight = FontWeight.SemiBold)
            }
        }
        val sus = game.suspicions()
        if (sus.isNotEmpty()) {
            SectionTitle("Lo que pensaba el pueblo")
            sus.forEach { (n, c) -> Muted("$n · $c ${if (c == 1) "sospecha" else "sospechas"}", Modifier.padding(vertical = 2.dp)) }
        }
        SectionTitle("Así fue la partida")
        game.timeline().forEach { line ->
            Text(line, fontSize = 15.sp, modifier = Modifier.padding(vertical = 3.dp),
                fontWeight = if (line.startsWith("🌙") || line.startsWith("☀️")) FontWeight.Bold else FontWeight.Normal)
        }
    }
}
