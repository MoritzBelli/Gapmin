# Gapmin Training – Belastung, Laufanalyse, Leistung

Stand: 27.11.2026. Grundlage der Ansicht **Läufe → Analyse** und der Analyse im Lauf-Detail. Alle Kennzahlen werden in der App aus den Rohdaten berechnet (`core/RunAnalysis.kt`, `core/Training.kt`), Werte, die nur Garmin liefert, stehen daneben (`core/GarminDaily.kt`). Kein Medizinprodukt.

---

## 1. Rechercheergebnisse

### 1.1 Trainingsimpuls (TRIMP)
- **Banister-TRIMP** (Banister 1991; Morton, Fitz-Clarke & Banister 1990): Trainingsimpuls = Dauer (min) × HRR × y, mit HRR = (HF − Ruhepuls) / (HFmax − Ruhepuls) und der Gewichtung y = 0,64·e^(1,92·HRR) (Männer) bzw. 0,86·e^(1,67·HRR) (Frauen). Die exponentielle Gewichtung bildet den überproportionalen Anstieg des Blutlaktats mit der Intensität ab.
- Gapmin integriert über die **Zeit in jeder Herzfrequenz** aus dem Track (Histogramm je bpm). Ohne Track wird die Durchschnitts-HF genommen; das unterschätzt Einheiten mit wechselnder Intensität.
- **HFmax** = höchster plausibler Wert aller Aktivitäten (ein einzelner Ausreißer > 8 bpm über dem zweithöchsten wird ignoriert), **Ruhepuls** = Median der letzten 30 Tageswerte. Ohne Messung gelten 190 bzw. 60 bpm, die VO2max-Schätzung entfällt dann.
- Verwendet werden die Koeffizienten für Männer. Für Frauen läge die Last bei hoher Intensität niedriger.

### 1.2 Fitness-Ermüdungs-Modell (CTL / ATL / TSB)
- **Banister et al. (1975)**: Leistung ≈ Fitness − Ermüdung, beide als exponentiell abklingende Antwort auf die Trainingslast. Praxisübliche Zeitkonstanten: **42 Tage** für Fitness (Chronic Training Load), **7 Tage** für Ermüdung (Acute Training Load). Form (Training Stress Balance) = CTL − ATL des Vortags.
- Die Einordnung der Form (z. B. „−10 bis −30 = produktiver Aufbau“) stammt aus der Praxis (Coggan, TrainingPeaks). Sie bezieht sich auf TSS-Einheiten. Weil TRIMP eine andere Skala hat, zeigt Gapmin die Form zusätzlich **in % der Fitness** und ordnet diesen Anteil ein: > +25 % erholt (Fitness sinkt), +5…+25 % frisch, −10…+5 % neutral, −30…−10 % Aufbau, < −30 % hohe Ermüdung. **Nicht validiert**, nur Orientierung.
- Die ersten etwa sechs Wochen einer Historie sind Einschwingzeit (CTL startet bei 0).

### 1.3 Acute:Chronic Workload Ratio (ACWR)
- **Gabbett (2016)**: Verhältnis akuter (1 Woche) zu chronischer Last (4 Wochen). 0,8–1,3 als „Sweet Spot“, > 1,5 als „Danger Zone“ mit erhöhtem Verletzungsrisiko in Mannschaftssportarten.
- **Williams et al. (2017)**: Exponentiell gewichtete gleitende Mittel (EWMA) mit λ = 2/(N+1), N = 7 bzw. 28, statt einfacher Mittel. **Murray et al. (2017)**: Das EWMA-Modell erkannte Laststeigerungen empfindlicher.
- **Kritik (Impellizzeri et al. 2020)**: mathematische Kopplung von Zähler und Nenner, Verhältnisbildung verzerrt, schwache Vorhersagekraft für Verletzungen. → Gapmin zeigt die ACWR nur als **Hinweis auf sprunghafte Steigerungen**, nicht als Risikowert, und erst ab 3 Wochen Historie.

### 1.4 Monotonie und Strain
- **Foster (1998)**: Monotonie = Mittel / SD der täglichen Last einer Woche (Ruhetage zählen als 0), Strain = Wochenlast × Monotonie. Hohe Monotonie (> 2) und hoher Strain gingen mit Infekten und Leistungseinbrüchen einher. Gapmin nutzt die letzten 7 Kalendertage.

### 1.5 Intensitätsverteilung
- **3-Zonen-Modell (Seiler & Kjerland 2006; Seiler 2010)**: Zone 1 unter der ersten Laktat-/Ventilationsschwelle (≈ < 2 mmol/l), Zone 2 zwischen den Schwellen, Zone 3 über der zweiten (≈ > 4 mmol/l). Ausdauerathleten trainieren meist etwa 80 % in Zone 1 (Stöggl & Sperlich 2015).
- Ohne Labortest nähert Gapmin die Schwellen über **82 % und 87 % der HFmax**, wie in der Praxis üblich. Individuelle Schwellen wären genauer.
- **Polarisationsindex (Treff et al. 2019)**: PI = log10(Z1/Z2 · Z3 · 100) mit Zeitanteilen; > 2,00 = polarisiert. Ist Z3 < 1 %, ist PI = 0; Z2 wird mit mindestens 1 % gerechnet (vgl. Treff et al. 2024, aktualisiertes Konzept).
- Einordnung in Gapmin: polarisiert (PI > 2 und Z1 > Z3 > Z2), pyramidal (Z1 > Z2 > Z3), schwellenbetont (Z2 ≥ Z1), grundlagenbetont (Z1 ≥ 90 %), sonst gemischt.
- Zusätzlich 5 Zonen in 10-%-Schritten der HFmax (50–60 … 90–100 %), wie Garmin sie standardmäßig verwendet.

### 1.6 VO2max-Schätzung aus Läufen
- **ACSM-Laufgleichung** (ACSM's Guidelines for Exercise Testing and Prescription): VO2 = 0,2·v + 0,9·v·Steigung + 3,5 ml/kg/min (v in m/min), gültig ab etwa 134 m/min.
- **%HRR ≈ %VO2R (Swain & Leutholtz 1997; Swain 1998)**: Der Anteil der Herzfrequenzreserve entspricht dem Anteil der VO2-Reserve. Daraus: VO2max ≈ 3,5 + (VO2 − 3,5) / HRR.
- Gapmin setzt statt der Rohgeschwindigkeit die **steigungskorrigierte Geschwindigkeit (GAP)** ein (§ 1.8), sodass der Steigungsterm entfällt.
- Nur gleichmäßige Läufe: ≥ 20 min Bewegungszeit, 50–95 % HRR, ≥ 8 km/h, keine Intervalle und keine Trailläufe. Angezeigt wird der **Median der letzten bis zu 8 Läufe aus 90 Tagen** (mind. 3).
- Grenzen: kardiale Drift (§ 1.7), Hitze, Dehydrierung und Müdigkeit erhöhen die HF und senken die Schätzung. Die Schätzung ist ein **Trend**, kein Laborwert, und mit der Garmin-VO2max (Firstbeat) nicht identisch.

### 1.7 Effizienz und Entkopplung
- **Efficiency Factor (EF)**: Geschwindigkeit (m/min) je Herzschlag; ein Praxismaß (Allen & Coggan; Friel). Steigt der EF lockerer Läufe über Wochen, wird die aerobe Ökonomie besser. Gapmin zeigt den EF lockerer, langer und regenerativer Läufe mit linearem Trend (OLS, Signifikanz über 95 %-KI der Steigung).
- **Aerobe Entkopplung (Pa:HR)**: Abfall des EF von der ersten zur zweiten Hälfte nach dem Einlaufen (5 min, ab 30 min Dauer 10 min). Ursache ist die **kardiovaskuläre Drift** (Coyle & González-Alonso 2001): Bei gleicher Belastung steigt die HF, vor allem durch Flüssigkeitsverlust und Wärme. In der Trainingspraxis gelten < 5 % als Zeichen ausreichender aerober Ausdauer für die Dauer (Friel).

### 1.8 Steigungskorrigierte Pace (GAP)
- **Minetti et al. (2002)**: Energiekosten des Laufens in Abhängigkeit der Steigung i, C(i) = 155,4i⁵ − 30,4i⁴ − 43,3i³ + 46,3i² + 19,5i + 3,6 J/kg/m (−45 % … +45 %). Auf der Ebene 3,6 J/kg/m; das Minimum liegt bei etwa −10 %.
- Gapmin gewichtet jeden Abschnitt (≥ 30 m, gegen Höhenrauschen) mit C(i)/C(0) und rechnet daraus die flache Äquivalentdistanz und die GAP.

### 1.9 Bestzeiten und Prognosen
- **Bestzeiten**: schnellster zusammenhängender Abschnitt über 1 km, 5 km, 10 km, Halbmarathon und Marathon in jedem Lauf, auf der Bewegungszeit (Pausen > 2 min und Stehen < 0,3 m/s zählen nicht). Abschnitte schneller als 2:20 /km gelten als GPS-Fehler.
- **Riegel (1981)**: T2 = T1 · (D2/D1)^1,06. Gapmin sagt jede Distanz aus der Bestzeit der letzten 90 Tage über die **ähnlichste andere** Distanz voraus (logarithmischer Abstand). **Vickers & Vertosick (2016)**: Für Freizeitläufer sagt Riegel den Marathon zu schnell voraus; Trainingsabschnitte sind zudem keine Wettkämpfe.
- **VDOT (Daniels & Gilbert 1979)**: VO2(v) = −4,60 + 0,182258·v + 0,000104·v², Anteil der VO2max über die Dauer t (min) = 0,8 + 0,1894393·e^(−0,012778·t) + 0,2989558·e^(−0,1932605·t); VDOT = VO2 / Anteil. Gapmin zeigt den höchsten VDOT der Bestzeiten ≥ 5 km der letzten 90 Tage.

### 1.10 Werte von Garmin
Aus dem Garmin-Connect-Export (nicht aus Gadgetbridge) übernimmt Gapmin, soweit vorhanden: VO2max (Laufen) und Fitnessalter, Trainingsbereitschaft, akute und chronische Last, Trainingsstatus, Rennprognosen, Endurance- und Hill-Score. Die Feldnamen werden aus einer Liste bekannter Namen gesucht; fehlt ein Feld, bleibt der Wert leer. Pro Aktivität zeigt das Detail außerdem den aeroben und anaeroben Trainingseffekt und die Erholungszeit der Uhr.

---

## 2. Darstellung in der App

- **Läufe → Analyse**: Form (CTL, ATL, TSB, 120-Tage-Diagramm mit Tageslast), Belastung (ACWR mit Bereichsskala, akute/chronische Last, Monotonie, Strain, Wochenlast 12 Wochen), Intensität (3 Zonen, PI, 5 Zonen; 28 oder 90 Tage), aerobe Leistung (VO2max-Schätzung mit Verlauf, VDOT, Garmin-VO2max, EF-Trend), Bestzeiten & Prognose, Umfang (16 Wochen).
- **Lauf-Detail → Analyse**: TRIMP, EF, Entkopplung, GAP, VO2-Schätzung, Bewegungszeit, Garmin-Trainingseffekt und Erholung, Zonenverteilung, Splits je km, Bestzeiten des Laufs.
- **Heute → Signale**: ACWR des Tages; Kachel „Trainingslast“ mit der TRIMP-Summe des Tages gegen die 28-Tage-Baseline.

## 3. Grenzen
- Optische HF am Handgelenk ist bei Intervallen und Kälte unzuverlässig; TRIMP und Zonen erben diese Fehler.
- HFmax aus Alltagsläufen liegt meist unter der echten HFmax. Dadurch fallen HRR-Anteile, TRIMP und Zonen eher zu hoch aus, die VO2max-Schätzung eher zu niedrig.
- Alle Schwellen für Form, ACWR und Monotonie sind Orientierungswerte aus Studien mit Leistungssportlern bzw. aus der Praxis.

---

## 4. Quellen

- Banister EW, Calvert TW, Savage MV, Bach T. A systems model of training for athletic performance. *Aust J Sports Med* 1975;7:57–61
- Banister EW. Modeling elite athletic performance. In: MacDougall JD et al. (Hrsg.) *Physiological Testing of the High-Performance Athlete*. Human Kinetics 1991:403–424
- Morton RH, Fitz-Clarke JR, Banister EW. Modeling human performance in running. *J Appl Physiol* 1990;69(3):1171–1177
- Gabbett TJ. The training–injury prevention paradox: should athletes be training smarter and harder? *Br J Sports Med* 2016;50:273–280
- Williams S, West S, Cross MJ, Stokes KA. Better way to determine the acute:chronic workload ratio? *Br J Sports Med* 2017;51:209–210
- Murray NB, Gabbett TJ, Townshend AD, Blanch P. Calculating acute:chronic workload ratios using exponentially weighted moving averages provides a more sensitive indicator of injury likelihood than rolling averages. *Br J Sports Med* 2017;51(9):749–754. doi:10.1136/bjsports-2016-097152
- Impellizzeri FM et al. Acute:chronic workload ratio: conceptual issues and fundamental pitfalls. *Int J Sports Physiol Perform* 2020;15(6):907–913
- Foster C. Monitoring training in athletes with reference to overtraining syndrome. *Med Sci Sports Exerc* 1998;30(7):1164–1168
- Seiler KS, Kjerland GØ. Quantifying training intensity distribution in elite endurance athletes: is there evidence for an "optimal" distribution? *Scand J Med Sci Sports* 2006;16(1):49–56
- Seiler S. What is best practice for training intensity and duration distribution in endurance athletes? *Int J Sports Physiol Perform* 2010;5(3):276–291
- Stöggl TL, Sperlich B. The training intensity distribution among well-trained and elite endurance athletes. *Front Physiol* 2015;6:295. doi:10.3389/fphys.2015.00295
- Treff G, Winkert K, Sareban M, Steinacker JM, Sperlich B. The Polarization-Index: a simple calculation to distinguish polarized from non-polarized training intensity distributions. *Front Physiol* 2019;10:707. doi:10.3389/fphys.2019.00707
- Treff G et al. An updated concept of the polarization index. *Current Issues in Sport Science* 2024
- American College of Sports Medicine. *ACSM's Guidelines for Exercise Testing and Prescription* (Metabolic equations, running)
- Swain DP, Leutholtz BC. Heart rate reserve is equivalent to %VO2 reserve, not to %VO2max. *Med Sci Sports Exerc* 1997;29(3):410–414
- Coyle EF, González-Alonso J. Cardiovascular drift during prolonged exercise: new perspectives. *Exerc Sport Sci Rev* 2001;29(2):88–92
- Minetti AE, Moia C, Roi GS, Susta D, Ferretti G. Energy cost of walking and running at extreme uphill and downhill slopes. *J Appl Physiol* 2002;93:1039–1046
- Riegel PS. Athletic records and human endurance. *American Scientist* 1981;69(3):285–290
- Vickers AJ, Vertosick EA. An empirical study of race times in recreational endurance runners. *BMC Sports Sci Med Rehabil* 2016;8:26
- Daniels J, Gilbert J. *Oxygen Power: Performance Tables for Distance Runners*. Tempe 1979
- Friel J. *The Triathlete's Training Bible*, 3. Aufl. VeloPress 2009 (EF, Entkopplung als Praxismaße)
