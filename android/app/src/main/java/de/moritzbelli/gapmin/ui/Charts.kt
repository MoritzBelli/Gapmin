package de.moritzbelli.gapmin.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.moritzbelli.gapmin.core.Fmt
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun epochText(day: Long): String = Fmt.dayShort(LocalDate.ofEpochDay(day).toString())

/** One series of a [TimeChart]: points (epoch day, value), drawn as glowing line and/or dots. */
class ChartSeries(
    val name: String,
    val pts: List<Pair<Long, Double>>,
    val color: Color,
    val line: Boolean = true,
    val dots: Boolean = false,
    val fill: Boolean = false,
    val width: Dp = 1.6.dp,
)

/**
 * Time chart in the Gapmin style: three hairlines with monospace labels, glowing lines, optional reference band.
 * Tap or drag reads the values of all series at the nearest day; the line draws itself in from the left.
 */
@Composable
fun TimeChart(
    series: List<ChartSeries>,
    from: Long,
    to: Long,
    fmt: (Double) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
    band: Pair<Double, Double>? = null,
    zeroLine: Boolean = false,
    unit: String = "",
) {
    val all = series.flatMap { s -> s.pts.filter { it.first in from..to }.map { it.second } }
    val measurer = rememberTextMeasurer()
    val reveal = remember(series, from, to) { Animatable(0f) }
    LaunchedEffect(series, from, to) { reveal.animateTo(1f, tween(G.T_DRAW + 300, easing = G.easeOut)) }
    var cursor by remember(series, from, to) { mutableStateOf<Long?>(null) }
    val span = (to - from).coerceAtLeast(1)

    Column(modifier) {
        val c = cursor
        val head = if (c == null) {
            series.filter { it.pts.isNotEmpty() }.joinToString("  ·  ") { s -> "${s.name} ${fmt(s.pts.last().second)}" }
        } else {
            epochText(c) + "  ·  " + series.joinToString("  ·  ") { s ->
                val p = s.pts.minByOrNull { abs(it.first - c) }?.takeIf { abs(it.first - c) <= maxOf(3L, span / 40) }
                "${s.name} ${p?.let { fmt(it.second) } ?: "–"}"
            }
        }
        Text(
            head + if (unit.isNotEmpty() && c != null) " $unit" else "",
            style = T.stat, color = if (c != null) G.text1 else G.text3, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = G.s2),
        )
        if (all.size < 2) {
            Text("Zu wenige Werte für einen Verlauf.", style = T.unit, modifier = Modifier.padding(vertical = G.s4))
            return@Column
        }
        var lo = all.min()
        var hi = all.max()
        band?.let { lo = minOf(lo, it.first); hi = maxOf(hi, it.second) }
        if (zeroLine) { lo = minOf(lo, 0.0); hi = maxOf(hi, 0.0) }
        val pad = ((hi - lo) * 0.12).coerceAtLeast(1e-3)
        lo -= pad
        hi += pad

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(series, from, to) {
                    val left = 32.dp.toPx()
                    fun day(x: Float) = from + (((x - left) / (size.width - left)).coerceIn(0f, 1f) * span).roundToInt()
                    detectTapGestures(onTap = { o -> val d = day(o.x); cursor = if (cursor == d) null else d })
                }
                .pointerInput(series, from, to) {
                    val left = 32.dp.toPx()
                    fun day(x: Float) = from + (((x - left) / (size.width - left)).coerceIn(0f, 1f) * span).roundToInt()
                    detectHorizontalDragGestures { ch, _ -> cursor = day(ch.position.x) }
                },
        ) {
            val left = 32.dp.toPx()
            val right = size.width
            val top = 4.dp.toPx()
            val bottom = size.height - 16.dp.toPx()
            fun x(d: Long) = left + (d - from).toFloat() / span * (right - left)
            fun y(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()

            for (k in 0..2) {
                val v = lo + pad + (hi - lo - 2 * pad) * k / 2
                drawLine(G.hairline, Offset(left, y(v)), Offset(right, y(v)), 1.dp.toPx())
                val t = measurer.measure(fmt(v), T.axis)
                drawText(t, topLeft = Offset(left - t.size.width - 6.dp.toPx(), y(v) - t.size.height / 2f))
            }
            if (zeroLine) drawLine(G.hairlineStrong, Offset(left, y(0.0)), Offset(right, y(0.0)), 1.dp.toPx())
            band?.let {
                drawRect(G.text1.copy(alpha = 0.04f), Offset(left, y(it.second)), Size(right - left, y(it.first) - y(it.second)))
                for (b in listOf(it.first, it.second)) {
                    drawLine(G.text3, Offset(left, y(b)), Offset(right, y(b)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 5f)))
                }
            }
            val limit = from + span * reveal.value
            for (s in series) {
                val pts = s.pts.filter { it.first in from..to && it.first <= limit }
                if (pts.isEmpty()) continue
                if (s.dots) for ((d, v) in pts) drawCircle(s.color.copy(alpha = if (s.line) 0.45f else 0.85f), 2.dp.toPx(), Offset(x(d), y(v)))
                if (s.line && pts.size >= 2) {
                    val path = Path()
                    pts.forEachIndexed { i, (d, v) -> if (i == 0) path.moveTo(x(d), y(v)) else path.lineTo(x(d), y(v)) }
                    if (s.fill) {
                        val area = Path().apply {
                            addPath(path)
                            lineTo(x(pts.last().first), bottom)
                            lineTo(x(pts.first().first), bottom)
                            close()
                        }
                        drawPath(area, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.16f), Color.Transparent), startY = top, endY = bottom))
                    }
                    drawPath(path, s.color.copy(alpha = 0.18f), style = Stroke(s.width.toPx() * 3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(path, s.color, style = Stroke(s.width.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            cursor?.let { d ->
                drawLine(G.hairlineStrong, Offset(x(d), top), Offset(x(d), bottom), 1.dp.toPx())
                for (s in series) {
                    val p = s.pts.minByOrNull { abs(it.first - d) } ?: continue
                    if (abs(p.first - d) <= maxOf(3L, span / 40)) drawCircle(s.color, 3.dp.toPx(), Offset(x(p.first), y(p.second)))
                }
            }
            for (k in 0..3) {
                val d = from + span * k / 3
                val t = measurer.measure(epochText(d), T.axis)
                val tx = when (k) { 0 -> left; 3 -> right - t.size.width; else -> x(d) - t.size.width / 2f }
                drawText(t, topLeft = Offset(tx, bottom + 4.dp.toPx()))
            }
        }
    }
}

/**
 * Vertical bars (oldest left), e.g. weekly volume. The newest bar is highlighted, a dashed line marks the mean.
 * Tap a bar to read it; bars grow in staggered from the left.
 */
@Composable
fun BarChart(
    values: List<Double>,
    labels: List<String>,
    color: Color,
    fmt: (Double) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 110.dp,
    unit: String = "",
) {
    val measurer = rememberTextMeasurer()
    val grow = remember(values) { Animatable(0f) }
    LaunchedEffect(values) { grow.animateTo(1f, tween(G.T_DRAW + 300, easing = G.easeOut)) }
    var sel by remember(values) { mutableStateOf<Int?>(null) }
    val mean = if (values.isEmpty()) 0.0 else values.average()
    Column(modifier) {
        val i = sel ?: values.lastIndex
        Text(
            if (values.isEmpty()) "" else "${labels.getOrElse(i) { "" }}  ·  ${fmt(values[i])} $unit  ·  Ø ${fmt(mean)}",
            style = T.stat, color = if (sel != null) G.text1 else G.text3, maxLines = 1, modifier = Modifier.padding(bottom = G.s2),
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(values) {
                    detectTapGestures(onTap = { o ->
                        if (values.isNotEmpty()) {
                            val k = ((o.x / size.width) * values.size).toInt().coerceIn(0, values.lastIndex)
                            sel = if (sel == k) null else k
                        }
                    })
                },
        ) {
            if (values.isEmpty()) return@Canvas
            val n = values.size
            val hi = (values.maxOrNull() ?: 1.0).coerceAtLeast(1e-6)
            val bottom = size.height - 14.dp.toPx()
            val slot = size.width / n
            val bw = slot * 0.62f
            for ((k, v) in values.withIndex()) {
                val g = ((grow.value * (n + 4) - k) / 5f).coerceIn(0f, 1f)
                val h = (v / hi).toFloat() * (bottom - 6.dp.toPx()) * g
                val on = k == (sel ?: n - 1)
                val cx = slot * k + slot / 2
                drawRoundRect(
                    color.copy(alpha = if (on) 1f else 0.38f),
                    Offset(cx - bw / 2, bottom - h), Size(bw, maxOf(h, if (v > 0) 2.dp.toPx() else 0f)), CornerRadius(minOf(bw / 2, 4.dp.toPx())),
                )
            }
            val ym = (bottom - (mean / hi).toFloat() * (bottom - 6.dp.toPx()))
            drawLine(G.text3, Offset(0f, ym), Offset(size.width, ym), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 5f)))
            for (k in listOf(0, n / 2, n - 1).distinct()) {
                val t = measurer.measure(labels.getOrElse(k) { "" }, T.axis)
                val cx = slot * k + slot / 2
                drawText(t, topLeft = Offset((cx - t.size.width / 2f).coerceIn(0f, size.width - t.size.width), bottom + 3.dp.toPx()))
            }
        }
    }
}
