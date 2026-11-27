package de.moritzbelli.gapmin.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Steuert Gadgetbridge per Intent-API (Einstellungen → Entwickleroptionen → Intent API muss aktiv sein):
 * Uhr synchronisieren, Datenbank-Export auslösen und auf die Rückmeldung warten.
 */
class GadgetbridgeLink(private val ctx: Context) {

    enum class Result { OK, TIMEOUT, FAILED }

    /** Fragt die Uhr ab. TIMEOUT heißt: keine Rückmeldung (Uhr nicht verbunden oder Intent-API aus). */
    suspend fun syncWatch(): Result {
        val r = request(listOf(A_SYNC), setOf(A_SYNC_DONE), SYNC_TIMEOUT_MS)
        return if (r == null) Result.TIMEOUT else Result.OK
    }

    /** Löst den Datenbank-Export aus; die Doku nennt zwei Action-Namen, daher der zweite Versuch. */
    suspend fun exportDatabase(): Result {
        val goal = setOf(A_EXPORT_OK, A_EXPORT_FAIL)
        val r = request(listOf(A_EXPORT), goal, 8_000)
            ?: request(listOf(A_EXPORT_ALT), goal, EXPORT_TIMEOUT_MS)
        return when (r) {
            A_EXPORT_OK -> Result.OK
            A_EXPORT_FAIL -> Result.FAILED
            else -> Result.TIMEOUT
        }
    }

    /** Registriert zuerst den Empfänger, sendet dann den Befehl und wartet auf eine der Antworten. */
    private suspend fun request(commands: List<String>, done: Set<String>, timeoutMs: Long): String? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<String?> { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        runCatching { ctx.unregisterReceiver(this) }
                        if (cont.isActive) cont.resume(i.action)
                    }
                }
                val filter = IntentFilter().apply { done.forEach(::addAction) }
                ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
                cont.invokeOnCancellation { runCatching { ctx.unregisterReceiver(receiver) } }
                for (pkg in PACKAGES) for (action in commands) {
                    ctx.sendBroadcast(Intent(action).setPackage(pkg))
                }
            }
        }

    companion object {
        private const val BASE = "nodomain.freeyourgadget.gadgetbridge"
        /** F-Droid/Play-Build und die Nightly-Varianten. */
        val PACKAGES = listOf(BASE, "$BASE.nightly", "$BASE.nightly_nopebble")

        const val A_SYNC = "$BASE.command.ACTIVITY_SYNC"
        const val A_SYNC_DONE = "$BASE.action.ACTIVITY_SYNC_FINISH"
        const val A_EXPORT = "$BASE.command.TRIGGER_EXPORT"
        const val A_EXPORT_ALT = "$BASE.command.TRIGGER_DATABASE_EXPORT"
        const val A_EXPORT_OK = "$BASE.action.DATABASE_EXPORT_SUCCESS"
        const val A_EXPORT_FAIL = "$BASE.action.DATABASE_EXPORT_FAIL"

        private const val SYNC_TIMEOUT_MS = 60_000L
        private const val EXPORT_TIMEOUT_MS = 30_000L
    }
}
