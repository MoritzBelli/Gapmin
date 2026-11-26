# Gapmin Health – Kennzahlen, Referenzen, Auswertung

Stand: 26.11.2026. Grundlage des Reiters **Health**: Stress, Body Battery, HRV, Puls, Aktivität, SpO2 und Atmung der Forerunner 265.
Kein Medizinprodukt, keine Diagnosen.

## 1. Auswertung (gilt für Health und Schlaf)

- **Tageswert**: je Kalendertag in Ortszeit aus den Rohdaten (`minute_samples`, `metric_samples`, `hrv_summary`), siehe `core/Health.kt`.
- **Baseline**: Mittel ± SD der 28 vorherigen Tage bzw. Nächte mit Messwert, mindestens 5. Der gewählte Tag zählt nicht mit.
- **HRV** ist rechtsschief und wird auf **ln-Skala** ausgewertet (Plews et al. 2012/2013). Angezeigt werden das geometrische Mittel und die Streuung als ± %.
- **z = (x − μ) / σ**. Der Balken zeigt z von −3 bis +3, hell hinterlegt ±1 SD, Striche bei ±2 SD.
- **Farben**: grün oder rot, wenn |z| ≥ 0,5 in der durch die Evidenz gegebenen Richtung (z. B. Ruhepuls niedriger = besser). Für Größen ohne Richtung (Atemfrequenz, Puls-Maximum) orange ab |z| ≥ 1,5.
- **Verlauf** (Zeile antippen): 90 Tage, Einzelwerte als Punkte, gleitendes 7-Tage-Mittel als Linie, Baseline-Band, Literaturgrenzen gestrichelt. Dazu n, Median, IQR (Quantil Typ 7), Spannweite, SD, Variationskoeffizient und linearer Trend (OLS, Steigung pro 30 Tage) mit Pearson-r gegen die Zeit.
- **GSS gegen Garmin**: Pearson-r und Bland-Altman (mittlere Differenz ± 1 SD) über die letzten 90 Nächte.

## 2. Kennzahlen und Referenzen

| Größe | Quelle der Daten | Referenz | Richtung |
|---|---|---|---|
| Ruhepuls | Garmin `resting_hr` | 60–100 bpm für Erwachsene (AHA). Ausdauertrainierte liegen oft unter 60. Ein Anstieg um ≥ 5 bpm über das eigene Mittel gilt in der Trainingspraxis als Warnzeichen | niedriger besser |
| Puls Ø, Min, Max | Minutenwerte | – | Ø und Min niedriger besser |
| HRV nachts, 7-Tage-Ø | Garmin HRV-Summary (RMSSD) | individuell. Das 7-Tage-Mittel von ln RMSSD bildet Fehlanpassung besser ab als Einzelnächte, Änderungen < 0,5 SD sind nicht relevant (Plews 2012/2013). Garmins Normalbereich („ausgeglichen“) ist ebenfalls personalisiert | höher besser |
| Stress | Garmin/Firstbeat 0–100 | 0–25 Ruhe, 26–50 niedrig, 51–75 mittel, 76–100 hoch (Garmin). Nur in Ruhe gemessen, aus HRV abgeleitet | niedriger besser |
| Body Battery | Garmin/Firstbeat 0–100 | proprietär, **nicht unabhängig validiert**, nur intraindividuell als Trend. Geladen/verbraucht = Summe der Anstiege/Abfälle innerhalb des Tages | höher besser |
| Schritte | Minutenwerte | Sterberisiko flacht bei 8.000–10.000 Schritten/Tag (< 60 Jahre) bzw. 6.000–8.000 (≥ 60 Jahre) ab (Paluch et al. 2022, Metaanalyse, 15 Kohorten) | höher besser |
| Intensitätsminuten | Garmin moderat/intensiv | Moderat + 2 × intensiv. ≥ 150–300 min/Woche moderat oder 75–150 min intensiv (WHO 2020, Bull et al.). Angezeigt als Tageswert und als gleitende 7-Tage-Summe | höher besser |
| SpO2 | Garmin Pulsoximetrie | 95–99 % in Ruhe; < 92 % abklären, anhaltend < 90 % auffällig. Am Handgelenk bei Bewegung ungenau | höher besser |
| Atemfrequenz | Garmin | nachts 11,8–19,2 /min bei 90 % gesunder Erwachsener (Natarajan et al. 2021). Anstieg > 3 /min über das eigene Normal ist ein Warnsignal | keine (Abweichung zählt) |
| Tragezeit | Minuten mit Puls | unter etwa 20 h sind Tagesmittel weniger vergleichbar | – |

HRV-Status (Gadgetbridge `statusnum`): 1 schlecht, 2 niedrig, 3 unausgeglichen, 4 ausgeglichen.

## 3. Grenzen

- Stress, Body Battery und HRV-Status sind Firstbeat-Algorithmen. Gut für Verläufe einer Person, nicht für Vergleiche zwischen Personen.
- Die Tagesgrenze ist Mitternacht Ortszeit. Nachtwerte (SpO2, Atmung) fallen deshalb zum Teil auf zwei Tage; die nächtlichen Werte pro Schlafphase stehen im Reiter Schlaf.
- Bevölkerungsreferenzen sind Orientierung. Aussagekräftig ist vor allem die Abweichung von der eigenen Baseline.

## 4. Quellen

- Plews DJ et al. Heart rate variability in elite triathletes … *Eur J Appl Physiol* 2012. PubMed 22367011; Plews DJ et al. Training adaptation and heart rate variability in elite endurance athletes. *Sports Med* 2013;43:773–781
- Paluch AE et al. Daily steps and all-cause mortality: a meta-analysis of 15 international cohorts. *Lancet Public Health* 2022;7(3):e219–e228. doi:10.1016/S2468-2667(21)00302-9
- Bull FC et al. World Health Organization 2020 guidelines on physical activity and sedentary behaviour. *Br J Sports Med* 2020;54:1451–1462. doi:10.1136/bjsports-2020-102955
- Natarajan A et al. Measurement of respiratory rate using wearable devices and applications to COVID-19 detection. *npj Digit Med* 2021. doi:10.1038/s41746-021-00493-6
- American Heart Association: „All About Heart Rate (Pulse)“ (heart.org)
- Garmin: Stress-Level- und Body-Battery-Erklärungen (support.garmin.com); Firstbeat Analytics White Papers
