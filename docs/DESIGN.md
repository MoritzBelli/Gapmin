# Gapmin Design System

Extrem modern, minimalistisch, komplett dunkel. Die Daten sind der Inhalt, die Oberfläche tritt zurück.
Alle Werte stehen als CSS-Variablen in [`web/src/styles/tokens.css`](../web/src/styles/tokens.css). Neue Komponenten verwenden **nur** diese Tokens, keine freien Hex-Werte, Abstände oder Zeiten.

## Prinzipien

1. **Daten zuerst.** Farbe ist für Daten reserviert. Die Oberfläche selbst ist schwarz, weiß und grau.
2. **Eine Akzentfarbe.** `--accent` (Volt-Gelb) nur für Marke, Fokus und aktive Auswahl. Nie für Diagramme.
3. **Ruhe durch Weglassen.** Keine Schatten-Stapel, kein Glow, keine Verläufe auf Flächen, keine Icons ohne Funktion. Trennung über Hairlines und Helligkeitsstufen.
4. **Große Zahlen, leichte Schrift.** Kennzahlen sind das Gestaltungselement: groß, `--fw-light`, tabellarische Ziffern, Einheit klein und gedimmt.
5. **Bewegung erklärt.** Animation zeigt, woher etwas kommt (Kachel → Detail), nie Dekoration. Weich, kurz, abschaltbar (`prefers-reduced-motion`).
6. **Eine Ebene tief.** Bereiche oben (mobil unten), Inhalt, Detail als Overlay. Kein Menü im Menü. Alles per Tastatur bedienbar.
7. **Maximale Auswertung, minimale Fläche.** Jede Ansicht zeigt eine Hauptzahl und darunter verdichtete Nebenwerte. Mehr Tiefe kommt durch Antippen, nicht durch mehr Elemente.

## Farben

| Rolle | Token | Verwendung |
|---|---|---|
| Hintergrund | `--bg` `#060708` | Fast-Schwarz, OLED-freundlich |
| Flächen | `--surface-1/2/3` | Weiß mit 3 / 5 / 8 % Deckkraft = Ebene 1 / Hover / aktiv |
| Linien | `--hairline`, `--hairline-strong` | 1-px-Trenner und Konturen |
| Text | `--text-1/2/3` | Werte / Sekundär / Labels und Einheiten |
| Akzent | `--accent` | Fokus, aktiver Zustand, Marke |
| Lauftypen | `--run-*` | gedämpfte Kategoriefarben gleicher Helligkeit |
| HF-Zonen | `--zone-1 … --zone-5` | sequentiell kühl → heiß |
| Trends | `--good`, `--bad`, `--neutral` | Veränderungen gegenüber Baseline |

Regel für Kategoriefarben: Sie erscheinen als kleiner Punkt (6 px) oder als Datenlinie, nie als Fläche oder Textfarbe.

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
- Inhaltsbreite max. `--content-max` 1320 px. Mobil: 2 Spalten, Navigation als schwebende Pille unten.

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
- **Kachel** (`.tile`): Visual oben (Strecke), Datum, Hauptzahl, Trenner, drei Nebenwerte. Hover: Fläche heller, Datenlinie nimmt Typfarbe an.
- **Typ-Tag** (`.type-tag`): Punkt + Label, ohne Rahmen.
- **Detail-Overlay**: Visual links, Zahlen rechts; mobil als Bottom-Sheet. Esc schließt, ← → blättert.

## Datenvisualisierung (für kommende Ansichten)

- Linien 1,5 px, `--data-line`, keine Füllungen außer sehr leichtem Verlauf unter Flächenkurven (max. 12 %).
- Keine Achsenrahmen; höchstens 3–4 horizontale Hairlines, Beschriftung `--fs-label` in `--text-3`.
- Hover/Touch: Fadenkreuz + Werte-Tooltip, alle Reihen am gleichen Zeitpunkt.
- Baselines (z. B. HRV-Normalbereich) als gedimmtes Band hinter der Linie.
- Trends immer mit Bezug: Wert, Differenz zur Baseline (`--good`/`--bad`), Zeitraum.
- Langzeitverläufe: gleitender Mittelwert als Hauptlinie, Tageswerte als Punkte in `--text-3`.
