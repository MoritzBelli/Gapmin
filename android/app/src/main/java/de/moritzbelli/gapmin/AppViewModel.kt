package de.moritzbelli.gapmin

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.moritzbelli.gapmin.core.DaySeries
import de.moritzbelli.gapmin.core.Fmt
import de.moritzbelli.gapmin.core.Health
import de.moritzbelli.gapmin.core.HealthDay
import de.moritzbelli.gapmin.core.GadgetbridgeImport
import de.moritzbelli.gapmin.core.ImportProgress
import de.moritzbelli.gapmin.core.Insights
import de.moritzbelli.gapmin.core.RunAnalysis
import de.moritzbelli.gapmin.core.Repo
import de.moritzbelli.gapmin.core.SleepJob
import de.moritzbelli.gapmin.core.RunSummary
import de.moritzbelli.gapmin.core.SleepNight
import de.moritzbelli.gapmin.core.Stats
import de.moritzbelli.gapmin.core.TrackSample
import de.moritzbelli.gapmin.data.AndroidSql
import de.moritzbelli.gapmin.data.AppDatabase
import de.moritzbelli.gapmin.data.GadgetbridgeLink
import de.moritzbelli.gapmin.data.NasSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class Notice(val ok: Boolean, val text: String, val id: Long = System.nanoTime())

data class UiState(
    val loaded: Boolean = false,
    val runs: List<RunSummary> = emptyList(),
    val nights: List<SleepNight> = emptyList(),
    val days: List<HealthDay> = emptyList(),
    /** Training load, run analyses, HRV guidance, sleep regularity, Garmin export values (core/Insights). */
    val insights: Insights = Insights(),
    val stats: Stats = Stats(0, 0, 0, null, null),
    val busy: ImportProgress? = null,
    val notice: Notice? = null,
    val nasUrl: String = "",
    val nasToken: String = "",
    val autoSync: Boolean = true,
    /** Display name of the remembered Gadgetbridge export (re-imported automatically when it changes). */
    val watchedExport: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // nights from an older Garmin import have no stage timeline yet; fill it in from the stored export data
            runCatching { db.use { SleepJob.backfillGarminHypnograms(it) } }
            // runs imported before the track analysis existed get their metrics now (once)
            runCatching { db.use { RunAnalysis.update(it) } }
            refresh()
        }
    }

    private suspend fun refresh() {
        val s = db.use { sql ->
            val nights = Repo.listNights(sql)
            val days = Health.listDays(sql)
            UiState(
                loaded = true,
                runs = Repo.listRuns(sql),
                nights = nights,
                days = days,
                insights = runCatching { Insights.compute(sql, days, nights) }.getOrDefault(Insights()),
                stats = Repo.stats(sql),
                nasUrl = Repo.getSetting(sql, "nas_url"),
                nasToken = Repo.getSetting(sql, "nas_token"),
                autoSync = Repo.getSetting(sql, "auto_sync") != "0",
                watchedExport = Repo.getSetting(sql, "export_name").ifEmpty { null },
            )
        }
        _state.update { s.copy(busy = it.busy, notice = it.notice) }
    }

    fun track(id: String, onResult: (List<TrackSample>) -> Unit) {
        viewModelScope.launch { onResult(db.use { Repo.track(it, id) }) }
    }

    fun daySeries(date: String, onResult: (DaySeries) -> Unit) {
        viewModelScope.launch { onResult(db.use { Health.daySeries(it, date) }) }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    // ---- import --------------------------------------------------------

    /** Import from a picked document; `remember` keeps read access and re-imports it whenever it changes. */
    fun importUri(uri: Uri, remember: Boolean) {
        val ctx = getApplication<Application>()
        if (remember) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        viewModelScope.launch {
            val ok = runImport { dst -> copyUri(uri, dst) }
            if (ok && remember) {
                val name = displayName(uri) ?: "Gadgetbridge-Export"
                val modified = lastModified(uri) ?: 0L
                db.use {
                    Repo.setSetting(it, "export_uri", uri.toString())
                    Repo.setSetting(it, "export_name", name)
                    Repo.setSetting(it, "export_modified", modified.toString())
                }
                refresh()
            }
        }
    }

    fun importDemo() {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            runImport { dst -> ctx.assets.open("demo/gadgetbridge-demo.zip").use { i -> dst.outputStream().use { i.copyTo(it) } } }
        }
    }

    /** Called when the app comes to the foreground: re-import the remembered export if Gadgetbridge rewrote it. */
    fun checkWatchedExport() {
        if (_state.value.busy != null) return
        viewModelScope.launch { importWatchedExport(force = false) }
    }

    /** Liest die gemerkte Exportdatei ein; ohne `force` nur, wenn sie seit dem letzten Import neu geschrieben wurde. */
    private suspend fun importWatchedExport(force: Boolean): Boolean {
        val (uriStr, known) = db.use { Repo.getSetting(it, "export_uri") to (Repo.getSetting(it, "export_modified").toLongOrNull() ?: 0L) }
        if (uriStr.isEmpty()) return false
        val uri = Uri.parse(uriStr)
        val modified = lastModified(uri) ?: if (force) 0L else return false
        if (!force && modified <= known) return false
        val ok = runImport(quietStart = true) { dst -> copyUri(uri, dst) }
        if (ok) db.use { Repo.setSetting(it, "export_modified", modified.toString()) }
        return ok
    }

    /**
     * Uhr synchronisieren → Gadgetbridge exportieren lassen → Export einlesen.
     * Braucht in Gadgetbridge die aktivierte Intent-API und eine bereits gewählte Exportdatei.
     */
    fun refreshFromWatch() {
        if (_state.value.busy != null) return
        viewModelScope.launch {
            val hasExport = db.use { Repo.getSetting(it, "export_uri") }.isNotEmpty()
            if (!hasExport) {
                _state.update { it.copy(notice = Notice(false, "Wähle zuerst die Exportdatei von Gadgetbridge")) }
                return@launch
            }
            val link = GadgetbridgeLink(getApplication())
            _state.update { it.copy(busy = ImportProgress("Uhr synchronisieren", 0.1f), notice = null) }
            val sync = link.syncWatch()
            _state.update { it.copy(busy = ImportProgress("Gadgetbridge exportiert", 0.5f)) }
            val export = link.exportDatabase()
            _state.update { it.copy(busy = null) }
            when (export) {
                GadgetbridgeLink.Result.OK -> {
                    val ok = importWatchedExport(force = true)
                    if (ok && sync == GadgetbridgeLink.Result.TIMEOUT) {
                        _state.update { it.copy(notice = Notice(true, "Export eingelesen, die Uhr hat aber nicht geantwortet (verbunden?)")) }
                    }
                }
                GadgetbridgeLink.Result.FAILED ->
                    _state.update { it.copy(notice = Notice(false, "Gadgetbridge konnte nicht exportieren")) }
                GadgetbridgeLink.Result.TIMEOUT ->
                    _state.update {
                        it.copy(notice = Notice(false, "Keine Antwort von Gadgetbridge. In Gadgetbridge unter Entwickleroptionen die Intent API aktivieren."))
                    }
            }
        }
    }

    fun forgetWatchedExport() {
        viewModelScope.launch {
            val uri = db.use { Repo.getSetting(it, "export_uri") }
            if (uri.isNotEmpty()) runCatching {
                getApplication<Application>().contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            db.use { for (k in listOf("export_uri", "export_name", "export_modified")) Repo.setSetting(it, k, "") }
            refresh()
        }
    }

    private suspend fun runImport(quietStart: Boolean = false, source: (File) -> Unit): Boolean {
        if (_state.value.busy != null) return false
        val ctx = getApplication<Application>()
        _state.update { it.copy(busy = ImportProgress(if (quietStart) "Neuer Export gefunden" else "Starte", 0f), notice = null) }
        return try {
            val src = File(ctx.cacheDir, "import-source")
            withContext(Dispatchers.IO) { source(src) }
            val r = db.use { sql ->
                GadgetbridgeImport(sql, { AndroidSql.open(it, readOnly = true) }, File(ctx.cacheDir, "import"))
                    .run(src) { p -> _state.update { it.copy(busy = p) } }
            }
            _state.update { it.copy(busy = ImportProgress("Läufe analysieren", 0.95f)) }
            db.use { RunAnalysis.update(it) }
            src.delete()
            refresh()
            _state.update {
                it.copy(
                    busy = null,
                    notice = Notice(
                        true,
                        "${r.activities} Aktivitäten, ${Fmt.num(r.samples.toDouble())} Messwerte, ${r.nights} Nächte in ${Fmt.num(r.ms / 1000.0, 1)} s",
                    ),
                )
            }
            val s = _state.value
            if (s.nasUrl.isNotBlank() && s.autoSync) sync(silent = true)
            true
        } catch (e: Exception) {
            _state.update { it.copy(busy = null, notice = Notice(false, e.message ?: e.javaClass.simpleName)) }
            false
        }
    }

    private fun copyUri(uri: Uri, dst: File) {
        val cr = getApplication<Application>().contentResolver
        (cr.openInputStream(uri) ?: error("Datei kann nicht gelesen werden")).use { i -> dst.outputStream().use { i.copyTo(it) } }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun lastModified(uri: Uri): Long? = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    // ---- NAS -----------------------------------------------------------

    fun saveNas(url: String, token: String, autoSync: Boolean) {
        viewModelScope.launch {
            db.use {
                Repo.setSetting(it, "nas_url", url.trim())
                Repo.setSetting(it, "nas_token", token.trim())
                Repo.setSetting(it, "auto_sync", if (autoSync) "1" else "0")
            }
            refresh()
            if (url.isBlank()) return@launch
            val ok = NasSync.ping(url)
            _state.update { it.copy(notice = Notice(ok, if (ok) "Server erreichbar" else "Server nicht erreichbar, Einstellungen trotzdem gespeichert")) }
        }
    }

    fun syncNow() = viewModelScope.launch { sync(silent = false) }

    private suspend fun sync(silent: Boolean) {
        val s = _state.value
        if (!silent) _state.update { it.copy(notice = Notice(true, "Synchronisiere …")) }
        val r = NasSync.upload(s.nasUrl, s.nasToken, db.snapshot())
        if (r.ok) db.use { it.exec("INSERT OR REPLACE INTO meta (key, value) VALUES ('last_sync', ?)", listOf((System.currentTimeMillis() / 1000).toString())) }
        refresh()
        _state.update {
            it.copy(notice = Notice(r.ok, if (r.ok) "NAS synchronisiert (${Fmt.num((r.rows ?: 0).toDouble())} Zeilen)" else r.message))
        }
    }

    // ---- storage -------------------------------------------------------

    fun wipe() {
        viewModelScope.launch {
            forgetWatchedExport()
            db.reset()
            refresh()
            _state.update { it.copy(notice = Notice(true, "App-Daten gelöscht")) }
        }
    }
}
