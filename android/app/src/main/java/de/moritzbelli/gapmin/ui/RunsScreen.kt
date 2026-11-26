@file:OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)

package de.moritzbelli.gapmin.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.moritzbelli.gapmin.core.Fmt
import de.moritzbelli.gapmin.core.RunCategory
import de.moritzbelli.gapmin.core.RunSummary
import kotlinx.coroutines.delay

enum class RunSort(val label: String) { DATE("Neueste"), DISTANCE("Längste"), PACE("Schnellste") }

fun visibleRuns(runs: List<RunSummary>, filter: RunCategory?, sort: RunSort): List<RunSummary> {
    val f = runs.filter { filter == null || it.category == filter }
    return when (sort) {
        RunSort.DATE -> f.sortedByDescending { it.startTs }
        RunSort.DISTANCE -> f.sortedByDescending { it.distanceM }
        RunSort.PACE -> f.sortedBy { if (it.paceSPerKm > 0) it.paceSPerKm else Double.MAX_VALUE }
    }
}

/** Shared-element plumbing: the route flies from tile to detail and back. */
class SharedRoute(val shared: SharedTransitionScope, val anim: AnimatedVisibilityScope)

@Composable
fun Modifier.sharedRoute(id: String, s: SharedRoute?): Modifier =
    if (s == null) this else with(s.shared) {
        this@sharedRoute.sharedElement(rememberSharedContentState("route-$id"), animatedVisibilityScope = s.anim)
    }

@Composable
fun RunsScreen(
    allRuns: List<RunSummary>,
    visible: List<RunSummary>,
    filter: RunCategory?,
    sort: RunSort,
    listState: LazyListState,
    bottomPadding: Dp,
    shared: SharedRoute?,
    onFilter: (RunCategory?) -> Unit,
    onSort: (RunSort) -> Unit,
    onOpen: (RunSummary) -> Unit,
    onGoToData: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cols = (maxWidth / 176.dp).toInt().coerceIn(2, 5)
        val typesPresent = remember(allRuns) { RunCategory.entries.filter { t -> allRuns.any { it.category == t } } }
        val groups = remember(visible, sort, cols) {
            if (sort != RunSort.DATE) listOf(Triple(0, null as String?, visible))
            else visible.groupBy { Fmt.monthKey(it.startTs) }.map { (k, rs) -> Triple(k, Fmt.month(rs.first().startTs), rs) }
        }
        val dist = visible.sumOf { it.distanceM }
        val hours = visible.sumOf { it.durationS } / 3600

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = G.s4, end = G.s4, bottom = bottomPadding),
        ) {
            item(key = "hero") {
                Hero(
                    "Aktivitäten", "Läufe",
                    listOf(
                        Triple("Läufe", visible.size.toString(), ""),
                        Triple("Distanz", Fmt.km(dist, 0), "km"),
                        Triple("Zeit", Fmt.num(hours), "h"),
                    ),
                )
            }
            item(key = "toolbar") {
                Column(Modifier.fillMaxWidth().padding(bottom = G.s2)) {
                    Row(
                        Modifier.fillMaxWidth().bleed(G.s4).horizontalScroll(rememberScrollState()).padding(horizontal = G.s4),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip("Alle", filter == null, null) { onFilter(null) }
                        for (t in typesPresent) FilterChip(t.label, filter == t, G.runColor(t)) { onFilter(t) }
                    }
                    Spacer(Modifier.height(G.s3))
                    Segmented(RunSort.entries.map { it to it.label }, sort, onSort, Modifier.fillMaxWidth())
                }
            }
            if (allRuns.isEmpty()) {
                item(key = "empty") {
                    Column(Modifier.fillMaxWidth().padding(vertical = G.s8), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Noch keine Läufe.", style = T.md, color = G.text2)
                        Spacer(Modifier.height(G.s2))
                        Text(
                            "Importiere deinen Gadgetbridge-Export unter Daten.",
                            style = T.sm, color = G.text3, textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(G.s4))
                        PillButton("Zu Daten", primary = true, onClick = onGoToData)
                    }
                }
            }
            var appearIndex = 0
            for ((key, title, runs) in groups) {
                if (title != null) {
                    stickyHeader(key = "m$key") {
                        MonthHeader(title, runs.size, runs.sumOf { it.distanceM })
                    }
                }
                val rows = runs.chunked(cols)
                for (row in rows) {
                    val start = appearIndex
                    appearIndex += row.size
                    item(key = "r${filter}-${sort}-${row.first().id}") {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = G.s3).animateItem(),
                            horizontalArrangement = Arrangement.spacedBy(G.s3),
                        ) {
                            row.forEachIndexed { i, run ->
                                RunTile(run, start + i, shared, Modifier.weight(1f)) { onOpen(run) }
                            }
                            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthHeader(title: String, count: Int, dist: Double) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to G.bg, 0.8f to G.bg, 1f to G.bg.copy(alpha = 0f)))
            .padding(top = G.s4, bottom = G.s3),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, style = T.mdMedium, modifier = Modifier.weight(1f))
        Text("$count Läufe · ${Fmt.km(dist, 0)} km", style = T.sm, color = G.text3)
    }
}

@Composable
fun RunTile(run: RunSummary, index: Int, shared: SharedRoute?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val color = G.runColor(run.category)
    // staggered entrance: 30 ms per tile, at most 12 tiles deep
    val appear = remember(run.id) { Animatable(0f) }
    LaunchedEffect(run.id) {
        delay(30L * index.coerceAtMost(12))
        appear.animateTo(1f, tween(G.T_SLOW, easing = G.easeOut))
    }
    Column(
        modifier
            .graphicsLayer {
                alpha = appear.value
                translationY = (1 - appear.value) * 14.dp.toPx()
            }
            .clip(RoundedCornerShape(G.rLg))
            .background(G.surface1)
            .border(1.dp, G.hairline, RoundedCornerShape(G.rLg))
            .pressable(onClick)
            .padding(G.s3),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            RouteShape(
                run.preview, color,
                Modifier.fillMaxSize().sharedRoute(run.id, shared),
                strokeWidth = 1.5.dp,
                paddingFraction = 0.1f,
            )
            Box(Modifier.align(Alignment.TopStart).padding(2.dp)) { TypeTag(run.category.label, color) }
        }
        Spacer(Modifier.height(G.s2))
        Text("${Fmt.date(run.startTs)} · ${Fmt.time(run.startTs)}", style = T.label.copy(letterSpacing = T.sm.letterSpacing), color = G.text3, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        ValueUnit(Fmt.km(run.distanceM), "km", T.xl)
        Spacer(Modifier.height(G.s2))
        Box(Modifier.fillMaxWidth().height(1.dp).background(G.hairline))
        Spacer(Modifier.height(G.s2))
        Row(Modifier.fillMaxWidth()) {
            MiniStat("Zeit", Fmt.duration(run.durationS), Modifier.weight(1.25f))
            MiniStat("Pace", Fmt.pace(run.paceSPerKm), Modifier.weight(1f))
            MiniStat("HF", run.avgHr?.toString() ?: "–", Modifier.weight(0.7f))
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Label(label)
        Text(value, style = T.sm, color = G.text1, maxLines = 1)
    }
}
