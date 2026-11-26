# Gapmin

Selbst gehostetes Fitness-Dashboard für Garmin-Uhren – ohne Garmin Connect, ohne Cloud.
Die Uhr synchronisiert per Bluetooth mit [Gadgetbridge](https://gadgetbridge.org); Gapmin liest dessen Export und zeigt alles lokal an.

```
Uhr ──BLE──▶ Gadgetbridge (Android) ──Export──▶ Server ──▶ Gapmin (Web-App)
```

## Stand

- [x] Lauf-Galerie: Läufe als Kacheln mit der GPS-Strecke als Form, nach Monat gruppiert, Filter nach Typ, Sortierung, Detailansicht
- [x] Design-System (`docs/DESIGN.md`, `web/src/styles/tokens.css`)
- [ ] Import aus der Gadgetbridge-Datenbank (aktuell Testdaten)
- [ ] Detailansicht mit Pace-, HF- und Höhenprofil
- [ ] Gesundheit: Schlaf, HRV, Body Battery, Ruhepuls
- [ ] Trends und Langzeitverläufe
- [ ] Lokale KI (Ollama)

## Entwicklung

```bash
# Testdaten erzeugen (60 Läufe als GPX + runs.json)
python3 scripts/generate_sample_data.py

# Web-App
cd web
npm install
npm run dev
```

## Aufbau

| Pfad | Inhalt |
|---|---|
| `web/` | React + TypeScript + Vite |
| `web/public/sample-data/` | Testdaten: `runs.json` (Übersicht) + `gpx/*.gpx` |
| `scripts/generate_sample_data.py` | Generator für realistische Testläufe |
| `docs/DESIGN.md` | Stilregeln |

`runs.json` ist das Format, das später auch das Backend aus der Gadgetbridge-Datenbank liefern wird.
