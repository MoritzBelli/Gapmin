package de.moritzbelli.gapmin.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.moritzbelli.gapmin.core.Fmt
import de.moritzbelli.gapmin.core.HealthDay

private val dayDate: (HealthDay) -> String = { Fmt.dayShort(it.date) }
private val dayIndex: (HealthDay) -> Long = { it.epochDay }

internal fun stressColor(v: Double) = when {
    v <= 25 -> G.stressRest
    v <= 50 -> G.stressLow
    v <= 75 -> G.stressMed
    else -> G.stressHigh
}

private fun hrvStatus(s: Int?) = when (s) {
    1 -> "Schlecht"
    2 -> "Niedrig"
    3 -> "Unausgeglichen"
    4 -> "Ausgeglichen"
    else -> "–"
}

private const val HRV_INFO =
    "Punkte: Nachtwerte. Linie: 7-Tage-Ø. Band: Garmin-Normalbereich (individuell).\n\n" +
        "Garmin bewertet den 7-Tage-Mittelwert gegen einen persönlichen Normalbereich aus mehreren Wochen. " +
        "Das entspricht dem Ansatz von Plews et al. (2012/2013): Das gleitende Mittel von ln RMSSD reagiert robuster auf " +
        "Fehlanpassung an Training als Einzelnächte, Änderungen unter ~0,5 SD gelten als nicht relevant. " +
        "„Unausgeglichen“ heißt: 7-Tage-Mittel außerhalb des Bandes; „Niedrig“: deutlich darunter."


// ---- metric definitions (references: docs/research/gesundheit.md) ------------------------------

private val HEART_DEFS = listOf(
    MetricDef<HealthDay>(
        "Ruhepuls (Garmin)", { it.restingHr }, unit = "bpm", better = -1, color = G.heart,
        ref = "60–100 bpm Erwachsene (AHA); Ausdauertrainierte oft < 60",
        note = "Von der Uhr ermittelter Tages-Ruhepuls. Ein Anstieg um ≥ 5 bpm über das eigene Mittel kann auf Ermüdung, Infekt oder Stress hinweisen.",
    ),
    MetricDef<HealthDay>("Puls Ø (24 h)", { it.hrAvg }, unit = "bpm", digits = 1, better = -1, color = G.heart),
    MetricDef<HealthDay>("Puls Minimum", { it.hrMin }, unit = "bpm", better = -1, color = G.heart),
    MetricDef<HealthDay>("Puls Maximum", { it.hrMax }, unit = "bpm", color = G.heart),
    MetricDef<HealthDay>(
        "HRV nachts (RMSSD)", { it.hrvNight }, unit = "ms", log = true, better = 1, color = G.hrvColor,
        ref = "individuell; Bewertung relativ zur eigenen Baseline (Plews 2012)",
        note = "Garmin-Nachtmittel der HRV. Statistik auf ln-Skala, Baseline als geometrisches Mittel.",
    ),
    MetricDef<HealthDay>(
        "HRV 7-Tage-Ø", { it.hrvWeekly }, unit = "ms", log = true, better = 1, color = G.hrvColor,
        note = "Das 7-Tage-Mittel bildet Belastungsanpassung besser ab als Einzelnächte (Plews 2012/2013).",
    ),
)

private val STRESS_DEFS = listOf(
    MetricDef<HealthDay>(
        "Stress Ø", { it.stressAvg }, better = -1, color = G.stressMed,
        ref = "0–25 Ruhe · 26–50 niedrig · 51–75 mittel · 76–100 hoch (Garmin)",
        note = "Firstbeat-Stresswert aus HRV und Puls, nur in Ruhe gemessen. Bewegung und Aktivitäten erzeugen keine Werte.",
    ),
    MetricDef<HealthDay>("Stress Maximum", { it.stressMax }, better = -1, color = G.stressMed),
    MetricDef<HealthDay>("Anteil Ruhe", { it.stressRest?.times(100) }, unit = "%", better = 1, color = G.stressRest),
    MetricDef<HealthDay>("Anteil hoher Stress", { it.stressHigh?.times(100) }, unit = "%", digits = 1, better = -1, color = G.stressHigh),
    MetricDef<HealthDay>(
        "Body Battery max.", { it.bbMax }, better = 1, color = G.battery,
        note = "Proprietäre Energieschätzung (Firstbeat) aus HRV, Stress, Schlaf und Aktivität. Nicht extern validiert, daher nur als Trend lesen.",
    ),
    MetricDef<HealthDay>("Body Battery min.", { it.bbMin }, better = 1, color = G.battery),
    MetricDef<HealthDay>("Body Battery geladen", { it.bbCharged }, better = 1, color = G.battery, note = "Summe aller Anstiege innerhalb des Tages."),
    MetricDef<HealthDay>("Body Battery verbraucht", { it.bbDrained }, color = G.battery, note = "Summe aller Abfälle innerhalb des Tages."),
)

private val BREATH_DEFS = listOf(
    MetricDef<HealthDay>("SpO2 Ø", { it.spo2Avg }, unit = "%", digits = 1, better = 1, color = G.oxygen, ref = "95–99 % in Ruhe; < 92 % abklären", refLo = 95.0),
    MetricDef<HealthDay>("SpO2 Minimum", { it.spo2Min }, unit = "%", better = 1, color = G.oxygen, ref = "anhaltend < 90 % auffällig", refLo = 90.0),
    MetricDef<HealthDay>(
        "Atemfrequenz Ø", { it.respAvg }, unit = "/min", digits = 1, color = G.breath,
        ref = "11,8–19,2 /min nachts (Natarajan 2021)", refLo = 11.8, refHi = 19.2,
    ),
    MetricDef<HealthDay>("Atemfrequenz min.", { it.respMin }, unit = "/min", digits = 1, color = G.breath),
    MetricDef<HealthDay>("Atemfrequenz max.", { it.respMax }, unit = "/min", digits = 1, color = G.breath),
)

private fun activityDefs(week: Map<Long, Double>) = listOf(
    MetricDef<HealthDay>(
        "Schritte", { it.steps }, better = 1, color = G.activity,
        ref = "Risiko-Plateau bei 8 000–10 000/Tag (< 60 J.), 6 000–8 000 (≥ 60 J.) (Paluch 2022)", refLo = 8000.0,
    ),
    MetricDef<HealthDay>(
        "Intensitätsminuten (Tag)", { it.imWeighted }, unit = "min", better = 1, color = G.activity,
        note = "Moderat + 2 × intensiv, wie Garmin und WHO zählen.",
    ),
    MetricDef<HealthDay>(
        "Intensitätsminuten (7 Tage)", { week[it.epochDay] }, unit = "min", better = 1, color = G.activity,
        ref = "≥ 150 min/Woche moderat oder 75 min intensiv (WHO 2020)", refLo = 150.0,
        note = "Gleitende Summe der letzten 7 Kalendertage einschließlich des gewählten Tages.",
    ),
    MetricDef<HealthDay>("Aktive Kalorien", { it.activeKcal }, unit = "kcal", better = 1, color = G.activity),
    MetricDef<HealthDay>(
        "Tragezeit (Puls)", { if (it.hrMinutes > 0) it.hrMinutes / 60.0 else null }, unit = "h", digits = 1, color = G.text3,
        note = "Stunden mit Pulsmessung. Unter etwa 20 h sind die Tagesmittel weniger vergleichbar.",
    ),
)

/** Rolling 7-day sum of weighted intensity minutes, keyed by epoch day. */
private fun weekIntensity(days: List<HealthDay>): Map<Long, Double> {
    val im = days.associate { it.epochDay to it.imWeighted }
    return days.associate { x -> x.epochDay to (0L..6L).sumOf { im[x.epochDay - it] ?: 0.0 } }
}

@Composable
internal fun HealthTrends(days: List<HealthDay>, selected: Int) {
    val defs = remember(days) {
        val a = activityDefs(weekIntensity(days))
        listOf(HEART_DEFS[0], HEART_DEFS[1], HEART_DEFS[4], STRESS_DEFS[0], STRESS_DEFS[2], STRESS_DEFS[4], a[0], a[2], BREATH_DEFS[2])
    }
    TrendsCard("Langzeittrends", defs, days, selected, dayIndex, dayDate)
}

/** Long-term view: trends, HRV status and all health metrics against their own baseline. Today's values live on the start screen. */
@Composable
fun TrendsScreen(
    days: List<HealthDay>,
    listState: LazyListState,
    bottomPadding: Dp,
    onGoToData: () -> Unit,
) {
    // the newest day is the reference for baselines and z-scores; every row opens a 90-day chart
    val selected = 0
    val activity = remember(days) { activityDefs(weekIntensity(days)) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = G.s4, end = G.s4, top = G.s4, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(G.s3),
    ) {
        if (days.isEmpty()) {
            item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(vertical = G.s8), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Noch keine Gesundheitsdaten.", style = T.md, color = G.text2)
                    Spacer(Modifier.height(G.s4))
                    PillButton("Zu Daten", primary = true, onClick = onGoToData)
                }
            }
            return@LazyColumn
        }
        item(key = "trends") { HealthTrends(days, selected) }
        item(key = "hrv") { HrvCard(days, selected) }
        item(key = "heart") { MetricGroup("Herz-Kreislauf", HEART_DEFS, days, selected, dayDate, dayIndex) }
        item(key = "stress") { MetricGroup("Stress & Body Battery", STRESS_DEFS, days, selected, dayDate, dayIndex) }
        item(key = "activity") { MetricGroup("Aktivität", activity, days, selected, dayDate, dayIndex) }
        item(key = "breath") { MetricGroup("Atmung & Sauerstoff", BREATH_DEFS, days, selected, dayDate, dayIndex) }
    }
}

@Composable
internal fun Kpi(label: String, value: String, unit: String, modifier: Modifier) {
    Column(modifier) {
        Label(label)
        Spacer(Modifier.height(2.dp))
        ValueUnit(value, unit, T.xl.copy(fontSize = 20.sp))
    }
}

/** Nightly HRV against Garmin's personal "balanced" band, with the 7-day mean (Plews). */
@Composable
private fun HrvCard(days: List<HealthDay>, selected: Int) {
    val window = remember(days, selected) { days.drop(selected).take(60).reversed() }
    if (window.none { it.hrvNight != null }) return
    val d = days[selected]
    val measurer = rememberTextMeasurer()
    val axis = TextStyle(fontFamily = Inter, fontSize = 9.5.sp, color = G.text3)
    val reveal = remember(window) { Animatable(0f) }
    LaunchedEffect(window) { reveal.animateTo(1f, tween(G.T_DRAW, easing = G.easeOut)) }
    GCard {
        CardHead("HRV-Status", hrvStatus(d.hrvStatus), info = HRV_INFO)
        Row(Modifier.fillMaxWidth()) {
            Kpi("Nacht", Fmt.num(d.hrvNight), "ms", Modifier.weight(1f))
            Kpi("7-Tage-Ø", Fmt.num(d.hrvWeekly), "ms", Modifier.weight(1f))
            Kpi("Normalbereich", if (d.hrvLow != null && d.hrvHigh != null) "${Fmt.num(d.hrvLow)}–${Fmt.num(d.hrvHigh)}" else "–", "ms", Modifier.weight(1.3f))
        }
        Spacer(Modifier.height(G.s4))
        val vals = window.flatMap { listOfNotNull(it.hrvNight, it.hrvWeekly, it.hrvLow, it.hrvHigh) }
        val lo = (vals.minOrNull() ?: 0.0) * 0.92
        val hi = (vals.maxOrNull() ?: 1.0) * 1.05
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val n = window.size
            val left = 28.dp.toPx()
            val bottom = size.height - 14.dp.toPx()
            fun x(i: Int) = left + (if (n > 1) i.toFloat() / (n - 1) else 0.5f) * (size.width - left)
            fun y(v: Double) = (bottom - (v - lo) / (hi - lo) * bottom).toFloat()
            for (k in 0..2) {
                val v = lo + (hi - lo) * (0.15 + 0.35 * k)
                drawLine(G.hairline, Offset(left, y(v)), Offset(size.width, y(v)), 1.dp.toPx())
                val t = measurer.measure(Fmt.num(v), axis)
                drawText(t, topLeft = Offset(0f, y(v) - t.size.height / 2f))
            }
            val limit = (n - 1) * reveal.value
            // Garmin balanced band
            for (i in 0 until n) {
                val a = window[i]
                if (a.hrvLow == null || a.hrvHigh == null || i > limit) continue
                val x0 = x(i) - (size.width - left) / maxOf(1, n - 1) / 2
                drawRect(G.hrvColor.copy(alpha = 0.10f), Offset(x0, y(a.hrvHigh)), Size((size.width - left) / maxOf(1, n - 1), y(a.hrvLow) - y(a.hrvHigh)))
            }
            // weekly mean
            val path = Path()
            var started = false
            for (i in 0 until n) {
                val v = window[i].hrvWeekly ?: continue
                if (i > limit) break
                if (!started) { path.moveTo(x(i), y(v)); started = true } else path.lineTo(x(i), y(v))
            }
            drawPath(path, G.hrvColor.copy(alpha = 0.2f), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, G.hrvColor, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            // nightly values
            for (i in 0 until n) {
                val v = window[i].hrvNight ?: continue
                if (i > limit) break
                drawCircle(if (i == n - 1) G.text1 else G.text3, if (i == n - 1) 3.dp.toPx() else 1.8.dp.toPx(), Offset(x(i), y(v)))
            }
            val a = measurer.measure(Fmt.dayShort(window.first().date), axis)
            drawText(a, topLeft = Offset(left, bottom + 2.dp.toPx()))
            val b = measurer.measure(Fmt.dayShort(window.last().date), axis)
            drawText(b, topLeft = Offset(size.width - b.size.width, bottom + 2.dp.toPx()))
        }
    }
}
