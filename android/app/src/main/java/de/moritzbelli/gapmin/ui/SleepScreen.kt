package de.moritzbelli.gapmin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.moritzbelli.gapmin.core.Baselines
import de.moritzbelli.gapmin.core.Component
import de.moritzbelli.gapmin.core.Fmt
import de.moritzbelli.gapmin.core.Sleep
import de.moritzbelli.gapmin.core.SleepJob
import de.moritzbelli.gapmin.core.SleepNight
import de.moritzbelli.gapmin.core.Stage
import de.moritzbelli.gapmin.core.Stat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

private data class StageInfo(val stage: Int, val label: String, val color: Color)

private val STAGES = listOf(
    StageInfo(Stage.AWAKE, "Wach", G.stageAwake),
    StageInfo(Stage.REM, "REM", G.stageRem),
    StageInfo(Stage.LIGHT, "Leicht", G.stageLight),
    StageInfo(Stage.DEEP, "Tief", G.stageDeep),
)

private fun share(n: SleepNight, min: Double) = if (n.tstMin > 0) min / n.tstMin * 100 else null
private fun eveningClock(ts: Long): Double = SleepJob.localClock(ts).minutes.let { if (it > 720) it - 1440 else it }
private val nightDate: (SleepNight) -> String = { Fmt.dayShort(it.night) }
private val nightDay: (SleepNight) -> Long = { LocalDate.parse(it.night).toEpochDay() }

// ---- metric definitions (references: docs/research/schlaf-score.md) ----------------------------

private val SCORE_DEFS = listOf(
    MetricDef<SleepNight>("Gapmin-Schlafscore (GSS)", { it.score.toDouble() }, better = 1, color = G.accent, ref = "eigener Score, 0–100, siehe Herleitung"),
    MetricDef<SleepNight>("Garmin-Sleep-Score", { it.garminScore?.toDouble() }, better = 1, color = G.text2, ref = "Wert der Uhr (Firstbeat), zum Vergleich"),
)

private val TIMING_DEFS = listOf(
    MetricDef<SleepNight>(
        "Gesamtschlafzeit (TST)", { it.tstMin }, kind = Kind.HM, better = 1, color = G.sleepColor,
        ref = "7–9 h (AASM/SRS 2015; NSF 2015)", refLo = 420.0, refHi = 540.0,
        note = "Summe aus Leicht-, Tief- und REM-Schlaf zwischen Einschlafen und Aufwachen.",
    ),
    MetricDef<SleepNight>("Zeit im Bett (TIB)", { it.tibMin }, kind = Kind.HM, color = G.sleepColor, note = "Erste bis letzte Minute der Schlafaufzeichnung."),
    MetricDef<SleepNight>("Einschlafzeitpunkt", { eveningClock(it.onsetTs) }, kind = Kind.CLOCK, color = G.sleepColor),
    MetricDef<SleepNight>("Aufwachzeitpunkt", { SleepJob.localClock(it.wakeTs).minutes }, kind = Kind.CLOCK, color = G.sleepColor),
    MetricDef<SleepNight>(
        "Schlafmitte", { it.midpointMin }, kind = Kind.CLOCK, color = G.sleepColor,
        ref = "02:00–04:00 Uhr (Ru-SATED-Timing, Buysse 2014)", refLo = 120.0, refHi = 240.0,
    ),
    MetricDef<SleepNight>(
        "Regelmäßigkeit (SD, 7 N.)", { it.regularitySd }, unit = "min", better = -1, color = G.sleepColor,
        ref = "< 30 min SD (Sletten 2023; Windred 2024)", refHi = 30.0,
        note = "Robuste Streuung (MAD × 1,4826) von Einschlaf- und Aufwachzeit über die letzten 7 Nächte, gemittelt.",
    ),
)

private val CONTINUITY_DEFS = listOf(
    MetricDef<SleepNight>(
        "Schlafeffizienz (SE)", { it.efficiency * 100 }, unit = "%", better = 1, color = G.stageLight,
        ref = "≥ 85 % (NSF, Ohayon 2017)", refLo = 85.0, note = "SE = TST / TIB.",
    ),
    MetricDef<SleepNight>(
        "Einschlaflatenz (SOL)", { it.solMin }, unit = "min", better = -1, color = G.stageLight,
        ref = "≤ 30 min (NSF 2017)", refHi = 30.0,
        note = "Beginn der Aufzeichnung bis zur ersten Schlafphase. Handgelenk-Geräte erkennen das Einschlafen ungenau.",
    ),
    MetricDef<SleepNight>(
        "Wach nach Einschlafen (WASO)", { it.wasoMin }, unit = "min", better = -1, color = G.stageLight,
        ref = "≤ 20 min (NSF 2017)", refHi = 20.0,
        note = "Wachzeit zwischen erstem Einschlafen und letztem Aufwachen. Wearables unterschätzen sie eher (geringe Wach-Spezifität).",
    ),
    MetricDef<SleepNight>(
        "Aufwachphasen > 5 min", { it.awakeningsLong.toDouble() }, better = -1, color = G.stageLight,
        ref = "≤ 1 pro Nacht (NSF 2017)", refHi = 1.0,
    ),
    MetricDef<SleepNight>("Aufwachphasen gesamt", { it.awakenings.toDouble() }, better = -1, color = G.stageLight),
    MetricDef<SleepNight>(
        "REM-Latenz", { it.remLatencyMin }, unit = "min", color = G.stageLight,
        ref = "typisch 70–120 min (PSG-Normwerte)", refLo = 70.0, refHi = 120.0,
        note = "Einschlafen bis zur ersten REM-Phase.",
    ),
)

private val ARCH_DEFS = listOf(
    MetricDef<SleepNight>("Tiefschlaf (N3)", { share(it, it.deepMin) }, unit = "% TST", digits = 1, color = G.stageDeep, ref = "13–23 % (Ohayon 2004; Score-Band)", refLo = 13.0, refHi = 23.0),
    MetricDef<SleepNight>("REM-Schlaf", { share(it, it.remMin) }, unit = "% TST", digits = 1, color = G.stageRem, ref = "18–26 % (Ohayon 2004; Score-Band)", refLo = 18.0, refHi = 26.0),
    MetricDef<SleepNight>("Leichtschlaf (N1+N2)", { share(it, it.lightMin) }, unit = "% TST", digits = 1, better = -1, color = G.stageLight, ref = "≤ 65 % (Score-Band)", refHi = 65.0),
    MetricDef<SleepNight>("Wach gesamt", { it.awakeMin }, unit = "min", better = -1, color = G.stageAwake, note = "Alle Wachminuten im Bett, inkl. Einschlafen und nach dem letzten Aufwachen."),
    MetricDef<SleepNight>(
        "Tiefschlaf in 1. Nachthälfte", { it.deepFirstHalfShare?.times(100) }, unit = "%", better = 1, color = G.stageDeep,
        note = "Anteil des Tiefschlafs vor der Schlafmitte. Physiologisch liegt der Großteil in der ersten Hälfte (homöostatischer Schlafdruck).",
    ),
    MetricDef<SleepNight>(
        "REM in 2. Nachthälfte", { it.remSecondHalfShare?.times(100) }, unit = "%", better = 1, color = G.stageRem,
        note = "Anteil des REM-Schlafs nach der Schlafmitte. Wer früh aufsteht, verliert überproportional REM.",
    ),
)

private val AUTONOMIC_DEFS = listOf(
    MetricDef<SleepNight>(
        "HRV nachts (RMSSD)", { it.physiology.hrvAvg }, unit = "ms", log = true, better = 1, color = G.hrvColor,
        ref = "individuell; Bewertung über z von ln RMSSD (Plews 2012)",
        note = "Mittel der nächtlichen HRV-Werte der Uhr. Statistik auf ln-Skala (HRV ist rechtsschief), Baseline als geometrisches Mittel, Streuung als ± %.",
    ),
    MetricDef<SleepNight>("HRV-Maximum", { it.physiology.hrvMax }, unit = "ms", log = true, better = 1, color = G.hrvColor),
    MetricDef<SleepNight>("Schlafpuls Ø", { it.physiology.hrAvg }, unit = "bpm", digits = 1, better = -1, color = G.heart),
    MetricDef<SleepNight>(
        "Tiefster Puls (5-min-Mittel)", { it.physiology.hrMin }, unit = "bpm", digits = 1, better = -1, color = G.heart,
        note = "Niedrigstes gleitendes 5-Minuten-Mittel zwischen Einschlafen und Aufwachen.",
    ),
    MetricDef<SleepNight>(
        "Nächtlicher Pulsabfall (Dipping)", { it.physiology.hrDip?.times(100) }, unit = "%", digits = 1, better = 1, color = G.heart,
        ref = "≥ 10 % gegenüber dem Tag (Hilmisson 2024)", refLo = 10.0,
        note = "1 − Schlafpuls / Tagespuls (14 bis 2 h vor dem Zubettgehen). Fehlendes Dipping ist mit höherem kardiovaskulärem Risiko assoziiert.",
    ),
    MetricDef<SleepNight>(
        "Stress im Schlaf", { it.physiology.stressAvg }, better = -1, color = G.stressRest,
        ref = "0–25 = Ruhe (Garmin-Skala)", refHi = 25.0, note = "Garmin/Firstbeat-Stresswert (0–100), aus HRV und Puls abgeleitet.",
    ),
    MetricDef<SleepNight>(
        "Body-Battery-Ladung", { it.physiology.bbGain }, better = 1, color = G.battery,
        note = "Body Battery beim Aufwachen − beim Einschlafen. Proprietärer Firstbeat-Wert, nicht extern validiert.",
    ),
)

private val BREATHING_DEFS = listOf(
    MetricDef<SleepNight>(
        "Atemfrequenz Ø", { it.physiology.respAvg }, unit = "/min", digits = 1, color = G.breath,
        ref = "11,8–19,2 /min, 90 % gesunder Erwachsener (Natarajan 2021)", refLo = 11.8, refHi = 19.2,
        note = "Anstieg > 3 /min über das eigene Normal gilt als Warnsignal (z. B. Infekt).",
    ),
    MetricDef<SleepNight>("Atemfrequenz max.", { it.physiology.respMax }, unit = "/min", digits = 1, color = G.breath),
    MetricDef<SleepNight>("SpO2 Ø", { it.physiology.spo2Avg }, unit = "%", digits = 1, better = 1, color = G.oxygen, ref = "≥ 95 %; < 92 % abklären", refLo = 95.0),
    MetricDef<SleepNight>("SpO2 Minimum", { it.physiology.spo2Min }, unit = "%", better = 1, color = G.oxygen, refLo = 90.0, ref = "anhaltend < 90 % auffällig"),
    MetricDef<SleepNight>("Anteil SpO2 < 90 %", { it.physiology.spo2Below90?.times(100) }, unit = "%", digits = 1, better = -1, color = G.oxygen),
)

/** Metrics offered in the long-term trend card. */
private val SLEEP_TRENDS = listOf(
    SCORE_DEFS[0], TIMING_DEFS[0], TIMING_DEFS[4], TIMING_DEFS[5], CONTINUITY_DEFS[0], CONTINUITY_DEFS[2],
    ARCH_DEFS[0], ARCH_DEFS[1], AUTONOMIC_DEFS[0], AUTONOMIC_DEFS[2], AUTONOMIC_DEFS[4],
)

@Composable
internal fun SleepTrends(nights: List<SleepNight>, selected: Int) =
    TrendsCard("Langzeittrends", SLEEP_TRENDS, nights, selected, nightDay, nightDate)

// ---- score derivation texts --------------------------------------------------------------------

private class CompInfo(val title: String, val what: String, val formula: String, val source: String)

private val COMP_INFO = mapOf(
    Component.DURATION to CompInfo(
        "Schlafdauer",
        "Gesamtschlafzeit (TST): Summe aus Leicht-, Tief- und REM-Schlaf.",
        "7–9 h → 100. Unter 7 h linear bis 0 bei 4 h (6 h ≈ 67). Über 9 h sanft bis 80 bei 11 h, da ein Schaden langer Schlafdauer nicht belegt ist.",
        "Watson et al. 2015 (AASM/SRS-Konsens); Hirshkowitz et al. 2015 (NSF)",
    ),
    Component.CONTINUITY to CompInfo(
        "Schlafkontinuität",
        "Wie ungestört der Schlaf war: geschlafener Anteil der Bettzeit, Wachzeit nach dem Einschlafen, längere Aufwachphasen und Einschlafdauer. Die am besten belegte Qualitätsdimension.",
        "0,40·SE + 0,30·WASO + 0,20·Aufwachen + 0,10·SOL. SE: 70 % → 0, 90 % → 100. WASO: 60 → 0, 10 min → 100. Aufwachen > 5 min: ≤ 1 → 100, 2 → 70, 3 → 40, ≥ 4 → 10. SOL: 60 → 0, 15 min → 100.",
        "Ohayon et al. 2017 (NSF-Delphi-Konsens, 277 Studien)",
    ),
    Component.RECOVERY to CompInfo(
        "Autonome Erholung",
        "Wie stark sich das vegetative Nervensystem nachts erholt hat. Im erholsamen Schlaf überwiegt der Parasympathikus (Vagusnerv): Die Herzfrequenzvariabilität (RMSSD) steigt, der Puls sinkt. Nach hoher Trainingslast, Alkohol, Infekt oder psychischem Stress fällt die HRV und der Schlafpuls steigt. Bewertet wird nur gegen deine letzten 28 Nächte, weil absolute HRV-Werte zwischen Personen stark streuen.",
        "0,50·HRV + 0,30·Schlafpuls + 0,20·Stress. HRV: z = (ln RMSSD − μ) / σ der Baseline; z ≤ −2 → 0, −1 → 45, 0 → 80, ≥ +0,5 → 100 (0,5 SD ≈ kleinste relevante Änderung). Schlafpuls: Δ zum Mittel ≤ 0 → 100, +3 → 70, +6 → 35, ≥ +9 bpm → 0. Stress (Garmin 0–100): ≤ 10 → 100, ≥ 40 → 0. Unter 7 Nächten Baseline grobe Bevölkerungswerte (vorläufig).",
        "Plews et al. 2012/2013 (ln RMSSD, 7-Tage-Mittel, SWC); Garmin/Firstbeat; Hilmisson et al. 2024",
    ),
    Component.STAGES to CompInfo(
        "Schlafarchitektur",
        "Anteile von Tief-, REM- und Leichtschlaf an der TST. Gering gewichtet: Kein Expertenkonsens zur optimalen Architektur, und Handgelenk-Geräte erreichen gegenüber Polysomnographie nur κ ≈ 0,2–0,5.",
        "Mittel aus drei Bändern: Tief 13–23 % (−6 Punkte je %-Punkt außerhalb), REM 18–26 % (−5), Leicht ≤ 65 % (−4).",
        "Ohayon et al. 2004 (Metaanalyse, 65 Studien); Miller et al. 2022; Schyvens et al. 2025",
    ),
    Component.REGULARITY to CompInfo(
        "Regelmäßigkeit",
        "Wie konstant Einschlaf- und Aufwachzeit über 7 Nächte sind. In der UK Biobank sagte die Regelmäßigkeit die Sterblichkeit besser vorher als die Dauer.",
        "Robuste SD (MAD × 1,4826) von Einschlaf- und Aufwachzeit, gemittelt. ≤ 20 min → 100, ≥ 90 min → 0. Eine einzelne Ausreißernacht wiegt wenig (Median).",
        "Windred et al. 2024 (n ≈ 61 000); Sletten et al. 2023 (NSF-Konsens)",
    ),
    Component.BREATHING to CompInfo(
        "Atmung & SpO2",
        "Nächtliche Atemfrequenz und Sauerstoffsättigung. Vor allem Warnsignale (Infekt, Höhe, Atmungsstörung), daher geringes Gewicht.",
        "0,6·Atemfrequenz + 0,4·SpO2. Atemfrequenz: |Δ| zum Mittel ≤ 1 → 100, 3 → 40, ≥ 4 /min → 0 (ohne Baseline: 11,8–19,2 /min → 100). SpO2: ≥ 95 % → 100, 92 % → 40, ≤ 90 % → 0.",
        "Natarajan et al. 2021; npj Digit Med 2023 (nächtliche SpO2-Referenz)",
    ),
)

private fun baselinesOf(n: SleepNight) = Baselines(n.baselineNights, n.lnHrvMean, n.lnHrvSd, n.hrMean, n.respMean)

private fun partsOf(n: SleepNight): List<Sleep.PartScore> {
    val p = n.physiology
    return Sleep.parts(
        Sleep.PartInput(
            n.tstMin, n.efficiency, n.wasoMin, n.awakeningsLong, n.solMin, n.deepMin, n.lightMin, n.remMin,
            p.hrvAvg, p.hrAvg, p.stressAvg, p.respAvg, p.spo2Avg,
            if (n.regularitySd != null) 7 else 0, n.regularitySd, baselinesOf(n),
        ),
    )
}

private fun partInput(key: String, n: SleepNight): String {
    val p = n.physiology
    fun pct(x: Double) = share(n, x)?.let { "${Fmt.num(it, 1)} %" } ?: "–"
    return when (key) {
        "tst" -> "${Fmt.hm(n.tstMin)} h"
        "se" -> "${Fmt.num(n.efficiency * 100, 1)} %"
        "waso" -> "${Fmt.num(n.wasoMin)} min"
        "wake" -> "${n.awakeningsLong}×"
        "sol" -> "${Fmt.num(n.solMin)} min"
        "hrv" -> p.hrvAvg?.let { v -> Sleep.hrvZ(v, baselinesOf(n))?.let { "${Fmt.num(v)} ms · z ${Fmt.signed(it, 2)}" } ?: "${Fmt.num(v)} ms" } ?: "–"
        "hr" -> p.hrAvg?.let { v -> n.hrMean?.let { "${Fmt.num(v, 1)} · Δ ${Fmt.signed(v - it, 1)} bpm" } ?: "${Fmt.num(v, 1)} bpm" } ?: "–"
        "stress" -> Fmt.num(p.stressAvg)
        "deep" -> pct(n.deepMin)
        "rem" -> pct(n.remMin)
        "light" -> pct(n.lightMin)
        "reg" -> n.regularitySd?.let { "± ${Fmt.num(it)} min" } ?: "–"
        "resp" -> p.respAvg?.let { v -> n.respMean?.let { "${Fmt.num(v, 1)} · Δ ${Fmt.signed(v - it, 1)} /min" } ?: "${Fmt.num(v, 1)} /min" } ?: "–"
        "spo2" -> p.spo2Avg?.let { "${Fmt.num(it, 1)} %" } ?: "–"
        else -> "–"
    }
}

@Composable
fun SleepScreen(
    nights: List<SleepNight>,
    selected: Int,
    onSelect: (Int) -> Unit,
    listState: LazyListState,
    bottomPadding: Dp,
    onGoToData: () -> Unit,
) {
    val n = nights.getOrNull(selected)

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = G.s4, end = G.s4, top = G.s4, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(G.s3),
    ) {
        if (n == null) {
            item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(vertical = G.s8), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Noch keine Schlafdaten.", style = T.md, color = G.text2)
                    Spacer(Modifier.height(G.s4))
                    PillButton("Zu Daten", primary = true, onClick = onGoToData)
                }
            }
            return@LazyColumn
        }
        item(key = "strip") { NightStrip(nights, selected, onSelect) }
        item(key = "score") { ScoreCard(nights, selected) }
        item(key = "hypno") { HypnoCard(nights, selected) }
        item(key = "trends") { SleepTrends(nights, selected) }
        item(key = "derivation") { DerivationCard(n) }
        item(key = "timing") { MetricGroup("Dauer & Timing", TIMING_DEFS, nights, selected, nightDate, nightDay) }
        item(key = "continuity") { MetricGroup("Kontinuität", CONTINUITY_DEFS, nights, selected, nightDate, nightDay) }
        item(key = "arch") { MetricGroup("Architektur", ARCH_DEFS, nights, selected, nightDate, nightDay, trailing = "Phasen") }
        item(key = "autonomic") {
            MetricGroup(
                "Autonome Physiologie", AUTONOMIC_DEFS, nights, selected, nightDate, nightDay,
                footnote = "Die Grundlage der Komponente „Autonome Erholung“.",
            )
        }
        item(key = "breathing") { MetricGroup("Atmung & Sauerstoff", BREATHING_DEFS, nights, selected, nightDate, nightDay) }
        item(key = "method") { MethodCard() }
    }
}

@Composable
private fun ScoreCard(nights: List<SleepNight>, selected: Int) {
    val n = nights[selected]
    val color by animateColorAsState(G.scoreColor(n.score.toDouble()), tween(G.T_SLOW), label = "score")
    val hist = remember(nights, selected) { nights.drop(selected) }
    val d7 = remember(hist) { Stat.desc(hist.take(7).map { it.score.toDouble() }) }
    val d28 = remember(hist) { Stat.desc(hist.drop(1).take(28).map { it.score.toDouble() }) }
    val rank = remember(hist) {
        val w = hist.take(90).map { it.score }
        if (w.size >= 10) (w.count { it < n.score } + 0.5 * (w.count { it == n.score } - 1)) / (w.size - 1) * 100 else null
    }
    val agree = remember(nights) {
        val pairs = nights.take(90).mapNotNull { x -> x.garminScore?.let { x.score.toDouble() to it.toDouble() } }
        Triple(pairs.size, Stat.pearson(pairs), Stat.blandAltman(pairs))
    }

    GCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(n.score, color, Modifier.size(116.dp))
            Spacer(Modifier.width(G.s5))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                Label(if (selected == 0) "Letzte Nacht" else Fmt.dayShort(n.night))
                Text(n.label, style = T.lg.copy(fontSize = 24.sp), color = color)
                Text("${Fmt.time(n.onsetTs)} – ${Fmt.time(n.wakeTs)} · ${Fmt.hm(n.tstMin)} h", style = T.sm, color = G.text2)
                if (n.garminScore != null) Text("Garmin ${n.garminScore}", style = T.sm, color = G.text3)
            }
        }
        Spacer(Modifier.height(G.s4))
        Row(Modifier.fillMaxWidth()) {
            StatCell("Ø 7 N.", d7?.let { Fmt.num(it.mean) } ?: "–", d7?.let { "± ${Fmt.num(it.sd, 1)}" } ?: "", Modifier.weight(1f))
            StatCell("Baseline 28 N.", d28?.let { Fmt.num(it.mean) } ?: "–", d28?.let { "± ${Fmt.num(it.sd, 1)}" } ?: "", Modifier.weight(1f))
            StatCell("Perzentil 90 N.", rank?.let { "P${it.roundToInt()}" } ?: "–", "", Modifier.weight(1f))
        }
        if (n.factors.isNotEmpty()) {
            Spacer(Modifier.height(G.s4))
            for (f in n.factors) {
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dot(G.text3, 4.dp)
                    Spacer(Modifier.width(G.s2))
                    Text(f, style = T.sm, color = G.text2)
                }
            }
        }
        Spacer(Modifier.height(G.s3))
        for (d in SCORE_DEFS) MetricRow(d, nights, selected, nightDate, nightDay, divider = true)
        val (pairs, r, ba) = agree
        if (r != null && ba != null) {
            Spacer(Modifier.height(G.s2))
            Text(
                "GSS vs. Garmin (n = $pairs): r = ${Fmt.num(r, 2)} · Bias ${Fmt.signed(ba.first, 1)} ± ${Fmt.num(ba.second, 1)} (Bland-Altman, 1 SD)",
                style = T.unit,
            )
        }
        if (n.provisional) {
            Spacer(Modifier.height(G.s2))
            Text("Vorläufig: Die persönliche Baseline braucht 7 Nächte (bisher ${n.baselineNights}).", style = T.sm, color = G.text3)
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, sub: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Label(label)
        Spacer(Modifier.height(2.dp))
        ValueUnit(value, sub, T.xl.copy(fontSize = 22.sp))
    }
}

@Composable
private fun HypnoCard(nights: List<SleepNight>, selected: Int) {
    val n = nights[selected]
    val prev = remember(nights, selected) { nights.drop(selected + 1).take(28) }
    GCard {
        CardHead("Hypnogramm", "${Fmt.time(n.bedTs)} – ${Fmt.time(n.outTs)}")
        if (n.hypnogram.isEmpty()) {
            Text("Für diese Nacht liegt nur die Summe der Phasen vor (Garmin-Export), kein Verlauf.", style = T.unit, color = G.text3)
        } else {
            Hypnogram(n, Modifier.fillMaxWidth().height(150.dp))
        }
        Spacer(Modifier.height(G.s4))
        // stage table: minutes, share of TST, own 28-night mean, reference band
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Label("Phase", Modifier.weight(1.3f))
            Label("Dauer", Modifier.weight(1f))
            Label("% TST", Modifier.weight(0.9f))
            Label("Ø 28", Modifier.weight(0.9f))
            Label("Ref.", Modifier.weight(1f))
        }
        for (s in STAGES.reversed()) {
            fun min(x: SleepNight) = when (s.stage) {
                Stage.DEEP -> x.deepMin
                Stage.LIGHT -> x.lightMin
                Stage.REM -> x.remMin
                else -> x.awakeMin
            }
            val awake = s.stage == Stage.AWAKE
            val pct = if (awake) null else share(n, min(n))
            val mean = if (awake) prev.map { it.awakeMin }.takeIf { it.isNotEmpty() }?.average()
            else prev.mapNotNull { share(it, min(it)) }.takeIf { it.isNotEmpty() }?.average()
            val ref = when (s.stage) {
                Stage.DEEP -> "13–23 %"
                Stage.REM -> "18–26 %"
                Stage.LIGHT -> "≤ 65 %"
                else -> "–"
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(G.hairline))
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1.3f), verticalAlignment = Alignment.CenterVertically) {
                    Dot(s.color)
                    Spacer(Modifier.width(6.dp))
                    Text(s.label, style = T.sm, color = G.text1)
                }
                Text(Fmt.hm(min(n)), style = T.sm, color = G.text1, modifier = Modifier.weight(1f))
                Text(pct?.let { Fmt.num(it, 1) } ?: "–", style = T.sm, color = G.text1, modifier = Modifier.weight(0.9f))
                Text(
                    mean?.let { if (awake) "${Fmt.num(it)} min" else Fmt.num(it, 1) } ?: "–",
                    style = T.sm, color = G.text3, modifier = Modifier.weight(0.9f),
                )
                Text(ref, style = T.sm, color = G.text3, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(G.s2))
        Text(
            "Phasen aus einem Handgelenk-Algorithmus (Garmin vs. PSG: 50 % Übereinstimmung, κ = 0,25; Miller 2022). Einzelnächte schwanken, Trends sind aussagekräftiger.",
            style = T.unit.copy(lineHeight = 16.sp),
        )
    }
}

@Composable
private fun DerivationCard(n: SleepNight) {
    val parts = remember(n) { partsOf(n) }
    var sum = 0.0
    for (c in Component.entries) n.components[c]?.let { sum += c.weight * it }
    GCard {
        CardHead("Score-Herleitung", "Punkte / max.")
        for ((i, c) in Component.entries.withIndex()) {
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(G.hairline))
            ComponentRow(c, n.components[c], parts.filter { it.component == c }, n)
        }
        Spacer(Modifier.height(G.s3))
        Text(
            "GSS = Σ wᵢ·sᵢ / Σ wᵢ = ${Fmt.num(sum, 1)} / ${Fmt.num(n.coverage, 2)} = ${n.score}",
            style = T.sm, color = G.text2,
        )
        if (n.coverage < 0.999) {
            Text("Datenabdeckung ${(n.coverage * 100).roundToInt()} %: Komponenten ohne Daten werden herausgerechnet.", style = T.unit)
        }
    }
}

@Composable
private fun ComponentRow(c: Component, v: Double?, parts: List<Sleep.PartScore>, n: SleepNight) {
    var open by remember(c) { mutableStateOf(false) }
    val info = COMP_INFO.getValue(c)
    val fill by animateFloatAsState(((v ?: 0.0) / 100).toFloat(), tween(G.T_SLOW, easing = G.easeOut), label = "comp")
    Column(Modifier.fillMaxWidth().pressable({ open = !open }).padding(vertical = G.s3)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(info.title, style = T.sm, color = G.text1, modifier = Modifier.weight(1f), maxLines = 1)
            Text(v?.let { Fmt.num(it * c.weight, 1) } ?: "–", style = T.sm, color = G.text1)
            Text(" / ${(c.weight * 100).roundToInt()}", style = T.unit)
            Spacer(Modifier.width(G.s2))
            Text(if (open) "–" else "+", style = T.sm, color = G.text3, modifier = Modifier.width(12.dp), textAlign = TextAlign.End)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(G.surface3)) {
                Box(Modifier.fillMaxWidth(fill).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(G.scoreColor(v)))
            }
            Spacer(Modifier.width(G.s3))
            Text("s = ${v?.roundToInt() ?: "–"}", style = T.unit, modifier = Modifier.width(44.dp), textAlign = TextAlign.End)
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(top = G.s3)) {
                Text(info.what, style = T.sm, color = G.text2)
                Spacer(Modifier.height(G.s3))
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                    Label("Eingang", Modifier.weight(1.4f))
                    Label("Wert", Modifier.weight(1.3f))
                    Label("sᵢ", Modifier.width(36.dp))
                    Label("Anteil", Modifier.width(44.dp))
                }
                for (p in parts) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.label, style = T.unit, color = G.text2, modifier = Modifier.weight(1.4f), maxLines = 2)
                        Text(partInput(p.key, n), style = T.unit, color = G.text1, modifier = Modifier.weight(1.3f), maxLines = 2)
                        Text(p.score?.let { Fmt.num(it) } ?: "–", style = T.unit, color = G.text1, modifier = Modifier.width(36.dp))
                        Text("${Fmt.num(p.share * 100)} %", style = T.unit, modifier = Modifier.width(44.dp))
                    }
                }
                Spacer(Modifier.height(G.s2))
                Text(info.formula, style = T.unit.copy(lineHeight = 16.sp), color = G.text2)
                Spacer(Modifier.height(G.s1))
                Text("Quelle: ${info.source}", style = T.unit.copy(lineHeight = 16.sp))
            }
        }
    }
}

@Composable
private fun MethodCard() {
    var open by remember { mutableStateOf(false) }
    GCard(Modifier.pressable({ open = !open })) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Methodik & Lesehilfe", style = T.mdMedium, modifier = Modifier.weight(1f))
            Text(if (open) "–" else "+", style = T.lg, color = G.text3)
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(top = G.s3), verticalArrangement = Arrangement.spacedBy(G.s3)) {
                Text(
                    "Baseline: Mittel ± SD deiner 28 vorherigen Nächte (mind. 5). z = (Wert − Mittel) / SD. Der Balken zeigt z von −3 bis +3, " +
                        "hell hinterlegt ±1 SD, Striche bei ±2 SD. Grün/Rot: besser/schlechter in Richtung der Evidenz (ab |z| 0,5); " +
                        "Orange: auffällige Abweichung ohne Richtung (|z| ≥ 1,5).",
                    style = T.sm, color = G.text2,
                )
                Text(
                    "Antippen einer Zeile zeigt den Verlauf der letzten 90 Nächte (Punkte: Einzelwerte, Linie: gleitendes Mittel über 7, " +
                        "Band: Baseline, gestrichelt: Literaturgrenzen) sowie n, Median, IQR, Spannweite, SD, Variationskoeffizient " +
                        "und den linearen Trend (OLS, pro 30 Tage) mit Korrelation r zur Zeit. Antippen oder Ziehen im Diagramm liest Einzelwerte ab.",
                    style = T.sm, color = G.text2,
                )
                Text(
                    "Gewichte: Dauer, Kontinuität und autonome Erholung je 25 %, Architektur und Regelmäßigkeit je 10 %, Atmung 5 %. " +
                        "Kein Medizinprodukt, keine Diagnose. Volle Begründung und Quellen: docs/research/schlaf-score.md.",
                    style = T.sm, color = G.text3,
                )
            }
        }
    }
}

@Composable
private fun NightStrip(nights: List<SleepNight>, selected: Int, onSelect: (Int) -> Unit) {
    val state = rememberLazyListState()
    val list = remember(nights) { nights.take(180) }
    LaunchedEffect(selected) {
        val visible = state.layoutInfo.visibleItemsInfo.map { it.index }
        if (selected !in visible) state.animateScrollToItem(maxOf(0, selected - 3))
    }
    Column(Modifier.fillMaxWidth()) {
        // reverseLayout: newest night on the right, scrolling left goes back in time
        LazyRow(
            state = state,
            reverseLayout = true,
            modifier = Modifier.fillMaxWidth().height(96.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            itemsIndexed(list, key = { _, it -> it.night }) { i, night ->
                StripBar(night, i == selected) { onSelect(i) }
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = G.s3).height(1.dp).background(G.hairline))
    }
}

@Composable
private fun StripBar(n: SleepNight, selected: Boolean, onClick: () -> Unit) {
    val c = G.scoreColor(n.score.toDouble())
    val alpha by animateFloatAsState(if (selected) 1f else 0.32f, tween(G.T_BASE), label = "bar")
    val showDate = selected || Fmt.dayOfMonth(n.night) == 1
    Column(
        Modifier.width(12.dp).fillMaxHeight().pressable(onClick, haptic = true),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        Box(
            Modifier
                .width(10.dp)
                .fillMaxHeight(0.78f * (n.score.coerceIn(6, 100) / 100f))
                .clip(RoundedCornerShape(3.dp))
                .background(c.copy(alpha = alpha)),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (showDate) Fmt.dayOfMonth(n.night).toString() else Fmt.weekdayLetter(n.night),
            style = T.label.copy(fontSize = 9.sp, letterSpacing = 0.sp),
            color = if (selected) G.text1 else G.text3,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun ScoreRing(score: Int, color: Color, modifier: Modifier) {
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(score) { sweep.animateTo(score / 100f, tween(G.T_DRAW, easing = G.easeOut)) }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 7.dp.toPx()
            val inset = 8.dp.toPx()
            val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
            val tl = Offset(inset, inset)
            drawArc(G.surface3, -90f, 360f, false, tl, arcSize, style = Stroke(sw))
            drawArc(color.copy(alpha = 0.25f), -90f, 360f * sweep.value, false, tl, arcSize, style = Stroke(sw * 2.2f, cap = StrokeCap.Round))
            drawArc(color, -90f, 360f * sweep.value, false, tl, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Text((sweep.value * 100).roundToInt().toString(), style = T.xxl.copy(fontSize = 36.sp))
    }
}

@Composable
private fun Hypnogram(n: SleepNight, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val reveal = remember(n.night) { Animatable(0f) }
    LaunchedEffect(n.night) { reveal.animateTo(1f, tween(G.T_DRAW, easing = G.easeOut)) }
    val tickStyle = TextStyle(fontFamily = Inter, fontSize = 10.sp, color = G.text3, textAlign = TextAlign.Center)
    Canvas(modifier) {
        val total = maxOf(1.0, (n.outTs - n.bedTs) / 60.0)
        val axisH = 18.dp.toPx()
        val h = size.height - axisH
        val rowH = h / 4
        fun row(stage: Int) = STAGES.indexOfFirst { it.stage == stage }.coerceAtLeast(0)
        for (i in 0 until 4) {
            val y = i * rowH + rowH / 2
            drawLine(G.hairline, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        }
        val limit = size.width * reveal.value
        // thin step line connecting the stages, under the blocks
        val step = androidx.compose.ui.graphics.Path()
        n.hypnogram.forEachIndexed { i, s ->
            val x0 = (s.offsetMin / total * size.width).toFloat().coerceAtMost(limit)
            val x1 = ((s.offsetMin + s.durMin) / total * size.width).toFloat().coerceAtMost(limit)
            val y = row(s.stage) * rowH + rowH / 2
            if (i == 0) step.moveTo(x0, y) else step.lineTo(x0, y)
            step.lineTo(x1, y)
        }
        drawPath(step, G.hairlineStrong, style = Stroke(1.dp.toPx()))
        for (s in n.hypnogram) {
            val x0 = (s.offsetMin / total * size.width).toFloat()
            if (x0 > limit) continue
            val w = maxOf(1.5.dp.toPx(), (s.durMin / total * size.width).toFloat()).coerceAtMost(limit - x0 + 1)
            val r = row(s.stage)
            drawRoundRect(
                STAGES[r].color,
                topLeft = Offset(x0, r * rowH + rowH * 0.18f),
                size = Size(w, rowH * 0.64f),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
        }
        // hour ticks
        val zone = ZoneId.systemDefault()
        var t = Instant.ofEpochSecond(n.bedTs).atZone(zone).withMinute(0).withSecond(0).plusHours(1)
        while (t.toEpochSecond() < n.outTs) {
            val x = ((t.toEpochSecond() - n.bedTs) / 60.0 / total * size.width).toFloat()
            val label = measurer.measure(t.hour.toString(), tickStyle)
            drawText(label, topLeft = Offset(x - label.size.width / 2f, h + 4.dp.toPx()))
            t = t.plusHours(1)
        }
    }
}

