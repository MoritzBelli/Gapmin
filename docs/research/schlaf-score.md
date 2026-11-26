# Gapmin Schlaf-Score – Recherche und Konzept

Stand: 26.11.2026. Ziel: ein nächtlicher Gesamtscore 0–100 (ähnlich Garmin), der **alle während des Schlafs erhobenen Daten** der Forerunner 265 verarbeitet und nach wissenschaftlicher Evidenz gewichtet. Er ist kein Medizinprodukt und stellt keine Diagnosen.

---

## 1. Rechercheergebnisse

### 1.1 Was „guter Schlaf“ ist: mehrdimensional
- **Buysse (2014), „Sleep health: can we define it?“** definiert Schlafgesundheit über sechs Dimensionen (**Ru-SATED**): Regelmäßigkeit, Zufriedenheit, Wachheit am Tag, Timing, Effizienz und Dauer. Die Uhr misst davon objektiv **Dauer, Effizienz, Timing und Regelmäßigkeit**. Zufriedenheit und Wachheit kann man später als Morgen-Abfrage ergänzen.
- Die Ru-SATED-Items geben konkrete Ziele vor: 7–9 h Schlaf, Schlafmitte zwischen 2 und 4 Uhr, weniger als 30 min wach zwischen Zubettgehen und Aufstehen, gleiche Zeiten jeden Tag.

### 1.2 Dauer
- **AASM/SRS-Konsens (Watson et al. 2015):** Erwachsene sollen regelmäßig **mindestens 7 h** schlafen. Weniger ist mit Gewichtszunahme, Diabetes, Bluthochdruck, Herzkrankheit, Depression, schlechterer Leistung und mehr Unfällen verbunden. Ob mehr als 9 h schadet, ist unklar. Für junge Erwachsene, bei Schlafschuld oder Krankheit kann es angemessen sein.
- **NSF (Hirshkowitz et al. 2015):** **7–9 h** für (junge) Erwachsene, 7–8 h für Ältere.

→ Die Dauer wird stark gewichtet, mit einem Plateau bei 7–9 h. Darunter fällt der Score steil ab, darüber nur leicht.

### 1.3 Kontinuität (Effizienz, Wachzeit, Aufwachen, Einschlafzeit)
- **NSF Sleep Quality Recommendations (Ohayon et al. 2017, Delphi-Konsens auf Basis von 277 Studien):** Experten waren sich einig, dass **Einschlaflatenz, Aufwachphasen > 5 min, Wachzeit nach dem Einschlafen (WASO) und Schlafeffizienz** geeignete Indikatoren guter Schlafqualität sind, über alle Altersgruppen hinweg. Zu Schlafarchitektur (Phasenanteilen) gab es **wenig oder keinen Konsens**.
- Zielwerte guter Qualität: **≥ 85 % Effizienz**, **≤ 30 min** bis zum Einschlafen, **höchstens 1 Aufwachen** pro Nacht, **≤ 20 min wach** nach dem Einschlafen.

→ Die Kontinuität ist die am besten belegte Qualitätsdimension und wird gleich stark wie die Dauer gewichtet.

### 1.4 Schlafphasen (Leicht/Tief/REM)
- **Normwerte (Ohayon et al. 2004, Metaanalyse, 65 Studien, 3.577 Personen):** Bei Erwachsenen nehmen Gesamtschlafzeit, Effizienz, Tiefschlaf- und REM-Anteil mit dem Alter ab, Wachzeit und Leichtschlaf nehmen zu. Klassische Richtwerte für junge bis mittelalte Erwachsene: Tiefschlaf (N3) etwa 15–25 %, REM etwa 20–25 %, Leichtschlaf (N1+N2) etwa 50–60 %.
- **Genauigkeit von Wearables:** In einer Validierung gegen Polysomnographie lag Garmin (Forerunner 245) bei der Unterscheidung von Phasen und Wachsein bei **50 % Übereinstimmung, κ = 0,25** (Miller et al. 2022). Beim Garmin Vivosmart 4 lag Schlaf/Wach bei κ = 0,33, die Phasen nur bei κ = 0,20, mit niedriger Spezifität für Wachphasen (Mouritzen et al. 2020). Auch neuere Geräte erreichen nur κ 0,21–0,53, eine „mäßige“ Übereinstimmung (Schyvens et al. 2025).

→ Die Phasen bekommen trotz Garmins Betonung **wenig Gewicht**: kein Expertenkonsens und schwache Messgenauigkeit. Sie werden nur über Bandbreiten bewertet, nicht über exakte Minuten. Weil Wearables Wachphasen schlecht erkennen (niedrige Spezifität), unterschätzen sie die WASO eher. Das spricht für strenge WASO-Schwellen.

### 1.5 Regelmäßigkeit
- **Windred et al. 2024 (UK Biobank, n ≈ 61.000, Beschleunigungsmessung):** Regelmäßigerer Schlaf ging mit **20–48 % niedrigerem Sterberisiko** einher. Die **Regelmäßigkeit sagte die Sterblichkeit besser vorher als die Schlafdauer.**

- **NSF-Konsens zur Regelmäßigkeit (Sletten et al. 2023, 63 Publikationen, Delphi-Verfahren):** Gleichbleibende Einschlaf- und Aufwachzeiten sind wichtig für Gesundheit, Sicherheit und Leistung. Fehlt unter der Woche Schlaf, kann Nachholschlaf am Wochenende trotzdem sinnvoll sein.

→ Regelmäßigkeit (Schwankung der Einschlaf- und Aufwachzeit über 7 Nächte) wird als eigene Komponente aufgenommen. Garmin berücksichtigt sie im Score nicht. Wegen des Konsenses zum Nachholschlaf wird eine längere Nacht nach Schlafmangel nicht doppelt bestraft: Der Regelmäßigkeits-Teilscore nutzt den Median der Abweichungen, sodass eine einzelne Ausreißernacht wenig Gewicht hat.

### 1.6 Autonome Erholung (HRV, Herzfrequenz, Stress)
- Garmin selbst kombiniert **Dauer, Phasenbalance und nächtliche Erholung über HRV** (Stresswert im Schlaf). Phasen werden aus HF, HRV, Atmung und Bewegung geschätzt (Garmin/Firstbeat; Zusammenfassung the5krunner). Zur Qualität zählen laut Garmin Phasen, Wachzeit, Unruhe und der durchschnittliche Stress im Schlaf. Bewertet wird gegen altersbasierte Standards.
- **HRV-Auswertung (Plews et al. 2012/2013):** Der 7-Tage-Mittelwert von ln(RMSSD) bildet Fehlanpassung an Training besser ab als Einzeltage. Bewertet wird gegen den individuellen **Smallest Worthwhile Change (SWC)**. ln(RMSSD) gilt als zuverlässigste, praxistaugliche Größe für die tägliche Überwachung.
- **Nächtliche Herzfrequenz:** Im Tiefschlaf sinkt sie deutlich, der Tiefpunkt liegt typisch zur Nachtmitte. Bei Menschen ohne nächtlichen Herzfrequenzabfall („non-dipping“) ist das kardiovaskuläre Risiko höher. Eine dauerhaft erhöhte Schlaf-HF hängt oft mit Stress, Krankheit, schlechtem Schlaf oder hoher Trainingsbelastung zusammen.

→ Die autonome Erholung wird wie Dauer und Kontinuität gewichtet, aber **nur relativ zur eigenen Baseline** bewertet. Absolute HRV-Werte unterscheiden sich zwischen Personen zu stark.

### 1.7 Atmung und Sauerstoff
- **Nächtliche Atemfrequenz (Natarajan et al. 2021, n = 10.000):** 90 % der Werte gesunder Erwachsener liegen zwischen 11,8 und 19,2 /min (Mittel 15,4). Bei Infekten (COVID-19) steigt sie messbar an. Ein Anstieg um mehr als 3 /min über das eigene Normal gilt als Warnsignal.
- **SpO2:** Typische Werte im Wachzustand liegen bei 95–99 %, nachts im Mittel etwa 0,8 Prozentpunkte niedriger. Werte unter 92 % in Raumluft auf Meereshöhe sollten abgeklärt werden, anhaltend unter 90 % gilt als auffällig.

→ Atmung und SpO2 sind vor allem **Warnsignale** und tragen wenig zur Qualität bei. Geringes Gewicht, deutlicher Hinweis bei Abweichung.

### 1.8 Ergänzend: Matthew Walker, „Das große Buch vom Schlaf“ (2017/2018)
Das Buch dient nur als **Zusatzinformation** für Erklärtexte, nicht als Grundlage der Gewichtung:
- Walker ordnet guten Schlaf in **Menge, Qualität, Regelmäßigkeit und Timing** ein. Das deckt sich mit Ru-SATED und den Komponenten oben.
- Sein wichtigster Tipp ist ein fester Schlafrhythmus. Das stützt die Regelmäßigkeits-Komponente, die unabhängig davon durch Windred 2024 und Sletten 2023 belegt ist.
- Tiefschlaf liegt vor allem in der ersten Nachthälfte, REM vor allem in der zweiten. Wer zu früh aufsteht, verliert also überproportional REM. Gapmin zeigt diese Verteilung als **Detailwert** an, sie fließt aber nur über die Phasen-Komponente (10 %) ein.
- **Einordnung:** Das Buch ist populärwissenschaftlich und wurde für Fehler und überzogene Aussagen kritisiert (Guzey 2019, diskutiert u. a. von Gelman, Columbia). Wo es den neueren Studien oben widerspricht, gelten die Studien.

---

## 2. Konzept: Gapmin Sleep Score (GSS)

### 2.1 Gewichtung

| Komponente | Gewicht | Evidenz | Eingangsdaten |
|---|---:|---|---|
| **Dauer** | 25 % | stark (AASM, NSF) | Schlafphasen → Gesamtschlafzeit |
| **Kontinuität** | 25 % | stark (NSF-Konsens 2017) | Effizienz, WASO, Aufwachen > 5 min, Einschlaflatenz |
| **Autonome Erholung** | 25 % | mittel–stark, individuell | HRV-Werte, Schlaf-HF, Stress im Schlaf |
| **Phasen** | 10 % | schwach (kein Konsens, κ ≈ 0,25) | Tief-, REM- und Leichtschlafanteil |
| **Regelmäßigkeit** | 10 % | stark für Gesundheit (Windred 2024) | Einschlaf- und Aufwachzeiten, 7 Nächte |
| **Atmung & SpO2** | 5 % | Warnsignal | Atemfrequenz, SpO2 |

Fehlen Daten für eine Komponente (z. B. keine HRV in den ersten Nächten), werden die übrigen Gewichte hochskaliert. Die App zeigt dann eine **Datenabdeckung** an (Anteil der Gewichte mit echten Daten).

### 2.2 Teilscores (je 0–100)

Hilfsfunktion `ramp(x, schlecht, gut)`: linear 0 → 100 zwischen den Schwellen, außerhalb begrenzt.

**Dauer** (Gesamtschlafzeit TST in h)
- 7–9 h → 100
- unter 7 h: `ramp(TST, 4, 7)`, also 6 h ≈ 67, 5 h ≈ 33
- über 9 h: sanft bis 80 bei 11 h (Evidenz für Schaden unklar)

**Kontinuität** = 0,40·Effizienz + 0,30·WASO + 0,20·Aufwachen + 0,10·Latenz
- Effizienz: `ramp(SE, 70 %, 90 %)` (85 % = NSF-Schwelle ≈ 75 Punkte)
- WASO: `ramp(−WASO, −60 min, −10 min)` (20 min ≈ 80 Punkte)
- Aufwachen > 5 min: 0–1 → 100, 2 → 70, 3 → 40, ≥ 4 → 10
- Latenz: `ramp(−SOL, −60, −15)` (30 min ≈ 67). Gering gewichtet, weil Wearables das Einschlafen ungenau erkennen.
- Die Untergrenzen (Score 0) sind eigene Designentscheidungen. Belegt sind nur die Schwellen für gute Qualität.

**Autonome Erholung** = 0,50·HRV + 0,30·Schlaf-HF + 0,20·Stress
- HRV: z-Wert von ln(Nacht-Mittel der HRV) gegen eine 28-Tage-Baseline (Mittel ± SD, mindestens 7 Nächte). `z ≥ +0,5 → 100`, `z = 0 → 80`, `z = −1 → 45`, `z ≤ −2 → 0`. Die ±0,5 SD entsprechen dem SWC-Ansatz nach Plews.
- Schlaf-HF: Abweichung der mittleren Schlaf-HF von der 28-Tage-Baseline in bpm. ≤ 0 → 100, +3 → 70, +6 → 35, ≥ +9 → 0. Zusätzlich wird der **Abfall gegenüber der Tages-HF** gespeichert (Ziel ≥ 10 %).
- Stress im Schlaf (Garmin 0–100, HRV-basiert): `ramp(−Stress, −40, −10)`.
- Bis 7 Nächte Baseline vorliegen, werden HRV und HF über Bevölkerungswerte grob bewertet und als „vorläufig“ markiert.

**Phasen** = Mittel aus drei Bandbewertungen (Abzug pro Prozentpunkt außerhalb des Bandes):
- Tiefschlaf-Anteil 13–23 % (−6 Punkte pro %-Punkt außerhalb)
- REM-Anteil 18–26 % (−5 Punkte pro %-Punkt außerhalb)
- Leichtschlaf-Anteil ≤ 65 % (−4 Punkte pro %-Punkt darüber)

**Regelmäßigkeit** (letzte 7 Nächte inkl. heute; robuste Streuung von Einschlaf- und Aufwachzeit in Minuten, Median der absoluten Abweichung × 1,4826 ≈ SD)
- `ramp(−Streuung, −90, −20)`. Die Grenze von 30 min SD entspricht der in der Sleep-Health-Literatur verwendeten Grenze für „günstige“ Regelmäßigkeit (< 30 min SD, PMC8665047).
- Zusätzlich Hinweis, wenn die Schlafmitte außerhalb von 2–4 Uhr liegt (Ru-SATED-Timing). Das fließt nicht in den Score ein.

**Atmung & SpO2** = 0,6·Atmung + 0,4·SpO2
- Atmung: Abweichung vom eigenen 28-Tage-Mittel. ≤ 1 /min → 100, 3 /min → 40, ≥ 4 /min → 0. Ohne Baseline: innerhalb 11,8–19,2 /min → 100.
- SpO2: Mittel ≥ 95 % → 100, 92 % → 40, ≤ 90 % → 0. Zusätzlich Anteil der Messungen unter 90 % als Hinweis.

### 2.3 Gesamtwert und Einordnung
`GSS = Σ (Gewicht · Teilscore) / Σ Gewichte mit Daten`, gerundet.

| Bereich | Label (wie Garmin) |
|---|---|
| 90–100 | Hervorragend |
| 80–89 | Gut |
| 60–79 | Ausreichend |
| < 60 | Schlecht |

Der von der Uhr gelieferte **Garmin-Sleep-Score** wird separat gespeichert und neben dem GSS angezeigt, damit beide vergleichbar bleiben.

### 2.4 Faktoren-Erklärung
Pro Nacht werden die zwei Komponenten mit dem größten gewichteten Punktverlust als Hinweis ausgegeben, z. B. „Kurz geschlafen (6:12 h)“ oder „HRV 14 % unter deinem Normal“. So ist jede Wertung nachvollziehbar.

### 2.5 Nachtdetails, die gespeichert werden
Zu Bett, Einschlafen, Aufwachen, Aufstehen, Zeit im Bett, Gesamtschlafzeit, Latenz, WASO, Anzahl Aufwachen (gesamt / > 5 min), Minuten und Anteile von Tief/Leicht/REM/Wach, REM-Latenz, Anteil des Tiefschlafs in der ersten und des REM-Schlafs in der zweiten Nachthälfte, Schlaf-HF (Mittel, Minimum, Zeitpunkt des Minimums, Abfall gegenüber dem Tag), HRV (Mittel, Maximum), Stress im Schlaf, Atmung (Mittel/Min/Max), SpO2 (Mittel/Min, Anteil < 90 %), Body-Battery-Ladung, Schlafmitte, Regelmäßigkeit, alle Teilscores, GSS und Garmin-Score.

### 2.6 Grenzen
- Phasen und Wachzeiten stammen aus einem Handgelenk-Algorithmus (κ ≈ 0,2–0,3 gegenüber PSG). Einzelnächte schwanken. Aussagekräftig sind Trends über Wochen.
- Die Schwellen stammen aus Bevölkerungsstudien. Die Teilscores für Erholung, Atmung und HF sind deshalb bewusst **personalisiert** (Baseline).
- Zufriedenheit und Wachheit am Tag (Ru-SATED) fehlen bisher. Geplant ist eine optionale 1-Tap-Morgenabfrage.

---

## 3. Quellen

- Buysse DJ. Sleep health: can we define it? Does it matter? *Sleep* 2014;37(1):9–17. doi:10.5665/sleep.3298
- Watson NF et al. Recommended Amount of Sleep for a Healthy Adult: AASM/SRS Consensus. *Sleep* 2015;38(6):843–844. doi:10.5665/sleep.4716
- Hirshkowitz M et al. National Sleep Foundation's sleep time duration recommendations. *Sleep Health* 2015;1(1):40–43. doi:10.1016/j.sleh.2014.12.010
- Ohayon M et al. National Sleep Foundation's sleep quality recommendations: first report. *Sleep Health* 2017;3(1):6–19. doi:10.1016/j.sleh.2016.11.006
- Ohayon MM, Carskadon MA, Guilleminault C, Vitiello MV. Meta-analysis of quantitative sleep parameters from childhood to old age. *Sleep* 2004;27(7):1255–1273. doi:10.1093/sleep/27.7.1255
- Sletten TL et al. The importance of sleep regularity: a consensus statement of the National Sleep Foundation sleep timing and variability panel. *Sleep Health* 2023;9(6):801–820. doi:10.1016/j.sleh.2023.07.016
- Windred DP et al. Sleep regularity is a stronger predictor of mortality risk than sleep duration. *Sleep* 2024;47(1). PMC10782501
- Miller DJ et al. A Validation of Six Wearable Devices for Estimating Sleep, Heart Rate and HRV in Healthy Adults. *Sensors* 2022;22(16):6317. doi:10.3390/s22166317
- Mouritzen NJ et al. Assessing the performance of a commercial multisensory sleep tracker. *PLoS One* 2020. PMC7732119
- Schyvens AM et al. A performance validation of six commercial wrist-worn wearable sleep-tracking devices. *SLEEP Advances* 2025;6(2):zpaf021. doi:10.1093/sleepadvances/zpaf021
- Plews DJ et al. Heart rate variability in elite triathletes … (7-Tage-ln-RMSSD). *Eur J Appl Physiol* 2012. PubMed 22367011; Plews DJ, Dissertation AUT 2014
- Natarajan A et al. Measurement of respiratory rate using wearable devices and applications to COVID-19 detection. *npj Digit Med* 2021. doi:10.1038/s41746-021-00493-6
- Nächtliche SpO2-Referenzwerte aus Wearables: *npj Digit Med* 2023. doi:10.1038/s41746-023-00851-6
- Herzfrequenz-Dipping: Hilmisson et al., *Front Sleep* 2024. doi:10.3389/frsle.2024.1230958; *J Hypertens* 2025;43(5):790–796
- Ergänzend: Walker M. *Why We Sleep / Das große Buch vom Schlaf.* Scribner 2017 / Goldmann 2018. Kritik: Guzey A. „Matthew Walker's 'Why We Sleep' Is Riddled with Scientific and Factual Errors“, guzey.com, 2019
- Garmin: „How Garmin watches track your sleep, calculate sleep score“ (garmin.com Blog); the5krunner, Garmin Sleep Score
