package de.moritzbelli.gapmin.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import de.moritzbelli.gapmin.R
import de.moritzbelli.gapmin.core.RunCategory

/**
 * Gapmin design tokens (docs/DESIGN.md), the native counterpart of web/src/styles/tokens.css.
 * New components use only these values.
 */
object G {
    // Surfaces: near-black base, elevation by white alpha (OLED-friendly)
    val bg = Color(0xFF060708)
    val surface1 = Color.White.copy(alpha = 0.028f)
    val surface2 = Color.White.copy(alpha = 0.05f)
    val surface3 = Color.White.copy(alpha = 0.08f)
    val surfaceSolid = Color(0xFF101113)
    val hairline = Color.White.copy(alpha = 0.07f)
    val hairlineStrong = Color.White.copy(alpha = 0.14f)

    // Text
    val text1 = Color(0xFFF4F5F6)
    val text2 = Color(0xFFF4F5F6).copy(alpha = 0.6f)
    val text3 = Color(0xFFF4F5F6).copy(alpha = 0.36f)

    // Accent: exactly one brand colour (focus, active, selection)
    val accent = Color(0xFFD4FF3A)
    val accentSoft = Color(0xFFD4FF3A).copy(alpha = 0.12f)
    val onAccent = Color(0xFF0B0C0D)

    // Data colours
    val runEasy = Color(0xFF4ADE80)
    val runRecovery = Color(0xFF2EE6D0)
    val runLong = Color(0xFF60A5FA)
    val runTempo = Color(0xFFFB923C)
    val runIntervals = Color(0xFFF472B6)
    val runTrail = Color(0xFFFACC15)
    const val GLOW = 0.45f

    val stageDeep = Color(0xFF7C6CFF)
    val stageLight = Color(0xFF4FA8FF)
    val stageRem = Color(0xFFE879F9)
    val stageAwake = Color(0xFFFFB36B)

    val zone1 = Color(0xFF6B7A8F)
    val zone2 = Color(0xFF5AA9E6)
    val zone3 = Color(0xFF7DDCA0)
    val zone4 = Color(0xFFFFAD72)
    val zone5 = Color(0xFFFF6B6B)

    val good = Color(0xFF7DDCA0)
    val bad = Color(0xFFFF7B7B)

    // Space (4 dp grid)
    val s1 = 4.dp; val s2 = 8.dp; val s3 = 12.dp; val s4 = 16.dp
    val s5 = 24.dp; val s6 = 32.dp; val s7 = 48.dp; val s8 = 64.dp

    // Shape
    val rSm = 10.dp; val rMd = 16.dp; val rLg = 24.dp

    // Motion
    val easeOut = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    val easeInOut = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
    const val T_FAST = 120
    const val T_BASE = 220
    const val T_SLOW = 420
    const val T_DRAW = 900

    fun runColor(c: RunCategory) = when (c) {
        RunCategory.EASY -> runEasy
        RunCategory.RECOVERY -> runRecovery
        RunCategory.LONG -> runLong
        RunCategory.TEMPO -> runTempo
        RunCategory.INTERVALS -> runIntervals
        RunCategory.TRAIL -> runTrail
    }

    fun scoreColor(s: Double?) = when {
        s == null -> surface3
        s >= 80 -> good
        s >= 60 -> zone4
        else -> bad
    }
}

val Inter = FontFamily(
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

/** Type scale for phones (web sizes scaled to a 360–430 dp wide screen). */
object T {
    private val base = TextStyle(fontFamily = Inter, color = G.text1, fontFeatureSettings = "tnum")
    val display = base.copy(fontSize = 40.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.045).em, lineHeight = 42.sp)
    val xxl = base.copy(fontSize = 30.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.035).em, lineHeight = 32.sp)
    val xl = base.copy(fontSize = 24.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.035).em, lineHeight = 26.sp)
    val lg = base.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.02).em, lineHeight = 24.sp)
    val md = base.copy(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 21.sp)
    val mdMedium = md.copy(fontWeight = FontWeight.Medium)
    val sm = base.copy(fontSize = 13.sp, fontWeight = FontWeight.Normal, color = G.text2, lineHeight = 18.sp)
    val label = base.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium, color = G.text3, letterSpacing = 0.09.em, lineHeight = 14.sp)
    val unit = base.copy(fontSize = 12.sp, fontWeight = FontWeight.Normal, color = G.text3)
}

@Composable
fun GapminTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = G.accent,
            onPrimary = G.onAccent,
            background = G.bg,
            surface = G.surfaceSolid,
            onSurface = G.text1,
            onBackground = G.text1,
            surfaceVariant = G.surface3,
            outline = G.hairlineStrong,
            error = G.bad,
        ),
        content = content,
    )
}
