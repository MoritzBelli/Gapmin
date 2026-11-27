# Gapmin Design System (v2, „Instrument“)

Extrem modern, minimalistisch, komplett dunkel, wissenschaftlich. Die Daten sind der Inhalt, die Oberfläche tritt zurück.
v2 (0.4): rahmenlose Flächen, Monospace als „Instrumentenschicht“ für Beschriftungen, Achsen und Statistik, Bildschirmtitel,
gestaffelte Einblendungen, zählende Kennzahlen, ein 24-Stunden-Chronogramm als Startbild.
Alle Werte stehen in der App in [`ui/Theme.kt`](../android/app/src/main/java/de/moritzbelli/gapmin/ui/Theme.kt) (`G` für Farben, Abstände, Radien, Bewegung, `T` für Schrift), in der Web-Version als CSS-Variablen in [`web/src/styles/tokens.css`](../web/src/styles/tokens.css). Neue Komponenten verwenden **nur** diese Tokens, keine freien Hex-Werte, Abstände oder Zeiten.

## Prinzipien

1. **Daten zuerst.** Farbe ist für Daten reserviert. Die Oberfläche selbst ist schwarz, weiß und grau.
2. **Eine Akzentfarbe.** `--accent` (Volt-Gelb) nur für Marke, Fokus und aktive Auswahl. Nie für Diagramme.
3. **Ruhe durch Weglassen.** Keine Rahmen um Karten, keine Schatten-Stapel, keine Verläufe auf Flächen, keine Icons ohne Funktion. Gruppen entstehen durch eine leicht hellere Fläche (`panel`) und Abstand. Glow gibt es nur an Datenlinien.
4. **Große Zahlen, leichte Schrift.** Kennzahlen sind das Gestaltungselement: groß, `--fw-light`, tabellarische Ziffern, Einheit klein und gedimmt.
5. **Bewegung erklärt.** Animation zeigt, woher etwas kommt (Kachel → Detail), nie Dekoration. Weich, kurz, abschaltbar (`prefers-reduced-motion`).
6. **Eine Ebene tief.** Bereiche oben (mobil unten), Inhalt, Detail als Overlay. Kein Menü im Menü. Alles per Tastatur bedienbar.
7. **Erklärungen nur auf Wunsch.** Auf den Karten stehen Daten, keine Legenden, Methodik oder Fußnoten. Alles, was erklärt statt misst, liegt hinter einem „i“ (`InfoButton`/`InfoPanel`, `CardHead(info = …)`) und klappt erst beim Antippen auf.
8. **Maximale Auswertung, minimale Fläche.** Jede Ansicht zeigt eine Hauptzahl und darunter verdichtete Nebenwerte. Mehr Tiefe kommt durch Antippen, nicht durch mehr Elemente.
9. **Zwei Schriftschichten.** Inter trägt Werte und Text, JetBrains Mono (ohne Ligaturen) trägt alles, was misst oder beschriftet: Abschnittstitel in Großbuchstaben, Achsen, n/Ø/SD, Δ und z. So liest sich jede Karte wie ein Messinstrument.
10. **Signale statt Superscore.** Unabhängige Größen (HRV, Belastung, Schlaf) stehen nebeneinander und werden nicht ohne Evidenz zu einer Zahl verrechnet.

## Farben

| Rolle | Token | Verwendung |
|---|---|---|
| Hintergrund | `--bg` `#060708` | Fast-Schwarz, OLED-freundlich |
| Flächen | `panel`, `--surface-1/2/3` | `panel` (3,4 %) für Karten und Kacheln ohne Rahmen; 3 / 5 / 8 % = Ebene 1 / Spur / aktiv |
| Linien | `--hairline`, `--hairline-strong` | 1-px-Trenner und Konturen |
| Text | `--text-1/2/3` | Werte / Sekundär / Labels und Einheiten |
| Akzent | `--accent` | Fokus, aktiver Zustand, Marke |
| Lauftypen | `--run-*` | Neon-Kategoriefarben, einzige satte Farben der App |
| Glow | `--glow-strength` | Halo unter Datenlinien (0,45, Hover 0,85) |
| HF-Zonen | `--zone-1 … --zone-5` | sequentiell kühl → heiß |
| Messgrößen | `heart`, `hrvColor`, `battery`, `activity`, `oxygen`, `breath`, `sleepColor` | eine Farbe pro physiologischer Größe (Punkt, Linie, Band) |
| Stresskategorien | `stressRest/Low/Med/High` | Garmin-Bereiche 0–25 / 26–50 / 51–75 / 76–100 |
| Trends | `--good`, `--bad`, `--neutral`, `caution` | Veränderungen gegenüber Baseline; `caution` für Bereiche ohne Wertung (z. B. ACWR 1,3–1,5, HRV über Normal) |
| Trainingslast | `fitness`, `fatigue`, `form` | CTL, ATL, TSB |

Regel für Kategoriefarben: Sie erscheinen als kleiner Punkt (6 px) oder als leuchtende Datenlinie (Linie + weichgezeichneter Halo), nie als Fläche oder Textfarbe. So leuchten die Daten auf einer sonst schwarz-grauen Oberfläche.

## Typografie

Inter für Werte und Text, JetBrains Mono NL für die Instrumentenschicht, tabellarische Ziffern überall.

| Stil (`T.`) | Schrift | Größe | Einsatz |
|---|---|---|---|
| `hero` | Inter Light | 64 (Karten 44–52) | Schlafscore, Body Battery, ACWR, SRI, VO2max |
| `title` | Inter Light | 30 | Bildschirmtitel (`ScreenHeader`) |
| `xxl` / `xl` | Inter Light | 30 / 24 (Kacheln 20–26) | Kennzahlen |
| `lg` | Inter Medium | 20 | Einordnung („Gut“, „Pyramidal“) |
| `md` / `sm` | Inter | 15 / 13 | Fließtext, Bedienelemente |
| `label` | Mono Medium | 10, Laufweite 0,08 em, GROSS | Abschnittstitel, Wertbeschriftungen |
| `stat` | Mono | 11 | Statistik, Meta-Zeilen, Δ und z |
| `axis` | Mono | 9 | Diagrammachsen |

Zahlen: Einheit als `ValueUnit` klein und gedimmt. Formatierung immer `de-DE` (Komma, `5:37 /km`, `1:02:55`), siehe `core/Format.kt`.

## Raster, Form

- Abstände im 4-px-Raster: `--s-1` (4) bis `--s-8` (64).
- Radien: `--r-sm` 10 (Fokus), `--r-md` 16, `--r-lg` 24 (Kacheln, Overlays), `--r-full` (Pillen).
- Inhaltsbreite max. `--content-max` 1320 px. App: 2 Spalten (breitere Geräte mehr), Navigation als schwebende Pille unten, Inhalte randlos unter Status- und Navigationsleiste.

## Bewegung

| Token | Dauer | Einsatz |
|---|---|---|
| `T_FAST` | 120 ms | Druck, Ausblenden |
| `T_BASE` | 220 ms | Zustandswechsel, Farben |
| `T_SLOW` | 420 ms | Overlay, Aufklappen, Einblenden |
| `T_DRAW` | 900 ms | Linien zeichnen sich ein, Zahlen zählen hoch |
| `T_HERO` | 1400 ms | Chronogramm-Sweep, Score-Ring, Skalenmarker |

- Standardkurve `easeOut`, Positionswechsel `easeInOut`; Navigation und Segmented Control gleiten mit einer weichen Feder (Dämpfung ≈ 0,8).
- **Gestaffeltes Einblenden** (`Modifier.reveal`): Abschnitte erscheinen von unten mit 45 ms Versatz (max. 8 Stufen), nur beim ersten Erscheinen, nicht beim Zurückscrollen.
- **Zählende Zahlen** (`CountUp`): Kennzahlen zählen beim ersten Erscheinen hoch und gleiten bei neuen Werten.
- Kacheln der Galerie erscheinen gestaffelt (30 ms pro Kachel, max. 12). Balken der Tag- und Nachtleiste wachsen von unten.
- Kachel → Detail per Shared Element: Die Strecke „fliegt“ von der Kachel ins Detail und zurück.
- Strecken, Linien, Balken und Ringe zeichnen sich beim ersten Erscheinen ein. Dauerhafte Animation nur am Jetzt-Zeiger des Chronogramms (eigene, kleine Zeichenebene).

## Komponenten

- **Segmented Control** (`.segmented`): einzige Form für exklusive Auswahl (Bereiche, Sortierung, Zeitraum).
- **Chip** (`.chip`): Filter, mit 6-px-Datenpunkt; aktiv = `--surface-3`.
- **Kachel** (`.tile`): Visual oben (Neon-Strecke in Typfarbe), Datum, Hauptzahl, Trenner, drei Nebenwerte. Hover: Fläche heller, Glow stärker.
- **Typ-Tag** (`.type-tag`): Punkt + Label, ohne Rahmen.
- **Detail-Overlay**: Visual links, Zahlen rechts; mobil als Bottom-Sheet. Esc schließt, ← → blättert.
- **Bildschirmtitel** (`ScreenHeader`, `ui/Kit.kt`): Titel in `T.title`, darunter eine Mono-Metazeile (Datum, Kalenderwoche, Stand). Nichts weiter im Kopf.
- **Navigation** (`NavPill`): schwebende Pille, inaktive Bereiche nur als Symbol, der aktive wächst auf Symbol + Name (Federanimation). Heute, Läufe, Schlaf, Trends, Daten (nur Symbol).
- **Heute**: Tagesleiste, 24-h-Chronogramm (`DayDial`), drei Signale (`SignalCard`: HRV-Steuerung, ACWR, Schlafscore; Antippen öffnet den Bereich), Kennzahl-Kacheln (`KpiTile`: Wert, Δ und z zur 28-Tage-Baseline, 21-Tage-Sparkline mit ±1-SD-Band; Antippen klappt 90-Tage-Verlauf und Statistik unter der Zeile auf), Schlaf der Nacht, Läufe des Tages.
- **Chronogramm** (`DayDial`): Mitternacht oben, im Uhrzeigersinn; Ringe von außen: Puls (radiale Balken), Stress (Kategorie-Bögen), Body Battery (Polarlinie), Schlafphasen. Mitte: Body Battery. Antippen eines Rings liest die Uhrzeit ab, lange drücken und ziehen fährt den Tag ab.
- **Läufe**: Segmented Control Galerie | Analyse. Analyse: Form (CTL/ATL/TSB), Belastung (ACWR auf `RangeGauge`), Intensität (3 Zonen, PI, 5 Zonen), aerobe Leistung, Bestzeiten & Prognose, Umfang. Methodik: `docs/research/training.md`.
- **Bausteine** (`ui/Kit.kt`, `ui/Charts.kt`): `ShareBar` + `ShareLegend` (Anteile), `RangeGauge` (Wert auf Skala mit Bereichen), `Sparkline`, `StatRows`/`StatTable` (Mono-Statistik), `Delta` (▲▼ in Evidenzfarbe), `TimeChart` (Zeitreihen, Tippen/Ziehen liest alle Reihen ab), `BarChart` (Wochen, Splits).
- **Info-Button** (`InfoButton`, `InfoPanel`, `ui/Components.kt`): 18-px-„i“ mit 32-px-Touchfläche rechts im Kartenkopf oder neben dem Namen einer Messwert-Zeile. Offen: gefüllt, darunter eine Fläche (`surface-2`) mit dem Erklärtext. Nie mehr als ein Absatz je Thema, Absätze durch Leerzeile getrennt.
- **Messwert-Zeile** (`MetricRow`, `ui/Metrics.kt`): Name, Wert mit Einheit, z-Balken (−3 bis +3 SD, ±1 SD hell), Δ und z zur Baseline (28 vorherige Werte). Baseline-Mittel ± SD, Referenzwerte und Hinweise stehen hinter dem „i“. Antippen der Zeile klappt Verlauf (90 Werte) und Statistik auf (n, Median, IQR, Min–Max, SD, CV, OLS-Trend, r). Methodik: `docs/research/gesundheit.md`.
- **Langzeittrends** (`TrendsCard`, `ui/Trends.kt`): Messgröße per Chip, Zeitraum per Segmented Control (3 M, 6 M, 1 J, Alles). Das Diagramm aggregiert je nach Fensterlänge, damit auf dem Handy nie mehr als etwa 15–50 Marken nebeneinander stehen: bis 3 Monate Wochenmittel mit ±1-SD-Band und Tagespunkten, bis 1 Jahr Wochenmittel mit Min–Max-Strichen, darüber Monatsmittel. Vier Datumsmarken auf der Zeitachse, Tippen/Ziehen liest den Wert des Abschnitts ab (ohne Berührung: der neueste). Unter dem Diagramm zeigt die **Minimap** (`WindowNavigator`) die ganze Historie als Monatsmittel und das Fenster darin; Tippen oder Ziehen verschiebt das Fenster, „Jetzt“ springt zurück. Darunter Kennzahlen mit 95 %-KI und ein Blockvergleich (höchstens 4 Blöcke, Δ, Cohen's d) ab Fensterende.
- **Tageswähler** (`DayStrip`): Balken pro Tag/Nacht, neuester rechts.

## Datenvisualisierung (für kommende Ansichten)

- Linien 1,5 px in Daten- oder Typfarbe mit Halo, keine Füllungen außer sehr leichtem Verlauf unter Flächenkurven (max. 12 %).
- Keine Achsenrahmen; höchstens 3–4 horizontale Hairlines, Beschriftung `--fs-label` in `--text-3`.
- Hover/Touch: Fadenkreuz + Werte-Tooltip, alle Reihen am gleichen Zeitpunkt.
- Baselines (z. B. HRV-Normalbereich) als gedimmtes Band hinter der Linie.
- Trends immer mit Bezug: Wert, Differenz zur Baseline (`--good`/`--bad`), Zeitraum.
- Langzeitverläufe: gleitender bzw. Wochen-/Monatsmittel als Hauptlinie, Tageswerte als Punkte in `--text-3` nur bei kurzen Fenstern (≤ 120 Tage), sonst Min–Max-Striche je Abschnitt. Immer mit Minimap, sobald die Historie länger ist als das Fenster.
