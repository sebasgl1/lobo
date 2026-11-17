package io.github.sebasgl1.lobo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF0E1020)
val Moon = Color(0xFFF2E6B8)
val Gold = Color(0xFFF2C230)
val WolfColor = Color(0xFFFF5A5A)
val VillageColor = Color(0xFF5FD39A)
val SeerColor = Color(0xFFB18CFF)
val DoctorColor = Color(0xFF5EC8FF)
val HunterColor = Color(0xFFFFB04D)

fun roleColor(r: Role) = when (r) {
    Role.LOBO -> WolfColor
    Role.ALDEANO -> VillageColor
    Role.VIDENTE -> SeerColor
    Role.DOCTOR -> DoctorColor
    Role.CAZADOR -> HunterColor
}

private val Scheme = darkColorScheme(
    primary = Moon, onPrimary = Ink,
    secondaryContainer = Color(0xFF2A2A3F), onSecondaryContainer = Gold,
    background = Ink, onBackground = Color(0xFFE9E8F2),
    surface = Color(0xFF181B33), onSurface = Color(0xFFE9E8F2),
    surfaceVariant = Color(0xFF262A47), onSurfaceVariant = Color(0xFF9A9CB8),
    error = WolfColor, outline = Color(0xFF3B3F63),
)

@Composable
fun LoboTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}
