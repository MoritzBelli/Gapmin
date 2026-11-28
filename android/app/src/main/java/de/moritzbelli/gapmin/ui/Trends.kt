package de.moritzbelli.gapmin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

private class Bucket(val day: Double, val n: Int, val mean: Double, val sd: Double?, val min: Double, val max: Double, val from: Long, val to: Long)

private val monthYear: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yy", Locale.GERMAN)

private fun dayText(epochDay: Long) = Fmt.dayShort(LocalDate.ofEpochDay(epochDay).toString())
private fun monthText(epochDay: Long) = monthYear.format(LocalDate.ofEpochDay(epochDay)).replace("..", ".")

/** Bucket key and bucket centre (epoch day) of a day: calendar weeks or calendar months. */
private fun bucketOf(day: Long, monthly: Boolean): Pair<Long, Double> {
    if (!monthly) {
        val w = Math.floorDiv(day, 7L)
        return w to w * 7 + 3.0
    }
    val d = LocalDate.ofEpochDay(day)
    return (d.year * 12L + d.monthValue) to (d.withDayOfMonth(1).toEpochDay() + (d.lengthOfMonth() - 1) / 2.0)
}

private fun buckets(pts: List<Pair<Long, Double>>, monthly: Boolean): List<Bucket> =
    pts.groupBy { bucketOf(it.first, monthly).first }.toSortedMap().map { (_, l) ->
        val vs = l.map { it.second }
        val m = vs.average()
        val sd = if (vs.size >= 3) sqrt(vs.sumOf { (it - m) * (it - m) } / (vs.size - 1)) else null
        Bucket(bucketOf(l.first().first, monthly).second, vs.size, m, sd, vs.min(), vs.max(), l.first().first, l.last().first)
    }

/** Distance between two monthly means above which the history has a hole (more than one missing month). */
private const val GAP_DAYS = 50.0

private val RANGES = listOf(30 to "1 M", 90 to "3 M", 180 to "6 M", 365 to "1 J", 0 to "Alles")

/**
 * Long-term trends of one metric, built for a narrow screen.
 *
 * The visible window (1 M, 3 M, 6 M, 1 J or all) is aggregated so that about 15–50 marks cross the screen at any zoom:
 * up to 3 months weekly means ± 1 SD with the daily dots, up to a year weekly means with min–max whiskers,
 * beyond that monthly means. A minimap of the whole history below the chart shows where the window sits and moves it
 * (tap or drag). Below: linear trend with 95 % CI and a comparison of consecutive blocks (Cohen's d).
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
    var endBack by remember { mutableIntStateOf(0) }
    val def = defs[idx.coerceIn(0, defs.lastIndex)]
    val ref = dayOf(items[selected.coerceIn(0, items.lastIndex)])
    val hist = remember(items, selected) { items.drop(selected) }
    val oldest = remember(hist) { hist.minOfOrNull(dayOf) ?: ref }
    val total = (ref - oldest).toInt() + 1
    val win = if (range == 0) total else range
    val maxBack = (total - win).coerceAtLeast(0)
    val back = endBack.coerceIn(0, maxBack)
    val winEnd = ref - back
    val winStart = (winEnd - win + 1).coerceAtLeast(oldest)

    val pts = remember(hist, def, win, back) {
        hist.filter { (ref - dayOf(it)).toInt() in back until back + win }
            .mapNotNull { x -> def.get(x)?.takeIf { it.isFinite() }?.let { dayOf(x) to it } }
            .sortedBy { it.first }
    }
    val dots = win <= 120
    val monthly = win > 400
    val bks = remember(pts, monthly) { buckets(pts, monthly) }
    val trend = remember(pts) { Stat.trend(pts.map { it.first.toDouble() to it.second }) }
    val desc = remember(pts) { Stat.desc(pts.map { it.second }) }
    val nBlocks = if (win >= 120) 4 else 3
    val blockLen = (win / nBlocks).coerceAtLeast(7)
    val blocks = remember(hist, def, win, back) {
        (0 until nBlocks).map { b ->
            Stat.desc(
                hist.filter { (ref - dayOf(it)).toInt() - back in (b * blockLen) until ((b + 1) * blockLen) }
                    .mapNotNull { def.get(it)?.takeIf { v -> v.isFinite() } },
            )
        }
    }
    val overview = remember(hist, def) {
        buckets(hist.mapNotNull { x -> def.get(x)?.takeIf { it.isFinite() }?.let { dayOf(x) to it } }.sortedBy { it.first }, monthly = true)
    }
    var cursor by remember(bks) { mutableStateOf<Int?>(null) }

    val info = remember(trend, def) {
        val sig = trend != null && abs(trend.slope) > 1.96 * trend.se
        "Linie: Mittel je Woche (bis 1 Jahr) bzw. je Monat. Bis 3 Monate: Punkte = Tageswerte, Band = ±1 SD der Woche. " +
            "Darüber: feine Striche = Minimum bis Maximum je Woche bzw. Monat. Gestrichelt: linearer Trend.\n\n" +
            "Zeitfenster: Der kleine Gesamtverlauf unter dem Diagramm zeigt die ganze Historie als Monatsmittel, das helle Fenster darin den " +
            "gezeigten Ausschnitt. Antippen oder Ziehen verschiebt ihn, „Jetzt“ springt zurück. Im Diagramm liest Tippen oder Ziehen Werte ab.\n\n" +
            (if (trend == null) "" else
                (if (sig) "Linearer Trend belegt: 95 %-KI schließt 0 aus. " else "Linearer Trend nicht belegt: 95 %-KI schließt 0 ein. ") +
                    "OLS auf Tageswerten, Näherung ohne Autokorrelation.\n\n") +
            "Blöcke: gleich lange Abschnitte rückwärts ab dem Fensterende. Δ und d (Cohen's d, gepoolte SD) gegenüber dem davor liegenden Block; ab |d| ≥ 0,5 farbig."
    }

    GCard {
        CardHead(title, "n = ${pts.size}", info = info)
        Row(
            Modifier.fillMaxWidth().bleed(G.s5).horizontalScroll(rememberScrollState()).padding(horizontal = G.s5),
            horizontalArrangement = Arrangement.spacedBy(G.s2),
        ) {
            defs.forEachIndexed { i, d -> FilterChip(d.label, i == idx, d.color) { idx = i; cursor = null } }
        }
        Spacer(Modifier.height(G.s3))
        Segmented(RANGES, range, { range = it; endBack = 0; cursor = null }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(G.s4))

        if (pts.size < 8 || bks.size < 2) {
            // keep the layout identical to the chart case so the minimap does not jump under the finger while dragging through a gap
            Text(
                "${dayText(winStart)}–${dayText(winEnd)} · n ${pts.size}",
                style = T.unit, color = G.text3, maxLines = 1, modifier = Modifier.padding(bottom = G.s2),
            )
            Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (pts.isEmpty()) "Keine Daten in diesem Zeitraum (Lücke in der Aufzeichnung)." else "Zu wenige Werte für einen Trend (mind. 8 Messungen im Zeitraum).",
                    style = T.sm, color = G.text3,
                )
            }
            if (maxBack > 0 || back > 0) {
                Spacer(Modifier.height(G.s3))
                WindowNavigator(overview, oldest, ref, winStart, winEnd, def.color, back > 0, { endBack = 0; cursor = null }) { center ->
                    endBack = (ref - (center + win / 2.0)).roundToInt().coerceIn(0, maxBack); cursor = null
                }
            }
            return@GCard
        }

        val shown = cursor ?: bks.lastIndex
        val c = bks[shown.coerceIn(0, bks.lastIndex)]
        val span = if (monthly) monthText(c.from) else "${dayText(c.from)}–${dayText(c.to)}"
        Text(
            "$span · n ${c.n} · Ø ${def.fmt(c.mean)}${c.sd?.let { " ± ${def.fmtSpread(it)}" } ?: ""} ${def.unitLabel}",
            style = T.unit, color = if (cursor != null) G.text1 else G.text3, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = G.s2),
        )
        TrendCanvas(def, pts, bks, trend, cursor, winStart, winEnd, dots, win > 200) { cursor = it }

        if (maxBack > 0 || back > 0) {
            Spacer(Modifier.height(G.s3))
            WindowNavigator(overview, oldest, ref, winStart, winEnd, def.color, back > 0, { endBack = 0; cursor = null }) { center ->
                endBack = (ref - (center + win / 2.0)).roundToInt().coerceIn(0, maxBack); cursor = null
            }
        }

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
            "Trend / 30 T" to (trend?.let { fine(def, it.slope * 30) } ?: "–"),
            "95 %-KI ±" to (trend?.let { fine(def, 1.96 * it.se * 30, signed = false) } ?: "–"),
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

        // consecutive blocks, newest first
        Spacer(Modifier.height(G.s3))
        Box(Modifier.fillMaxWidth().height(1.dp).background(G.hairline))
        Spacer(Modifier.height(G.s3))
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Label("Ab", Modifier.weight(1.0f))
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
            val start = winEnd - (b + 1) * blockLen + 1
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(if (blockLen >= 60) monthText(start) else dayText(start), style = cell, color = G.text2, modifier = Modifier.weight(1.0f), maxLines = 1)
                Text(ds?.n?.toString() ?: "–", style = cell, color = G.text2, modifier = Modifier.weight(0.45f), maxLines = 1)
                Text(
                    if (ds != null && ds.n >= 3) "${def.fmt(ds.mean)} ± ${def.fmtSpread(ds.sd)}" else "–",
                    style = cell.copy(color = G.text1), modifier = Modifier.weight(1.9f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(delta, style = cell.copy(color = zTone(d, def.better)), modifier = Modifier.weight(1.45f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(d?.let { Fmt.signed(it, 2) } ?: "–", style = cell, color = G.text2, modifier = Modifier.weight(0.55f), maxLines = 1)
            }
        }
    }
}

/**
 * Minimap of the whole history (monthly means). The light window marks the part shown in the chart;
 * tap or drag moves it, "Jetzt" jumps back to the selected day.
 */
@Composable
private fun WindowNavigator(
    overview: List<Bucket>,
    oldest: Long,
    newest: Long,
    from: Long,
    to: Long,
    color: Color,
    showNow: Boolean,
    onNow: () -> Unit,
    onCenter: (Double) -> Unit,
) {
    val center by rememberUpdatedState(onCenter)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Canvas(
            Modifier
                .weight(1f)
                .height(34.dp)
                .pointerInput(oldest, newest) {
                    fun day(x: Float): Double = oldest + (x / size.width).coerceIn(0f, 1f).toDouble() * (newest - oldest).coerceAtLeast(1)
                    detectTapGestures(onTap = { o -> center(day(o.x)) })
                }
                .pointerInput(oldest, newest) {
                    fun day(x: Float): Double = oldest + (x / size.width).coerceIn(0f, 1f).toDouble() * (newest - oldest).coerceAtLeast(1)
                    detectHorizontalDragGestures { ch, _ -> center(day(ch.position.x)) }
                },
        ) {
            val span = (newest - oldest).coerceAtLeast(1).toDouble()
            fun x(day: Double) = ((day - oldest) / span * size.width).toFloat()
            val pad = 5.dp.toPx()
            val lo = overview.minOfOrNull { it.mean } ?: 0.0
            val hi = overview.maxOfOrNull { it.mean } ?: 1.0
            fun y(v: Double) = if (hi - lo < 1e-9) size.height / 2 else (size.height - pad - (v - lo) / (hi - lo) * (size.height - 2 * pad)).toFloat()
            // window
            val x0 = x(from.toDouble())
            val x1 = x(to.toDouble() + 1).coerceAtLeast(x0 + 10.dp.toPx())
            drawRoundRect(color.copy(alpha = 0.10f), Offset(x0, 0f), Size(x1 - x0, size.height), CornerRadius(8.dp.toPx()))
            drawRoundRect(G.hairlineStrong, Offset(x0, 0f), Size(x1 - x0, size.height), CornerRadius(8.dp.toPx()), style = Stroke(1.dp.toPx()))
            // history
            // a month without data is a gap: break the line there instead of drawing a straight bridge
            val path = Path()
            var prevDay = Double.NEGATIVE_INFINITY
            overview.forEach { b ->
                if (b.day - prevDay > GAP_DAYS) {
                    path.moveTo(x(b.day), y(b.mean))
                    if (prevDay > Double.NEGATIVE_INFINITY) {
                        drawLine(G.hairlineStrong, Offset(x(prevDay), size.height - pad), Offset(x(b.day), size.height - pad), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 5f)))
                    }
                } else path.lineTo(x(b.day), y(b.mean))
                prevDay = b.day
            }
            drawPath(path, color.copy(alpha = 0.7f), style = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            for (b in overview) drawCircle(color.copy(alpha = 0.7f), 1.2.dp.toPx(), Offset(x(b.day), y(b.mean)))
        }
        // always laid out (invisible when at "now") so the minimap keeps its width while dragging
        Spacer(Modifier.width(G.s3))
        Text(
            "Jetzt",
            style = T.unit.copy(fontSize = 11.5.sp),
            color = G.text1,
            modifier = Modifier
                .alpha(if (showNow) 1f else 0f)
                .clip(CircleShape)
                .border(1.dp, G.hairlineStrong, CircleShape)
                .then(if (showNow) Modifier.pressable(onNow, haptic = true) else Modifier)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** Trend values are small: one more decimal than the metric's display precision. */
private fun fine(def: MetricDef<*>, v: Double, signed: Boolean = true): String = when (def.kind) {
    Kind.NUM -> {
        val d = def.digits + 1
        (if (signed) Fmt.signed(v, d) else Fmt.num(v, d)) + if (def.unit.isNotEmpty()) " ${def.unit}" else ""
    }
    else -> (if (signed) Fmt.signed(v, 1) else Fmt.num(v, 1)) + " min"
}

@Composable
private fun TrendCanvas(
    def: MetricDef<*>,
    pts: List<Pair<Long, Double>>,
    bks: List<Bucket>,
    trend: Stat.Trend?,
    cursor: Int?,
    winStart: Long,
    winEnd: Long,
    dots: Boolean,
    longLabels: Boolean,
    onCursor: (Int?) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val axis = T.axis
    val d0 = winStart
    val d1 = if (winEnd <= winStart) winStart + 1 else winEnd
    val curNow by rememberUpdatedState(cursor)
    val setCursor by rememberUpdatedState(onCursor)

    var lo = minOf(pts.minOf { it.second }, bks.minOf { it.mean - (if (dots) it.sd ?: 0.0 else 0.0) })
    var hi = maxOf(pts.maxOf { it.second }, bks.maxOf { it.mean + (if (dots) it.sd ?: 0.0 else 0.0) })
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
            .pointerInput(bks) {
                val left = 34.dp.toPx()
                fun nearest(x: Float): Int {
                    val day = d0 + ((x - left) / (size.width - left - 4.dp.toPx())).coerceIn(0f, 1f) * (d1 - d0)
                    return bks.indices.minBy { abs(bks[it].day - day) }
                }
                detectTapGestures(onTap = { o -> val i = nearest(o.x); setCursor(if (curNow == i) null else i) })
            }
            .pointerInput(bks) {
                val left = 34.dp.toPx()
                fun nearest(x: Float): Int {
                    val day = d0 + ((x - left) / (size.width - left - 4.dp.toPx())).coerceIn(0f, 1f) * (d1 - d0)
                    return bks.indices.minBy { abs(bks[it].day - day) }
                }
                detectHorizontalDragGestures { ch, _ -> setCursor(nearest(ch.position.x)) }
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
        if (dots) {
            // ±1 SD band of the weekly means, daily values as dots
            val bw = bks.filter { it.sd != null }
            if (bw.size >= 2) {
                val p = Path()
                bw.forEachIndexed { i, w -> if (i == 0) p.moveTo(x(w.day), y(w.mean + w.sd!!)) else p.lineTo(x(w.day), y(w.mean + w.sd!!)) }
                for (w in bw.reversed()) p.lineTo(x(w.day), y(w.mean - w.sd!!))
                p.close()
                drawPath(p, def.color.copy(alpha = 0.10f))
            }
            for ((day, v) in pts) drawCircle(G.text3.copy(alpha = 0.7f), 1.5.dp.toPx(), Offset(x(day.toDouble()), y(v)))
        } else {
            // long windows: min–max whisker per bucket instead of hundreds of dots
            for (b in bks) if (b.n >= 2) drawLine(def.color.copy(alpha = 0.28f), Offset(x(b.day), y(b.max)), Offset(x(b.day), y(b.min)), 1.4.dp.toPx(), cap = StrokeCap.Round)
        }
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
        val maxStep = if (bks.size > 1) maxOf(bks.zipWithNext { a, b -> b.day - a.day }.sorted()[bks.size / 2] * 2.5, 10.0) else GAP_DAYS
        bks.forEachIndexed { i, w -> if (i == 0 || w.day - bks[i - 1].day > maxStep) line.moveTo(x(w.day), y(w.mean)) else line.lineTo(x(w.day), y(w.mean)) }
        drawPath(line, def.color.copy(alpha = 0.18f), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(line, def.color, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        bks.lastOrNull()?.let {
            drawCircle(def.color.copy(alpha = 0.25f), 6.dp.toPx(), Offset(x(it.day), y(it.mean)))
            drawCircle(def.color, 3.dp.toPx(), Offset(x(it.day), y(it.mean)))
        }
        cursor?.let { bks.getOrNull(it) }?.let { w ->
            drawLine(G.hairlineStrong, Offset(x(w.day), top), Offset(x(w.day), bottom), 1.dp.toPx())
            drawCircle(G.text1, 3.dp.toPx(), Offset(x(w.day), y(w.mean)))
        }
        // four date ticks, outer ones aligned to the plot edges
        for (k in 0..3) {
            val day = d0 + (d1 - d0) * k / 3
            val t = measurer.measure(if (longLabels) monthText(day) else dayText(day), axis)
            val px = x(day.toDouble())
            val tx = when (k) { 0 -> left; 3 -> right - t.size.width; else -> px - t.size.width / 2f }
            drawText(t, topLeft = Offset(tx, bottom + 3.dp.toPx()))
        }
    }
}
