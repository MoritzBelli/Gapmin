package de.moritzbelli.gapmin

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.moritzbelli.gapmin.ui.DataActions
import de.moritzbelli.gapmin.ui.GapminApp
import de.moritzbelli.gapmin.ui.GapminTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { !vm.state.value.loaded }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        val actions = DataActions(
            importUri = { vm.importUri(it, remember = true) },
            importDemo = vm::importDemo,
            forgetExport = vm::forgetWatchedExport,
            saveNas = vm::saveNas,
            sync = { vm.syncNow() },
            wipe = vm::wipe,
        )
        setContent {
            GapminTheme {
                val state = vm.state.collectAsStateWithLifecycle().value
                GapminApp(state, actions, vm::track, vm::dismissNotice)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.checkWatchedExport()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** "Öffnen mit" / "Teilen" a Gadgetbridge export from another app. */
    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) vm.importUri(uri, remember = false)
    }
}
