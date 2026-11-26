package de.moritzbelli.gapmin.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** German formatting (Komma, 5:37 /km, 1:02:55). */
object Fmt {
    val DE: Locale = Locale.GERMANY
    private val zone get() = ZoneId.systemDefault()

    fun km(m: Double, digits: Int = 2): String = String.format(DE, "%,.${digits}f", m / 1000)

    fun num(x: Double?, digits: Int = 0): String = if (x == null || !x.isFinite()) "–" else String.format(DE, "%,.${digits}f", x)

    fun duration(s: Double): String {
        val total = s.roundToInt()
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}" else "$m:${sec.toString().padStart(2, '0')}"
    }

    fun pace(sPerKm: Double): String {
        if (!sPerKm.isFinite() || sPerKm <= 0) return "–"
        val m = floor(sPerKm / 60).toInt()
        val s = (sPerKm % 60).roundToInt()
        return if (s == 60) "${m + 1}:00" else "$m:${s.toString().padStart(2, '0')}"
    }

    /** "1:05" hours:minutes from minutes. */
    fun hm(min: Double): String {
        val t = min.roundToInt()
        return "${t / 60}:${(t % 60).toString().padStart(2, '0')}"
    }

    private val dateFmt = DateTimeFormatter.ofPattern("EEE, d. MMM", DE)
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", DE)
    private val dayLong = DateTimeFormatter.ofPattern("EEEE, d. MMMM", DE)
    private val dayShort = DateTimeFormatter.ofPattern("d. MMM", DE)
    private val dateTime = DateTimeFormatter.ofPattern("d. MMM yyyy, HH:mm", DE)

    fun date(ts: Long): String = dateFmt.format(Instant.ofEpochSecond(ts).atZone(zone)).replace("..", ".")
    fun time(ts: Long): String = timeFmt.format(Instant.ofEpochSecond(ts).atZone(zone))
    fun dateTime(ts: Long?): String = if (ts == null) "nie" else dateTime.format(Instant.ofEpochSecond(ts).atZone(zone)).replace("..", ".")
    fun month(ts: Long): String = Instant.ofEpochSecond(ts).atZone(zone).let {
        "${it.month.getDisplayName(TextStyle.FULL_STANDALONE, DE)} ${it.year}"
    }
    fun monthKey(ts: Long): Int = Instant.ofEpochSecond(ts).atZone(zone).let { it.year * 100 + it.monthValue }
    fun hour(ts: Long): Int = Instant.ofEpochSecond(ts).atZone(zone).hour

    fun dayLong(night: String): String = dayLong.format(LocalDate.parse(night))
    fun dayShort(night: String): String = dayShort.format(LocalDate.parse(night)).replace("..", ".")
    fun dayOfMonth(night: String): Int = LocalDate.parse(night).dayOfMonth
    fun weekdayLetter(night: String): String = LocalDate.parse(night).dayOfWeek.getDisplayName(TextStyle.NARROW, DE)

    fun signed(x: Double, digits: Int = 0): String {
        if (abs(x) < 0.5 * Math.pow(10.0, -digits.toDouble())) return "±0"
        return (if (x > 0) "+" else "−") + num(abs(x), digits)
    }
}
