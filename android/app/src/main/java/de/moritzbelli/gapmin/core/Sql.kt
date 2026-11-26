package de.moritzbelli.gapmin.core

/**
 * Tiny SQLite abstraction so the import and analysis code runs unchanged on Android
 * (bundled SQLite driver) and in JVM unit tests (JDBC).
 * Row values are Long, Double, String, ByteArray or null.
 */
interface Sql {
    fun exec(sql: String, args: List<Any?> = emptyList())
    fun forEach(sql: String, args: List<Any?> = emptyList(), row: (Array<Any?>) -> Unit)
    fun prepare(sql: String): Stmt
    fun close()

    fun query(sql: String, args: List<Any?> = emptyList()): List<Array<Any?>> {
        val out = ArrayList<Array<Any?>>()
        forEach(sql, args) { out.add(it) }
        return out
    }

    fun one(sql: String, args: List<Any?> = emptyList()): Any? {
        var v: Any? = null
        var seen = false
        forEach(sql, args) { if (!seen) { v = it.getOrNull(0); seen = true } }
        return v
    }

    fun <T> transaction(block: () -> T): T {
        exec("BEGIN")
        try {
            val r = block()
            exec("COMMIT")
            return r
        } catch (e: Throwable) {
            runCatching { exec("ROLLBACK") }
            throw e
        }
    }
}

interface Stmt {
    fun run(args: List<Any?>)
    fun close()
}

fun Any?.num(): Double? = when (this) {
    is Long -> toDouble()
    is Int -> toDouble()
    is Double -> takeIf { it.isFinite() }
    is Float -> toDouble()
    is String -> toDoubleOrNull()
    else -> null
}

fun Any?.lng(): Long? = when (this) {
    is Long -> this
    is Int -> toLong()
    is Double -> toLong()
    is String -> toLongOrNull() ?: toDoubleOrNull()?.toLong()
    else -> null
}

fun Any?.str(): String? = when (this) {
    null -> null
    is ByteArray -> String(this)
    else -> toString()
}
