package de.moritzbelli.gapmin.core

/**
 * Minimal JSON reader/writer (no dependencies, so the core also runs in plain JVM tests).
 * Values: Map<String, Any?>, List<Any?>, Double, String, Boolean, null.
 */
object Json {
    fun parse(text: String?): Any? {
        if (text.isNullOrBlank()) return null
        return try {
            Parser(text).run { val v = value(); v }
        } catch (_: Exception) {
            null
        }
    }

    fun write(v: Any?): String = StringBuilder().also { write(v, it) }.toString()

    private fun write(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is String -> str(v, sb)
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v)
            is Number -> {
                val d = v.toDouble()
                if (!d.isFinite()) sb.append("null")
                else if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) sb.append(d.toLong())
                else sb.append(d)
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    str(k.toString(), sb); sb.append(':'); write(x, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(x, sb)
                }
                sb.append(']')
            }
            is DoubleArray -> write(v.toList(), sb)
            is IntArray -> write(v.toList(), sb)
            else -> str(v.toString(), sb)
        }
    }

    private fun str(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> if (c == '-' || c.isDigit()) number() else error("bad json at $i")
            }
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = string(); ws(); i++ // ':'
                m[k] = value(); ws()
                if (s[i++] == '}') return m
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l.add(value()); ws()
                if (s[i++] == ']') return l
            }
        }
        fun string(): String {
            val sb = StringBuilder()
            i++ // opening quote
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }
    }
}

fun Any?.asMap(): Map<String, Any?>? = this as? Map<String, Any?>
fun Any?.asList(): List<Any?>? = this as? List<Any?>
fun Any?.asDouble(): Double? = when (this) {
    is Number -> toDouble().takeIf { it.isFinite() }
    is String -> toDoubleOrNull()?.takeIf { it.isFinite() }
    else -> null
}
