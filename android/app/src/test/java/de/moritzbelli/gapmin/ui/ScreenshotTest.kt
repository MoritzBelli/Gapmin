package de.moritzbelli.gapmin.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import de.moritzbelli.gapmin.UiState
import de.moritzbelli.gapmin.core.GadgetbridgeImport
import de.moritzbelli.gapmin.core.Health
import de.moritzbelli.gapmin.core.Insights
import de.moritzbelli.gapmin.core.RunAnalysis
import de.moritzbelli.gapmin.core.JdbcSql
import de.moritzbelli.gapmin.core.Repo
import de.moritzbelli.gapmin.core.Schema
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the main screens with the demo export on a phone-sized screen and writes PNGs to
 * build/screenshots (CI publishes them). Run: ./gradlew testDebugUnitTest --tests '*ScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w392dp-h850dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: de.moritzbelli.gapmin.core.Sql

    private val state: UiState by lazy {
        val demo = listOf("../../web/public/demo/gadgetbridge-demo.zip", "../web/public/demo/gadgetbridge-demo.zip").map(::File).first { it.exists() }
        db = JdbcSql.open(null)
        Schema.migrate(db)
        GadgetbridgeImport(db, { JdbcSql.open(it, readOnly = true) }, File(System.getProperty("java.io.tmpdir"), "gapmin-shot")).run(demo)
        RunAnalysis.update(db)
        val nights = Repo.listNights(db)
        val days = Health.listDays(db, 100_000)
        val runs = Repo.listRuns(db)
        // the demo ends on a fixed date: evaluate training load as of the last run, not as of the build day
        val last = java.time.Instant.ofEpochSecond(runs.maxOf { it.startTs }).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
        UiState(
            loaded = true, runs = runs, nights = nights, days = days, stats = Repo.stats(db),
            insights = Insights.compute(db, days, nights, last),
        )
    }

    private val actions = DataActions({}, {}, {}, {}, { _, _, _ -> }, {}, {})

    private fun shot(name: String, tab: Tab, run: String? = null) = render(name) {
        GapminApp(state, actions, { _, cb -> cb(emptyList()) }, { d, cb -> cb(Health.daySeries(db, d)) }, {}, initialTab = tab, initialRun = run)
    }

    private fun render(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { GapminTheme { content() } }
        compose.mainClock.advanceTimeBy(3000)
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val out = File("build/screenshots").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun today() = shot("0-heute", Tab.TODAY)
    @Test fun runs() = shot("1-laeufe", Tab.RUNS)
    @Test fun detail() = shot("2-lauf-detail", Tab.RUNS, state.runs.first().id)
    @Test fun sleep() = shot("3-schlaf", Tab.SLEEP)
    @Test fun trends() = shot("4-trends", Tab.TRENDS)
    @Test fun trendsSleep() = render("6-trends-schlaf") {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize().background(G.bg).padding(16.dp),
        ) { SleepTrends(state.nights, 0) }
    }
    @Test fun trendsHealth() = render("7-trends-health") {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize().background(G.bg).padding(16.dp),
        ) { HealthTrends(state.days, 0) }
    }
    @Test fun data() = shot("5-daten", Tab.DATA)
    @Test fun analysis() = render("8-laeufe-analyse") {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize().background(G.bg).padding(16.dp),
        ) {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                TrainingAnalysis(state.insights.training, state.insights.garmin, rememberReveal()) {}
            }
        }
    }
    @Test fun todayLower() = render("9-heute-kennzahlen") {
        val list = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = 3)
        TodayScreen(
            state.days, state.nights, state.runs, state.insights, 0, {}, { d, cb -> cb(Health.daySeries(db, d)) }, list, 120.dp,
            {}, {}, {}, {}, {},
        )
    }
    @Test fun runDetailLower() = render("10-lauf-analyse") {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(G.bg).padding(16.dp)) {
            RunAnalysisCard(state.runs[1], state.insights.training?.hr)
        }
    }
}
