package de.moritzbelli.gapmin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import de.moritzbelli.gapmin.core.Fmt
import de.moritzbelli.gapmin.core.HrAnchors
import de.moritzbelli.gapmin.core.RunCategory
import de.moritzbelli.gapmin.core.Training
import de.moritzbelli.gapmin.core.TrainingActivity
import de.moritzbelli.gapmin.core.RunSummary
import de.moritzbelli.gapmin.core.TrackSample
import kotlin.math.max
import kotlin.math.roundToInt

/** Full-screen run detail; swipe sideways for newer/older runs, back gesture closes. */
@Composable
fun RunDetailPager(
    runs: List<RunSummary>,
    initialId: String,
    shared: SharedRoute?,
    hr: HrAnchors?,
    loadTrack: (String, (List<TrackSample>) -> Unit) -> Unit,
    onPageRun: (String) -> Unit,
    onClose: () -> Unit,
) {
    val start = runs.indexOfFirst { it.id == initialId }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start) { runs.size }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { runs.getOrNull(it)?.let { r -> onPageRun(r.id) } }
    }
    Box(Modifier.fillMaxSize().background(G.bg)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { runs[it].id }) { page ->
            RunDetailPage(runs[page], shared, hr, loadTrack, page, runs.size)
        }
        // close button floats above the pages
        Box(
            Modifier
                .statusBarsPadding()
                .padding(G.s4)
                .align(Alignment.TopEnd)
                .size(40.dp)
                .clip(CircleShape)
                .background(G.surfaceSolid.copy(alpha = 0.85f))
                .pressable(onClose, haptic = true),
            contentAlignment = Alignment.Center,
        ) {
            Icon(GIcons.Close, "Schließen", tint = G.text1, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun RunDetailPage(
    run: RunSummary,
    shared: SharedRoute?,
    hr: HrAnchors?,
    loadTrack: (String, (List<TrackSample>) -> Unit) -> Unit,
    page: Int,
    count: Int,
) {
    val color = G.runColor(run.category)
    var track by remember(run.id) { mutableStateOf<List<TrackSample>?>(null) }
    LaunchedEffect(run.id) { loadTrack(run.id) { track = it } }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = G.s4),
    ) {
        Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
            TypeTag(run.category.label, color)
            Spacer(Modifier.size(G.s3))
            Label("${page + 1} / $count")
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            RouteShape(
                run.preview, color,
                Modifier.widthIn(max = 460.dp).fillMaxWidth().aspectRatio(routeAspect(run.preview)).sharedRoute(run.id, shared),
                strokeWidth = 2.2.dp,
                drawIn = false,
                paddingFraction = 0.06f,
            )
        }
        Spacer(Modifier.height(G.s4))
        Text(run.name, style = T.lg)
        Spacer(Modifier.height(G.s1))
        Text("${Fmt.date(run.startTs)} · ${Fmt.time(run.startTs)}", style = T.sm)
        Spacer(Modifier.height(G.s4))
        ValueUnit(Fmt.km(run.distanceM), "km", T.display.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Light, fontSize = T.display.fontSize * 1.3f))
        Spacer(Modifier.height(G.s5))

        val stats = listOf(
            Triple("Zeit", Fmt.duration(run.durationS), ""),
            Triple("Ø Pace", Fmt.pace(run.paceSPerKm), "/km"),
            Triple("Ø Herzfrequenz", run.avgHr?.toString() ?: "–", "bpm"),
            Triple("Max. Herzfrequenz", run.maxHr?.toString() ?: "–", "bpm"),
            Triple("Anstieg", run.ascentM?.toString() ?: "–", "m"),
            Triple("Aerober Effekt", run.teAerobic?.let { Fmt.num(it, 1) } ?: "–", ""),
            Triple("Kalorien", run.calories?.toString() ?: "–", "kcal"),
            Triple("Kadenz", run.cadence?.let { (if (it < 120) it * 2 else it).toString() } ?: "–", "spm"),
        )
        StatGrid(stats)
        Spacer(Modifier.height(G.s5))
        RunAnalysisCard(run, hr)
        Spacer(Modifier.height(G.s3))

        val t = track
        AnimatedVisibility(t != null && t.size > 10, enter = fadeIn(tween(G.T_SLOW))) {
            val series = remember(t) { t?.let { TrackSeries.from(it) } }
            if (series != null) Column(verticalArrangement = Arrangement.spacedBy(G.s3)) {
                if (series.pace.isNotEmpty()) LineChartCard("Pace", "min/km", series.dist, series.pace, color, invert = true) { Fmt.pace(it) }
                if (series.hr.isNotEmpty()) LineChartCard("Herzfrequenz", "bpm", series.dist, series.hr, G.zone5) { it.roundToInt().toString() }
                if (series.ele.isNotEmpty()) LineChartCard("Höhe", "m", series.dist, series.ele, G.text1.copy(alpha = 0.85f), fill = true) { it.roundToInt().toString() }
                Text("Lange drücken und ziehen zeigt Werte an. Seitlich wischen für neuere und ältere Läufe.", style = T.stat, color = G.text3)
            }
        }
        Spacer(Modifier.height(G.s7))
    }
}

@Composable
fun StatGrid(items: List<Triple<String, String, String>>, columns: Int = 2) {
    Column(verticalArrangement = Arrangement.spacedBy(G.s4)) {
        for (row in items.chunked(columns)) {
            Row(Modifier.fillMaxWidth()) {
                for ((k, v, u) in row) {
                    Column(Modifier.weight(1f).padding(end = G.s2)) {
                        Label(k)
                        Spacer(Modifier.height(2.dp))
                        ValueUnit(v, u, T.xl)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Distance-indexed, smoothed series for the charts (≈ 240 points). */
class TrackSeries(val dist: List<Double>, val pace: List<Double?>, val hr: List<Double?>, val ele: List<Double?>) {
    companion object {
        fun from(t: List<TrackSample>, n: Int = 240): TrackSeries? {
            val pts = t.filter { it.dist != null }
            if (pts.size < 10) return null
            val total = pts.last().dist!!
            if (total < 50) return null
            // 30-second rolling speed from distance/time
            val speed = DoubleArray(pts.size)
            var j = 0
            for (i in pts.indices) {
                while (pts[i].t - pts[j].t > 30) j++
                val dt = (pts[i].t - pts[j].t).toDouble()
                speed[i] = if (dt > 0) (pts[i].dist!! - pts[j].dist!!) / dt else pts[i].speed ?: 0.0
            }
            val dist = ArrayList<Double>(n); val pace = ArrayList<Double?>(n); val hr = ArrayList<Double?>(n); val ele = ArrayList<Double?>(n)
            var k = 0
            for (s in 0 until n) {
                val d = total * s / (n - 1)
                while (k < pts.size - 1 && pts[k].dist!! < d) k++
                // average over a small window around k
                val a = max(0, k - 3); val b = minOf(pts.size - 1, k + 3)
                val sp = (a..b).map { speed[it] }.filter { it > 0.5 }.average().takeIf { !it.isNaN() }
                dist.add(d)
                pace.add(sp?.let { (1000 / it).coerceAtMost(15 * 60.0) })
                hr.add((a..b).mapNotNull { pts[it].hr?.takeIf { h -> h > 0 } }.average().takeIf { !it.isNaN() })
                ele.add((a..b).mapNotNull { pts[it].ele }.average().takeIf { !it.isNaN() })
            }
            return TrackSeries(
                dist,
                if (pace.count { it != null } > n / 3) pace else emptyList(),
                if (hr.count { it != null } > n / 3) hr else emptyList(),
                if (ele.count { it != null } > n / 3) ele else emptyList(),
            )
        }
    }
}

/**
 * Line chart per DESIGN.md: 1.5 dp line with halo, no axis frame, three hairlines,
 * long-press + drag shows a crosshair with the value at that distance.
 */
@Composable
fun LineChartCard(
    title: String,
    unit: String,
    x: List<Double>,
    y: List<Double?>,
    color: Color,
    invert: Boolean = false,
    fill: Boolean = false,
    format: (Double) -> String,
) {
    val vals = y.filterNotNull()
    // robust range (ignore outliers at both ends)
    val sorted = vals.sorted()
    val lo = sorted[(sorted.size * 0.02).toInt()]
    val hi = sorted[((sorted.size - 1) * 0.98).toInt()]
    val span = (hi - lo).takeIf { it > 1e-6 } ?: 1.0
    val avg = vals.average()
    var scrub by remember { mutableStateOf<Int?>(null) }
    val haptic = LocalHapticFeedback.current

    GCard(padding = G.s4) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(title, style = T.mdMedium, modifier = Modifier.weight(1f))
            val i = scrub
            if (i != null && y[i] != null) {
                Label("${Fmt.km(x[i], 1)} km  ")
                ValueUnit(format(y[i]!!), unit, T.mdMedium)
            } else {
                Label("Ø  ")
                ValueUnit(format(avg), unit, T.mdMedium.copy(color = G.text2))
            }
        }
        Spacer(Modifier.height(G.s3))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .pointerInput(x.size) {
                    fun idx(px: Float) = ((px / size.width) * (x.size - 1)).roundToInt().coerceIn(0, x.size - 1)
                    detectDragGesturesAfterLongPress(
                        onDragStart = { scrub = idx(it.x); haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                        onDragEnd = { scrub = null },
                        onDragCancel = { scrub = null },
                        onDrag = { change, _ -> change.consume(); scrub = idx(change.position.x) },
                    )
                },
        ) {
            val w = size.width
            val h = size.height
            fun py(v: Double): Float {
                val f = ((v - lo) / span).coerceIn(-0.05, 1.05)
                return (if (invert) f else 1 - f).toFloat() * h * 0.86f + h * 0.07f
            }
            for (k in 0..2) {
                val yy = h * (0.07f + 0.43f * k)
                drawLine(G.hairline, Offset(0f, yy), Offset(w, yy), 1.dp.toPx())
            }
            val path = Path()
            var started = false
            var firstX = 0f
            var lastX = 0f
            for (i in y.indices) {
                val v = y[i] ?: continue
                val px = (i.toFloat() / (y.size - 1)) * w
                if (!started) { path.moveTo(px, py(v)); started = true; firstX = px } else path.lineTo(px, py(v))
                lastX = px
            }
            if (fill) {
                val area = Path().apply { addPath(path); lineTo(lastX, h); lineTo(firstX, h); close() }
                drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.12f), Color.Transparent)))
            }
            val sw = 1.5.dp.toPx()
            drawPath(path, color.copy(alpha = 0.18f), style = Stroke(sw * 4, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, color, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
            scrub?.let { i ->
                val px = (i.toFloat() / (y.size - 1)) * w
                drawLine(G.hairlineStrong, Offset(px, 0f), Offset(px, h), 1.dp.toPx())
                y[i]?.let { drawCircle(color, 4.dp.toPx(), Offset(px, py(it))) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = G.s1)) {
            Label("0 km", Modifier.weight(1f))
            Label("${Fmt.km(x.last(), 1)} km")
        }
    }
}

/** Width/height of the route's bounding box, clamped so the detail visual stays between square and 16:10. */
private fun routeAspect(points: List<de.moritzbelli.gapmin.core.LatLon>): Float {
    if (points.size < 2) return 1f
    val k = kotlin.math.cos(points.sumOf { it.lat } / points.size * Math.PI / 180)
    val w = (points.maxOf { it.lon } - points.minOf { it.lon }) * k
    val h = points.maxOf { it.lat } - points.minOf { it.lat }
    if (h <= 0) return 1.6f
    return (w / h).toFloat().coerceIn(1f, 1.6f)
}

private const val RUN_INFO =
    "TRIMP: Trainingsimpuls nach Banister aus der Zeit in jeder Herzfrequenz (Minuten × HRR × 0,64·e^(1,92·HRR); Morton et al. 1990).\n\n" +
        "EF (Efficiency Factor): Geschwindigkeit in m/min je Herzschlag nach dem Einlaufen. Entkopplung (Pa:HR): Abfall des EF von der ersten " +
        "zur zweiten Hälfte. Unter 5 % gilt in der Trainingspraxis als gute aerobe Ausdauer für diese Dauer (Friel); Ursache des Anstiegs ist die " +
        "kardiovaskuläre Drift (Coyle & González-Alonso 2001).\n\n" +
        "GAP: steigungskorrigierte Pace, jede Strecke mit den Energiekosten ihrer Steigung gegenüber der Ebene gewichtet (Minetti et al. 2002).\n\n" +
        "VO2-Schätzung: ACSM-Laufgleichung auf die GAP-Geschwindigkeit, hochgerechnet über %HRR ≈ %VO2R (Swain & Leutholtz 1997); nur gleichmäßige Läufe.\n\n" +
        "Zonen: Zeitanteile in 10-%-Schritten der HFmax. Bestzeiten: schnellster Abschnitt der Distanz in diesem Lauf (Bewegungszeit)."

/** Run-level analysis from the track: load, efficiency, drift, grade-adjusted pace, zones, splits and best efforts. */
@Composable
internal fun RunAnalysisCard(run: RunSummary, hr: HrAnchors?) {
    val m = run.metrics
    val act = remember(run) {
        TrainingActivity(
            id = run.id, sport = if (run.category == RunCategory.TRAIL) "trail_running" else "running", category = run.category,
            startTs = run.startTs, epochDay = run.startTs / 86400, durationS = run.durationS, distanceM = run.distanceM,
            avgHr = run.avgHr?.toDouble(), maxHr = run.maxHr?.toDouble(), ascentM = run.ascentM?.toDouble(), metrics = m,
        )
    }
    GCard(padding = G.s4) {
        CardHead("Analyse", if (m == null) "ohne Track" else "aus dem Track", info = RUN_INFO)
        val cells = buildList {
            add(Triple("TRIMP", hr?.let { Fmt.num(Training.trimp(act, it)) } ?: "–", ""))
            add(Triple("EF", m?.ef?.let { Fmt.num(it, 2) } ?: "–", ""))
            add(Triple("Entkopplung", m?.decoupling?.let { Fmt.num(it, 1) } ?: "–", if (m?.decoupling != null) "%" else ""))
            add(Triple("GAP", m?.gapSPerKm?.let { Fmt.pace(it) } ?: "–", if (m?.gapSPerKm != null) "/km" else ""))
            add(Triple("VO2-Schätzung", hr?.let { Training.vo2(act, it) }?.let { Fmt.num(it, 1) } ?: "–", ""))
            add(Triple("Bewegt", m?.let { Fmt.duration(it.movingS) } ?: "–", ""))
            if (run.teAnaerobic != null) add(Triple("Anaerober TE", Fmt.num(run.teAnaerobic, 1), ""))
            if (run.recoveryH != null) add(Triple("Erholung", Fmt.num(run.recoveryH), "h"))
        }
        StatRows(cells, columns = 3)
        if (m != null && hr != null && m.hrHist.isNotEmpty()) {
            val z = Training.zones(act, hr).z5
            val zc = listOf(G.zone1, G.zone2, G.zone3, G.zone4, G.zone5)
            Spacer(Modifier.height(G.s4))
            Label("Zeit in Zonen (% HFmax ${Fmt.num(hr.max)})")
            Spacer(Modifier.height(G.s2))
            ShareBar(z.indices.map { z[it] to zc[it] }, height = 8.dp)
            Spacer(Modifier.height(G.s2))
            ShareLegend(z.indices.map { Triple("Z${it + 1}", if (z[it] < 3600) "${Fmt.num(z[it] / 60)} min" else "${Fmt.hm(z[it] / 60)} h", zc[it]) })
        }
        if (m != null && m.splits.size >= 2) {
            Spacer(Modifier.height(G.s4))
            Label("Splits je km")
            Spacer(Modifier.height(G.s2))
            // bar height = speed, so faster kilometres stand taller; the readout shows the pace
            BarChart(
                m.splits.map { 3600.0 / it },
                m.splits.indices.map { "km ${it + 1}" },
                G.runColor(run.category),
                { Fmt.pace(3600 / it) }, height = 90.dp, unit = "/km",
            )
        }
        if (m != null && m.bests.isNotEmpty()) {
            Spacer(Modifier.height(G.s4))
            StatTable(m.bests.toSortedMap().map { (d, s) -> Training.distanceLabel(d) to "${Fmt.duration(s)} · ${Fmt.pace(s / (d / 1000.0))}" }, columns = 2)
        }
    }
}
