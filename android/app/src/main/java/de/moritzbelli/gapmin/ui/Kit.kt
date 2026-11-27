package de.moritzbelli.gapmin.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/*
 * Building blocks of the v2 interface (docs/DESIGN.md): screen header, staggered reveal, counting numbers,
 * deltas, sparklines, stacked shares and the range gauge.
 */

/** Screen title with a monospace meta line; replaces the cards' former page-level clutter. */
@Composable
fun ScreenHeader(title: String, meta: String? = null, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val a = remember(title) { Animatable(0f) }
    LaunchedEffect(title) { a.animateTo(1f, tween(G.T_SLOW + 200, easing = G.easeOut)) }
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = G.s5, bottom = G.s4)
            .graphicsLayer { alpha = a.value; translationY = (1 - a.value) * 10.dp.toPx() },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = T.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            trailing?.invoke(this)
        }
        if (meta != null) {
            Spacer(Modifier.height(2.dp))
            Text(meta.uppercase(), style = T.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Remembers which sections have already faded in, so scrolling back does not replay the entrance. */
class RevealState {
    val seen = HashSet<Any>()
}

@Composable
fun rememberReveal(): RevealState = remember { RevealState() }

/** Staggered entrance: fade and rise, `order` × 45 ms later than the first section. */
fun Modifier.reveal(state: RevealState, key: Any, order: Int): Modifier = composed {
    val first = remember(key) { key !in state.seen }
    val a = remember(key) { Animatable(if (first) 0f else 1f) }
    LaunchedEffect(key) {
        if (first) {
            state.seen.add(key)
            delay(G.STAGGER * order.coerceIn(0, 8))
            a.animateTo(1f, tween(G.T_SLOW + 160, easing = G.easeOut))
        }
    }
    graphicsLayer {
        alpha = a.value
        translationY = (1 - a.value) * 22.dp.toPx()
        val s = 0.985f + 0.015f * a.value
        scaleX = s
        scaleY = s
    }
}

/** A number that counts up on first appearance and glides to new values. */
@Composable
fun CountUp(
    value: Double?,
    format: (Double) -> String,
    unit: String = "",
    style: TextStyle = T.xl,
    modifier: Modifier = Modifier,
    color: Color = G.text1,
    durationMs: Int = G.T_DRAW,
) {
    if (value == null || !value.isFinite()) {
        ValueUnit("–", unit, style, modifier, G.text3)
        return
    }
    val a = remember { Animatable(0f) }
    LaunchedEffect(value) { a.animateTo(value.toFloat(), tween(durationMs, easing = G.easeOut)) }
    ValueUnit(format(a.value.toDouble()), unit, style, modifier, color)
}

/** Small monospace change marker, e.g. "▲ 4 %" in the evidence colour. */
@Composable
fun Delta(text: String, tone: Color, modifier: Modifier = Modifier, up: Boolean? = null) {
    val arrow = when (up) {
        true -> "▲ "
        false -> "▼ "
        null -> ""
    }
    Text(arrow + text, style = T.stat, color = tone, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Label above value, used in dense stat grids. */
@Composable
fun StatCell(label: String, value: String, unit: String = "", modifier: Modifier = Modifier, style: TextStyle = T.xl.copy(fontSize = 20.sp), color: Color = G.text1) {
    Column(modifier) {
        Label(label)
        Spacer(Modifier.height(3.dp))
        ValueUnit(value, unit, style, color = color)
    }
}

/** Grid of label/value cells, `columns` per row. */
@Composable
fun StatRows(cells: List<Triple<String, String, String>>, columns: Int = 3, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(G.s4)) {
        for (row in cells.chunked(columns)) {
            Row(Modifier.fillMaxWidth()) {
                for ((l, v, u) in row) StatCell(l, v, u, Modifier.weight(1f))
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Monospace key/value statistics in a compact table (n, Ø, SD …). */
@Composable
fun StatTable(cells: List<Pair<String, String>>, columns: Int = 4, modifier: Modifier = Modifier, tone: (Int) -> Color = { G.text1 }) {
    Column(modifier) {
        for ((ri, row) in cells.chunked(columns).withIndex()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                for ((ci, c) in row.withIndex()) {
                    Column(Modifier.weight(1f).padding(end = 6.dp)) {
                        Text(c.first, style = T.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(c.second, style = T.stat, color = tone(ri * columns + ci), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(G.hairline))
}

/** Thin line of the last values (oldest first) that draws itself in; the newest value is marked. */
@Composable
fun Sparkline(values: List<Double?>, color: Color, modifier: Modifier = Modifier, band: Pair<Double, Double>? = null) {
    val present = values.filterNotNull()
    val reveal = remember(values) { Animatable(0f) }
    LaunchedEffect(values) { reveal.animateTo(1f, tween(G.T_DRAW + 200, easing = G.easeOut)) }
    Canvas(modifier) {
        if (present.size < 2) {
            drawLine(G.hairline, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
            return@Canvas
        }
        var lo = present.min()
        var hi = present.max()
        band?.let { lo = minOf(lo, it.first); hi = maxOf(hi, it.second) }
        if (hi - lo < 1e-9) { lo -= 1; hi += 1 }
        val pad = 3.dp.toPx()
        val n = values.size
        fun x(i: Int) = pad + i.toFloat() / (n - 1).coerceAtLeast(1) * (size.width - 2 * pad)
        fun y(v: Double) = (size.height - pad - (v - lo) / (hi - lo) * (size.height - 2 * pad)).toFloat()
        band?.let { drawRect(color.copy(alpha = 0.08f), Offset(0f, y(it.second)), Size(size.width, y(it.first) - y(it.second))) }
        val limit = (n - 1) * reveal.value
        val path = Path()
        var started = false
        var lastI = -1
        for (i in 0 until n) {
            val v = values[i] ?: continue
            if (i > limit) break
            if (!started) { path.moveTo(x(i), y(v)); started = true } else path.lineTo(x(i), y(v))
            lastI = i
        }
        drawPath(path, color.copy(alpha = 0.18f), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, color, style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (lastI >= 0 && reveal.value >= 0.99f) {
            val v = values[lastI]!!
            drawCircle(color.copy(alpha = 0.25f), 4.5.dp.toPx(), Offset(x(lastI), y(v)))
            drawCircle(color, 2.2.dp.toPx(), Offset(x(lastI), y(v)))
        }
    }
}

/** Shares as one rounded bar (e.g. sleep stages, intensity zones); widths grow in on appearance. */
@Composable
fun ShareBar(parts: List<Pair<Double, Color>>, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(G.T_DRAW, easing = G.easeOut)) }
    val g = grow.value
    val total = parts.sumOf { it.first }
    Row(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(G.surface2)) {
        if (total > 0) for ((v, c) in parts) if (v > 0) Box(Modifier.weight((v / total).toFloat() * g + 1e-4f).fillMaxHeight().background(c))
        if (total > 0 && g < 1f) Spacer(Modifier.weight(1 - g + 1e-4f))
    }
}

/** Legend row for a share bar: dot, label, value. */
@Composable
fun ShareLegend(items: List<Triple<String, String, Color>>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth()) {
        for ((l, v, c) in items) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(c, 5.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(l, style = T.label, maxLines = 1)
                }
                Text(v, style = T.sm, color = G.text1, maxLines = 1)
            }
        }
    }
}

/**
 * Value on a scale with coloured ranges (e.g. ACWR 0–2 with the 0,8–1,3 band). The marker slides in from the left.
 * `ticks` are labelled below in monospace.
 */
@Composable
fun RangeGauge(
    value: Double?,
    min: Double,
    max: Double,
    ranges: List<Triple<Double, Double, Color>>,
    ticks: List<Double>,
    fmt: (Double) -> String,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val pos = remember { Animatable(min.toFloat()) }
    LaunchedEffect(value) { if (value != null) pos.animateTo(value.coerceIn(min, max).toFloat(), tween(G.T_HERO, easing = G.easeOut)) }
    Canvas(modifier.fillMaxWidth().height(40.dp)) {
        val barH = 6.dp.toPx()
        val cy = 10.dp.toPx()
        fun x(v: Double) = ((v - min) / (max - min)).toFloat().coerceIn(0f, 1f) * size.width
        drawRoundRect(G.surface2, Offset(0f, cy - barH / 2), Size(size.width, barH), CornerRadius(barH / 2))
        for ((a, b, c) in ranges) {
            drawRoundRect(c.copy(alpha = 0.55f), Offset(x(a), cy - barH / 2), Size(x(b) - x(a), barH), CornerRadius(barH / 2))
        }
        for (t in ticks) {
            val tl = measurer.measure(fmt(t), T.axis)
            drawText(tl, topLeft = Offset((x(t) - tl.size.width / 2f).coerceIn(0f, size.width - tl.size.width), cy + barH + 6.dp.toPx()))
        }
        if (value != null) {
            val px = x(pos.value.toDouble())
            drawCircle(G.text1.copy(alpha = 0.18f), 10.dp.toPx(), Offset(px, cy))
            drawCircle(G.bg, 6.dp.toPx(), Offset(px, cy))
            drawCircle(G.text1, 6.dp.toPx(), Offset(px, cy), style = Stroke(2.dp.toPx()))
        }
    }
}
