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
import de.moritzbelli.gapmin.core.HrvGuide
import de.moritzbelli.gapmin.core.HrvGuideDay
import de.moritzbelli.gapmin.core.Insights
import androidx.compose.foundation.layout.Box
import kotlin.math.exp
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
    insights: Insights,
    listState: LazyListState,
    bottomPadding: Dp,
    onGoToData: () -> Unit,
) {
    // the newest day is the reference for baselines and z-scores; every row opens a 90-day chart
    val selected = 0
    val activity = remember(days) { activityDefs(weekIntensity(days)) }
    val reveal = rememberReveal()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = G.s4, end = G.s4, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(G.s3),
    ) {
        item(key = "head") {
            ScreenHeader("Trends", days.firstOrNull()?.let { "Stand ${Fmt.dayLong(it.date)} · ${days.size} Tage" } ?: "Noch keine Daten")
        }
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
        item(key = "hrv") { Box(Modifier.reveal(reveal, "hrv", 0)) { HrvGuideCard(days, insights.hrv) } }
        item(key = "trends") { Box(Modifier.reveal(reveal, "trends", 1)) { HealthTrends(days, selected) } }
        item(key = "metrics") {
            BaselineOverview(
                "Kennzahlen", listOf(
                    MetricSection("Herz-Kreislauf", HEART_DEFS),
                    MetricSection("Stress & Body Battery", STRESS_DEFS),
                    MetricSection("Aktivität", activity),
                    MetricSection("Atmung & Sauerstoff", BREATH_DEFS),
                ),
                days, selected, dayDate, dayIndex, trailing = "vs. 28 Tage",
            )
        }
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

private const val HRV_GUIDE_INFO =
    "HRV-gesteuertes Monitoring: Das 7-Tage-Mittel von ln RMSSD (Linie) wird mit dem kleinsten relevanten Unterschied verglichen, " +
        "dem Bereich Baseline ± 0,5 SD (gestrichelt; Baseline = 60 Tage vor dem 7-Tage-Fenster, mind. 14 Nächte). Punkte: Einzelnächte.\n\n" +
        "Unter dem Bereich spricht für unvollständige Erholung (Belastung, Infekt, Schlafmangel, Alkohol); in Studien wurde dann locker statt " +
        "intensiv trainiert, mit gleichen oder besseren Leistungszuwächsen (Kiviniemi 2007; Vesterinen 2016; Javaloyes 2019). " +
        "Das 7-Tage-Mittel bildet die Anpassung besser ab als Einzelnächte (Plews et al. 2012/2013; AIS-Leitfaden).\n\n" +
        "CV: Variationskoeffizient von ln RMSSD über 7 Tage. Ein Abfall des Mittels bei steigender oder auffällig sinkender Variation kann " +
        "auf Fehlanpassung hinweisen (Plews et al. 2012).\n\n" +
        "Zum Vergleich der Garmin-HRV-Status (eigener, nicht offengelegter Normalbereich)."


/** 7-day ln RMSSD against the smallest worthwhile change, the basis of HRV-guided training. */
@Composable
private fun HrvGuideCard(days: List<HealthDay>, guide: List<HrvGuideDay>) {
    val g = guide.firstOrNull { it.roll7 != null } ?: return
    val d = days.firstOrNull { it.date == g.date }
    val tone = when (g.status) { -1 -> G.bad; 1 -> G.caution; 0 -> G.good; else -> G.text3 }
    val win = remember(guide) { guide.take(90).reversed() }
    val from = win.first().epochDay
    val to = win.last().epochDay
    fun ms(x: Double?) = x?.let { exp(it) }
    GCard {
        CardHead("HRV-Steuerung", "ln RMSSD · 7 T", info = HRV_GUIDE_INFO)
        Row(verticalAlignment = Alignment.Bottom) {
            CountUp(ms(g.roll7), { Fmt.num(it) }, "ms", T.hero.copy(fontSize = 48.sp))
            Spacer(Modifier.width(G.s3))
            Column(Modifier.padding(bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(tone, 7.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(HrvGuide.statusLabel(g.status), style = T.sm, color = tone)
                }
                Text("7-Tage-Mittel · ${Fmt.dayShort(g.date)}", style = T.stat, color = G.text3)
            }
        }
        Spacer(Modifier.height(G.s4))
        TimeChart(
            listOf(
                ChartSeries("Nacht", win.mapNotNull { x -> x.ln?.let { x.epochDay to exp(it) } }, G.text2, line = false, dots = true),
                ChartSeries("Ø7", win.mapNotNull { x -> x.roll7?.let { x.epochDay to exp(it) } }, G.hrvColor, width = 1.8.dp),
                ChartSeries("SWC−", win.mapNotNull { x -> x.swcLo?.let { x.epochDay to exp(it) } }, G.text3, width = 0.8.dp),
                ChartSeries("SWC+", win.mapNotNull { x -> x.swcHi?.let { x.epochDay to exp(it) } }, G.text3, width = 0.8.dp),
            ),
            from, to, { Fmt.num(it) }, height = 150.dp,
        )
        Spacer(Modifier.height(G.s3))
        StatTable(
            listOf(
                "Nacht" to (ms(g.ln)?.let { "${Fmt.num(it)} ms" } ?: "–"),
                "Normalbereich" to (if (g.swcLo != null && g.swcHi != null) "${Fmt.num(exp(g.swcLo!!))}–${Fmt.num(exp(g.swcHi!!))}" else "–"),
                "CV 7 T" to (g.cv7?.let { "${Fmt.num(it, 1)} %" } ?: "–"),
                "Garmin" to hrvStatus(d?.hrvStatus),
            ),
        )
    }
}
