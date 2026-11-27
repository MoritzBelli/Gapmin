package de.moritzbelli.gapmin.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.moritzbelli.gapmin.core.DaySeries
import de.moritzbelli.gapmin.core.Fmt
import de.moritzbelli.gapmin.core.Rhythm
import de.moritzbelli.gapmin.core.Sample
import de.moritzbelli.gapmin.core.SleepNight
import de.moritzbelli.gapmin.core.Stage
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A sleep segment of the day in minutes after local midnight. */
private class DialSleep(val from: Float, val to: Float, val stage: Int)

private fun stageColor(s: Int) = when (s) {
    Stage.DEEP -> G.stageDeep
    Stage.REM -> G.stageRem
    Stage.LIGHT -> G.stageLight
    else -> G.stageAwake
}

private fun stageName(s: Int) = when (s) {
    Stage.DEEP -> "Tiefschlaf"
    Stage.REM -> "REM"
    Stage.LIGHT -> "Leichtschlaf"
    else -> "wach im Bett"
}

/** Sleep stages of the nights that touch this calendar day, clipped to 00:00–24:00. */
private fun sleepOfDay(date: String, nights: List<SleepNight>, start: Long): List<DialSleep> {
    val next = LocalDate.parse(date).plusDays(1).toString()
    val end = start + 86400
    val out = ArrayList<DialSleep>()
    for (n in nights) {
        if (n.night != date && n.night != next) continue
        val segs = if (n.hypnogram.isNotEmpty()) n.hypnogram.map { Triple(n.bedTs + it.offsetMin * 60L, n.bedTs + (it.offsetMin + it.durMin) * 60L, it.stage) }
        else Rhythm.sleepIntervals(n).map { Triple(it[0], it[1], Stage.LIGHT) }
        for ((a, b, s) in segs) {
            val x0 = maxOf(a, start)
            val x1 = minOf(b, end)
            if (x1 > x0) out.add(DialSleep((x0 - start) / 60f, (x1 - start) / 60f, s))
        }
    }
    return out
}

private fun nearest(xs: List<Sample>, t: Long, maxGap: Long): Sample? {
    var best: Sample? = null
    for (s in xs) if (abs(s.ts - t) <= maxGap && (best == null || abs(s.ts - t) < abs(best.ts - t))) best = s
    return best
}

/** Mean per 10-minute bin of the day (144 bins), null where there is no sample. */
private fun bins(xs: List<Sample>, start: Long, n: Int = 144): Array<Double?> {
    val sum = DoubleArray(n)
    val cnt = IntArray(n)
    val w = 86400 / n
    for (s in xs) {
        val i = ((s.ts - start) / w).toInt()
        if (i in 0 until n) { sum[i] += s.value; cnt[i]++ }
    }
    return Array(n) { if (cnt[it] > 0) sum[it] / cnt[it] else null }
}

/**
 * 24-hour chronogram of one day, midnight at the top, clockwise. Rings from outside in: heart rate (radial bars, 10-min means),
 * stress (Garmin categories), Body Battery (polar line) and sleep stages. Today a hand marks the current time.
 * Tap a ring to read the values at that time (long-press and drag to scrub), tap the centre to reset.
 */
@Composable
fun DayDial(date: String, series: DaySeries?, nights: List<SleepNight>, isToday: Boolean, modifier: Modifier = Modifier) {
    val zone = ZoneId.systemDefault()
    val start = remember(date) { LocalDate.parse(date).atStartOfDay(zone).toEpochSecond() }
    val sleep = remember(date, nights) { sleepOfDay(date, nights, start) }
    val hr = remember(series) { series?.let { bins(it.hr, start) } }
    val stress = remember(series) { series?.let { bins(it.stress, start) } }
    val bb = remember(series) { series?.bb ?: emptyList() }
    val hrLo = remember(hr) { hr?.filterNotNull()?.minOrNull() ?: 40.0 }
    val hrHi = remember(hr) { hr?.filterNotNull()?.maxOrNull() ?: 160.0 }

    val sweep = remember(date) { Animatable(0f) }
    LaunchedEffect(date, series != null) { if (series != null) sweep.animateTo(1f, tween(G.T_HERO, easing = G.easeOut)) }
    val pulse by rememberInfiniteTransition(label = "now").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart), label = "pulse",
    )
    var cursor by remember(date) { mutableStateOf<Float?>(null) }
    val haptic = LocalHapticFeedback.current
    val measurer = rememberTextMeasurer()
    val nowMin = if (isToday) ((System.currentTimeMillis() / 1000 - start) / 60f).coerceIn(0f, 1440f) else null

    // values shown in the centre
    val shownMin = cursor ?: nowMin
    val centre = remember(shownMin, series, sleep) {
        val t = start + ((shownMin ?: 1440f) * 60).toLong().coerceAtMost(86399)
        val bbV = if (shownMin == null) bb.maxOfOrNull { it.value } else nearest(bb, t, 20 * 60)?.value
        DialReadout(
            t = t,
            bb = bbV,
            hr = series?.let { nearest(it.hr, t, 6 * 60)?.value },
            stress = series?.let { nearest(it.stress, t, 10 * 60)?.value },
            stage = shownMin?.let { m -> sleep.firstOrNull { m >= it.from && m < it.to }?.stage },
        )
    }

    Box(modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(date) {
                    fun minuteAt(o: Offset): Float? {
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val r = minOf(size.width, size.height) / 2f
                        val dx = o.x - c.x
                        val dy = o.y - c.y
                        if (hypot(dx, dy) < (r - 24.dp.toPx()) * 0.46f) return null
                        var a = atan2(dx, -dy) // 0 at the top, clockwise
                        if (a < 0) a += (2 * PI).toFloat()
                        return (a / (2 * PI).toFloat() * 1440f)
                    }
                    detectTapGestures(onTap = { o -> cursor = minuteAt(o) })
                }
                .pointerInput(date) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { o ->
                            val c = Offset(size.width / 2f, size.height / 2f)
                            var a = atan2(o.x - c.x, -(o.y - c.y))
                            if (a < 0) a += (2 * PI).toFloat()
                            cursor = a / (2 * PI).toFloat() * 1440f
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDrag = { ch, _ ->
                            ch.consume()
                            val c = Offset(size.width / 2f, size.height / 2f)
                            var a = atan2(ch.position.x - c.x, -(ch.position.y - c.y))
                            if (a < 0) a += (2 * PI).toFloat()
                            cursor = a / (2 * PI).toFloat() * 1440f
                        },
                    )
                },
        ) {
            val c = center
            val R = size.minDimension / 2f - 24.dp.toPx()
            val limit = sweep.value * 1440f
            fun ang(min: Float) = (-90f + min / 1440f * 360f)
            fun pt(min: Float, r: Float): Offset {
                val a = Math.toRadians(ang(min).toDouble())
                return Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat())
            }

            // ring tracks
            val tracks = listOf(0.93f to 0.14f, 0.795f to 0.07f, 0.645f to 0.19f, 0.505f to 0.05f)
            for ((rf, wf) in tracks) drawCircle(G.surface2, R * rf, c, style = Stroke(R * wf))

            // hour ticks and labels
            for (h in 0 until 24) {
                val m = h * 60f
                val major = h % 6 == 0
                drawLine(if (major) G.hairlineStrong else G.hairline, pt(m, R + 2.dp.toPx()), pt(m, R + (if (major) 6 else 4).dp.toPx()), 1.dp.toPx())
                if (major) {
                    val tl = measurer.measure(h.toString().padStart(2, '0'), T.axis)
                    val p = pt(m, R + 15.dp.toPx())
                    drawText(tl, topLeft = Offset(p.x - tl.size.width / 2f, p.y - tl.size.height / 2f))
                }
            }

            // heart rate: radial bars
            hr?.let { bins ->
                val r0 = R * 0.865f
                val band = R * 0.135f
                val w = (2 * PI * R / 144 * 0.48).toFloat()
                for (i in bins.indices) {
                    val v = bins[i] ?: continue
                    val m = i * 10f + 5f
                    if (m > limit) break
                    val f = (((v - hrLo) / (hrHi - hrLo).coerceAtLeast(1.0)).toFloat() * 0.88f + 0.12f)
                    drawLine(G.heart.copy(alpha = 0.35f + 0.6f * f), pt(m, r0), pt(m, r0 + band * f), w, cap = StrokeCap.Round)
                }
            }

            // stress: arc per bin in the Garmin category colour
            stress?.let { bins ->
                val r = R * 0.795f
                val sw = R * 0.07f
                for (i in bins.indices) {
                    val v = bins[i] ?: continue
                    val m = i * 10f
                    if (m > limit) break
                    drawArc(
                        stressColor(v).copy(alpha = 0.9f), ang(m) + 0.35f, 2.5f - 0.7f, false,
                        Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(sw),
                    )
                }
            }

            // Body Battery: polar line inside its band, with a soft fill towards the band's base
            if (bb.size >= 2) {
                val r0 = R * 0.555f
                val band = R * 0.18f
                val line = Path()
                var started = false
                var prev: Long? = null
                // one closed polar area per continuous stretch: out along the values, back along the base circle
                val area = Path()
                val stretch = ArrayList<Float>()
                fun closeArea() {
                    if (stretch.size >= 2) {
                        val a = stretch.first()
                        val b = stretch.last()
                        val p = pt(b, r0)
                        area.lineTo(p.x, p.y)
                        val steps = ((b - a) / 10f).toInt().coerceAtLeast(1)
                        for (k in steps downTo 0) { val q = pt(a + (b - a) * k / steps, r0); area.lineTo(q.x, q.y) }
                        area.close()
                    }
                    stretch.clear()
                }
                for (s in bb) {
                    val m = (s.ts - start) / 60f
                    if (m < 0 || m > 1440f || m > limit) continue
                    val p = pt(m, r0 + band * (s.value / 100f).toFloat())
                    if (!started || (prev != null && s.ts - prev > 3600)) {
                        closeArea()
                        line.moveTo(p.x, p.y)
                        area.moveTo(p.x, p.y)
                    } else {
                        line.lineTo(p.x, p.y)
                        area.lineTo(p.x, p.y)
                    }
                    stretch.add(m)
                    started = true
                    prev = s.ts
                }
                closeArea()
                drawPath(area, G.battery.copy(alpha = 0.10f))
                drawPath(line, G.battery.copy(alpha = 0.18f), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(line, G.battery, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            // sleep stages
            run {
                val r = R * 0.505f
                val sw = R * 0.05f
                for (s in sleep) {
                    if (s.from > limit) continue
                    val to = minOf(s.to, limit)
                    val sweepDeg = (to - s.from) / 1440f * 360f
                    if (sweepDeg <= 0f) continue
                    drawArc(stageColor(s.stage), ang(s.from), maxOf(sweepDeg, 0.4f), false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(sw))
                }
            }

            drawCentreGlow(c, R * 0.46f)
        }
        // overlay: current time and reading cursor; only this small layer redraws for the pulse
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val R = size.minDimension / 2f - 24.dp.toPx()
            fun pt(min: Float, r: Float): Offset {
                val a = Math.toRadians((-90f + min / 1440f * 360f).toDouble())
                return Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat())
            }
            nowMin?.let { m ->
                if (m <= sweep.value * 1440f + 1) {
                    drawLine(G.accent.copy(alpha = 0.55f), pt(m, R * 0.46f), pt(m, R + 2.dp.toPx()), 1.2.dp.toPx(), cap = StrokeCap.Round)
                    val tip = pt(m, R + 2.dp.toPx())
                    drawCircle(G.accent.copy(alpha = 0.35f * (1 - pulse)), 3.dp.toPx() + 9.dp.toPx() * pulse, tip)
                    drawCircle(G.accent, 3.dp.toPx(), tip)
                }
            }
            cursor?.let { m ->
                drawLine(G.text1.copy(alpha = 0.7f), pt(m, R * 0.46f), pt(m, R * 1.0f), 1.dp.toPx())
                drawCircle(G.text1, 2.5.dp.toPx(), pt(m, R * 1.0f))
            }
        }

        AnimatedContent(
            targetState = cursor != null,
            transitionSpec = { fadeIn(tween(G.T_BASE)) togetherWith fadeOut(tween(G.T_FAST)) },
            label = "dialCentre",
        ) { reading ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (reading) {
                    Text(Fmt.time(centre.t), style = T.label, color = G.text1)
                    Spacer(Modifier.height(4.dp))
                    ValueUnit(Fmt.num(centre.hr), "bpm", T.xl.copy(fontSize = 26.sp), color = if (centre.hr == null) G.text3 else G.text1)
                    Text("Stress ${Fmt.num(centre.stress)}", style = T.stat, color = G.text2)
                    Text("BB ${Fmt.num(centre.bb)}", style = T.stat, color = G.text2)
                    centre.stage?.let { Text(stageName(it), style = T.stat, color = stageColor(it)) }
                } else {
                    Text(if (isToday) "BODY BATTERY" else "BB MAX", style = T.label)
                    CountUp(centre.bb, { Fmt.num(it) }, style = T.hero.copy(fontSize = 52.sp), durationMs = G.T_HERO)
                    Text(if (isToday && nowMin != null) "jetzt · ${Fmt.time(System.currentTimeMillis() / 1000)}" else Fmt.dayShort(date), style = T.stat, color = G.text3)
                }
            }
        }
    }
}

private class DialReadout(val t: Long, val bb: Double?, val hr: Double?, val stress: Double?, val stage: Int?)

private fun DrawScope.drawCentreGlow(c: Offset, r: Float) {
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.035f), Color.Transparent), c, r), r, c)
}
