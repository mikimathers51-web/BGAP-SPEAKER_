package id.bgap.speaker

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val THEME_NAMES = listOf("Gelap", "Terang", "AMOLED", "Nord")

fun schemeFor(i: Int): ColorScheme = when (i) {
    1 -> lightColorScheme(
        primary = Color(0xFF0891B2), onPrimary = Color.White, secondary = Color(0xFF7C3AED),
        background = Color(0xFFEEF2F7), onBackground = Color(0xFF0B1220),
        surface = Color(0xFFFFFFFF), onSurface = Color(0xFF0B1220),
        surfaceVariant = Color(0xFFE2E8F0), onSurfaceVariant = Color(0xFF475569),
    )
    2 -> darkColorScheme(
        primary = Color(0xFFC084FC), onPrimary = Color.Black, secondary = Color(0xFF818CF8),
        background = Color.Black, onBackground = Color(0xFFF5F3FF),
        surface = Color(0xFF0B0B10), onSurface = Color(0xFFF5F3FF),
        surfaceVariant = Color(0xFF1A1424), onSurfaceVariant = Color(0xFFA78BFA),
    )
    3 -> darkColorScheme(
        primary = Color(0xFF88C0D0), onPrimary = Color(0xFF2E3440), secondary = Color(0xFFB48EAD),
        background = Color(0xFF2E3440), onBackground = Color(0xFFECEFF4),
        surface = Color(0xFF3B4252), onSurface = Color(0xFFECEFF4),
        surfaceVariant = Color(0xFF434C5E), onSurfaceVariant = Color(0xFFD8DEE9),
    )
    else -> darkColorScheme(
        primary = Color(0xFF22D3EE), onPrimary = Color(0xFF04141A), secondary = Color(0xFFA78BFA),
        background = Color(0xFF040608), onBackground = Color(0xFFF1F5F9),
        surface = Color(0xFF0F141B), onSurface = Color(0xFFF1F5F9),
        surfaceVariant = Color(0xFF1B2430), onSurfaceVariant = Color(0xFF94A3B8),
    )
}

@Composable
fun BgapTheme(theme: Int, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = schemeFor(theme), content = content)
}
