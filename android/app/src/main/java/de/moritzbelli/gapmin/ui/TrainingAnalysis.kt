package de.moritzbelli.gapmin.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.moritzbelli.gapmin.core.Fmt
import de.moritzbelli.gapmin.core.GarminDaily
import de.moritzbelli.gapmin.core.LoadDay
import de.moritzbelli.gapmin.core.Stat
import de.moritzbelli.gapmin.core.Training
import de.moritzbelli.gapmin.core.TrainingSummary
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

// ---- explanations (sources: docs/research/training.md) -------------------------------------------

private const val FORM_INFO =
    "Fitness-Ermüdungs-Modell nach Banister (1975): Jede Einheit erzeugt eine Trainingslast (Banister-TRIMP aus Dauer und " +
        "Herzfrequenzreserve, exponentiell gewichtet). Fitness ist das exponentiell gewichtete Mittel über 42 Tage (CTL), Ermüdung über " +
        "7 Tage (ATL). Form = Fitness − Ermüdung des Vortags (TSB).\n\n" +
        "Die Einordnung der Form in % der Fitness (über +25 % erholt, −10 bis −30 % Aufbau, unter −30 % hohe Ermüdung) ist eine " +
        "Praxiskonvention (Coggan, TrainingPeaks), nicht validiert. Die ersten sechs Wochen der Historie sind Einschwingzeit.\n\n" +
        "TRIMP: Minuten × HRR × 0,64·e^(1,92·HRR) (Morton, Fitz-Clarke & Banister 1990; Koeffizienten für Männer, für Frauen 0,86·e^(1,67·HRR)). " +
        "HFmax = höchster plausibler Wert aller Aktivitäten, Ruhepuls = Median der letzten 30 Tage."

private const val LOAD_INFO =
    "Acute:Chronic Workload Ratio (ACWR): akute Last (EWMA, 7 Tage) / chronische Last (EWMA, 28 Tage), λ = 2/(N+1) nach " +
        "Williams et al. 2017. Gabbett (2016) beschreibt 0,8–1,3 als günstigen Bereich und Werte über 1,5 als sprunghafte Steigerung mit " +
        "erhöhtem Verletzungsrisiko. Die Methode ist umstritten (Impellizzeri et al. 2020: mathematische Kopplung, schwache Vorhersagekraft) " +
        "und dient hier nur als grober Hinweis auf Laststeigerungen.\n\n" +
        "Monotonie = Mittel / SD der täglichen Last der letzten 7 Tage, Strain = Wochenlast × Monotonie (Foster 1998). " +
        "Monotonie über 2 bedeutet wenig Wechsel zwischen harten und leichten Tagen und ging bei Foster mit Infekten und Leistungseinbruch einher."

private const val TID_INFO =
    "Intensitätsverteilung nach dem 3-Zonen-Modell (Seiler & Kjerland 2006; Seiler 2010): Zone 1 unter der ersten, Zone 3 über der zweiten " +
        "Laktat-/Ventilationsschwelle. Ohne Labortest werden die Schwellen über die Herzfrequenz genähert: < 82 %, 82–87 %, ≥ 87 % der HFmax.\n\n" +
        "Polarisationsindex PI = log10(Z1/Z2 · Z3 · 100) mit Zeitanteilen; über 2,00 gilt die Verteilung als polarisiert (Treff et al. 2019). " +
        "Ausdauerathleten trainieren meist etwa 80 % niedrig intensiv (Stöggl & Sperlich 2015).\n\n" +
        "Darunter: 5 Zonen in 10-%-Schritten der HFmax (50–60 … 90–100 %), wie Garmin sie standardmäßig nutzt."

private const val AEROBIC_INFO =
    "VO2max-Schätzung je Lauf: Sauerstoffbedarf der steigungskorrigierten Geschwindigkeit nach der ACSM-Laufgleichung " +
        "(VO2 = 0,2·v + 3,5, v in m/min; Steigung über Minetti et al. 2002), hochgerechnet über %HRR ≈ %VO2R (Swain & Leutholtz 1997). " +
        "Nur gleichmäßige Läufe ≥ 20 min, 50–95 % HRR, ≥ 8 km/h, ohne Intervalle und Trail. Angezeigt wird der Median der letzten bis zu 8 " +
        "Läufe aus 90 Tagen. Kardiale Drift und Hitze senken die Schätzung, sie ist ein Trend, kein Laborwert.\n\n" +
        "VDOT: Leistungs-VO2max aus der besten Zeit über ≥ 5 km der letzten 90 Tage (Daniels & Gilbert 1979).\n\n" +
        "Efficiency Factor (EF): Geschwindigkeit (m/min) je Herzschlag lockerer und langer Läufe nach dem Einlaufen. Steigt er bei gleicher " +
        "Belastung, wird die aerobe Ökonomie besser (Praxismaß nach Friel/Allen & Coggan)."

private const val RECORDS_INFO =
    "Bestzeiten: schnellster zusammenhängender Abschnitt der Distanz in einem Lauf (GPS, Bewegungszeit ohne Pausen).\n\n" +
        "Prognose nach Riegel (1981): T2 = T1 · (D2/D1)^1,06, ausgehend von der Bestzeit der letzten 90 Tage über die ähnlichste andere Distanz " +
        "(z. B. 10 km aus 5 km). " +
        "Trainingsabschnitte sind keine Wettkämpfe, die Prognose ist daher eher vorsichtig. Für den Marathon sagt Riegel Freizeitläufern " +
        "typischerweise zu schnelle Zeiten voraus (Vickers & Vertosick 2016)."

private const val VOLUME_INFO =
    "Laufumfang je Kalenderwoche (Montag bis Sonntag). Gestrichelt: Mittel der gezeigten Wochen. Antippen eines Balkens liest die Woche ab."

/** Training analysis: form, load balance, intensity distribution, aerobic capacity, records and volume. */
@Composable
fun TrainingAnalysis(t: TrainingSummary?, garmin: GarminDaily, reveal: RevealState, onGoToData: () -> Unit) {
    if (t == null) {
        Column(Modifier.fillMaxWidth().padding(vertical = G.s8), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Noch keine Aktivitäten mit Herzfrequenz.", style = T.md, color = G.text2)
            Spacer(Modifier.height(G.s4))
            PillButton("Zu Daten", primary = true, onClick = onGoToData)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(G.s3)) {
        FormCard(t, Modifier.reveal(reveal, "form", 0))
        LoadCard(t, garmin, Modifier.reveal(reveal, "load", 1))
        IntensityCard(t, Modifier.reveal(reveal, "tid", 2))
        AerobicCard(t, garmin, Modifier.reveal(reveal, "aerobic", 3))
        RecordsCard(t, garmin, Modifier.reveal(reveal, "records", 4))
        VolumeCard(t, Modifier.reveal(reveal, "volume", 5))
    }
}

@Composable
private fun FormCard(t: TrainingSummary, modifier: Modifier) {
    val d = t.today ?: return
    val week = t.days.getOrNull(t.days.size - 8)
    val pct = Training.formPct(d)
    GCard(modifier) {
        CardHead("Form", "120 Tage", info = FORM_INFO)
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Label("Fitness")
                CountUp(d.ctl, { Fmt.num(it) }, style = T.xxl, color = G.fitness)
                week?.let { Delta("${Fmt.signed(d.ctl - it.ctl)} / 7 T", G.text3) }
            }
            Column(Modifier.weight(1f)) {
                Label("Ermüdung")
                CountUp(d.atl, { Fmt.num(it) }, style = T.xxl, color = G.fatigue)
                week?.let { Delta("${Fmt.signed(d.atl - it.atl)} / 7 T", G.text3) }
            }
            Column(Modifier.weight(1f)) {
                Label("Form")
                ValueUnit(Fmt.signed(d.tsb), pct?.let { "${Fmt.signed(it)} %" } ?: "", T.xxl)
                Text(Training.formLabel(pct), style = T.stat, color = G.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(G.s4))
        PmcChart(t.days.takeLast(120))
    }
}

/** Fitness and fatigue lines over daily load bars; below, form (TSB) as bars around zero. Tap or drag to read a day. */
@Composable
private fun PmcChart(days: List<LoadDay>) {
    if (days.size < 2) return
    val measurer = rememberTextMeasurer()
    val reveal = remember(days) { Animatable(0f) }
    LaunchedEffect(days) { reveal.animateTo(1f, tween(G.T_HERO, easing = G.easeOut)) }
    var cursor by remember(days) { mutableStateOf<Int?>(null) }
    val n = days.size
    val c = cursor ?: (n - 1)
    val sel = days[c]
    Text(
        "${epochText(sel.epochDay)} · Last ${Fmt.num(sel.load)} · CTL ${Fmt.num(sel.ctl)} · ATL ${Fmt.num(sel.atl)} · TSB ${Fmt.signed(sel.tsb)}",
        style = T.stat, color = if (cursor != null) G.text1 else G.text3, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = G.s2),
    )
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(196.dp)
            .pointerInput(days) {
                fun idx(x: Float) = ((x / size.width) * (n - 1)).roundToInt().coerceIn(0, n - 1)
                detectTapGestures(onTap = { o -> val i = idx(o.x); cursor = if (cursor == i) null else i })
            }
            .pointerInput(days) {
                fun idx(x: Float) = ((x / size.width) * (n - 1)).roundToInt().coerceIn(0, n - 1)
                detectHorizontalDragGestures { ch, _ -> cursor = idx(ch.position.x) }
            },
    ) {
        val w = size.width
        val axisH = 14.dp.toPx()
        val gap = 10.dp.toPx()
        val topH = (size.height - axisH - gap) * 0.70f
        val botTop = topH + gap
        val botH = size.height - axisH - botTop
        fun x(i: Int) = i.toFloat() / (n - 1) * w
        val hi = days.maxOf { maxOf(it.ctl, it.atl) }.coerceAtLeast(1.0) * 1.1
        val loadHi = days.maxOf { it.load }.coerceAtLeast(1.0)
        fun y(v: Double) = (topH - v / hi * topH).toFloat()
        val limit = (n - 1) * reveal.value

        for (k in 1..3) drawLine(G.hairline, Offset(0f, topH * k / 4f), Offset(w, topH * k / 4f), 1.dp.toPx())
        drawLine(G.hairline, Offset(0f, topH), Offset(w, topH), 1.dp.toPx())
        // daily load as faint bars in the lower third of the top panel
        val bw = (w / n * 0.55f).coerceAtLeast(1.dp.toPx())
        for (i in 0 until n) {
            if (i > limit) break
            val l = days[i].load
            if (l <= 0) continue
            val h = (l / loadHi).toFloat() * topH * 0.38f
            drawRect(G.text3.copy(alpha = 0.35f), Offset(x(i) - bw / 2, topH - h), Size(bw, h))
        }
        fun line(get: (LoadDay) -> Double, color: Color, width: Float, fill: Boolean) {
            val p = Path()
            var last = 0f
            for (i in 0 until n) {
                if (i > limit) break
                if (i == 0) p.moveTo(x(i), y(get(days[i]))) else p.lineTo(x(i), y(get(days[i])))
                last = x(i)
            }
            if (fill) {
                val area = Path().apply { addPath(p); lineTo(last, topH); lineTo(0f, topH); close() }
                drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.18f), Color.Transparent), startY = 0f, endY = topH))
            }
            drawPath(p, color.copy(alpha = 0.2f), style = Stroke(width * 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(p, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        line({ it.atl }, G.fatigue, 1.2.dp.toPx(), false)
        line({ it.ctl }, G.fitness, 1.8.dp.toPx(), true)

        // form bars
        val tsbMax = days.maxOf { abs(it.tsb) }.coerceAtLeast(1.0)
        val zero = botTop + botH / 2
        drawLine(G.hairlineStrong, Offset(0f, zero), Offset(w, zero), 1.dp.toPx())
        for (i in 0 until n) {
            if (i > limit) break
            val v = days[i].tsb
            val h = (abs(v) / tsbMax).toFloat() * botH / 2
            drawRect(
                (if (v >= 0) G.good else G.caution).copy(alpha = 0.75f),
                Offset(x(i) - bw / 2, if (v >= 0) zero - h else zero), Size(bw, h),
            )
        }
        val fl = measurer.measure("FORM", T.axis)
        drawText(fl, topLeft = Offset(0f, botTop))

        cursor?.let { i ->
            drawLine(G.text2, Offset(x(i), 0f), Offset(x(i), botTop + botH), 1.dp.toPx())
            drawCircle(G.fitness, 3.dp.toPx(), Offset(x(i), y(days[i].ctl)))
            drawCircle(G.fatigue, 3.dp.toPx(), Offset(x(i), y(days[i].atl)))
        } ?: run {
            drawCircle(G.fitness.copy(alpha = 0.3f), 6.dp.toPx(), Offset(x(n - 1), y(days[n - 1].ctl)))
            drawCircle(G.fitness, 3.dp.toPx(), Offset(x(n - 1), y(days[n - 1].ctl)))
        }
        for (k in 0..3) {
            val i = (n - 1) * k / 3
            val tl = measurer.measure(epochText(days[i].epochDay), T.axis)
            val tx = when (k) { 0 -> 0f; 3 -> w - tl.size.width; else -> x(i) - tl.size.width / 2f }
            drawText(tl, topLeft = Offset(tx, size.height - axisH + 2.dp.toPx()))
        }
    }
    Spacer(Modifier.height(G.s3))
    ShareLegend(
        listOf(
            Triple("Fitness", "CTL 42 T", G.fitness),
            Triple("Ermüdung", "ATL 7 T", G.fatigue),
            Triple("Form", "TSB", G.good),
        ),
    )
}

@Composable
private fun LoadCard(t: TrainingSummary, garmin: GarminDaily, modifier: Modifier) {
    val d = t.today ?: return
    val acwr = d.acwr
    GCard(modifier) {
        CardHead("Belastung", "ACWR", info = LOAD_INFO)
        Row(verticalAlignment = Alignment.Bottom) {
            CountUp(acwr, { Fmt.num(it, 2) }, style = T.hero.copy(fontSize = 48.sp))
            Spacer(Modifier.width(G.s3))
            Text(
                if (acwr == null) "Verlauf im Aufbau (≥ 3 Wochen)" else Training.acwrLabel(acwr),
                style = T.sm, color = G.text2, modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Spacer(Modifier.height(G.s3))
        RangeGauge(
            acwr, 0.0, 2.0,
            listOf(Triple(0.8, 1.3, G.good), Triple(1.3, 1.5, G.caution), Triple(1.5, 2.0, G.bad)),
            listOf(0.0, 0.8, 1.3, 1.5, 2.0), { Fmt.num(it, 1) },
        )
        Spacer(Modifier.height(G.s4))
        StatTable(
            listOf(
                "Akut" to Fmt.num(d.acute, 1),
                "Chronisch" to Fmt.num(d.chronic, 1),
                "Monotonie" to (t.monotony?.let { Fmt.num(it, 2) } ?: "–"),
                "Strain" to (t.strain?.let { Fmt.num(it) } ?: "–"),
            ),
            tone = { i -> if (i == 2 && (t.monotony ?: 0.0) > 2.0) G.caution else G.text1 },
        )
        if (garmin.acuteLoad != null || garmin.trainingStatus != null) {
            Spacer(Modifier.height(G.s2))
            Hairline()
            Spacer(Modifier.height(G.s3))
            StatTable(
                listOfNotNull(
                    garmin.trainingStatus?.let { "Garmin-Status" to it.second },
                    garmin.acuteLoad?.let { "Garmin akut" to Fmt.num(it.value) },
                    garmin.chronicLoad?.let { "Garmin chron." to Fmt.num(it.value) },
                ),
                columns = 3,
            )
        }
        Spacer(Modifier.height(G.s4))
        val weeks = remember(t) { lastWeeks(t, 12) }
        Label("Wochenlast (TRIMP)")
        Spacer(Modifier.height(G.s2))
        BarChart(weeks.map { it.second }, weeks.map { epochText(it.first) }, G.fatigue, { Fmt.num(it) })
    }
}

/** The last `n` calendar weeks (Monday, value), empty weeks included. */
private fun lastWeeks(t: TrainingSummary, n: Int, value: (de.moritzbelli.gapmin.core.TrainingWeek) -> Double = { it.load }): List<Pair<Long, Double>> {
    val today = t.days.lastOrNull()?.epochDay ?: LocalDate.now().toEpochDay()
    val monday = today - Math.floorMod(today + 3, 7L)
    val byMonday = t.weeks.associateBy { it.monday }
    return (n - 1 downTo 0).map { k -> val m = monday - 7L * k; m to (byMonday[m]?.let(value) ?: 0.0) }
}

@Composable
private fun IntensityCard(t: TrainingSummary, modifier: Modifier) {
    var range by remember { mutableIntStateOf(28) }
    val tid = if (range == 28) t.tid28 else t.tid90
    GCard(modifier) {
        CardHead("Intensität", "Seiler 3 Zonen", info = TID_INFO)
        Segmented(listOf(28 to "28 Tage", 90 to "90 Tage"), range, { range = it }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(G.s4))
        if (tid.total3 <= 0) {
            Text("Keine Herzfrequenzdaten im Zeitraum.", style = T.sm, color = G.text3)
            return@GCard
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Label("Verteilung")
                Text(tid.label, style = T.lg.copy(fontSize = 22.sp))
            }
            Column(horizontalAlignment = Alignment.End) {
                Label("PI")
                ValueUnit(tid.pi?.let { Fmt.num(it, 2) } ?: "–", "a. U.", T.xl, color = if ((tid.pi ?: 0.0) > 2) G.good else G.text1)
            }
        }
        Spacer(Modifier.height(G.s3))
        val zc = listOf(G.zone2, G.zone4, G.zone5)
        ShareBar((0..2).map { tid.f3(it) to zc[it] }, height = 10.dp)
        Spacer(Modifier.height(G.s2))
        ShareLegend(
            (0..2).map { i ->
                Triple(listOf("Z1 niedrig", "Z2 Schwelle", "Z3 hoch")[i], "${Fmt.num(tid.f3(i) * 100)} % · ${Fmt.hm(tid.z3[i] / 60)} h", zc[i])
            },
        )
        Spacer(Modifier.height(G.s4))
        Hairline()
        Spacer(Modifier.height(G.s3))
        val z5c = listOf(G.zone1, G.zone2, G.zone3, G.zone4, G.zone5)
        val total5 = tid.z5.sum().coerceAtLeast(1.0)
        for (i in 4 downTo 0) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Z${i + 1}", style = T.stat, color = G.text2, modifier = Modifier.width(26.dp))
                Text("${50 + 10 * i}–${60 + 10 * i} %", style = T.axis, modifier = Modifier.width(62.dp))
                ShareBar(listOf(tid.z5[i] to z5c[i], (total5 - tid.z5[i]) to Color.Transparent), Modifier.weight(1f), height = 6.dp)
                Text(Fmt.hm(tid.z5[i] / 60), style = T.stat, color = G.text1, modifier = Modifier.width(52.dp).padding(start = G.s2), maxLines = 1)
            }
        }
    }
}

@Composable
private fun AerobicCard(t: TrainingSummary, garmin: GarminDaily, modifier: Modifier) {
    val last = t.days.lastOrNull()?.epochDay ?: return
    val from = last - 180
    val pts = t.vo2.filter { it.epochDay >= from }.map { it.epochDay to it.value }
    val smooth = remember(pts) { pts.indices.map { i -> pts[i].first to pts.subList(maxOf(0, i - 4), i + 1).map { it.second }.sorted().let { s -> s[s.size / 2] } } }
    val gVo2 = garmin.vo2max.lastOrNull()
    val efTrend = remember(t) {
        val e = t.ef.filter { it.first >= last - 90 }
        Stat.trend(e.map { it.first.toDouble() to it.second })
    }
    GCard(modifier) {
        CardHead("Aerobe Leistung", "VO2max", info = AEROBIC_INFO)
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1.2f)) {
                Label("Gapmin")
                CountUp(t.vo2Now, { Fmt.num(it, 1) }, "ml/kg/min", T.hero.copy(fontSize = 44.sp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(G.s2)) {
                StatCell("VDOT", t.vdot?.let { Fmt.num(it, 1) } ?: "–")
                if (gVo2 != null) StatCell("Garmin", Fmt.num(gVo2.value, 1))
            }
        }
        if (garmin.fitnessAge != null) {
            Text("Fitnessalter (Garmin) ${Fmt.num(garmin.fitnessAge.value)} Jahre", style = T.stat, color = G.text3, modifier = Modifier.padding(top = G.s1))
        }
        Spacer(Modifier.height(G.s4))
        TimeChart(
            listOfNotNull(
                ChartSeries("Lauf", pts, G.oxygen, line = false, dots = true),
                ChartSeries("Median 5", smooth, G.oxygen),
                gVo2?.let { ChartSeries("Garmin", garmin.vo2max.map { LocalDate.parse(it.date).toEpochDay() to it.value }.filter { it.first >= from }, G.text2, width = 1.2.dp) },
            ),
            from, last, { Fmt.num(it, 1) },
        )
        Spacer(Modifier.height(G.s4))
        Hairline()
        Spacer(Modifier.height(G.s3))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("Efficiency Factor · locker")
                ValueUnit(t.ef.lastOrNull()?.let { Fmt.num(it.second, 2) } ?: "–", "m/min/bpm", T.xl.copy(fontSize = 20.sp))
                efTrend?.let {
                    val per30 = it.slope * 30
                    val sig = abs(it.slope) > 1.96 * it.se
                    Delta("${Fmt.signed(per30 / (t.ef.map { e -> e.second }.average()) * 100, 1)} % / 30 T${if (sig) "" else " (n. s.)"}", if (sig) zTone(if (per30 > 0) 1.0 else -1.0, 1) else G.text3)
                }
            }
            Sparkline(t.ef.takeLast(30).map { it.second }, G.activity, Modifier.width(120.dp).height(40.dp))
        }
    }
}

@Composable
private fun RecordsCard(t: TrainingSummary, garmin: GarminDaily, modifier: Modifier) {
    if (t.bestsAll.isEmpty()) return
    val showGarmin = garmin.races.isNotEmpty()
    GCard(modifier) {
        CardHead("Bestzeiten & Prognose", "Riegel", info = RECORDS_INFO)
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Label("Distanz", Modifier.weight(1.3f))
            Label("Gesamt", Modifier.weight(1f))
            Label("90 T", Modifier.weight(1f))
            Label("Prognose", Modifier.weight(1f))
            if (showGarmin) Label("Garmin", Modifier.weight(1f))
        }
        for (dist in listOf(1000, 5000, 10000, 21097, 42195)) {
            val all = t.bestsAll[dist]
            val b90 = t.bests90[dist]
            val pred = t.predictions.firstOrNull { it.distance == dist }
            if (all == null && pred == null) continue
            Hairline()
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Training.distanceLabel(dist), style = T.sm, color = G.text1, modifier = Modifier.weight(1.3f), maxLines = 1)
                Text(all?.let { Fmt.duration(it.seconds) } ?: "–", style = T.stat, color = G.text1, modifier = Modifier.weight(1f))
                Text(b90?.let { Fmt.duration(it.seconds) } ?: "–", style = T.stat, color = G.text2, modifier = Modifier.weight(1f))
                Text(
                    pred?.let { Fmt.duration(it.seconds) } ?: "–",
                    style = T.stat, color = G.accent, modifier = Modifier.weight(1f),
                )
                if (showGarmin) Text(garmin.races[dist]?.let { Fmt.duration(it.value) } ?: "–", style = T.stat, color = G.text3, modifier = Modifier.weight(1f))
            }
        }
        val pace5 = t.bests90[5000]?.let { it.seconds / 5 }
        if (pace5 != null) {
            Spacer(Modifier.height(G.s2))
            Text("Bester 5-km-Abschnitt (90 T) in ${Fmt.pace(pace5)} /km", style = T.stat, color = G.text3)
        }
    }
}

@Composable
private fun VolumeCard(t: TrainingSummary, modifier: Modifier) {
    val weeks = remember(t) { lastWeeks(t, 16) { it.distanceM / 1000 } }
    val last = t.days.lastOrNull()?.epochDay ?: return
    val runs90 = t.activities.filter { it.isRun && it.epochDay > last - 90 }
    GCard(modifier) {
        CardHead("Umfang", "16 Wochen", info = VOLUME_INFO)
        BarChart(weeks.map { it.second }, weeks.map { epochText(it.first) }, G.activity, { Fmt.num(it, 1) }, unit = "km")
        Spacer(Modifier.height(G.s4))
        StatTable(
            listOf(
                "Ø km/Woche" to Fmt.num(weeks.takeLast(12).map { it.second }.average(), 1),
                "Läufe 90 T" to runs90.size.toString(),
                "Längster" to (runs90.maxOfOrNull { it.distanceM }?.let { "${Fmt.km(it, 1)} km" } ?: "–"),
                "Zeit 90 T" to "${Fmt.hm(runs90.sumOf { it.durationS } / 60)} h",
            ),
        )
    }
}
