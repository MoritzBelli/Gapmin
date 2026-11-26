package de.moritzbelli.gapmin.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import de.moritzbelli.gapmin.UiState
import de.moritzbelli.gapmin.core.GadgetbridgeImport
import de.moritzbelli.gapmin.core.JdbcSql
import de.moritzbelli.gapmin.core.Repo
import de.moritzbelli.gapmin.core.Schema
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

    private val state: UiState by lazy {
        val demo = listOf("../../web/public/demo/gadgetbridge-demo.zip", "../web/public/demo/gadgetbridge-demo.zip").map(::File).first { it.exists() }
        val db = JdbcSql.open(null)
        Schema.migrate(db)
        GadgetbridgeImport(db, { JdbcSql.open(it, readOnly = true) }, File(System.getProperty("java.io.tmpdir"), "gapmin-shot")).run(demo)
        UiState(loaded = true, runs = Repo.listRuns(db), nights = Repo.listNights(db), stats = Repo.stats(db))
    }

    private val actions = DataActions({}, {}, {}, { _, _, _ -> }, {}, {})

    private fun shot(name: String, tab: Tab, run: String? = null) {
        compose.setContent {
            GapminTheme { GapminApp(state, actions, { _, cb -> cb(emptyList()) }, {}, initialTab = tab, initialRun = run) }
        }
        compose.mainClock.advanceTimeBy(3000)
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val out = File("build/screenshots").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun runs() = shot("1-laeufe", Tab.RUNS)
    @Test fun detail() = shot("2-lauf-detail", Tab.RUNS, state.runs.first().id)
    @Test fun sleep() = shot("3-schlaf", Tab.SLEEP)
    @Test fun data() = shot("4-daten", Tab.DATA)
}
