package de.moritzbelli.gapmin.data

import android.content.Context
import de.moritzbelli.gapmin.core.Schema
import de.moritzbelli.gapmin.core.Sql
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * The app's local database file. Same name and place as the former Capacitor app
 * (`files/gapmin.sqlite`), so an update keeps all imported data.
 * All access runs on one thread, so reads never see half-written imports.
 */
class AppDatabase(context: Context) {
    val file = File(context.filesDir, "gapmin.sqlite")
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "gapmin-db") }.asCoroutineDispatcher()
    private var sql: Sql? = null

    private fun db(): Sql = sql ?: AndroidSql.open(file).also {
        Schema.migrate(it)
        sql = it
    }

    suspend fun <T> use(block: (Sql) -> T): T = withContext(dispatcher) { block(db()) }

    /** Close, delete the file and start over with an empty database. */
    suspend fun reset() = withContext(dispatcher) {
        sql?.close()
        sql = null
        file.delete()
        File(file.path + "-journal").delete()
        db()
        Unit
    }

    /** Raw bytes of the database file (for the NAS upload), taken while no write is running. */
    suspend fun snapshot(): ByteArray = withContext(dispatcher) {
        db()
        file.readBytes()
    }
}
