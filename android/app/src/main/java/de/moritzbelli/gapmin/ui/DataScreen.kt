package de.moritzbelli.gapmin.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.moritzbelli.gapmin.UiState
import de.moritzbelli.gapmin.core.Fmt
import de.moritzbelli.gapmin.core.ImportProgress
import kotlin.math.roundToInt

class DataActions(
    val importUri: (android.net.Uri) -> Unit,
    val importDemo: () -> Unit,
    val refresh: () -> Unit,
    val forgetExport: () -> Unit,
    val saveNas: (String, String, Boolean) -> Unit,
    val sync: () -> Unit,
    val wipe: () -> Unit,
)

@Composable
fun DataScreen(state: UiState, actions: DataActions, listState: LazyListState, bottomPadding: Dp) {
    val s = state.stats
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(actions.importUri) }
    var confirmWipe by remember { mutableStateOf(false) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = G.s4, end = G.s4, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(G.s3),
    ) {
        item { ScreenHeader("Daten", "Lokal auf dem Gerät · zuletzt ${Fmt.dateTime(s.lastImport)}") }
        item {
            Totals(
                listOf(
                    Triple("Aktivitäten", s.activities.toString(), ""),
                    Triple("Nächte", s.nights.toString(), ""),
                    Triple("Messwerte", Fmt.num(s.samples / 1000.0), "k"),
                ),
            )
        }
        item {
            GCard {
                CardHead("Gadgetbridge-Import", "zuletzt ${Fmt.dateTime(s.lastImport)}")
                Text(
                    "In Gadgetbridge unter Einstellungen → Automatisierungen den Auto-Export (ZIP) aktivieren und hier " +
                        "die Exportdatei wählen. Gapmin merkt sich die Datei und übernimmt neue Daten automatisch, " +
                        "sobald du die App öffnest.",
                    style = T.sm, color = G.text3,
                )
                Spacer(Modifier.height(G.s4))
                ImportProgressBar(state.busy)
                if (state.watchedExport != null) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(G.rMd)).background(G.accentSoft).padding(G.s3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Dot(G.accent)
                        Spacer(Modifier.width(G.s3))
                        Column(Modifier.weight(1f)) {
                            Label("Automatisch", color = G.accent)
                            Text(state.watchedExport, style = T.sm, color = G.text1, maxLines = 1)
                        }
                        TextButton(onClick = actions.forgetExport) { Text("Vergessen", style = T.sm, color = G.text2) }
                    }
                    Spacer(Modifier.height(G.s3))
                }
                if (state.watchedExport != null) {
                    PillButton("Jetzt von der Uhr aktualisieren", Modifier.fillMaxWidth(), primary = true, enabled = state.busy == null, icon = GIcons.Sync, onClick = actions.refresh)
                    Spacer(Modifier.height(G.s2))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(G.s2)) {
                    PillButton(
                        if (state.watchedExport == null) "Export wählen" else "Andere Datei",
                        Modifier.weight(1f), primary = state.watchedExport == null, enabled = state.busy == null, icon = GIcons.Import,
                    ) { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")) }
                    PillButton("Demo-Daten", Modifier.weight(1f), enabled = state.busy == null, onClick = actions.importDemo)
                }
            }
        }
        item { NasCard(state, actions) }
        item {
            GCard {
                CardHead("Speicher")
                Text("Alle Daten liegen lokal auf diesem Gerät in einer SQLite-Datenbank.", style = T.sm, color = G.text3)
                Spacer(Modifier.height(G.s4))
                PillButton("App-Daten löschen", danger = true) { confirmWipe = true }
            }
        }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            containerColor = G.surfaceSolid,
            title = { Text("App-Daten löschen?", style = T.lg) },
            text = { Text("Dein Gadgetbridge-Export und der NAS bleiben unverändert.", style = T.sm) },
            confirmButton = {
                TextButton(onClick = { confirmWipe = false; actions.wipe() }) { Text("Löschen", color = G.bad, style = T.mdMedium) }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) { Text("Abbrechen", color = G.text2, style = T.md) }
            },
        )
    }
}

@Composable
fun ImportProgressBar(busy: ImportProgress?) {
    AnimatedVisibility(busy != null) {
        val p = busy ?: ImportProgress("", 1f)
        val f by animateFloatAsState(p.fraction, tween(G.T_BASE), label = "progress")
        Column(Modifier.padding(bottom = G.s4)) {
            Row {
                Text(p.step, style = T.sm, color = G.text1, modifier = Modifier.weight(1f))
                Text("${(p.fraction * 100).roundToInt()} %", style = T.sm, color = G.text3)
            }
            Spacer(Modifier.height(G.s2))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(G.surface3)) {
                Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(G.accent))
            }
        }
    }
}

@Composable
private fun NasCard(state: UiState, actions: DataActions) {
    var url by rememberSaveable(state.nasUrl) { mutableStateOf(state.nasUrl) }
    var token by rememberSaveable(state.nasToken) { mutableStateOf(state.nasToken) }
    var auto by rememberSaveable(state.autoSync) { mutableStateOf(state.autoSync) }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = G.accent,
        unfocusedBorderColor = G.hairlineStrong,
        focusedTextColor = G.text1,
        unfocusedTextColor = G.text1,
        cursorColor = G.accent,
        focusedLabelColor = G.accent,
        unfocusedLabelColor = G.text3,
        focusedPlaceholderColor = G.text3,
        unfocusedPlaceholderColor = G.text3,
    )
    LaunchedEffect(state.nasUrl, state.nasToken) {
        url = state.nasUrl
        token = state.nasToken
    }
    GCard {
        CardHead("NAS-Server", "zuletzt ${Fmt.dateTime(state.stats.lastSync)}")
        OutlinedTextField(
            url, { url = it }, Modifier.fillMaxWidth(),
            label = { Text("Adresse") }, placeholder = { Text("http://192.168.1.20:8085") },
            singleLine = true, colors = colors, shape = RoundedCornerShape(G.rMd), textStyle = T.md,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Spacer(Modifier.height(G.s2))
        OutlinedTextField(
            token, { token = it }, Modifier.fillMaxWidth(),
            label = { Text("Token") }, placeholder = { Text("GAPMIN_TOKEN aus der .env") },
            singleLine = true, colors = colors, shape = RoundedCornerShape(G.rMd), textStyle = T.md,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Spacer(Modifier.height(G.s3))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Nach jedem Import synchronisieren", style = T.sm, color = G.text1)
                Text("wenn der NAS erreichbar ist (LAN, WireGuard)", style = T.sm, color = G.text3)
            }
            Switch(
                auto, { auto = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = G.onAccent, checkedTrackColor = G.accent,
                    uncheckedThumbColor = G.text3, uncheckedTrackColor = G.surface3, uncheckedBorderColor = G.hairlineStrong,
                ),
            )
        }
        Spacer(Modifier.height(G.s4))
        Row(horizontalArrangement = Arrangement.spacedBy(G.s2)) {
            PillButton("Speichern", Modifier.weight(1f)) { actions.saveNas(url, token, auto) }
            PillButton("Sync starten", Modifier.weight(1f), primary = true, enabled = state.nasUrl.isNotBlank(), icon = GIcons.Sync, onClick = actions.sync)
        }
    }
}
