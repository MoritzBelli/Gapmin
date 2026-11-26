package de.moritzbelli.gapmin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.moritzbelli.gapmin.core.Desc
import de.moritzbelli.gapmin.core.Fmt
import de.moritzbelli.gapmin.core.Stat
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.sqrt

private class Wk(val day: Double, val n: Int, val mean: Double, val sd: Double?, val from: Long, val to: Long)

private fun dayText(epochDay: Long) = Fmt.dayShort(LocalDate.ofEpochDay(epochDay).toString())

/**
 * Long-term trends of one metric: weekly means ± 1 SD with daily values and an OLS trend line,
 * the linear trend with 95 % CI, and a comparison of consecutive 30-day blocks (Cohen's d).
 * Items are newest first; windows are counted back from the selected item.
 */
@Composable
fun <T> TrendsCard(
    title: String,
    defs: List<MetricDef<T>>,
    items: List<T>,
    selected: Int,
    dayOf: (T) -> Long,
    dateOf: (T) -> String,
) {
    if (items.isEmpty() || defs.isEmpty()) return
    var idx by remember { mutableIntStateOf(0) }
    var range by remember { mutableIntStateOf(90) }
    val def = defs[idx.coerceIn(0, defs.lastIndex)]
    val ref = dayOf(items[selected.coerceIn(0, items.lastIndex)])
    val hist = remember(items, selected) { items.drop(selected) }

    val pts = remember(hist, def, range) {
        hist.takeWhile { ref - dayOf(it) < range }
            .mapNotNull { x -> def.get(x)?.takeIf { it.isFinite() }?.let { dayOf(x) to it } }
            .sortedBy { it.first }
    }
    val weeks = remember(pts) {
        pts.groupBy { Math.floorDiv(it.first, 7L) }.toSortedMap().map { (w, l) ->
            val vs = l.map { it.second }
            val m = vs.average()
            val sd = if (vs.size >= 3) sqrt(vs.sumOf { (it - m) * (it - m) } / (vs.size - 1)) else null
            Wk(w * 7 + 3.0, vs.size, m, sd, l.first().first, l.last().first)
        }
    }
    val trend = remember(pts) { Stat.trend(pts.map { it.first.toDouble() to it.second }) }
    val desc = remember(pts) { Stat.desc(pts.map { it.second }) }
    val blocks = remember(hist, def, range) {
        val k = (range / 30).coerceIn(2, 12)
        (0 until k).map { b ->
            Stat.desc(
                hist.filter { (ref - dayOf(it)).toInt() in (b * 30) until ((b + 1) * 30) }
                    .mapNotNull { def.get(it)?.takeIf { v -> v.isFinite() } },
            )
        }
    }
    var cursor by remember(weeks) { mutableStateOf<Int?>(null) }

    GCard {
        CardHead(title, "n = ${pts.size}")
        Row(
            Modifier.fillMaxWidth().bleed(G.s5).horizontalScroll(rememberScrollState()).padding(horizontal = G.s5),
            horizontalArrangement = Arrangement.spacedBy(G.s2),
        ) {
            defs.forEachIndexed { i, d -> FilterChip(d.label, i == idx, d.color) { idx = i; cursor = null } }
        }
        Spacer(Modifier.height(G.s3))
        Segmented(listOf(90 to "90 T", 180 to "180 T", 365 to "1 Jahr"), range, { range = it; cursor = null }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(G.s4))

        if (pts.size < 8 || weeks.size < 2) {
            Text("Zu wenige Werte für einen Trend (mind. 8 Messungen im Zeitraum).", style = T.sm, color = G.text3, modifier = Modifier.padding(vertical = G.s4))
            return@GCard
        }

        val c = cursor?.let { weeks.getOrNull(it) }
        val head = if (c != null) {
            "${dayText(c.from)}–${dayText(c.to)} · n ${c.n} · Ø ${def.fmt(c.mean)}${c.sd?.let { " ± ${def.fmtSpread(it)}" } ?: ""} ${def.unitLabel}"
        } else {
            "Wochenmittel ± 1 SD · Punkte: Tage · gestrichelt: Trend"
        }
        Text(head, style = T.unit, color = if (c != null) G.text1 else G.text3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = G.s2))
        TrendCanvas(def, pts, weeks, trend, cursor) { cursor = it }

        // key figures
        Spacer(Modifier.height(G.s3))
        val sig = trend != null && abs(trend.slope) > 1.96 * trend.se
        val tone = if (sig && trend != null) zTone(if (trend.slope > 0) 1.0 else -1.0, def.better) else G.text2
        val row1 = listOf(
            "n" to (desc?.n?.toString() ?: "–"),
            "Ø" to (desc?.let { def.fmt(it.mean) } ?: "–"),
            "SD" to (desc?.let { def.fmtSpread(it.sd) } ?: "–"),
            "Median" to (desc?.let { def.fmt(it.median) } ?: "–"),
        )
        val row2 = listOf(
            "Trend / 30 T" to (trend?.let { def.fmtDelta(it.slope * 30) } ?: "–"),
            "95 %-KI ±" to (trend?.let { def.fmtSpread(1.96 * it.se * 30) } ?: "–"),
            "r" to (trend?.let { Fmt.num(it.r, 2) } ?: "–"),
            "R²" to (trend?.let { Fmt.num(it.r * it.r, 2) } ?: "–"),
        )
        for ((ri, cells) in listOf(row1, row2).withIndex()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                for ((k, v) in cells) {
                    Column(Modifier.weight(1f)) {
                        Text(k, style = T.label.copy(letterSpacing = 0.sp), maxLines = 1)
                        Text(v, style = T.sm.copy(fontSize = 12.sp), color = if (ri == 1 && k.startsWith("Trend")) tone else G.text1, maxLines = 1)
                    }
                }
            }
        }
        if (trend != null) {
            Spacer(Modifier.height(G.s1))
            Text(
                (if (sig) "Linearer Trend belegt: 95 %-KI schließt 0 aus." else "Linearer Trend nicht belegt: 95 %-KI schließt 0 ein.") +
                    " OLS auf Tageswerten, Näherung ohne Autokorrelation.",
                style = T.unit.copy(lineHeight = 16.sp),
            )
        }

        // 30-day blocks
        Spacer(Modifier.height(G.s4))
        Box(Modifier.fillMaxWidth().height(1.dp).background(G.hairline))
        Spacer(Modifier.height(G.s3))
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Label("Zeitraum", Modifier.weight(1.0f))
            Label("n", Modifier.weight(0.45f))
            Label("Ø ± SD", Modifier.weight(1.9f))
            Label("Δ davor", Modifier.weight(1.45f))
            Label("d", Modifier.weight(0.55f))
        }
        val cell = T.unit.copy(fontSize = 11.5.sp)
        for ((b, ds) in blocks.withIndex()) {
            val prev: Desc? = blocks.getOrNull(b + 1)
            val d = if (ds != null && prev != null) Stat.cohenD(ds, prev) else null
            val delta = if (ds != null && prev != null && ds.n >= 3 && prev.n >= 3) def.fmtDelta(ds.mean - prev.mean) else "–"
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text("${b * 30}–${(b + 1) * 30} T", style = cell, color = G.text2, modifier = Modifier.weight(1.0f), maxLines = 1)
                Text(ds?.n?.toString() ?: "–", style = cell, color = G.text2, modifier = Modifier.weight(0.45f), maxLines = 1)
                Text(
                    if (ds != null && ds.n >= 3) "${def.fmt(ds.mean)} ± ${def.fmtSpread(ds.sd)}" else "–",
                    style = cell.copy(color = G.text1), modifier = Modifier.weight(1.9f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(delta, style = cell.copy(color = zTone(d, def.better)), modifier = Modifier.weight(1.45f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(d?.let { Fmt.signed(it, 2) } ?: "–", style = cell, color = G.text2, modifier = Modifier.weight(0.55f), maxLines = 1)
            }
        }
        Spacer(Modifier.height(G.s2))
        Text(
            "Blöcke zu je 30 Tagen, rückwärts ab dem gewählten Tag. d = Cohen's d (gepoolte SD) gegenüber dem davor liegenden Block; ab |d| ≥ 0,5 farbig.",
            style = T.unit.copy(lineHeight = 16.sp),
        )
    }
}

@Composable
private fun TrendCanvas(def: MetricDef<*>, pts: List<Pair<Long, Double>>, weeks: List<Wk>, trend: Stat.Trend?, cursor: Int?, onCursor: (Int?) -> Unit) {
    val measurer = rememberTextMeasurer()
    val axis = TextStyle(fontFamily = Inter, fontSize = 9.5.sp, color = G.text3)
    val d0 = pts.first().first
    val d1 = pts.last().first.let { if (it == d0) d0 + 1 else it }

    var lo = minOf(pts.minOf { it.second }, weeks.minOf { it.mean - (it.sd ?: 0.0) })
    var hi = maxOf(pts.maxOf { it.second }, weeks.maxOf { it.mean + (it.sd ?: 0.0) })
    val span0 = (hi - lo).coerceAtLeast(1e-6)
    for (r in listOfNotNull(def.refLo, def.refHi)) if (r > lo - span0 * 0.6 && r < hi + span0 * 0.6) { lo = minOf(lo, r); hi = maxOf(hi, r) }
    val pad = ((hi - lo) * 0.1).coerceAtLeast(if (def.digits > 0) 0.1 else 1.0)
    lo -= pad
    hi += pad
    val mx = pts.map { it.first.toDouble() }.average()
    val my = pts.map { it.second }.average()

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .pointerInput(weeks) {
                val left = 34.dp.toPx()
                fun nearest(x: Float): Int {
                    val day = d0 + ((x - left) / (size.width - left - 4.dp.toPx())).coerceIn(0f, 1f) * (d1 - d0)
                    return weeks.indices.minBy { abs(weeks[it].day - day) }
                }
                detectTapGestures(onTap = { o -> val i = nearest(o.x); onCursor(if (cursor == i) null else i) })
            }
            .pointerInput(weeks) {
                val left = 34.dp.toPx()
                fun nearest(x: Float): Int {
                    val day = d0 + ((x - left) / (size.width - left - 4.dp.toPx())).coerceIn(0f, 1f) * (d1 - d0)
                    return weeks.indices.minBy { abs(weeks[it].day - day) }
                }
                detectHorizontalDragGestures { ch, _ -> onCursor(nearest(ch.position.x)) }
            },
    ) {
        val left = 34.dp.toPx()
        val right = size.width - 4.dp.toPx()
        val top = 4.dp.toPx()
        val bottom = size.height - 16.dp.toPx()
        fun x(day: Double) = (left + (day - d0) / (d1 - d0) * (right - left)).toFloat()
        fun y(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()

        for (k in 0..2) {
            val v = lo + pad + (hi - lo - 2 * pad) * k / 2
            drawLine(G.hairline, Offset(left, y(v)), Offset(right, y(v)), 1.dp.toPx())
            val t = measurer.measure(def.fmt(v), axis)
            drawText(t, topLeft = Offset(left - t.size.width - 6.dp.toPx(), y(v) - t.size.height / 2f))
        }
        for (r in listOfNotNull(def.refLo, def.refHi)) {
            if (r in lo..hi) drawLine(G.text3, Offset(left, y(r)), Offset(right, y(r)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 5f)))
        }
        // ±1 SD band of the weekly means
        val bw = weeks.filter { it.sd != null }
        if (bw.size >= 2) {
            val p = Path()
            bw.forEachIndexed { i, w -> if (i == 0) p.moveTo(x(w.day), y(w.mean + w.sd!!)) else p.lineTo(x(w.day), y(w.mean + w.sd!!)) }
            for (w in bw.reversed()) p.lineTo(x(w.day), y(w.mean - w.sd!!))
            p.close()
            drawPath(p, def.color.copy(alpha = 0.10f))
        }
        for ((day, v) in pts) drawCircle(G.text3.copy(alpha = 0.7f), 1.5.dp.toPx(), Offset(x(day.toDouble()), y(v)))
        // OLS trend
        if (trend != null) {
            val ya = my + trend.slope * (d0 - mx)
            val yb = my + trend.slope * (d1 - mx)
            drawLine(
                G.text2, Offset(x(d0.toDouble()), y(ya)), Offset(x(d1.toDouble()), y(yb)), 1.2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
            )
        }
        val line = Path()
        weeks.forEachIndexed { i, w -> if (i == 0) line.moveTo(x(w.day), y(w.mean)) else line.lineTo(x(w.day), y(w.mean)) }
        drawPath(line, def.color.copy(alpha = 0.18f), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(line, def.color, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        weeks.lastOrNull()?.let {
            drawCircle(def.color.copy(alpha = 0.25f), 6.dp.toPx(), Offset(x(it.day), y(it.mean)))
            drawCircle(def.color, 3.dp.toPx(), Offset(x(it.day), y(it.mean)))
        }
        cursor?.let { weeks.getOrNull(it) }?.let { w ->
            drawLine(G.hairlineStrong, Offset(x(w.day), top), Offset(x(w.day), bottom), 1.dp.toPx())
            drawCircle(G.text1, 3.dp.toPx(), Offset(x(w.day), y(w.mean)))
        }
        val a = measurer.measure(dayText(d0), axis)
        drawText(a, topLeft = Offset(left, bottom + 3.dp.toPx()))
        val b = measurer.measure(dayText(pts.last().first), axis)
        drawText(b, topLeft = Offset(right - b.size.width, bottom + 3.dp.toPx()))
    }
}
