package com.waide.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Carried over from the desktop build: warm charcoal shop floor, amber gauge light.
object W {
    val Bg        = Color(0xFF12110F)
    val Surface   = Color(0xFF1C1A17)
    val SurfaceHi = Color(0xFF262320)
    val Line      = Color(0xFF332E27)
    val Amber     = Color(0xFFF0A81E)
    val AmberDim  = Color(0xFF7A5510)
    val Text      = Color(0xFFE8E2D8)
    val Muted     = Color(0xFF8C857A)
    val Danger    = Color(0xFFD1543A)
}

private val scheme = darkColorScheme(
    primary = W.Amber,
    onPrimary = Color(0xFF1A1204),
    background = W.Bg,
    onBackground = W.Text,
    surface = W.Surface,
    onSurface = W.Text,
    surfaceVariant = W.SurfaceHi,
    onSurfaceVariant = W.Muted,
    outline = W.Line,
    error = W.Danger
)

// No bundled font files, so the personality comes from weight, tracking and case.
// Mono carries the machine-readout role, sans carries the reading role.
private val typography = Typography(
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.4.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp
    )
)

@Composable
fun WaideTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme()  // always dark by design
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
