package de.moritzbelli.gapmin.core

import java.time.LocalDate

/** Derived analyses for the UI, computed once per refresh (off the main thread). */
data class Insights(
    val training: TrainingSummary? = null,
    /** HRV guidance per health day, newest first (same order as `days`). */
    val hrv: List<HrvGuideDay> = emptyList(),
    /** SRI per night (7 days ending with it). */
    val sri: Map<String, Double> = emptyMap(),
    val garmin: GarminDaily = GarminDaily(),
) {
    companion object {
        fun compute(db: Sql, days: List<HealthDay>, nights: List<SleepNight>, today: Long = LocalDate.now().toEpochDay()): Insights =
            Insights(
                training = Training.summarize(Training.list(db), days, today),
                hrv = HrvGuide.series(days),
                sri = Rhythm.sriSeries(nights),
                garmin = runCatching { Garmin.load(db) }.getOrDefault(GarminDaily()),
            )
    }
}
