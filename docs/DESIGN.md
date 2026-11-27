# Gapmin Design System

Extrem modern, minimalistisch, komplett dunkel. Die Daten sind der Inhalt, die Oberfläche tritt zurück.
Alle Werte stehen in der App in [`ui/Theme.kt`](../android/app/src/main/java/de/moritzbelli/gapmin/ui/Theme.kt) (`G` für Farben, Abstände, Radien, Bewegung, `T` für Schrift), in der Web-Version als CSS-Variablen in [`web/src/styles/tokens.css`](../web/src/styles/tokens.css). Neue Komponenten verwenden **nur** diese Tokens, keine freien Hex-Werte, Abstände oder Zeiten.

## Prinzipien

1. **Daten zuerst.** Farbe ist für Daten reserviert. Die Oberfläche selbst ist schwarz, weiß und grau.
2. **Eine Akzentfarbe.** `--accent` (Volt-Gelb) nur für Marke, Fokus und aktive Auswahl. Nie für Diagramme.
3. **Ruhe durch Weglassen.** Keine Schatten-Stapel, keine Verläufe auf Flächen, keine Icons ohne Funktion. Trennung über Hairlines und Helligkeitsstufen. Glow gibt es nur an Datenlinien.
4. **Große Zahlen, leichte Schrift.** Kennzahlen sind das Gestaltungselement: groß, `--fw-light`, tabellarische Ziffern, Einheit klein und gedimmt.
5. **Bewegung erklärt.** Animation zeigt, woher etwas kommt (Kachel → Detail), nie Dekoration. Weich, kurz, abschaltbar (`prefers-reduced-motion`).
6. **Eine Ebene tief.** Bereiche oben (mobil unten), Inhalt, Detail als Overlay. Kein Menü im Menü. Alles per Tastatur bedienbar.
7. **Erklärungen nur auf Wunsch.** Auf den Karten stehen Daten, keine Legenden, Methodik oder Fußnoten. Alles, was erklärt statt misst, liegt hinter einem „i“ (`InfoButton`/`InfoPanel`, `CardHead(info = …)`) und klappt erst beim Antippen auf.
8. **Maximale Auswertung, minimale Fläche.** Jede Ansicht zeigt eine Hauptzahl und darunter verdichtete Nebenwerte. Mehr Tiefe kommt durch Antippen, nicht durch mehr Elemente.

## Farben

| Rolle | Token | Verwendung |
|---|---|---|
| Hintergrund | `--bg` `#060708` | Fast-Schwarz, OLED-freundlich |
| Flächen | `--surface-1/2/3` | Weiß mit 3 / 5 / 8 % Deckkraft = Ebene 1 / Hover / aktiv |
| Linien | `--hairline`, `--hairline-strong` | 1-px-Trenner und Konturen |
| Text | `--text-1/2/3` | Werte / Sekundär / Labels und Einheiten |
| Akzent | `--accent` | Fokus, aktiver Zustand, Marke |
| Lauftypen | `--run-*` | Neon-Kategoriefarben, einzige satte Farben der App |
| Glow | `--glow-strength` | Halo unter Datenlinien (0,45, Hover 0,85) |
| HF-Zonen | `--zone-1 … --zone-5` | sequentiell kühl → heiß |
| Messgrößen | `heart`, `hrvColor`, `battery`, `activity`, `oxygen`, `breath`, `sleepColor` | eine Farbe pro physiologischer Größe (Punkt, Linie, Band) |
| Stresskategorien | `stressRest/Low/Med/High` | Garmin-Bereiche 0–25 / 26–50 / 51–75 / 76–100 |
| Trends | `--good`, `--bad`, `--neutral` | Veränderungen gegenüber Baseline |

Regel für Kategoriefarben: Sie erscheinen als kleiner Punkt (6 px) oder als leuchtende Datenlinie (Linie + weichgezeichneter Halo), nie als Fläche oder Textfarbe. So leuchten die Daten auf einer sonst schwarz-grauen Oberfläche.

## Typografie

Inter Variable, tabellarische Ziffern überall.

| Stufe | Größe | Einsatz |
|---|---|---|
| `--fs-display` | 64 | Seitentitel, Hauptkennzahl im Detail |
| `--fs-2xl` | 44 | Summen im Kopfbereich |
| `--fs-xl` | 28 | Kennzahl in Kachel, Detailwerte |
| `--fs-lg` | 20 | Titel im Detail |
| `--fs-md` | 15 | Fließtext |
| `--fs-sm` | 13 | Bedienelemente, Nebenwerte |
| `--fs-label` | 11 | `.label`: GROSSBUCHSTABEN, Laufweite 0,09 em, `--text-3` |

Zahlen: Klasse `.num` (leicht, enge Laufweite), Einheit als `<span class="unit">`. Formatierung immer `de-DE` (Komma, `5:37 /km`, `1:02:55`), siehe `web/src/lib/format.ts`.

## Raster, Form

- Abstände im 4-px-Raster: `--s-1` (4) bis `--s-8` (64).
- Radien: `--r-sm` 10 (Fokus), `--r-md` 16, `--r-lg` 24 (Kacheln, Overlays), `--r-full` (Pillen).
- Inhaltsbreite max. `--content-max` 1320 px. App: 2 Spalten (breitere Geräte mehr), Navigation als schwebende Pille unten, Inhalte randlos unter Status- und Navigationsleiste.

## Bewegung

| Token | Dauer | Einsatz |
|---|---|---|
| `--t-fast` | 120 ms | Hover, Druck |
| `--t-base` | 220 ms | Zustandswechsel, Filter |
| `--t-slow` | 420 ms | Overlay, Kacheln einblenden |
| `--t-draw` | 900 ms | Strecke/Diagramm zeichnet sich |

- Standardkurve `--ease-out`, Positionswechsel `--ease-in-out`. Kein Bounce außer bei winzigen Bestätigungen (`--ease-spring`).
- Kacheln erscheinen gestaffelt (30 ms pro Kachel, max. 12).
- Kachel → Detail per View-Transition: Die Strecke „fliegt“ von der Kachel ins Detail und zurück.
- Strecken und Datenreihen zeichnen sich beim ersten Erscheinen ein.

## Komponenten

- **Segmented Control** (`.segmented`): einzige Form für exklusive Auswahl (Bereiche, Sortierung, Zeitraum).
- **Chip** (`.chip`): Filter, mit 6-px-Datenpunkt; aktiv = `--surface-3`.
- **Kachel** (`.tile`): Visual oben (Neon-Strecke in Typfarbe), Datum, Hauptzahl, Trenner, drei Nebenwerte. Hover: Fläche heller, Glow stärker.
- **Typ-Tag** (`.type-tag`): Punkt + Label, ohne Rahmen.
- **Detail-Overlay**: Visual links, Zahlen rechts; mobil als Bottom-Sheet. Esc schließt, ← → blättert.
- **Keine Seitentitel.** Der aktive Bereich steht in der Navigation. Oben stehen höchstens Summen (`Totals`) oder der Tag-/Nacht-Wähler.
- **Navigation**: Läufe, Schlaf, Health, Daten.
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
