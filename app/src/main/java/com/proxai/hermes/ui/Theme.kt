package com.proxai.hermes.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Brand = Color(0xFF9D85FF)
val BrandDeep = Color(0xFF3A2A8C)
val Mint = Color(0xFF7DD3C0)

private val Scheme = darkColorScheme(
    primary = Brand,
    onPrimary = Color(0xFF170B45),
    primaryContainer = BrandDeep,
    onPrimaryContainer = Color(0xFFE6DEFF),
    secondary = Mint,
    onSecondary = Color(0xFF00382F),
    background = Color(0xFF0E0F13),
    onBackground = Color(0xFFE7E7EC),
    surface = Color(0xFF0E0F13),
    onSurface = Color(0xFFE7E7EC),
    surfaceVariant = Color(0xFF22232B),
    onSurfaceVariant = Color(0xFFA9AAB6),
    surfaceContainer = Color(0xFF17181E),
    surfaceContainerHigh = Color(0xFF1D1E25),
    surfaceContainerHighest = Color(0xFF25262E),
    surfaceContainerLow = Color(0xFF131419),
    outline = Color(0xFF3A3B45),
    outlineVariant = Color(0xFF2A2B33),
    error = Color(0xFFFF6B7A),
)

@Composable
fun ProxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Typography(), content = content)
}
