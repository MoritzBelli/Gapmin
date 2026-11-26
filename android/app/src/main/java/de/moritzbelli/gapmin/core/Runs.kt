package de.moritzbelli.gapmin.core

import java.time.Instant
import java.time.ZoneId

/** Classification of runs into categories and German default names. */
enum class RunCategory(val key: String, val label: String) {
    EASY("easy", "Locker"),
    RECOVERY("recovery", "Regeneration"),
    LONG("long", "Langer Lauf"),
    TEMPO("tempo", "Tempo"),
    INTERVALS("intervals", "Intervalle"),
    TRAIL("trail", "Trail");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: EASY
    }
}

object Runs {
    // Gadgetbridge ActivityKind codes (model/ActivityKind.java)
    private val KINDS = mapOf(
        0x10L to "running", 0x20L to "walking", 0x80L to "cycling", 0x100L to "treadmill", 0x800L to "indoor_cycling",
        0x04000001L to "indoor_track", 0x04000067L to "cross_country_running", 0x040000b0L to "indoor_running",
        0x040000b1L to "running", 0x040000b2L to "walking", 0x040000b3L to "cycling", 0x040000f2L to "trail_running",
    )

    fun sportOf(kind: Long?): String = KINDS[kind] ?: "other"

    private val RUN_SPORTS = setOf("running", "trail_running", "treadmill", "indoor_running", "indoor_track", "cross_country_running")
    fun isRun(sport: String?) = sport in RUN_SPORTS

    /**
     * Heuristic, relative to the athlete's own runs:
     * trail by sport, intervals by pace variation, long by distance/duration,
     * tempo / recovery by average HR relative to the highest max HR seen.
     */
    fun classify(sport: String, distanceM: Double?, durationS: Double?, avgHr: Double?, paceCv: Double?, hrMax: Double?): RunCategory {
        if (sport == "trail_running" || sport == "cross_country_running") return RunCategory.TRAIL
        if (paceCv != null && paceCv > 0.16) return RunCategory.INTERVALS
        val km = (distanceM ?: 0.0) / 1000
        val min = (durationS ?: 0.0) / 60
        if (km >= 15 || min >= 85) return RunCategory.LONG
        if (avgHr != null && avgHr > 0 && hrMax != null && hrMax > 0) {
            val rel = avgHr / hrMax
            if (rel >= 0.85) return RunCategory.TEMPO
            if (rel <= 0.74 && km < 9) return RunCategory.RECOVERY
        }
        return RunCategory.EASY
    }

    fun defaultName(category: RunCategory, startTs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val h = Instant.ofEpochSecond(startTs).atZone(zone).hour
        val part = when {
            h < 11 -> "Morgenlauf"
            h < 14 -> "Mittagslauf"
            h < 18 -> "Nachmittagslauf"
            else -> "Abendlauf"
        }
        return when (category) {
            RunCategory.EASY -> "Lockerer $part"
            RunCategory.RECOVERY -> "Regenerationslauf"
            RunCategory.LONG -> "Langer Lauf"
            RunCategory.TEMPO -> "Tempolauf"
            RunCategory.INTERVALS -> "Intervalle"
            RunCategory.TRAIL -> "Traillauf"
        }
    }
}
