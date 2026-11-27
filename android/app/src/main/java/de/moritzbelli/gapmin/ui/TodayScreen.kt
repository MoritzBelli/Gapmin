package de.moritzbelli.gapmin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.moritzbelli.gapmin.core.DaySeries
import de.moritzbelli.gapmin.core.Fmt
import de.moritzbelli.gapmin.core.HealthDay
import de.moritzbelli.gapmin.core.HrvGuide
import de.moritzbelli.gapmin.core.Insights
import de.moritzbelli.gapmin.core.RunSummary
import de.moritzbelli.gapmin.core.SleepNight
import de.moritzbelli.gapmin.core.Training
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import kotlin.math.exp

private const val DIAL_INFO =
    "24-Stunden-Ansicht des Tages, Mitternacht oben, im Uhrzeigersinn. Von außen nach innen: Puls (10-Minuten-Mittel, Balkenlänge " +
        "zwischen Tagesminimum und -maximum), Stress (Garmin-Kategorien Ruhe, niedrig, mittel, hoch), Body Battery (Linie, 0–100) und " +
        "Schlafphasen (Tief, Leicht, REM, wach). Heute markiert ein Zeiger die aktuelle Uhrzeit.\n\n" +
        "Antippen eines Rings zeigt die Werte zu dieser Uhrzeit, lange drücken und ziehen fährt den Tag ab, Antippen der Mitte setzt zurück.\n\n" +
        "Stress und Body Battery sind proprietäre Firstbeat-Algorithmen ohne unabhängige Validierung: gut für Verläufe einer Person, " +
        "nicht für Vergleiche zwischen Personen. Quellen und Schwellen: docs/research/gesundheit.md. Kein Medizinprodukt."

private const val SIGNAL_INFO =
    "Drei Signale, bewusst nicht zu einem Score verrechnet:\n\n" +
        "HRV: 7-Tage-Mittel von ln RMSSD gegen den kleinsten relevanten Unterschied (Baseline ± 0,5 SD der 60 Tage davor). " +
        "Darunter spricht für unvollständige Erholung (Plews 2012/2013; Vesterinen 2016; Javaloyes 2019).\n\n" +
        "Belastung: Verhältnis akuter zu chronischer Trainingslast (EWMA 7/28 Tage, Banister-TRIMP). 0,8–1,3 gilt als günstiger Bereich, " +
        "über 1,5 als sprunghafte Steigerung (Gabbett 2016; Williams 2017). Die Aussagekraft für Verletzungen ist umstritten (Impellizzeri 2020).\n\n" +
        "Schlaf: Gapmin-Schlafscore der Nacht (docs/research/schlaf-score.md)."

private fun localDate(ts: Long): String = Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()).toLocalDate().toString()
private val dayDate: (HealthDay) -> String = { Fmt.dayShort(it.date) }
private val dayIndex: (HealthDay) -> Long = { it.epochDay }

/** Rolling 7-day sum of weighted intensity minutes, keyed by epoch day. */
internal fun weekIntensity(days: List<HealthDay>): Map<Long, Double> {
    val im = days.associate { it.epochDay to it.imWeighted }
    return days.associate { x -> x.epochDay to (0L..6L).sumOf { im[x.epochDay - it] ?: 0.0 } }
}

/** The tiles of the start screen: the daily key figures, each against its own 28-day baseline. */
private fun tileDefs(days: List<HealthDay>, insights: Insights): List<MetricDef<HealthDay>> {
    val week = weekIntensity(days)
    val t = insights.training
    return listOf(
        MetricDef<HealthDay>("Ruhepuls", { it.restingHr }, unit = "bpm", better = -1, color = G.heart, ref = "60–100 bpm (AHA); Ausdauertrainierte oft < 60"),
        MetricDef<HealthDay>("HRV nachts", { it.hrvNight }, unit = "ms", log = true, better = 1, color = G.hrvColor, ref = "individuell (Plews 2012)"),
        MetricDef<HealthDay>("Stress Ø", { it.stressAvg }, better = -1, color = G.stressMed, ref = "0–25 Ruhe · 26–50 niedrig · 51–75 mittel · 76–100 hoch (Garmin)"),
        MetricDef<HealthDay>("Body Battery", { it.bbMax }, better = 1, color = G.battery, note = "Tagesmaximum. Proprietärer Firstbeat-Wert, nur als Trend lesen."),
        MetricDef<HealthDay>("Schritte", { it.steps }, better = 1, color = G.activity, ref = "Plateau des Sterberisikos bei 8 000–10 000/Tag (Paluch 2022)", refLo = 8000.0),
        MetricDef<HealthDay>(
            "Intensität 7 T", { week[it.epochDay] }, unit = "min", better = 1, color = G.activity,
            ref = "≥ 150 min/Woche moderat oder 75 min intensiv (WHO 2020)", refLo = 150.0,
            note = "Gleitende 7-Tage-Summe, moderat + 2 × intensiv.",
        ),
        MetricDef<HealthDay>(
            "Trainingslast", { d -> t?.day(d.epochDay)?.load }, better = 0, color = G.fatigue,
            note = "Banister-TRIMP aller Aktivitäten des Tages: Minuten × Herzfrequenzreserve × 0,64·e^(1,92·HRR) (Morton, Fitz-Clarke & Banister 1990).",
        ),
        MetricDef<HealthDay>("SpO2 Ø", { it.spo2Avg }, unit = "%", digits = 1, better = 1, color = G.oxygen, ref = "95–99 % in Ruhe; < 92 % abklären", refLo = 95.0),
    )
}

/**
 * Start screen: the day at a glance. A 24-hour chronogram, three recovery/load signals, the key figures against their baseline,
 * last night's sleep and the day's runs. The day strip walks back through earlier days.
 */
@Composable
fun TodayScreen(
    days: List<HealthDay>,
    nights: List<SleepNight>,
    runs: List<RunSummary>,
    insights: Insights,
    selected: Int,
    onSelect: (Int) -> Unit,
    loadDay: (String, (DaySeries) -> Unit) -> Unit,
    listState: LazyListState,
    bottomPadding: Dp,
    onOpenSleep: () -> Unit,
    onOpenRun: (RunSummary) -> Unit,
    onOpenTraining: () -> Unit,
    onOpenTrends: () -> Unit,
    onGoToData: () -> Unit,
) {
    val d = days.getOrNull(selected)
    // the night that ended on the shown day; for the newest day fall back to the latest recorded night
    val night = remember(nights, d, selected) {
        nights.firstOrNull { it.night == d?.date } ?: if (selected == 0) nights.firstOrNull() else null
    }
    val date = d?.date ?: night?.night
    val dayRuns = remember(runs, date) { if (date == null) emptyList() else runs.filter { localDate(it.startTs) == date } }
    val reveal = rememberReveal()
    val defs = remember(days, insights) { tileDefs(days, insights) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = G.s4, end = G.s4, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(G.s3),
    ) {
        if (date == null) {
            item(key = "head") { ScreenHeader("Heute", "Noch keine Daten") }
            item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(vertical = G.s8), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Noch keine Tagesdaten.", style = T.md, color = G.text2)
                    Spacer(Modifier.height(G.s4))
                    PillButton("Zu Daten", primary = true, onClick = onGoToData)
                }
            }
            return@LazyColumn
        }
        val ld = LocalDate.parse(date)
        val today = ld == LocalDate.now()
        item(key = "head") {
            val title = when (ld) {
                LocalDate.now() -> "Heute"
                LocalDate.now().minusDays(1) -> "Gestern"
                else -> Fmt.dayShort(date)
            }
            ScreenHeader(title, "${Fmt.dayLong(date)} · KW ${ld.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}")
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
        if (d != null) {
            item(key = "dial-$date") { DialCard(d, nights, today, loadDay, Modifier.reveal(reveal, "dial", 0)) }
            item(key = "signals") { Signals(d, night, insights, onOpenTrends, onOpenTraining, onOpenSleep, Modifier.reveal(reveal, "signals", 1)) }
            item(key = "tiles") { KpiGrid(defs, days, selected, Modifier.reveal(reveal, "tiles", 2)) }
        }
        if (night != null) {
            item(key = "sleep") { SleepToday(night, sameDay = night.night == date, onClick = onOpenSleep, modifier = Modifier.reveal(reveal, "sleep", 3)) }
        }
        itemsIndexed(dayRuns, key = { _, r -> "run-${r.id}" }) { i, run ->
            TodayRun(run, onClick = { onOpenRun(run) }, modifier = Modifier.reveal(reveal, "run-${run.id}", 4 + i))
        }
    }
}

@Composable
private fun DialCard(d: HealthDay, nights: List<SleepNight>, today: Boolean, loadDay: (String, (DaySeries) -> Unit) -> Unit, modifier: Modifier) {
    var series by remember(d.date) { mutableStateOf<DaySeries?>(null) }
    LaunchedEffect(d.date) { loadDay(d.date) { series = it } }
    val night = nights.firstOrNull { it.night == d.date }
    GCard(modifier, padding = G.s4) {
        CardHead("Tagesverlauf", "24 h", info = DIAL_INFO)
        DayDial(d.date, series, nights, today, Modifier.padding(horizontal = G.s2))
        Spacer(Modifier.height(G.s3))
        ShareLegend(
            listOf(
                Triple("Puls", if (d.hrMin != null && d.hrMax != null) "${Fmt.num(d.hrMin)}–${Fmt.num(d.hrMax)}" else "–", G.heart),
                Triple("Stress", d.stressAvg?.let { "Ø ${Fmt.num(it)}" } ?: "–", G.stressMed),
                Triple("Batterie", if (d.bbCharged != null) "+${Fmt.num(d.bbCharged)} / −${Fmt.num(d.bbDrained)}" else "–", G.battery),
                Triple("Schlaf", night?.let { "${Fmt.hm(it.tstMin)} h" } ?: "–", G.stageLight),
            ),
            Modifier.padding(horizontal = G.s1),
        )
    }
}

/** HRV guidance, training load balance and sleep: three independent signals for the day. */
@Composable
private fun Signals(
    d: HealthDay,
    night: SleepNight?,
    insights: Insights,
    onHrv: () -> Unit,
    onLoad: () -> Unit,
    onSleep: () -> Unit,
    modifier: Modifier,
) {
    val hrv = insights.hrv.firstOrNull { it.date == d.date }
    val load = insights.training?.day(d.epochDay)
    val acwr = load?.acwr
    var info by remember { mutableStateOf(false) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = G.s1), verticalAlignment = Alignment.CenterVertically) {
            Text("SIGNALE", style = T.label, color = G.text2, modifier = Modifier.weight(1f))
            InfoButton(info, { info = !info })
        }
        InfoPanel(info, SIGNAL_INFO, Modifier.padding(bottom = G.s2))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(G.s2)) {
            SignalCard(
                "HRV 7 T",
                hrv?.roll7?.let { Fmt.num(exp(it)) } ?: "–", "ms",
                when (hrv?.status) { -1 -> "unter Normal"; 0 -> "normal"; 1 -> "über Normal"; else -> "im Aufbau" },
                when (hrv?.status) { -1 -> G.bad; 1 -> G.caution; 0 -> G.good; else -> G.text3 },
                Modifier.weight(1f), onHrv,
            )
            SignalCard(
                "Belastung",
                acwr?.let { Fmt.num(it, 2) } ?: "–", "ACWR",
                if (acwr == null) (if (load != null) "im Aufbau" else "keine Daten") else Training.acwrLabel(acwr),
                when {
                    acwr == null -> G.text3
                    acwr > 1.5 -> G.bad
                    acwr > 1.3 -> G.caution
                    acwr >= 0.8 -> G.good
                    else -> G.text2
                },
                Modifier.weight(1f), onLoad,
            )
            SignalCard(
                "Schlaf",
                night?.score?.toString() ?: "–", "GSS",
                night?.label ?: "keine Nacht",
                night?.let { G.scoreColor(it.score.toDouble()) } ?: G.text3,
                Modifier.weight(1f), onSleep,
            )
        }
    }
}

@Composable
private fun SignalCard(label: String, value: String, unit: String, status: String, tone: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(20.dp))
            .background(G.panel)
            .pressable(onClick, haptic = true)
            .padding(horizontal = 12.dp, vertical = 14.dp),
    ) {
        Text(label.uppercase(), style = T.label, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        ValueUnit(value, unit, T.xl.copy(fontSize = 22.sp))
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(tone, 6.dp)
            Spacer(Modifier.width(5.dp))
            Text(status, style = T.stat.copy(fontSize = 10.sp), color = tone, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Two-column grid of key figures; a tile opens its 90-day course with statistics below its row. */
@Composable
private fun KpiGrid(defs: List<MetricDef<HealthDay>>, days: List<HealthDay>, selected: Int, modifier: Modifier) {
    var open by remember { mutableStateOf<Int?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(G.s2)) {
        Text("KENNZAHLEN · VS. 28 TAGE", style = T.label, color = G.text2, modifier = Modifier.padding(start = G.s1, top = G.s2, bottom = G.s1))
        for ((r, row) in defs.chunked(2).withIndex()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(G.s2)) {
                for ((c, def) in row.withIndex()) {
                    val i = r * 2 + c
                    KpiTile(def, days, selected, open == i, Modifier.weight(1f)) { open = if (open == i) null else i }
                }
            }
            val shown = open?.takeIf { it / 2 == r }
            // keep showing the closing tile's content while the panel collapses
            val keep = remember(r) { intArrayOf(r * 2) }
            if (shown != null) keep[0] = shown
            AnimatedVisibility(
                shown != null,
                enter = expandVertically(tween(G.T_SLOW, easing = G.easeOut)) + fadeIn(tween(G.T_BASE)),
                exit = shrinkVertically(tween(G.T_BASE)) + fadeOut(tween(G.T_FAST)),
            ) {
                val def = defs[keep[0].coerceIn(0, defs.lastIndex)]
                val base = remember(def, days, selected) { def.baseline(days, selected) }
                GCard(padding = G.s4) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(def.color, 6.dp)
                        Spacer(Modifier.width(G.s2))
                        Text(def.label.uppercase(), style = T.label, color = G.text2, modifier = Modifier.weight(1f))
                        Text("90 Tage", style = T.stat, color = G.text3)
                    }
                    val txt = listOfNotNull(def.note, def.ref?.let { "Referenz: $it" }).joinToString("\n")
                    if (txt.isNotEmpty()) Text(txt, style = T.unit, color = G.text3, modifier = Modifier.padding(top = G.s2))
                    Spacer(Modifier.height(G.s3))
                    TrendBlock(def, days, selected, base, dayDate, dayIndex)
                }
            }
        }
    }
}

@Composable
private fun KpiTile(def: MetricDef<HealthDay>, days: List<HealthDay>, selected: Int, open: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val item = days.getOrNull(selected) ?: return
    val v = def.get(item)?.takeIf { it.isFinite() }
    val base = remember(def, days, selected) { def.baseline(days, selected) }
    val z = if (v != null && base != null) base.z(v) else null
    val tone = zTone(z, def.better)
    val spark = remember(def, days, selected) { days.drop(selected).take(21).reversed().map { def.get(it)?.takeIf { x -> x.isFinite() } } }
    val bg by animateColorAsState(if (open) G.surface3 else G.panel, tween(G.T_BASE), label = "tile")
    val delta = when {
        v == null -> "keine Messung"
        base == null -> "Baseline im Aufbau"
        z == null -> "–"
        def.log -> "${Fmt.signed((v / base.center - 1) * 100)} % · z ${Fmt.signed(z, 1)}"
        else -> "${def.fmtDelta(v - base.center)} · z ${Fmt.signed(z, 1)}"
    }
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .pressable(onClick, haptic = true)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(def.color, 5.dp)
            Spacer(Modifier.width(6.dp))
            Text(def.label.uppercase(), style = T.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        CountUp(v, { def.fmt(it) }, def.unitLabel, T.xl.copy(fontSize = 26.sp))
        Spacer(Modifier.height(2.dp))
        Delta(delta, tone, up = if (z == null || kotlin.math.abs(z) < 0.5) null else z > 0)
        Spacer(Modifier.height(G.s2))
        Sparkline(spark, def.color, Modifier.fillMaxWidth().height(28.dp), band = base?.let { it.lo to it.hi })
    }
}

@Composable
private fun SleepToday(n: SleepNight, sameDay: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val color = G.scoreColor(n.score.toDouble())
    GCard(modifier.pressable(onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(n.score, color, Modifier.size(84.dp), fontSize = 28.sp)
            Spacer(Modifier.width(G.s4))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Label(if (sameDay) "Schlaf" else "Letzte Nacht · ${Fmt.dayShort(n.night)}")
                Text(n.label, style = T.lg.copy(fontSize = 21.sp), color = color)
                Text("${Fmt.time(n.onsetTs)} – ${Fmt.time(n.wakeTs)} · ${Fmt.hm(n.tstMin)} h", style = T.stat, color = G.text2)
            }
            Icon(GIcons.Next, null, tint = G.text3, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.height(G.s4))
        if (n.hypnogram.isNotEmpty()) {
            Hypnogram(n, Modifier.fillMaxWidth().height(84.dp))
            Spacer(Modifier.height(G.s3))
        }
        ShareBar(
            listOf(n.deepMin to G.stageDeep, n.lightMin to G.stageLight, n.remMin to G.stageRem, n.awakeMin to G.stageAwake),
            height = 6.dp,
        )
        Spacer(Modifier.height(G.s2))
        ShareLegend(
            listOf(
                Triple("Tief", Fmt.hm(n.deepMin), G.stageDeep),
                Triple("Leicht", Fmt.hm(n.lightMin), G.stageLight),
                Triple("REM", Fmt.hm(n.remMin), G.stageRem),
                Triple("Wach", Fmt.hm(n.awakeMin), G.stageAwake),
            ),
        )
    }
}

@Composable
private fun TodayRun(run: RunSummary, onClick: () -> Unit, modifier: Modifier) {
    val color = G.runColor(run.category)
    GCard(modifier.pressable(onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Lauf · ${Fmt.time(run.startTs)}", Modifier.weight(1f))
            TypeTag(run.category.label, color)
        }
        Spacer(Modifier.height(G.s3))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                ValueUnit(Fmt.km(run.distanceM), "km", T.xxl)
                Spacer(Modifier.height(G.s3))
                Row(Modifier.fillMaxWidth()) {
                    StatCell("Zeit", Fmt.duration(run.durationS), "", Modifier.weight(1.25f), T.sm.copy(color = G.text1))
                    StatCell("Pace", Fmt.pace(run.paceSPerKm), "", Modifier.weight(1f), T.sm.copy(color = G.text1))
                    StatCell("HF", run.avgHr?.toString() ?: "–", "", Modifier.weight(0.7f), T.sm.copy(color = G.text1))
                }
            }
            Spacer(Modifier.width(G.s4))
            RouteShape(run.preview, color, Modifier.size(88.dp), strokeWidth = 1.5.dp, paddingFraction = 0.1f)
        }
    }
}
