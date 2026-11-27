package de.moritzbelli.gapmin.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.moritzbelli.gapmin.core.DaySeries
import de.moritzbelli.gapmin.core.Fmt
import de.moritzbelli.gapmin.core.HealthDay
import de.moritzbelli.gapmin.core.RunSummary
import de.moritzbelli.gapmin.core.Sample
import de.moritzbelli.gapmin.core.SleepNight
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

private const val HEALTH_METHOD =
    "Tageswerte werden je Kalendertag (Ortszeit) aus den Rohdaten der Uhr gebildet. Body Battery: aktueller Wert, daneben das Tagesmaximum.\n\n" +
        "Tagesverlauf: Body Battery (Linie), Stress (Balken nach Garmin-Kategorie), Puls (Linie). Tippen oder Ziehen liest Werte ab.\n\n" +
        "Stress und Body Battery sind proprietäre Firstbeat-Algorithmen ohne unabhängige Validierung; sie eignen sich für " +
        "Verläufe innerhalb einer Person, nicht für Vergleiche zwischen Personen. Puls, Schritte und SpO2 sind direkte Messungen, " +
        "SpO2 am Handgelenk ist bei Bewegung ungenau.\n\n" +
        "Quellen und Schwellen: docs/research/gesundheit.md. Kein Medizinprodukt."

private fun localDate(ts: Long): String = Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()).toLocalDate().toString()

/**
 * Start screen: the day at a glance. Last night's sleep, today's key figures and 24-hour curve, today's runs.
 * The day strip walks back through earlier days; long-term views live in "Trends".
 */
@Composable
fun TodayScreen(
    days: List<HealthDay>,
    nights: List<SleepNight>,
    runs: List<RunSummary>,
    selected: Int,
    onSelect: (Int) -> Unit,
    loadDay: (String, (DaySeries) -> Unit) -> Unit,
    listState: LazyListState,
    bottomPadding: Dp,
    onOpenSleep: () -> Unit,
    onOpenRun: (RunSummary) -> Unit,
    onGoToData: () -> Unit,
) {
    val d = days.getOrNull(selected)
    // the night that ended on the shown day; for the newest day fall back to the latest recorded night
    val night = remember(nights, d, selected) {
        nights.firstOrNull { it.night == d?.date } ?: if (selected == 0) nights.firstOrNull() else null
    }
    val date = d?.date ?: night?.night
    val dayRuns = remember(runs, date) { if (date == null) emptyList() else runs.filter { localDate(it.startTs) == date } }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = G.s4, end = G.s4, top = G.s4, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(G.s3),
    ) {
        if (date == null) {
            item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(vertical = G.s8), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Noch keine Tagesdaten.", style = T.md, color = G.text2)
                    Spacer(Modifier.height(G.s4))
                    PillButton("Zu Daten", primary = true, onClick = onGoToData)
                }
            }
            return@LazyColumn
        }
        if (days.isNotEmpty()) {
            item(key = "strip") {
                DayStrip(
                    days, selected, onSelect,
                    key = { it.date },
                    level = { x -> x.bbMax?.let { (it / 100).toFloat() } },
                    color = G.battery,
                    dayOfMonth = { LocalDate.parse(it.date).dayOfMonth },
                    weekday = { Fmt.weekdayLetter(it.date) },
                )
            }
        }
        if (night != null) {
            item(key = "sleep") { SleepToday(night, sameDay = night.night == date, onClick = onOpenSleep) }
        }
        if (d != null) {
            item(key = "day") { TodayCard(d, d.date == LocalDate.now().toString(), loadDay) }
        }
        items(dayRuns, key = { "run-${it.id}" }) { run -> TodayRun(run, onClick = { onOpenRun(run) }) }
    }
}

@Composable
private fun SleepToday(n: SleepNight, sameDay: Boolean, onClick: () -> Unit) {
    val color = G.scoreColor(n.score.toDouble())
    GCard(Modifier.pressable(onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(n.score, color, Modifier.size(96.dp))
            Spacer(Modifier.width(G.s4))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label(if (sameDay) "Schlaf" else "Letzte Nacht · ${Fmt.dayShort(n.night)}")
                Text(n.label, style = T.lg.copy(fontSize = 22.sp), color = color)
                Text("${Fmt.time(n.onsetTs)} – ${Fmt.time(n.wakeTs)}", style = T.sm, color = G.text2)
                ValueUnit(Fmt.hm(n.tstMin), "h", T.xl.copy(fontSize = 20.sp))
            }
            Icon(GIcons.Next, null, tint = G.text3, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.height(G.s4))
        val parts = listOf(
            Triple("Tief", n.deepMin, G.stageDeep),
            Triple("Leicht", n.lightMin, G.stageLight),
            Triple("REM", n.remMin, G.stageRem),
            Triple("Wach", n.awakeMin, G.stageAwake),
        )
        Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(G.surface3)) {
            for ((_, v, c) in parts) if (v > 0) Box(Modifier.weight(v.toFloat()).fillMaxHeight().background(c))
        }
        Spacer(Modifier.height(G.s2))
        Row(Modifier.fillMaxWidth()) {
            for ((l, v, c) in parts) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(c, 5.dp)
                        Spacer(Modifier.width(4.dp))
                        Text(l, style = T.unit)
                    }
                    Text(Fmt.hm(v), style = T.sm, color = G.text1)
                }
            }
        }
    }
}

@Composable
private fun TodayCard(d: HealthDay, today: Boolean, loadDay: (String, (DaySeries) -> Unit) -> Unit) {
    var series by remember(d.date) { mutableStateOf<DaySeries?>(null) }
    LaunchedEffect(d.date) { loadDay(d.date) { series = it } }
    var infoOpen by remember { mutableStateOf(false) }
    // today: current Body Battery; earlier days: the day's maximum
    val bb = (if (today) series?.bb?.lastOrNull()?.value else null) ?: d.bbMax
    GCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label(if (today) "Heute" else Fmt.dayLong(d.date), Modifier.weight(1f))
            InfoButton(infoOpen, { infoOpen = !infoOpen })
        }
        InfoPanel(infoOpen, HEALTH_METHOD)
        Spacer(Modifier.height(G.s3))
        Row(Modifier.fillMaxWidth()) {
            Kpi("Ruhepuls", Fmt.num(d.restingHr), "bpm", Modifier.weight(1f))
            Kpi("Stress Ø", Fmt.num(d.stressAvg), "", Modifier.weight(1f))
            Kpi("Body Batt.", Fmt.num(bb), d.bbMax?.let { "max ${Fmt.num(it)}" } ?: "", Modifier.weight(1.2f))
            Kpi("Schritte", d.steps?.let { Fmt.num(it / 1000, 1) } ?: "–", "k", Modifier.weight(1f))
        }
        Spacer(Modifier.height(G.s4))
        Row(Modifier.fillMaxWidth()) {
            Kpi("HRV", Fmt.num(d.hrvNight), "ms", Modifier.weight(1f))
            Kpi("Intensiv", Fmt.num(d.imWeighted), "min", Modifier.weight(1f))
            Kpi("Kalorien", Fmt.num(d.activeKcal), "", Modifier.weight(1.2f))
            Kpi("SpO2", Fmt.num(d.spo2Avg), "%", Modifier.weight(1f))
        }
        Spacer(Modifier.height(G.s4))
        DayChart(series)
        Spacer(Modifier.height(G.s4))
        StressDistribution(d)
    }
}

@Composable
private fun TodayRun(run: RunSummary, onClick: () -> Unit) {
    val color = G.runColor(run.category)
    GCard(Modifier.pressable(onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Lauf · ${Fmt.time(run.startTs)}", Modifier.weight(1f))
            TypeTag(run.category.label, color)
        }
        Spacer(Modifier.height(G.s3))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                ValueUnit(Fmt.km(run.distanceM), "km", T.xl)
                Spacer(Modifier.height(G.s3))
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1.25f)) { Label("Zeit"); Text(Fmt.duration(run.durationS), style = T.sm, color = G.text1, maxLines = 1) }
                    Column(Modifier.weight(1f)) { Label("Pace"); Text(Fmt.pace(run.paceSPerKm), style = T.sm, color = G.text1, maxLines = 1) }
                    Column(Modifier.weight(0.7f)) { Label("HF"); Text(run.avgHr?.toString() ?: "–", style = T.sm, color = G.text1, maxLines = 1) }
                }
            }
            Spacer(Modifier.width(G.s4))
            RouteShape(run.preview, color, Modifier.size(84.dp), strokeWidth = 1.5.dp, paddingFraction = 0.1f)
        }
    }
}

private fun nearest(xs: List<Sample>, t: Long, maxGap: Long = 15 * 60): Sample? {
    var best: Sample? = null
    for (s in xs) if (abs(s.ts - t) <= maxGap && (best == null || abs(s.ts - t) < abs(best.ts - t))) best = s
    return best
}

/** 24-hour view: Body Battery (line), stress (bars by Garmin category), heart rate (line). Tap or drag to read values. */
@Composable
private fun DayChart(s: DaySeries?) {
    val measurer = rememberTextMeasurer()
    val reveal = remember(s) { Animatable(0f) }
    LaunchedEffect(s) { if (s != null) reveal.animateTo(1f, tween(G.T_DRAW, easing = G.easeOut)) }
    var cursor by remember(s) { mutableStateOf<Float?>(null) }
    val axis = TextStyle(fontFamily = Inter, fontSize = 9.5.sp, color = G.text3)

    val head = if (s != null && cursor != null) {
        val t = s.startTs + ((s.endTs - s.startTs) * cursor!!).toLong()
        val bb = nearest(s.bb, t)?.value
        val st = nearest(s.stress, t)?.value
        val hr = nearest(s.hr, t, 5 * 60)?.value
        "${Fmt.time(t)}  ·  BB ${Fmt.num(bb)}  ·  Stress ${Fmt.num(st)}  ·  Puls ${Fmt.num(hr)}"
    } else {
        ""
    }
    Text(head, style = T.unit, color = G.text1, maxLines = 1, modifier = Modifier.padding(bottom = G.s2).height(16.dp))
    if (s == null) {
        Box(Modifier.fillMaxWidth().height(210.dp))
        return
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(210.dp)
            .pointerInput(s) {
                detectTapGestures(onTap = { o -> cursor = if (cursor != null) null else (o.x / size.width).coerceIn(0f, 1f) })
            }
            .pointerInput(s) {
                detectHorizontalDragGestures { ch, _ -> cursor = (ch.position.x / size.width).coerceIn(0f, 1f) }
            },
    ) {
        val w = size.width
        val span = (s.endTs - s.startTs).toFloat()
        fun x(ts: Long) = (ts - s.startTs) / span * w
        val gap = 10.dp.toPx()
        val axisH = 14.dp.toPx()
        val h = size.height - axisH - 2 * gap
        val bbTop = 0f
        val bbH = h * 0.38f
        val stTop = bbH + gap
        val stH = h * 0.34f
        val hrTop = stTop + stH + gap
        val hrH = h * 0.28f
        val limit = w * reveal.value

        // panel labels and baselines
        for ((top, hh, label) in listOf(Triple(bbTop, bbH, "BB"), Triple(stTop, stH, "Stress"), Triple(hrTop, hrH, "Puls"))) {
            drawLine(G.hairline, Offset(0f, top + hh), Offset(w, top + hh), 1.dp.toPx())
            val t = measurer.measure(label, axis)
            drawText(t, topLeft = Offset(0f, top))
        }

        // Body Battery
        if (s.bb.size >= 2) {
            val line = Path()
            val fill = Path()
            s.bb.forEachIndexed { i, p ->
                val px = x(p.ts).coerceAtMost(limit)
                val py = bbTop + bbH - (p.value / 100).toFloat() * bbH
                if (i == 0) { line.moveTo(px, py); fill.moveTo(px, bbTop + bbH); fill.lineTo(px, py) } else { line.lineTo(px, py); fill.lineTo(px, py) }
            }
            fill.lineTo(x(s.bb.last().ts).coerceAtMost(limit), bbTop + bbH)
            fill.close()
            drawPath(fill, G.battery.copy(alpha = 0.10f))
            drawPath(line, G.battery.copy(alpha = 0.2f), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(line, G.battery, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        // Stress bars
        val bw = (w / (24 * 20f)).coerceAtLeast(1.dp.toPx())
        for (p in s.stress) {
            val px = x(p.ts)
            if (px > limit) break
            val bh = (p.value / 100).toFloat() * stH
            drawRect(stressColor(p.value), topLeft = Offset(px, stTop + stH - bh), size = Size(bw, bh))
        }

        // Heart rate
        if (s.hr.size >= 2) {
            val lo = s.hr.minOf { it.value } - 3
            val hi = s.hr.maxOf { it.value } + 3
            val path = Path()
            var prev: Long? = null
            for (p in s.hr) {
                val px = x(p.ts)
                if (px > limit) break
                val py = (hrTop + hrH - (p.value - lo) / (hi - lo) * hrH).toFloat()
                if (prev == null || p.ts - prev > 10 * 60) path.moveTo(px, py) else path.lineTo(px, py)
                prev = p.ts
            }
            drawPath(path, G.heart, style = Stroke(1.dp.toPx(), join = StrokeJoin.Round))
            val tHi = measurer.measure(Fmt.num(hi - 3), axis)
            drawText(tHi, topLeft = Offset(w - tHi.size.width, hrTop))
            val tLo = measurer.measure(Fmt.num(lo + 3), axis)
            drawText(tLo, topLeft = Offset(w - tLo.size.width, hrTop + hrH - tLo.size.height))
        }

        // hour axis
        for (hr in 0..24 step 6) {
            val px = w * hr / 24f
            drawLine(G.hairline, Offset(px, 0f), Offset(px, h + 2 * gap), 1.dp.toPx())
            val t = measurer.measure(hr.toString().padStart(2, '0'), axis)
            drawText(t, topLeft = Offset((px - t.size.width / 2f).coerceIn(0f, w - t.size.width), size.height - axisH + 2.dp.toPx()))
        }

        cursor?.let { cx ->
            drawLine(G.text2, Offset(cx * w, 0f), Offset(cx * w, h + 2 * gap), 1.dp.toPx())
        }
    }
}

@Composable
private fun StressDistribution(d: HealthDay) {
    val parts = listOf(
        Triple("Ruhe", d.stressRest, G.stressRest),
        Triple("Niedrig", d.stressLow, G.stressLow),
        Triple("Mittel", d.stressMed, G.stressMed),
        Triple("Hoch", d.stressHigh, G.stressHigh),
    )
    if (parts.all { it.second == null }) return
    Label("Stressverteilung · n = ${d.stressN}")
    Spacer(Modifier.height(G.s2))
    Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(G.surface3)) {
        for ((_, v, c) in parts) {
            val f = (v ?: 0.0).toFloat()
            if (f > 0f) Box(Modifier.weight(f).fillMaxHeight().background(c))
        }
    }
    Spacer(Modifier.height(G.s2))
    Row(Modifier.fillMaxWidth()) {
        for ((l, v, c) in parts) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(c, 5.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(l, style = T.unit)
                }
                Text(v?.let { "${Fmt.num(it * 100)} %" } ?: "–", style = T.sm, color = G.text1)
            }
        }
    }
}
