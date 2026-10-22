package com.proxai.hermes.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.proxai.hermes.R

// ProxAI glowing blue (v0.3.1; was purple).
val Brand = Color(0xFF4DA8FF)      // sky blue
val BrandDeep = Color(0xFF0B3D91)  // deep blue
val BrandCyan = Color(0xFF38E1FF)  // glow highlight
val Mint = BrandCyan               // "listening" color

/** The ProxAI gradient: cyan glow into blue into deep navy. */
val BrandGradient = Brush.linearGradient(listOf(BrandCyan, Brand, BrandDeep))

/** Soft blue glow behind the top of a screen. */
val ScreenGlow = Brush.verticalGradient(0f to Color(0xFF0C2547), 0.35f to Color(0xFF0A111D), 1f to Color(0xFF090C12))

private val Scheme = darkColorScheme(
    primary = Brand,
    onPrimary = Color(0xFF00213F),
    primaryContainer = Color(0xFF123F7A),
    onPrimaryContainer = Color(0xFFDCEBFF),
    secondary = BrandCyan,
    onSecondary = Color(0xFF00313A),
    secondaryContainer = Color(0xFF0E3A4A),
    onSecondaryContainer = Color(0xFFCDF4FF),
    tertiaryContainer = Color(0xFF16345C),
    onTertiaryContainer = Color(0xFFD6E8FF),
    background = Color(0xFF090C12),
    onBackground = Color(0xFFE6ECF5),
    surface = Color(0xFF090C12),
    onSurface = Color(0xFFE6ECF5),
    surfaceVariant = Color(0xFF1B2433),
    onSurfaceVariant = Color(0xFFA3B0C4),
    surfaceContainer = Color(0xFF111723),
    surfaceContainerHigh = Color(0xFF162030),
    surfaceContainerHighest = Color(0xFF1D293B),
    surfaceContainerLow = Color(0xFF0D121B),
    outline = Color(0xFF2E3B52),
    outlineVariant = Color(0xFF223047),
    error = Color(0xFFFF6B7A),
)

@Composable
fun ProxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Typography(), content = content)
}

/** "ProxAI" in the brand gradient (screen titles). */
@Composable
fun BrandTitle(text: String) {
    androidx.compose.material3.Text(text, style = MaterialTheme.typography.titleLarge.copy(
        brush = Brush.linearGradient(listOf(Color(0xFFBFF6FF), BrandCyan, Brand)),
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
}

/** The ProxAI mark (a "P" with a spark) on the blue gradient. */
@Composable
fun ProxLogo(size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(RoundedCornerShape(size / 3.4f)).background(BrandGradient),
        contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_logo_mark), contentDescription = "ProxAI", modifier = Modifier.size(size * 0.78f))
    }
}
