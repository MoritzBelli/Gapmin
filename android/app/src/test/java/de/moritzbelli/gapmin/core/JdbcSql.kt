package de.moritzbelli.gapmin.core

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Types

/** JDBC-backed [Sql] for JVM unit tests (sqlite-jdbc). */
class JdbcSql(private val c: Connection) : Sql {
    companion object {
        fun open(file: File?, readOnly: Boolean = false): JdbcSql {
            Class.forName("org.sqlite.JDBC")
            val url = if (file == null) "jdbc:sqlite::memory:" else "jdbc:sqlite:${file.absolutePath}"
            val props = java.util.Properties().apply { if (readOnly) setProperty("open_mode", "1") }
            return JdbcSql(DriverManager.getConnection(url, props)).also { it.c.autoCommit = true }
        }
    }

    private fun bind(ps: PreparedStatement, args: List<Any?>) {
        args.forEachIndexed { i, a ->
            when (a) {
                null -> ps.setNull(i + 1, Types.NULL)
                is Int -> ps.setLong(i + 1, a.toLong())
                is Long -> ps.setLong(i + 1, a)
                is Double -> if (a.isFinite()) ps.setDouble(i + 1, a) else ps.setNull(i + 1, Types.NULL)
                is Float -> ps.setDouble(i + 1, a.toDouble())
                is Boolean -> ps.setLong(i + 1, if (a) 1 else 0)
                is ByteArray -> ps.setBytes(i + 1, a)
                else -> ps.setString(i + 1, a.toString())
            }
        }
    }

    override fun exec(sql: String, args: List<Any?>) {
        val s = sql.trim().uppercase()
        when (s) {
            "BEGIN" -> { c.autoCommit = false; return }
            "COMMIT" -> { c.commit(); c.autoCommit = true; return }
            "ROLLBACK" -> { c.rollback(); c.autoCommit = true; return }
        }
        c.prepareStatement(sql).use { bind(it, args); it.execute() }
    }

    override fun forEach(sql: String, args: List<Any?>, row: (Array<Any?>) -> Unit) {
        c.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeQuery().use { rs ->
                val n = rs.metaData.columnCount
                while (rs.next()) {
                    row(Array(n) { i ->
                        when (val v = rs.getObject(i + 1)) {
                            is Int -> v.toLong()
                            is Float -> v.toDouble()
                            else -> v
                        }
                    })
                }
            }
        }
    }

    override fun prepare(sql: String): Stmt {
        val ps = c.prepareStatement(sql)
        return object : Stmt {
            override fun run(args: List<Any?>) { ps.clearParameters(); bind(ps, args); ps.execute() }
            override fun close() = ps.close()
        }
    }

    override fun close() = c.close()
}
