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
import de.moritzbelli.gapmin.core.Component
import de.moritzbelli.gapmin.core.Fmt
import de.moritzbelli.gapmin.core.SleepNight
import de.moritzbelli.gapmin.core.Stage
import java.time.Instant
import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.roundToInt

private data class StageInfo(val stage: Int, val label: String, val color: Color)

private val STAGES = listOf(
    StageInfo(Stage.AWAKE, "Wach", G.stageAwake),
    StageInfo(Stage.REM, "REM", G.stageRem),
    StageInfo(Stage.LIGHT, "Leicht", G.stageLight),
    StageInfo(Stage.DEEP, "Tief", G.stageDeep),
)

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
    fun avg(k: Int) = nights.take(k).map { it.score }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = G.s4, end = G.s4, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(G.s3),
    ) {
        item(key = "hero") {
            Hero(
                "Schlaf",
                if (n == null || selected == 0) "Letzte Nacht" else Fmt.dayShort(n.night),
                listOf(
                    Triple("Ø 7 Nächte", avg(7)?.toString() ?: "–", ""),
                    Triple("Ø 28 Nächte", avg(28)?.toString() ?: "–", ""),
                ),
            )
        }
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
        item(key = "score") { ScoreCard(n, selected == 0) }
        item(key = "hypno") { HypnoCard(n) }
        item(key = "components") { ComponentsCard(n) }
        item(key = "timing") {
            MetricCard(
                "Dauer & Timing",
                listOf(
                    Triple("Schlaf", Fmt.hm(n.tstMin), "h"),
                    Triple("Im Bett", Fmt.hm(n.tibMin), "h"),
                    Triple("Eingeschlafen", Fmt.time(n.onsetTs), ""),
                    Triple("Aufgewacht", Fmt.time(n.wakeTs), ""),
                    Triple("Schlafmitte", Fmt.hm(((n.midpointMin % 1440) + 1440) % 1440), "Uhr"),
                    Triple("Regelmäßigkeit", n.regularitySd?.let { "±${it.roundToInt()}" } ?: "–", "min"),
                ),
            )
        }
        item(key = "continuity") {
            MetricCard(
                "Kontinuität",
                listOf(
                    Triple("Effizienz", Fmt.num(n.efficiency * 100), "%"),
                    Triple("Einschlafzeit", Fmt.num(n.solMin), "min"),
                    Triple("Wach nach Einschlafen", Fmt.num(n.wasoMin), "min"),
                    Triple("Aufwachen", n.awakenings.toString(), "${n.awakeningsLong} > 5 min"),
                    Triple("REM-Latenz", Fmt.num(n.remLatencyMin), "min"),
                    Triple("Tiefschlaf 1. Hälfte", n.deepFirstHalfShare?.let { Fmt.num(it * 100) } ?: "–", "%"),
                ),
            )
        }
        item(key = "body") {
            val p = n.physiology
            val hrVs = if (p.hrAvg != null && n.hrMean != null) p.hrAvg - n.hrMean else null
            val hrvVs = if (p.hrvAvg != null && n.lnHrvMean != null) (p.hrvAvg / exp(n.lnHrvMean) - 1) * 100 else null
            MetricCard(
                "Körper",
                listOf(
                    Triple("Schlafpuls Ø", Fmt.num(p.hrAvg), if (hrVs == null) "bpm" else "bpm · ${Fmt.signed(hrVs, 1)}"),
                    Triple("Tiefster Puls", Fmt.num(p.hrMin), p.hrMinTs?.let { "bpm · ${Fmt.time(it)}" } ?: "bpm"),
                    Triple("HRV Ø", Fmt.num(p.hrvAvg), if (hrvVs == null) "ms" else "ms · ${Fmt.signed(hrvVs)} %"),
                    Triple("Pulsabfall vs. Tag", p.hrDip?.let { Fmt.num(it * 100) } ?: "–", "%"),
                    Triple("Stress Ø", Fmt.num(p.stressAvg), ""),
                    Triple("Body Battery", p.bbGain?.let { (if (it >= 0) "+" else "") + Fmt.num(it) } ?: "–", ""),
                    Triple("Atmung Ø", Fmt.num(p.respAvg, 1), "/min"),
                    Triple("SpO2 Ø", Fmt.num(p.spo2Avg), p.spo2Min?.let { "% · min ${Fmt.num(it)}" } ?: "%"),
                ),
                footnote = if (hrVs != null || hrvVs != null) "± Werte: Abweichung von deinem 28-Nächte-Normal" else null,
            )
        }
        item(key = "method") { MethodCard() }
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
private fun ScoreCard(n: SleepNight, latest: Boolean) {
    val color by animateColorAsState(G.scoreColor(n.score.toDouble()), tween(G.T_SLOW), label = "score")
    GCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(n.score, color, Modifier.size(124.dp))
            Spacer(Modifier.width(G.s5))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                Text(n.label, style = T.lg.copy(fontSize = 24.sp), color = color)
                Label(if (latest) Fmt.dayLong(n.night) else "${Fmt.time(n.onsetTs)} – ${Fmt.time(n.wakeTs)}")
                if (n.garminScore != null) Text("Garmin-Score ${n.garminScore}", style = T.sm, color = G.text3)
            }
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
        if (n.provisional) {
            Spacer(Modifier.height(G.s3))
            Text("Vorläufig: Deine persönliche Baseline braucht 7 Nächte.", style = T.sm, color = G.text3)
        }
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
private fun HypnoCard(n: SleepNight) {
    GCard {
        CardHead("Schlafphasen", "${Fmt.time(n.bedTs)} – ${Fmt.time(n.outTs)}")
        Hypnogram(n, Modifier.fillMaxWidth().height(150.dp))
        Spacer(Modifier.height(G.s4))
        Row(Modifier.fillMaxWidth()) {
            for (s in STAGES.reversed()) {
                val min = when (s.stage) {
                    Stage.DEEP -> n.deepMin
                    Stage.LIGHT -> n.lightMin
                    Stage.REM -> n.remMin
                    else -> n.awakeMin
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(s.color)
                        Spacer(Modifier.width(6.dp))
                        Label(s.label)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(Fmt.hm(min), style = T.mdMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Light, fontSize = 18.sp))
                    if (s.stage != Stage.AWAKE && n.tstMin > 0) Text("${(min / n.tstMin * 100).roundToInt()} %", style = T.unit)
                }
            }
        }
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

@Composable
private fun ComponentsCard(n: SleepNight) {
    GCard {
        CardHead("Zusammensetzung", "Gewicht")
        Column(verticalArrangement = Arrangement.spacedBy(G.s3)) {
            for (c in Component.entries) {
                val v = n.components[c]
                val fill by animateFloatAsState(((v ?: 0.0) / 100).toFloat(), tween(G.T_SLOW, easing = G.easeOut), label = "comp")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.label, style = T.sm, color = G.text1, modifier = Modifier.width(112.dp), maxLines = 1)
                    Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(G.surface3)) {
                        Box(Modifier.fillMaxWidth(fill).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(G.scoreColor(v)))
                    }
                    Text(v?.roundToInt()?.toString() ?: "–", style = T.sm, color = G.text1, modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
                    Text("${(c.weight * 100).roundToInt()} %", style = T.unit, modifier = Modifier.width(38.dp), textAlign = TextAlign.End)
                }
            }
        }
        if (n.coverage < 0.999) {
            Spacer(Modifier.height(G.s3))
            Text("Datenabdeckung ${(n.coverage * 100).roundToInt()} %, fehlende Teile werden herausgerechnet.", style = T.sm, color = G.text3)
        }
    }
}

@Composable
private fun MetricCard(title: String, items: List<Triple<String, String, String>>, footnote: String? = null) {
    GCard {
        CardHead(title)
        Column(verticalArrangement = Arrangement.spacedBy(G.s4)) {
            for (row in items.chunked(2)) {
                Row(Modifier.fillMaxWidth()) {
                    for ((k, v, u) in row) {
                        Column(Modifier.weight(1f).padding(end = G.s2)) {
                            Label(k)
                            Spacer(Modifier.height(2.dp))
                            Text(v, style = T.xl.copy(fontSize = 22.sp), maxLines = 1)
                            if (u.isNotEmpty()) Text(u, style = T.unit, maxLines = 1)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (footnote != null) {
            Spacer(Modifier.height(G.s3))
            Text(footnote, style = T.sm, color = G.text3)
        }
    }
}

@Composable
private fun MethodCard() {
    var open by remember { mutableStateOf(false) }
    GCard(Modifier.pressable({ open = !open })) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Wie wird der Score berechnet?", style = T.mdMedium, modifier = Modifier.weight(1f))
            Text(if (open) "–" else "+", style = T.lg, color = G.text3)
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Text(
                "Der Gapmin-Schlafscore gewichtet sechs Bereiche nach wissenschaftlicher Evidenz: Dauer, Kontinuität und " +
                    "Erholung je 25 %, Schlafphasen und Regelmäßigkeit je 10 %, Atmung 5 %. Phasen zählen bewusst wenig, weil " +
                    "Handgelenk-Geräte sie nur mäßig genau erkennen. Erholung und Atmung werden gegen deine eigenen " +
                    "28-Nächte-Werte bewertet. Details und Quellen: docs/research/schlaf-score.md.",
                style = T.sm, color = G.text2, modifier = Modifier.padding(top = G.s3),
            )
        }
    }
}
