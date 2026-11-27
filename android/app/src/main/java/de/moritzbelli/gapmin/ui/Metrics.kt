package de.moritzbelli.gapmin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.moritzbelli.gapmin.core.Fmt
import de.moritzbelli.gapmin.core.Stat
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How a metric value is shown: plain number, duration (minutes → h:mm) or clock time (minutes after midnight). */
enum class Kind { NUM, HM, CLOCK }

/**
 * One measured quantity in a dashboard. `better`: +1 higher is better, −1 lower is better, 0 neither
 * (deviation in either direction matters). `log`: baseline statistics on ln(x), e.g. HRV.
 */
class MetricDef<T>(
    val label: String,
    val get: (T) -> Double?,
    val unit: String = "",
    val kind: Kind = Kind.NUM,
    val digits: Int = 0,
    val ref: String? = null,
    val refLo: Double? = null,
    val refHi: Double? = null,
    val better: Int = 0,
    val log: Boolean = false,
    val color: Color = G.text2,
    val note: String? = null,
) {
    fun fmt(v: Double?): String = if (v == null || !v.isFinite()) "–" else when (kind) {
        Kind.HM -> Fmt.hm(v)
        Kind.CLOCK -> Fmt.hm(((v % 1440) + 1440) % 1440)
        Kind.NUM -> Fmt.num(v, digits)
    }

    val unitLabel: String get() = when (kind) {
        Kind.HM -> "h"
        Kind.CLOCK -> "Uhr"
        Kind.NUM -> unit
    }

    fun fmtDelta(d: Double): String = when (kind) {
        Kind.NUM -> Fmt.signed(d, digits) + if (unit.isNotEmpty()) " $unit" else ""
        else -> Fmt.signed(d) + " min"
    }

    fun fmtSpread(sd: Double): String = when (kind) {
        Kind.NUM -> Fmt.num(sd, if (digits == 0 && sd < 10) 1 else digits)
        else -> "${Fmt.num(sd)} min"
    }
}

/** Personal baseline from the previous values (mean ± SD; for log metrics on ln scale). */
class Baseline(val n: Int, val mean: Double, val sd: Double, val log: Boolean) {
    /** Centre in display units (geometric mean for log metrics). */
    val center: Double get() = if (log) exp(mean) else mean
    val lo: Double get() = if (log) exp(mean - sd) else mean - sd
    val hi: Double get() = if (log) exp(mean + sd) else mean + sd
    fun z(x: Double): Double? = if (sd < 1e-9 || (log && x <= 0)) null else ((if (log) ln(x) else x) - mean) / sd
}

const val BASELINE_WINDOW = 28

fun <T> MetricDef<T>.baseline(items: List<T>, i: Int, window: Int = BASELINE_WINDOW): Baseline? {
    val xs = items.drop(i + 1).take(window).mapNotNull(get).filter { it.isFinite() && (!log || it > 0) }.map { if (log) ln(it) else it }
    if (xs.size < 5) return null
    val mu = xs.average()
    return Baseline(xs.size, mu, sqrt(xs.sumOf { (it - mu) * (it - mu) } / (xs.size - 1)), log)
}

fun zTone(z: Double?, better: Int): Color = when {
    z == null -> G.text3
    better == 0 -> if (abs(z) >= 1.5) G.zone4 else G.text2
    abs(z) < 0.5 -> G.text2
    (z > 0) == (better > 0) -> G.good
    else -> G.bad
}

/** Card with a list of metric rows sharing one time axis (newest item first). */
@Composable
fun <T> MetricGroup(
    title: String,
    defs: List<MetricDef<T>>,
    items: List<T>,
    selected: Int,
    dateOf: (T) -> String,
    dayOf: (T) -> Long,
    trailing: String? = null,
    footnote: String? = null,
) {
    GCard(padding = G.s4) {
        Box(Modifier.padding(horizontal = G.s1)) { CardHead(title, trailing, info = footnote) }
        for ((i, d) in defs.withIndex()) MetricRow(d, items, selected, dateOf, dayOf, divider = i > 0)
    }
}

@Composable
fun <T> MetricRow(def: MetricDef<T>, items: List<T>, selected: Int, dateOf: (T) -> String, dayOf: (T) -> Long, divider: Boolean) {
    val item = items.getOrNull(selected) ?: return
    val v = def.get(item)?.takeIf { it.isFinite() }
    val base = remember(items, selected, def) { def.baseline(items, selected) }
    val z = if (v != null && base != null) base.z(v) else null
    val tone = zTone(z, def.better)
    var open by remember(def) { mutableStateOf(false) }

    val delta = when {
        v == null -> "keine Messung"
        base == null -> "Baseline ab 5 Werten"
        z == null -> "–"
        def.log -> "${Fmt.signed((v / base.center - 1) * 100)} % · z ${Fmt.signed(z, 1)}"
        else -> "${def.fmtDelta(v - base.center)} · z ${Fmt.signed(z, 1)}"
    }
    // everything that explains rather than measures sits behind the "i"
    val info = remember(def, base) {
        listOfNotNull(
            def.note,
            def.ref?.let { "Referenz: $it" },
            base?.let {
                val spread = if (def.log) "${Fmt.num((exp(it.sd) - 1) * 100)} %" else def.fmtSpread(it.sd)
                "Baseline ${if (def.log) "(geom.) " else ""}${def.fmt(it.center)} ± $spread aus ${it.n} Werten (28 vorherige Tage bzw. Nächte)."
            },
        ).joinToString("\n\n")
    }
    var infoOpen by remember(def) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(G.hairline))
        Column(Modifier.fillMaxWidth().pressable({ open = !open }).padding(start = G.s1, end = G.s1, top = G.s3, bottom = G.s3)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(def.color, 5.dp)
                Spacer(Modifier.width(G.s2))
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(def.label, style = T.sm, color = G.text1, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (info.isNotEmpty()) InfoButton(infoOpen, { infoOpen = !infoOpen })
                }
                Spacer(Modifier.width(G.s2))
                ValueUnit(def.fmt(v), def.unitLabel, T.xl.copy(fontSize = 20.sp))
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZBar(z, tone, Modifier.weight(1f).height(12.dp))
                Spacer(Modifier.width(G.s3))
                Text(delta, style = T.unit, color = tone, maxLines = 1)
            }
        }
        if (info.isNotEmpty()) InfoPanel(infoOpen, info, Modifier.padding(horizontal = G.s1).padding(bottom = G.s2))
        AnimatedVisibility(open, enter = expandVertically(tween(G.T_SLOW, easing = G.easeOut)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            TrendBlock(def, items, selected, base, dateOf, dayOf)
        }
    }
}

class MetricSection<T>(val title: String, val defs: List<MetricDef<T>>)

private class OvRow<T>(val def: MetricDef<T>, val v: Double?, val base: Baseline?, val z: Double?, val head: String?)

private fun <T> deltaText(def: MetricDef<T>, v: Double?, base: Baseline?, z: Double?): String = when {
    v == null -> "keine Messung"
    base == null -> "Baseline ab 5 Werten"
    z == null -> "–"
    def.log -> "${Fmt.signed((v / base.center - 1) * 100)} % · z ${Fmt.signed(z, 1)}"
    else -> "${def.fmtDelta(v - base.center)} · z ${Fmt.signed(z, 1)}"
}

private fun <T> infoText(def: MetricDef<T>, base: Baseline?): String = listOfNotNull(
    def.note,
    def.ref?.let { "Referenz: $it" },
    base?.let {
        val spread = if (def.log) "${Fmt.num((exp(it.sd) - 1) * 100)} %" else def.fmtSpread(it.sd)
        "Baseline ${if (def.log) "(geom.) " else ""}${def.fmt(it.center)} ± $spread aus ${it.n} Werten (28 vorherige Tage bzw. Nächte)."
    },
).joinToString("\n\n")

/**
 * All single-value metrics in one diagram: one row per metric, bar = deviation of the selected day from the own 28-day
 * baseline in SD units (grey band ±1 SD, green better / red worse / grey neutral). Tap a row for value, baseline,
 * references and the 90-day course; tap again to close.
 */
@Composable
fun <T> BaselineOverview(
    title: String,
    sections: List<MetricSection<T>>,
    items: List<T>,
    selected: Int,
    dateOf: (T) -> String,
    dayOf: (T) -> Long,
    trailing: String? = null,
) {
    val item = items.getOrNull(selected) ?: return
    val rows = remember(sections, items, selected) {
        sections.flatMap { sec ->
            sec.defs.mapIndexed { i, d ->
                val v = d.get(item)?.takeIf { it.isFinite() }
                val b = d.baseline(items, selected)
                OvRow(d, v, b, if (v != null && b != null) b.z(v) else null, if (i == 0) sec.title else null)
            }
        }
    }
    var sel by remember(sections) { mutableStateOf<Int?>(null) }
    val reveal = remember(items, selected) { Animatable(0f) }
    LaunchedEffect(items, selected) { reveal.animateTo(1f, tween(G.T_DRAW, easing = G.easeOut)) }
    val measurer = rememberTextMeasurer()
    val rowH = 26.dp
    val headH = 24.dp
    val total = rowH * rows.size + headH * rows.count { it.head != null }
    val info = "Jede Zeile ist eine Kennzahl der gewählten Nacht bzw. des gewählten Tages. Der Balken zeigt die Abweichung vom eigenen Mittel " +
        "der 28 vorherigen Werte in Standardabweichungen; das graue Band ist ±1 SD, die Marken sind ±2 SD. " +
        "Grün: besser, rot: schlechter als gewöhnlich (ab 0,5 SD); grau: neutral oder keine Richtung. Zeile antippen für Details."

    GCard(padding = G.s4) {
        Box(Modifier.padding(horizontal = G.s1)) { CardHead(title, trailing, info = info) }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(total)
                .pointerInput(rows) {
                    detectTapGestures { o ->
                        var y = 0f
                        var hit: Int? = null
                        for ((i, r) in rows.withIndex()) {
                            if (r.head != null) y += headH.toPx()
                            if (o.y >= y && o.y < y + rowH.toPx()) { hit = i; break }
                            y += rowH.toPx()
                        }
                        val h = hit
                        sel = if (h == null || sel == h) null else h
                    }
                },
        ) {
            val labelW = size.width * 0.37f
            val valW = 64.dp.toPx()
            val gap = 8.dp.toPx()
            val bx0 = labelW + gap
            val bx1 = size.width - valW - gap
            fun x(z: Double) = bx0 + ((z.coerceIn(-3.0, 3.0) + 3.0) / 6.0 * (bx1 - bx0)).toFloat()
            val headStyle = T.label
            // band ±1 SD and zero line through the whole diagram
            drawRoundRect(G.surface3.copy(alpha = 0.55f), Offset(x(-1.0), 0f), Size(x(1.0) - x(-1.0), size.height), CornerRadius(4.dp.toPx()))
            for (t in listOf(-2.0, 2.0)) drawLine(G.hairline, Offset(x(t), 0f), Offset(x(t), size.height), 1.dp.toPx())
            drawLine(G.hairlineStrong, Offset(x(0.0), 0f), Offset(x(0.0), size.height), 1.dp.toPx())

            var y = 0f
            for ((i, r) in rows.withIndex()) {
                r.head?.let { h ->
                    val t = measurer.measure(h.uppercase(), headStyle, maxLines = 1, softWrap = false)
                    drawText(t, topLeft = Offset(0f, y + headH.toPx() - t.size.height - 4.dp.toPx()))
                    y += headH.toPx()
                }
                val cy = y + rowH.toPx() / 2
                val on = sel == i
                if (on) drawRoundRect(G.hairlineStrong.copy(alpha = 0.35f), Offset(0f, y + 1.dp.toPx()), Size(size.width, rowH.toPx() - 2.dp.toPx()), CornerRadius(6.dp.toPx()))
                val tone = zTone(r.z, r.def.better)
                val ls = TextStyle(fontFamily = Inter, fontSize = 11.sp, color = if (on) G.text1 else G.text2)
                val lt = measurer.measure(r.def.label, ls, overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1, constraints = Constraints(maxWidth = (labelW - 4.dp.toPx()).toInt().coerceAtLeast(1)))
                drawText(lt, topLeft = Offset(4.dp.toPx(), cy - lt.size.height / 2f))
                if (r.z != null) {
                    val zEnd = x(r.z * reveal.value)
                    val x0 = x(0.0)
                    val bh = 7.dp.toPx()
                    drawRoundRect(tone.copy(alpha = 0.85f), Offset(minOf(x0, zEnd), cy - bh / 2), Size(maxOf(abs(zEnd - x0), 2.dp.toPx()), bh), CornerRadius(3.dp.toPx()))
                    drawCircle(tone, 3.dp.toPx(), Offset(zEnd, cy))
                } else {
                    drawCircle(G.text3.copy(alpha = 0.5f), 1.5.dp.toPx(), Offset(x(0.0), cy))
                }
                val vs = T.stat.copy(color = if (r.v == null) G.text3 else G.text1)
                val vt = measurer.measure(
                    if (r.v == null) "–" else (r.def.fmt(r.v) + if (r.def.unitLabel.isNotEmpty()) " ${r.def.unitLabel}" else ""),
                    vs, overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1, constraints = Constraints(maxWidth = valW.toInt()),
                )
                drawText(vt, topLeft = Offset(size.width - vt.size.width - 2.dp.toPx(), cy - vt.size.height / 2f))
                y += rowH.toPx()
            }
        }
        val r = sel?.let { rows.getOrNull(it) }
        AnimatedVisibility(r != null, enter = expandVertically(tween(G.T_SLOW, easing = G.easeOut)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            if (r != null) {
                val tone = zTone(r.z, r.def.better)
                val txt = infoText(r.def, r.base)
                Column(Modifier.fillMaxWidth().padding(horizontal = G.s1)) {
                    Spacer(Modifier.height(G.s3))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(G.hairline))
                    Spacer(Modifier.height(G.s3))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(r.def.color, 5.dp)
                        Spacer(Modifier.width(G.s2))
                        Text(r.def.label, style = T.sm, color = G.text1, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ValueUnit(r.def.fmt(r.v), r.def.unitLabel, T.xl.copy(fontSize = 20.sp))
                    }
                    Text(deltaText(r.def, r.v, r.base, r.z), style = T.unit, color = tone, modifier = Modifier.padding(top = 4.dp))
                    if (txt.isNotEmpty()) Text(txt, style = T.unit, color = G.text3, modifier = Modifier.padding(top = G.s2))
                    Spacer(Modifier.height(G.s3))
                    TrendBlock(r.def, items, selected, r.base, dateOf, dayOf)
                }
            }
        }
    }
}

/** Position of today's value in SD units of the baseline: light band ±1 SD, ticks at ±2 SD. */
@Composable
fun ZBar(z: Double?, color: Color, modifier: Modifier = Modifier) {
    val pos by animateFloatAsState((z ?: 0.0).coerceIn(-3.0, 3.0).toFloat(), tween(G.T_SLOW, easing = G.easeOut), label = "z")
    Canvas(modifier) {
        val cy = size.height / 2
        fun x(v: Float) = (v + 3f) / 6f * size.width
        drawLine(G.hairline, Offset(0f, cy), Offset(size.width, cy), 1.dp.toPx())
        drawRoundRect(
            G.surface3,
            topLeft = Offset(x(-1f), cy - 3.dp.toPx()),
            size = Size(x(1f) - x(-1f), 6.dp.toPx()),
            cornerRadius = CornerRadius(3.dp.toPx()),
        )
        for (t in listOf(-2f, 2f)) drawLine(G.hairlineStrong, Offset(x(t), cy - 3.dp.toPx()), Offset(x(t), cy + 3.dp.toPx()), 1.dp.toPx())
        drawLine(G.text3, Offset(x(0f), cy - 5.dp.toPx()), Offset(x(0f), cy + 5.dp.toPx()), 1.dp.toPx())
        if (z != null) {
            val c = Offset(x(pos), cy)
            drawCircle(color.copy(alpha = 0.22f), 7.dp.toPx(), c)
            drawCircle(color, 3.5.dp.toPx(), c)
        }
    }
}

@Composable
internal fun <T> TrendBlock(def: MetricDef<T>, items: List<T>, selected: Int, base: Baseline?, dateOf: (T) -> String, dayOf: (T) -> Long) {
    val window = remember(items, selected) { items.drop(selected).take(90).reversed() }
    val vals = remember(window, def) { window.map { def.get(it)?.takeIf { v -> v.isFinite() } } }
    val d = remember(vals) { Stat.desc(vals.filterNotNull()) }
    val reg = remember(window, vals) { Stat.regression(window.zip(vals).mapNotNull { (w, v) -> v?.let { dayOf(w).toDouble() to it } }) }

    Column(Modifier.fillMaxWidth().padding(start = G.s1, end = G.s1, bottom = G.s4)) {
        TrendChart(def, window, vals, base, dateOf)
        if (d != null) {
            Spacer(Modifier.height(G.s3))
            val cells = buildList {
                add("n" to d.n.toString())
                add("Median" to def.fmt(d.median))
                add("IQR" to "${def.fmt(d.q1)}–${def.fmt(d.q3)}")
                add("Min–Max" to "${def.fmt(d.min)}–${def.fmt(d.max)}")
                add("SD" to def.fmtSpread(d.sd))
                add("CV" to if (def.kind == Kind.NUM && d.mean > 0) "${Fmt.num(d.sd / d.mean * 100, 1)} %" else "–")
                add("Trend / 30 T" to (reg?.let { def.fmtDelta(it.first * 30) } ?: "–"))
                add("r (Zeit)" to (reg?.let { Fmt.num(it.second, 2) } ?: "–"))
            }
            for (row in cells.chunked(4)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    for ((k, v) in row) {
                        Column(Modifier.weight(1f)) {
                            Text(k, style = T.label.copy(letterSpacing = 0.sp), maxLines = 1)
                            Text(v, style = T.sm.copy(fontSize = 12.sp), color = G.text1, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Daily values as dots, 7-point rolling mean as the main line, today's baseline (mean ± 1 SD) as a band,
 * reference limits dashed. Tap or drag to read single values.
 */
@Composable
private fun <T> TrendChart(def: MetricDef<T>, window: List<T>, vals: List<Double?>, base: Baseline?, dateOf: (T) -> String) {
    val measurer = rememberTextMeasurer()
    val roll = remember(vals) { Stat.rollingMean(vals, 7) }
    var cursor by remember(window) { mutableStateOf<Int?>(null) }
    val reveal = remember(window) { Animatable(0f) }
    LaunchedEffect(window) { reveal.animateTo(1f, tween(G.T_DRAW, easing = G.easeOut)) }
    val present = vals.filterNotNull()
    if (present.size < 2 || window.size < 2) {
        Text("Zu wenige Werte für einen Verlauf.", style = T.unit, modifier = Modifier.padding(vertical = G.s3))
        return
    }
    var lo = present.min()
    var hi = present.max()
    if (base != null) { lo = minOf(lo, base.lo); hi = maxOf(hi, base.hi) }
    val span0 = (hi - lo).coerceAtLeast(1e-6)
    def.refLo?.let { if (it > lo - span0 * 0.6 && it < hi + span0 * 0.6) { lo = minOf(lo, it); hi = maxOf(hi, it) } }
    def.refHi?.let { if (it > lo - span0 * 0.6 && it < hi + span0 * 0.6) { lo = minOf(lo, it); hi = maxOf(hi, it) } }
    val pad = ((hi - lo) * 0.1).coerceAtLeast(if (def.digits > 0) 0.1 else 1.0)
    lo -= pad
    hi += pad
    val n = window.size
    val axis = T.axis

    val c = cursor
    val shown = c ?: (n - 1)
    Text(
        "${dateOf(window[shown])}  ·  ${def.fmt(vals[shown])} ${def.unitLabel}  ·  Ø7 ${def.fmt(roll[shown])}",
        style = T.unit, color = if (c != null) G.text1 else G.text3, maxLines = 1, modifier = Modifier.padding(bottom = G.s2),
    )
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(132.dp)
            .pointerInput(window) {
                val left = 34.dp.toPx()
                fun idx(x: Float) = (((x - left) / (size.width - left - 4.dp.toPx())) * (n - 1)).roundToInt().coerceIn(0, n - 1)
                detectTapGestures(onTap = { o -> val i = idx(o.x); cursor = if (cursor == i) null else i })
            }
            .pointerInput(window) {
                val left = 34.dp.toPx()
                fun idx(x: Float) = (((x - left) / (size.width - left - 4.dp.toPx())) * (n - 1)).roundToInt().coerceIn(0, n - 1)
                detectHorizontalDragGestures { change, _ -> cursor = idx(change.position.x) }
            },
    ) {
        val left = 34.dp.toPx()
        val right = size.width - 4.dp.toPx()
        val top = 4.dp.toPx()
        val bottom = size.height - 16.dp.toPx()
        fun x(i: Int) = left + i.toFloat() / (n - 1) * (right - left)
        fun y(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()

        // axis: three hairlines with labels
        for (k in 0..2) {
            val v = lo + pad + (hi - lo - 2 * pad) * k / 2
            val yy = y(v)
            drawLine(G.hairline, Offset(left, yy), Offset(right, yy), 1.dp.toPx())
            val t = measurer.measure(def.fmt(v), axis)
            drawText(t, topLeft = Offset(left - t.size.width - 6.dp.toPx(), yy - t.size.height / 2f))
        }
        // baseline band of the selected day
        if (base != null) {
            val y1 = y(base.hi)
            val y0 = y(base.lo)
            drawRect(def.color.copy(alpha = 0.08f), topLeft = Offset(left, y1), size = Size(right - left, y0 - y1))
            drawLine(def.color.copy(alpha = 0.35f), Offset(left, y(base.center)), Offset(right, y(base.center)), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        }
        // reference limits from the literature
        for (r in listOfNotNull(def.refLo, def.refHi)) {
            if (r in lo..hi) drawLine(G.text3, Offset(left, y(r)), Offset(right, y(r)), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 5f)))
        }
        val limit = (n - 1) * reveal.value
        // daily values
        for (i in 0 until n) {
            val v = vals[i] ?: continue
            if (i > limit) break
            drawCircle(G.text3, 1.8.dp.toPx(), Offset(x(i), y(v)))
        }
        // rolling mean with halo
        val path = Path()
        var started = false
        for (i in 0 until n) {
            val v = roll[i] ?: continue
            if (i > limit) break
            if (!started) { path.moveTo(x(i), y(v)); started = true } else path.lineTo(x(i), y(v))
        }
        if (started) {
            drawPath(path, def.color.copy(alpha = 0.18f), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, def.color, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        // selected (= newest in window) value
        vals[n - 1]?.let {
            drawCircle(def.color.copy(alpha = 0.25f), 6.dp.toPx(), Offset(x(n - 1), y(it)))
            drawCircle(def.color, 3.dp.toPx(), Offset(x(n - 1), y(it)))
        }
        // cursor
        if (c != null) {
            drawLine(G.hairlineStrong, Offset(x(c), top), Offset(x(c), bottom), 1.dp.toPx())
            vals[c]?.let { drawCircle(G.text1, 3.dp.toPx(), Offset(x(c), y(it))) }
        }
        // x labels: first and last date
        val a = measurer.measure(dateOf(window.first()), axis)
        drawText(a, topLeft = Offset(left, bottom + 3.dp.toPx()))
        val b = measurer.measure(dateOf(window.last()), axis)
        drawText(b, topLeft = Offset(right - b.size.width, bottom + 3.dp.toPx()))
    }
}

/** Horizontal day selector: newest on the right, bar height = `level` (0-1). */
@Composable
fun <T> DayStrip(
    items: List<T>,
    selected: Int,
    onSelect: (Int) -> Unit,
    key: (T) -> String,
    level: (T) -> Float?,
    color: Color,
    dayOfMonth: (T) -> Int,
    weekday: (T) -> String,
) {
    val state = rememberLazyListState()
    val list = remember(items) { items.take(180) }
    LaunchedEffect(selected) {
        val visible = state.layoutInfo.visibleItemsInfo.map { it.index }
        if (selected !in visible) state.animateScrollToItem(maxOf(0, selected - 3))
    }
    Column(Modifier.fillMaxWidth()) {
        LazyRow(
            state = state,
            reverseLayout = true,
            modifier = Modifier.fillMaxWidth().height(72.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            itemsIndexed(list, key = { _, it -> key(it) }) { i, it ->
                val on = i == selected
                val alpha by animateFloatAsState(if (on) 1f else 0.28f, tween(G.T_BASE), label = "bar")
                val l = level(it)
                // bars grow in from the baseline, newest first
                val grow = remember(key(it)) { Animatable(0f) }
                LaunchedEffect(key(it)) { kotlinx.coroutines.delay(18L * i.coerceAtMost(20)); grow.animateTo(1f, tween(G.T_SLOW, easing = G.easeOut)) }
                Column(
                    Modifier.width(12.dp).fillMaxHeight().pressable({ onSelect(i) }, haptic = true),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Box(
                        Modifier
                            .width(if (on) 8.dp else 6.dp)
                            .fillMaxHeight(0.74f * ((l ?: 0.04f).coerceIn(0.04f, 1f) * grow.value).coerceAtLeast(0.02f))
                            .clip(RoundedCornerShape(4.dp))
                            .background((if (l == null) G.surface3 else color).copy(alpha = alpha)),
                    )
                    Spacer(Modifier.height(4.dp))
                    val d = dayOfMonth(it)
                    Text(
                        if (on || d == 1) d.toString() else weekday(it),
                        style = T.label.copy(fontSize = 9.sp, letterSpacing = 0.sp),
                        color = if (on) G.text1 else G.text3,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        Spacer(Modifier.height(G.s1))
    }
}

/** Compact key figures in a row (replaces the former big page titles). */
@Composable
fun Totals(totals: List<Triple<String, String, String>>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = G.s1, bottom = G.s4), horizontalArrangement = Arrangement.spacedBy(G.s6)) {
        for ((l, v, u) in totals) {
            Column {
                Label(l)
                Spacer(Modifier.height(G.s1))
                ValueUnit(v, u, T.xxl)
            }
        }
    }
}
