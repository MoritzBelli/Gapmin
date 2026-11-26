package de.moritzbelli.gapmin.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import de.moritzbelli.gapmin.core.Sql
import de.moritzbelli.gapmin.core.Stmt
import java.io.File

/** [Sql] on top of the bundled SQLite driver (same SQLite version on every phone). */
class AndroidSql private constructor(private val conn: SQLiteConnection) : Sql {
    companion object {
        private const val OPEN_READONLY = 0x01
        private const val OPEN_READWRITE = 0x02
        private const val OPEN_CREATE = 0x04
        private val driver = BundledSQLiteDriver()

        fun open(file: File, readOnly: Boolean = false): AndroidSql =
            AndroidSql(driver.open(file.absolutePath, if (readOnly) OPEN_READONLY else OPEN_READWRITE or OPEN_CREATE))

        // sqlite3 column type codes
        private const val INTEGER = 1
        private const val FLOAT = 2
        private const val BLOB = 4
        private const val NULL = 5
    }

    private fun SQLiteStatement.bindAll(args: List<Any?>) {
        args.forEachIndexed { i, a ->
            val k = i + 1
            when (a) {
                null -> bindNull(k)
                is Int -> bindLong(k, a.toLong())
                is Long -> bindLong(k, a)
                is Double -> if (a.isFinite()) bindDouble(k, a) else bindNull(k)
                is Float -> bindDouble(k, a.toDouble())
                is Boolean -> bindLong(k, if (a) 1L else 0L)
                is ByteArray -> bindBlob(k, a)
                else -> bindText(k, a.toString())
            }
        }
    }

    override fun exec(sql: String, args: List<Any?>) {
        conn.prepare(sql).use { st ->
            st.bindAll(args)
            while (st.step()) { /* drain */ }
        }
    }

    override fun forEach(sql: String, args: List<Any?>, row: (Array<Any?>) -> Unit) {
        conn.prepare(sql).use { st ->
            st.bindAll(args)
            while (st.step()) {
                val n = st.getColumnCount()
                row(Array(n) { i ->
                    when (st.getColumnType(i)) {
                        NULL -> null
                        INTEGER -> st.getLong(i)
                        FLOAT -> st.getDouble(i)
                        BLOB -> st.getBlob(i)
                        else -> st.getText(i)
                    }
                })
            }
        }
    }

    override fun prepare(sql: String): Stmt {
        val st = conn.prepare(sql)
        return object : Stmt {
            override fun run(args: List<Any?>) {
                st.reset()
                st.clearBindings()
                st.bindAll(args)
                while (st.step()) { /* drain */ }
            }
            override fun close() = st.close()
        }
    }

    override fun close() = conn.close()
}
