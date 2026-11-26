@file:OptIn(ExperimentalSharedTransitionApi::class)

package de.moritzbelli.gapmin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import de.moritzbelli.gapmin.Notice
import de.moritzbelli.gapmin.UiState
import de.moritzbelli.gapmin.core.RunCategory
import de.moritzbelli.gapmin.core.TrackSample
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Tab(val label: String, val icon: ImageVector) {
    RUNS("Läufe", GIcons.Run),
    SLEEP("Schlaf", GIcons.Moon),
    DATA("Daten", GIcons.Data),
}

@Composable
fun GapminApp(
    state: UiState,
    actions: DataActions,
    loadTrack: (String, (List<TrackSample>) -> Unit) -> Unit,
    dismissNotice: () -> Unit,
    initialTab: Tab? = null,
    initialRun: String? = null,
) {
    var tab by rememberSaveable { mutableStateOf(initialTab ?: Tab.RUNS) }
    var openRun by rememberSaveable { mutableStateOf(initialRun) }
    var filter by rememberSaveable { mutableStateOf<RunCategory?>(null) }
    var sort by rememberSaveable { mutableStateOf(RunSort.DATE) }
    var night by rememberSaveable { mutableIntStateOf(0) }
    var routedEmpty by rememberSaveable { mutableStateOf(false) }
    val runsList = rememberLazyListState()
    val sleepList = rememberLazyListState()
    val dataList = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // first start without data: go straight to the import
    LaunchedEffect(state.loaded) {
        if (state.loaded && !routedEmpty && initialTab == null) {
            routedEmpty = true
            if (state.stats.activities == 0 && state.stats.nights == 0) tab = Tab.DATA
        }
    }
    LaunchedEffect(state.nights.size) { if (night >= state.nights.size) night = 0 }

    val visible = remember(state.runs, filter, sort) { visibleRuns(state.runs, filter, sort) }

    BackHandler(openRun != null) { openRun = null }
    BackHandler(openRun == null && tab != Tab.RUNS) { tab = Tab.RUNS }

    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomPad = navInset + 104.dp

    Box(Modifier.fillMaxSize().background(G.bg)) {
        SharedTransitionLayout(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = openRun,
                transitionSpec = { fadeIn(tween(G.T_SLOW, easing = G.easeOut)) togetherWith fadeOut(tween(G.T_BASE)) },
                label = "detail",
            ) { runId ->
                val shared = SharedRoute(this@SharedTransitionLayout, this@AnimatedContent)
                if (runId != null && visible.any { it.id == runId }) {
                    RunDetailPager(
                        runs = visible,
                        initialId = runId,
                        shared = shared,
                        loadTrack = loadTrack,
                        onPageRun = { },
                        onClose = { openRun = null },
                    )
                } else {
                    Box(Modifier.fillMaxSize()) {
                        AnimatedContent(
                            targetState = tab,
                            transitionSpec = {
                                (fadeIn(tween(G.T_BASE, delayMillis = 60)) + slideInVertically(tween(G.T_SLOW, easing = G.easeOut)) { it / 40 }) togetherWith
                                    fadeOut(tween(G.T_FAST))
                            },
                            label = "tab",
                        ) { t ->
                            when (t) {
                                Tab.RUNS -> RunsScreen(
                                    state.runs, visible, filter, sort, runsList, bottomPad, shared,
                                    onFilter = { filter = it },
                                    onSort = { sort = it },
                                    onOpen = { openRun = it.id },
                                    onGoToData = { tab = Tab.DATA },
                                )
                                Tab.SLEEP -> SleepScreen(state.nights, night, { night = it }, sleepList, bottomPad, onGoToData = { tab = Tab.DATA })
                                Tab.DATA -> DataScreen(state, actions, dataList, bottomPad)
                            }
                        }
                        // status bar scrim so scrolled content does not collide with the clock
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                                .background(G.bg),
                        )
                        NavPill(
                            tab,
                            onSelect = { t ->
                                if (t == tab) {
                                    // tapping the active tab scrolls to the top, like native apps
                                    scope.launch {
                                        when (t) {
                                            Tab.RUNS -> runsList.animateScrollToItem(0)
                                            Tab.SLEEP -> sleepList.animateScrollToItem(0)
                                            Tab.DATA -> dataList.animateScrollToItem(0)
                                        }
                                    }
                                } else {
                                    tab = t
                                }
                            },
                            busy = state.busy != null,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }
        NoticeBanner(state.notice, state.busy?.step, dismissNotice, Modifier.align(Alignment.TopCenter))
    }
}

@Composable
private fun NavPill(tab: Tab, onSelect: (Tab) -> Unit, busy: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(G.bg.copy(alpha = 0f), G.bg.copy(alpha = 0.92f), G.bg)))
            .navigationBarsPadding()
            .padding(top = G.s6, bottom = G.s3),
        contentAlignment = Alignment.Center,
    ) {
        var itemW by remember { mutableIntStateOf(0) }
        val density = LocalDensity.current
        val indicatorX by animateDpAsState(
            with(density) { (itemW * tab.ordinal).toDp() },
            tween(G.T_SLOW, easing = G.easeOut), label = "indicator",
        )
        Box(
            Modifier
                .clip(CircleShape)
                .background(G.surfaceSolid.copy(alpha = 0.96f))
                .border(1.dp, G.hairlineStrong, CircleShape)
                .padding(4.dp),
        ) {
            // sliding selection indicator
            if (itemW > 0) Box(
                Modifier
                    .offset(x = indicatorX)
                    .width(with(density) { itemW.toDp() })
                    .height(48.dp)
                    .clip(CircleShape)
                    .background(G.surface3),
            )
            Row {
                for (t in Tab.entries) {
                    val on = t == tab
                    Row(
                        Modifier
                            .width(104.dp)
                            .height(48.dp)
                            .onSizeChanged { itemW = it.width }
                            .clip(CircleShape)
                            .pressable({ onSelect(t) }, haptic = true),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        Icon(t.icon, null, tint = if (on) G.accent else G.text3, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t.label, style = T.sm, color = if (on) G.text1 else G.text2)
                        if (t == Tab.DATA && busy) {
                            Spacer(Modifier.width(4.dp))
                            Dot(G.accent, 5.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeBanner(notice: Notice?, busyStep: String?, onDismiss: () -> Unit, modifier: Modifier) {
    var shown by remember { mutableStateOf<Notice?>(null) }
    LaunchedEffect(notice) {
        shown = notice
        if (notice != null) {
            delay(if (notice.ok) 3500 else 6000)
            if (shown == notice) shown = null
        }
    }
    val text = busyStep?.let { "$it …" } ?: shown?.text
    val ok = busyStep != null || shown?.ok != false
    AnimatedVisibility(
        text != null,
        modifier = modifier.statusBarsPadding().padding(G.s3),
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(G.rMd))
                .background(G.surfaceSolid)
                .border(1.dp, if (ok) G.hairlineStrong else G.bad.copy(alpha = 0.5f), RoundedCornerShape(G.rMd))
                .pressable({ shown = null; onDismiss() })
                .padding(horizontal = G.s4, vertical = G.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dot(if (busyStep != null) G.accent else if (ok) G.good else G.bad)
            Spacer(Modifier.width(G.s3))
            Text(text ?: "", style = T.sm, color = G.text1)
        }
    }
}
