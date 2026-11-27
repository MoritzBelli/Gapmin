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
import de.moritzbelli.gapmin.core.Insights
import de.moritzbelli.gapmin.core.Rhythm
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
internal fun SleepTrends(nights: List<SleepNight>, selected: Int, sri: Map<String, Double> = emptyMap()) {
    val defs = remember(sri) { if (sri.isEmpty()) SLEEP_TRENDS else SLEEP_TRENDS + sriDef(sri) }
    TrendsCard("Langzeittrends", defs, nights, selected, nightDay, nightDate)
}

/** Sleep Regularity Index per night (7 days ending with it), Phillips et al. 2017. */
private fun sriDef(sri: Map<String, Double>) = MetricDef<SleepNight>(
    "Sleep Regularity Index", { sri[it.night] }, digits = 1, better = 1, color = G.sleepColor,
    ref = "UK Biobank: Median 81, IQR 74–86 (Windred 2024)", refLo = 73.8, refHi = 86.3,
    note = "Wahrscheinlichkeit, 24 h später im selben Zustand (Schlaf/wach) zu sein, über 7 Tage, skaliert auf −100…100. 100 = identische Schlafzeiten.",
)

private const val RHYTHM_INFO =
    "Sleep Regularity Index (SRI, Phillips et al. 2017): Wahrscheinlichkeit, zu zwei Zeitpunkten im Abstand von 24 h im selben Zustand " +
        "(Schlaf oder wach) zu sein, über die letzten 7 Tage in 5-Minuten-Epochen, skaliert auf −100 bis 100. In der UK Biobank " +
        "(n ≈ 61 000, Beschleunigungsmessung) lag der Median bei 81 (IQR 73,8–86,3); regelmäßigerer Schlaf ging mit 20–48 % niedrigerem " +
        "Sterberisiko einher und sagte es besser vorher als die Schlafdauer (Windred et al. 2024). Die Uhr erfasst nur den Hauptschlaf, " +
        "Nickerchen zählen als wach.\n\n" +
        "Social Jetlag: Differenz der Schlafmitte an freien Tagen (Nächte auf Sa/So) und Arbeitstagen, letzte 28 Nächte (Wittmann et al. 2006). " +
        "Mehr als 1 h war mit höherem BMI assoziiert (Roenneberg et al. 2012).\n\n" +
        "Chronotyp (MSFsc): Schlafmitte freier Tage, korrigiert um den Nachholschlaf (Roenneberg et al. 2004). Früher = Lerche, später = Eule.\n\n" +
        "Schlafbilanz: Σ (Gesamtschlafzeit − 7 h) der letzten 7 Nächte; 7 h ist die Mindestempfehlung der AASM (Watson et al. 2015). " +
        "Streuung: SD der Gesamtschlafzeit; eine hohe Nacht-zu-Nacht-Variabilität ist mit schlechterer Gesundheit assoziiert (Bei et al. 2016).\n\n" +
        "Unten: Schlaffenster der letzten 21 Nächte (18–12 Uhr), freie Tage hervorgehoben."


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

private const val SLEEP_METHOD =
    "Baseline: Mittel ± SD deiner 28 vorherigen Nächte (mind. 5). z = (Wert − Mittel) / SD. Der Balken zeigt z von −3 bis +3, " +
        "hell hinterlegt ±1 SD, Striche bei ±2 SD. Grün/Rot: besser/schlechter in Richtung der Evidenz (ab |z| 0,5); " +
        "Orange: auffällige Abweichung ohne Richtung (|z| ≥ 1,5).\n\n" +
        "Antippen einer Zeile zeigt den Verlauf der letzten 90 Nächte (Punkte: Einzelwerte, Linie: gleitendes Mittel über 7, " +
        "Band: Baseline, gestrichelt: Literaturgrenzen) sowie n, Median, IQR, Spannweite, SD, Variationskoeffizient " +
        "und den linearen Trend (OLS, pro 30 Tage) mit Korrelation r zur Zeit. Antippen oder Ziehen im Diagramm liest Einzelwerte ab.\n\n" +
        "Gewichte: Dauer, Kontinuität und autonome Erholung je 25 %, Architektur und Regelmäßigkeit je 10 %, Atmung 5 %. " +
        "Kein Medizinprodukt, keine Diagnose. Volle Begründung und Quellen: docs/research/schlaf-score.md."

private const val STAGE_INFO =
    "Phasen aus einem Handgelenk-Algorithmus (Garmin vs. PSG: 50 % Übereinstimmung, κ = 0,25; Miller 2022). " +
        "Einzelnächte schwanken, Trends sind aussagekräftiger."

@Composable
fun SleepScreen(
    nights: List<SleepNight>,
    insights: Insights,
    selected: Int,
    onSelect: (Int) -> Unit,
    listState: LazyListState,
    bottomPadding: Dp,
    onGoToData: () -> Unit,
) {
    val n = nights.getOrNull(selected)
    val reveal = rememberReveal()
    val sriMetric = remember(insights.sri) { if (insights.sri.isEmpty()) null else sriDef(insights.sri) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = G.s4, end = G.s4, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(G.s3),
    ) {
        if (n == null) {
            item(key = "head") { ScreenHeader("Schlaf", "Noch keine Nächte") }
            item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(vertical = G.s8), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Noch keine Schlafdaten.", style = T.md, color = G.text2)
                    Spacer(Modifier.height(G.s4))
                    PillButton("Zu Daten", primary = true, onClick = onGoToData)
                }
            }
            return@LazyColumn
        }
        item(key = "head") {
            ScreenHeader(if (selected == 0) "Letzte Nacht" else Fmt.dayShort(n.night), "Nacht zum ${Fmt.dayLong(n.night)}")
        }
        item(key = "strip") { NightStrip(nights, selected, onSelect) }
        item(key = "score") { Box(Modifier.reveal(reveal, "score", 0)) { ScoreCard(nights, selected) } }
        item(key = "hypno") { Box(Modifier.reveal(reveal, "hypno", 1)) { HypnoCard(nights, selected) } }
        item(key = "rhythm") { Box(Modifier.reveal(reveal, "rhythm", 2)) { RhythmCard(nights, selected) } }
        item(key = "trends") { Box(Modifier.reveal(reveal, "trends", 3)) { SleepTrends(nights, selected, insights.sri) } }
        item(key = "derivation") { Box(Modifier.reveal(reveal, "derivation", 4)) { DerivationCard(n) } }
        item(key = "metrics") {
            BaselineOverview(
                "Kennzahlen", listOf(
                    MetricSection("Dauer & Timing", TIMING_DEFS + listOfNotNull(sriMetric)),
                    MetricSection("Kontinuität", CONTINUITY_DEFS),
                    MetricSection("Architektur", ARCH_DEFS),
                    MetricSection("Autonome Physiologie", AUTONOMIC_DEFS),
                    MetricSection("Atmung & Sauerstoff", BREATHING_DEFS),
                ),
                nights, selected, nightDate, nightDay, trailing = "vs. 28 Nächte",
            )
        }
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

    var infoOpen by remember { mutableStateOf(false) }
    val (pairs, r, ba) = agree
    val info = remember(pairs, r, ba) {
        buildString {
            append(SLEEP_METHOD)
            if (r != null && ba != null) {
                append("\n\nGSS vs. Garmin (n = $pairs): r = ${Fmt.num(r, 2)} · Bias ${Fmt.signed(ba.first, 1)} ± ${Fmt.num(ba.second, 1)} (Bland-Altman, 1 SD)")
            }
        }
    }
    GCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("GAPMIN-SCHLAFSCORE", style = T.label, color = G.text2, modifier = Modifier.weight(1f))
            InfoButton(infoOpen, { infoOpen = !infoOpen })
        }
        Box(Modifier.fillMaxWidth().padding(vertical = G.s2), contentAlignment = Alignment.Center) {
            ScoreRing(n.score, color, Modifier.size(184.dp), fontSize = 64.sp)
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(n.label, style = T.lg.copy(fontSize = 24.sp), color = color)
            Spacer(Modifier.height(2.dp))
            Text(
                "${Fmt.time(n.onsetTs)} – ${Fmt.time(n.wakeTs)} · ${Fmt.hm(n.tstMin)} h" + (n.garminScore?.let { " · Garmin $it" } ?: ""),
                style = T.stat, color = G.text2,
            )
        }
        InfoPanel(infoOpen, info)
        Spacer(Modifier.height(G.s4))
        Row(Modifier.fillMaxWidth()) {
            StatCell("Ø 7 N.", d7?.let { Fmt.num(it.mean) } ?: "–", d7?.let { "± ${Fmt.num(it.sd, 1)}" } ?: "", Modifier.weight(1f), T.xl.copy(fontSize = 22.sp))
            StatCell("Baseline 28 N.", d28?.let { Fmt.num(it.mean) } ?: "–", d28?.let { "± ${Fmt.num(it.sd, 1)}" } ?: "", Modifier.weight(1f), T.xl.copy(fontSize = 22.sp))
            StatCell("Perzentil 90 N.", rank?.let { "P${it.roundToInt()}" } ?: "–", "", Modifier.weight(1f), T.xl.copy(fontSize = 22.sp))
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
        if (n.provisional) {
            Spacer(Modifier.height(G.s2))
            Text("Vorläufig: Die persönliche Baseline braucht 7 Nächte (bisher ${n.baselineNights}).", style = T.sm, color = G.text3)
        }
    }
}

@Composable
private fun HypnoCard(nights: List<SleepNight>, selected: Int) {
    val n = nights[selected]
    val prev = remember(nights, selected) { nights.drop(selected + 1).take(28) }
    GCard {
        CardHead("Hypnogramm", "${Fmt.time(n.bedTs)} – ${Fmt.time(n.outTs)}", info = STAGE_INFO)
        if (n.hypnogram.isEmpty()) {
            Text("Für diese Nacht liegt nur die Summe der Phasen vor, kein zeitlicher Verlauf.", style = T.unit, color = G.text3)
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
            modifier = Modifier.fillMaxWidth().height(80.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            itemsIndexed(list, key = { _, it -> it.night }) { i, night ->
                StripBar(night, i == selected) { onSelect(i) }
            }
        }
        Spacer(Modifier.height(G.s1))
    }
}

@Composable
private fun StripBar(n: SleepNight, selected: Boolean, onClick: () -> Unit) {
    val c = G.scoreColor(n.score.toDouble())
    val alpha by animateFloatAsState(if (selected) 1f else 0.28f, tween(G.T_BASE), label = "bar")
    val grow = remember(n.night) { Animatable(0f) }
    LaunchedEffect(n.night) { grow.animateTo(1f, tween(G.T_SLOW, easing = G.easeOut)) }
    val showDate = selected || Fmt.dayOfMonth(n.night) == 1
    Column(
        Modifier.width(12.dp).fillMaxHeight().pressable(onClick, haptic = true),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        Box(
            Modifier
                .width(if (selected) 8.dp else 6.dp)
                .fillMaxHeight((0.76f * (n.score.coerceIn(6, 100) / 100f) * grow.value).coerceAtLeast(0.02f))
                .clip(RoundedCornerShape(4.dp))
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
internal fun ScoreRing(score: Int, color: Color, modifier: Modifier, fontSize: androidx.compose.ui.unit.TextUnit = 34.sp) {
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(score) { sweep.animateTo(score / 100f, tween(G.T_HERO, easing = G.easeOut)) }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = (size.minDimension * 0.055f).coerceAtLeast(5.dp.toPx())
            val inset = sw * 1.6f
            val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
            val tl = Offset(inset, inset)
            val r = arcSize.width / 2
            // 40 fine ticks around the track, like an instrument dial
            for (k in 0 until 40) {
                val a = Math.toRadians(-90.0 + k * 9.0)
                val c = center
                val r0 = r + sw * 0.9f
                val r1 = r0 + sw * (if (k % 10 == 0) 0.9f else 0.45f)
                drawLine(
                    if (k / 40f <= sweep.value) color.copy(alpha = 0.5f) else G.hairline,
                    Offset(c.x + (r0 * kotlin.math.cos(a)).toFloat(), c.y + (r0 * kotlin.math.sin(a)).toFloat()),
                    Offset(c.x + (r1 * kotlin.math.cos(a)).toFloat(), c.y + (r1 * kotlin.math.sin(a)).toFloat()),
                    1.dp.toPx(),
                )
            }
            drawArc(G.surface3, -90f, 360f, false, tl, arcSize, style = Stroke(sw))
            val brush = androidx.compose.ui.graphics.Brush.sweepGradient(listOf(color.copy(alpha = 0.35f), color, color.copy(alpha = 0.35f)), center)
            drawArc(color.copy(alpha = 0.22f), -90f, 360f * sweep.value, false, tl, arcSize, style = Stroke(sw * 2.4f, cap = StrokeCap.Round))
            drawArc(brush, -90f, 360f * sweep.value, false, tl, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
            val end = Math.toRadians(-90.0 + 360.0 * sweep.value)
            val p = Offset(center.x + (r * kotlin.math.cos(end)).toFloat(), center.y + (r * kotlin.math.sin(end)).toFloat())
            drawCircle(G.text1, sw * 0.42f, p)
        }
        Text((sweep.value * 100).roundToInt().toString(), style = T.hero.copy(fontSize = fontSize, lineHeight = fontSize))
    }
}

@Composable
internal fun Hypnogram(n: SleepNight, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val reveal = remember(n.night) { Animatable(0f) }
    LaunchedEffect(n.night) { reveal.animateTo(1f, tween(G.T_DRAW, easing = G.easeOut)) }
    val tickStyle = T.axis.copy(textAlign = TextAlign.Center)
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


private fun clockText(min: Double): String = Fmt.hm(((min % 1440) + 1440) % 1440) + " Uhr"
private fun signedHm(min: Double): String = (if (min < 0) "−" else "+") + Fmt.hm(kotlin.math.abs(min)) + " h"

/** Position of an SRI value relative to the UK Biobank distribution (Windred et al. 2024). */
private fun sriLabel(sri: Double?) = when {
    sri == null -> "mind. 5 Nächte in Folge nötig"
    sri >= 86.3 -> "Regelmäßiger als 75 % der UK Biobank"
    sri >= 73.8 -> "Mittlere Hälfte der UK Biobank"
    else -> "Unregelmäßiger als 75 % der UK Biobank"
}

/** Regularity and timing across nights: SRI, social jetlag, chronotype, sleep balance and the sleep windows of three weeks. */
@Composable
private fun RhythmCard(nights: List<SleepNight>, selected: Int) {
    val r = remember(nights, selected) { Rhythm.stats(nights, selected) }
    GCard {
        CardHead("Rhythmus", "SRI · 7 Tage", info = RHYTHM_INFO)
        Row(verticalAlignment = Alignment.Bottom) {
            CountUp(r.sri, { Fmt.num(it, 1) }, style = T.hero.copy(fontSize = 48.sp))
            Spacer(Modifier.width(G.s3))
            Column(Modifier.padding(bottom = 8.dp)) {
                Text(sriLabel(r.sri), style = T.sm, color = G.text2)
                if (r.sriPairs > 0) Text("${r.sriPairs} Tagespaare", style = T.stat, color = G.text3)
            }
        }
        Spacer(Modifier.height(G.s3))
        RangeGauge(r.sri, 40.0, 100.0, listOf(Triple(73.8, 86.3, G.sleepColor)), listOf(40.0, 60.0, 81.0, 100.0), { Fmt.num(it) })
        Spacer(Modifier.height(G.s3))
        StatTable(
            listOf(
                "Social Jetlag" to (r.sjlMin?.let { "${Fmt.hm(it)} h" } ?: "–"),
                "Chronotyp" to (r.msfsc?.let { clockText(it) } ?: "–"),
                "Bilanz 7 N." to (r.balance7?.let { signedHm(it) } ?: "–"),
                "Streuung" to (r.tstSd7?.let { "± ${Fmt.hm(it)} h" } ?: "–"),
            ),
            tone = { i ->
                when {
                    i == 0 && (r.sjlMin ?: 0.0) > 60 -> G.caution
                    i == 2 && (r.balance7 ?: 0.0) < -120 -> G.caution
                    else -> G.text1
                }
            },
        )
        Spacer(Modifier.height(G.s4))
        SleepWindows(nights, selected)
        Spacer(Modifier.height(G.s2))
        ShareLegend(listOf(Triple("Arbeitstag", "Nacht auf Mo–Fr", G.stageLight), Triple("Frei", "Nacht auf Sa/So", G.stageRem)))
    }
}

/** Actogram-style raster: one row per night (oldest on top), 18:00 to 12:00, sleep in colour. */
@Composable
private fun SleepWindows(nights: List<SleepNight>, selected: Int) {
    val rows = remember(nights, selected) { nights.drop(selected).take(21).reversed() }
    if (rows.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val reveal = remember(rows) { Animatable(0f) }
    LaunchedEffect(rows) { reveal.animateTo(1f, tween(G.T_HERO, easing = G.easeOut)) }
    val zone = ZoneId.systemDefault()
    Canvas(Modifier.fillMaxWidth().height((rows.size * 8 + 18).dp)) {
        val left = 18.dp.toPx()
        val w = size.width - left
        val rowH = 8.dp.toPx()
        val span = 18 * 60f
        fun x(min: Float) = left + (min / span).coerceIn(0f, 1f) * w
        for (h in listOf(20, 0, 4, 8)) {
            val m = ((h - 18 + 24) % 24) * 60f
            drawLine(G.hairline, Offset(x(m), 0f), Offset(x(m), rows.size * rowH), 1.dp.toPx())
            val t = measurer.measure(h.toString().padStart(2, '0'), T.axis)
            drawText(t, topLeft = Offset(x(m) - t.size.width / 2f, rows.size * rowH + 4.dp.toPx()))
        }
        for ((i, n) in rows.withIndex()) {
            val wake = LocalDate.parse(n.night)
            val free = wake.dayOfWeek.value >= 6
            val start = wake.minusDays(1).atTime(18, 0).atZone(zone).toEpochSecond()
            val y = i * rowH
            val wd = measurer.measure(Fmt.weekdayLetter(n.night), T.axis)
            drawText(wd, topLeft = Offset(0f, y + rowH / 2 - wd.size.height / 2f))
            val color = if (free) G.stageRem else G.stageLight
            val rowLimit = ((reveal.value * (rows.size + 6) - i) / 6f).coerceIn(0f, 1f)
            for (iv in Rhythm.sleepIntervals(n)) {
                val a = (iv[0] - start) / 60f
                val b = (iv[1] - start) / 60f
                if (b <= 0 || a >= span) continue
                val x0 = x(a)
                val x1 = x0 + (x(b) - x0) * rowLimit
                drawRoundRect(color.copy(alpha = if (i == rows.lastIndex) 1f else 0.7f), Offset(x0, y + 1.5.dp.toPx()), Size(maxOf(x1 - x0, 1f), rowH - 3.dp.toPx()), CornerRadius(2.dp.toPx()))
            }
        }
    }
}
