package de.moritzbelli.gapmin.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.moritzbelli.gapmin.core.LatLon
import kotlin.math.cos
import kotlin.math.max

@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = G.text3) {
    Text(text.uppercase(), style = T.label, color = color, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Big number with a small dimmed unit, e.g. "6,12 km". */
@Composable
fun ValueUnit(value: String, unit: String = "", style: TextStyle = T.xl, modifier: Modifier = Modifier, color: Color = G.text1) {
    Text(
        buildAnnotatedString {
            append(value)
            if (unit.isNotEmpty()) {
                withStyle(SpanStyle(fontSize = T.unit.fontSize, color = G.text3, fontWeight = T.unit.fontWeight, letterSpacing = 0.sp)) {
                    append(" $unit")
                }
            }
        },
        style = style,
        color = color,
        maxLines = 1,
        modifier = modifier,
    )
}

/** Press feedback: a slight shrink while touched, like native list items. */
fun Modifier.pressable(onClick: () -> Unit, haptic: Boolean = false): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.965f else 1f, tween(G.T_FAST, easing = G.easeOut), label = "press")
    val h = LocalHapticFeedback.current
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = source, indication = null) {
            if (haptic) h.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
}

@Composable
fun GCard(modifier: Modifier = Modifier, padding: Dp = G.s5, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(G.rLg))
            .background(G.surface1)
            .border(1.dp, G.hairline, RoundedCornerShape(G.rLg))
            .padding(padding),
        content = content,
    )
}

@Composable
fun CardHead(title: String, trailing: String? = null) {
    if (trailing != null && trailing.length > 12) {
        // long meta (e.g. "zuletzt 26. Nov. 2026, 19:32") goes below the title on phones
        Column(Modifier.fillMaxWidth().padding(bottom = G.s4)) {
            Text(title, style = T.mdMedium, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Label(trailing)
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(bottom = G.s4), verticalAlignment = Alignment.Bottom) {
        Text(title, style = T.mdMedium, modifier = Modifier.weight(1f), maxLines = 1)
        if (trailing != null) Label(trailing)
    }
}

/** Let horizontally scrolling content run to the screen edges inside a padded list. */
fun Modifier.bleed(horizontal: Dp): Modifier = this.layout { measurable, constraints ->
    val extra = horizontal.roundToPx() * 2
    val p = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, p.height) { p.place(-extra / 2, 0) }
}

@Composable
fun Dot(color: Color, size: Dp = 6.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun TypeTag(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(color)
        Spacer(Modifier.width(6.dp))
        Text(label, style = T.sm.copy(fontSize = 12.sp), color = G.text2, maxLines = 1)
    }
}

@Composable
fun FilterChip(label: String, selected: Boolean, color: Color?, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) G.surface3 else Color.Transparent, tween(G.T_BASE), label = "chip")
    val fg by animateColorAsState(if (selected) G.text1 else G.text2, tween(G.T_BASE), label = "chipText")
    Row(
        Modifier
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, if (selected) Color.Transparent else G.hairline, CircleShape)
            .pressable(onClick, haptic = true)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (color != null) {
            Dot(color)
            Spacer(Modifier.width(G.s2))
        }
        Text(label, style = T.sm, color = fg, maxLines = 1)
    }
}

/** Exclusive choice (sort order, range); the only control shape for that, see DESIGN.md. */
@Composable
fun <K> Segmented(options: List<Pair<K, String>>, selected: K, onSelect: (K) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(CircleShape)
            .background(G.surface1)
            .border(1.dp, G.hairline, CircleShape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((k, label) in options) {
            val on = k == selected
            val bg by animateColorAsState(if (on) G.surface3 else Color.Transparent, tween(G.T_BASE), label = "seg")
            Box(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(bg)
                    .pressable({ onSelect(k) }, haptic = true)
                    .padding(vertical = 8.dp, horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = T.sm, color = if (on) G.text1 else G.text2, maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Routes

/** Route projected into a unit square (equirectangular, latitude-corrected, aspect kept, centred). */
fun normalizeRoute(points: List<LatLon>, maxPoints: Int = 400): List<Offset> {
    if (points.size < 2) return emptyList()
    val step = max(1, points.size / maxPoints)
    val thinned = points.filterIndexed { i, _ -> i % step == 0 || i == points.size - 1 }
    val k = cos(thinned.sumOf { it.lat } / thinned.size * Math.PI / 180)
    val xs = thinned.map { it.lon * k }
    val ys = thinned.map { -it.lat }
    val minX = xs.min(); val minY = ys.min()
    val w = (xs.max() - minX).coerceAtLeast(1e-9)
    val h = (ys.max() - minY).coerceAtLeast(1e-9)
    val scale = 1.0 / max(w, h)
    val ox = (1 - w * scale) / 2
    val oy = (1 - h * scale) / 2
    return thinned.indices.map { i -> Offset((ox + (xs[i] - minX) * scale).toFloat(), (oy + (ys[i] - minY) * scale).toFloat()) }
}

/**
 * Minimal neon line drawing of a route with a soft halo. Draws itself in on first appearance.
 */
@Composable
fun RouteShape(
    points: List<LatLon>,
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 1.6.dp,
    drawIn: Boolean = true,
    paddingFraction: Float = 0.08f,
    glow: Float = G.GLOW,
) {
    val norm = remember(points) { normalizeRoute(points) }
    val progress = remember(points) { Animatable(if (drawIn) 0f else 1f) }
    LaunchedEffect(points) { if (drawIn) progress.animateTo(1f, tween(G.T_DRAW, easing = G.easeOut)) }
    if (norm.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("keine GPS-Daten", style = T.label) }
        return
    }
    Box(
        modifier.drawWithCache {
            // fit the route's own bounding box into the drawing area (works for square and wide boxes)
            val minX = norm.minOf { it.x }; val maxX = norm.maxOf { it.x }
            val minY = norm.minOf { it.y }; val maxY = norm.maxOf { it.y }
            val bw = (maxX - minX).coerceAtLeast(1e-6f)
            val bh = (maxY - minY).coerceAtLeast(1e-6f)
            val pad = size.minDimension * paddingFraction
            val scale = minOf((size.width - 2 * pad) / bw, (size.height - 2 * pad) / bh)
            val ox = (size.width - bw * scale) / 2 - minX * scale
            val oy = (size.height - bh * scale) / 2 - minY * scale
            val pts = norm.map { Offset(ox + it.x * scale, oy + it.y * scale) }
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
            }
            val measure = PathMeasure().apply { setPath(path, false) }
            val sw = strokeWidth.toPx()
            val seg = Path()
            onDrawBehind {
                val p = progress.value
                val drawn = if (p >= 1f) path else seg.also { it.reset(); measure.getSegment(0f, measure.length * p, it, true) }
                // halo: two wide translucent passes under the crisp line
                drawPath(drawn, color.copy(alpha = 0.10f * glow / G.GLOW), style = Stroke(sw * 7, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(drawn, color.copy(alpha = 0.22f * glow / G.GLOW), style = Stroke(sw * 3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(drawn, color, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
                if (p >= 1f) {
                    drawCircle(color, radius = sw * 1.6f, center = pts.last())
                    drawCircle(G.bg, radius = sw * 1.5f, center = pts.first())
                    drawCircle(G.text1, radius = sw * 1.5f, center = pts.first(), style = Stroke(sw * 0.9f))
                }
            }
        },
    )
}

@Composable
fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> G.surface1
        primary -> G.accent
        else -> G.surface3
    }
    val fg = when {
        !enabled -> G.text3
        primary -> G.onAccent
        danger -> G.bad
        else -> G.text1
    }
    Row(
        modifier
            .clip(CircleShape)
            .background(bg)
            .then(if (enabled) Modifier.pressable(onClick, haptic = true) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            androidx.compose.material3.Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(G.s2))
        }
        Text(text, style = T.sm.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = fg, maxLines = 1)
    }
}
