package app.mixtape.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Amber = Color(0xFFF5B841)

private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF1B1D24),
    secondary = Color(0xFF8FB8DE),
    background = Color(0xFF101114),
    surface = Color(0xFF15171C),
    surfaceVariant = Color(0xFF22252D),
    onBackground = Color(0xFFE8E6E1),
    onSurface = Color(0xFFE8E6E1),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF8A5A00),
    secondary = Color(0xFF3C6A92),
)

@Composable
fun MixtapeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
