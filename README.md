# Gapmin

Selbst gehostetes Fitness-Dashboard für Garmin-Uhren (getestet für Forerunner 265): ohne Garmin Connect, ohne Konto, ohne Cloud.

```
Uhr ──BLE──▶ Gadgetbridge ──ZIP-Export──▶ Gapmin-App (Android, lokale SQLite)
                                              │  wenn erreichbar (LAN / WireGuard)
                                              ▼
                                   NAS: Gapmin-API ──▶ PostgreSQL + TimescaleDB
                                                       (Archiv, Langzeitauswertung, lokale KI)
```

Die App liest den Gadgetbridge-Export direkt auf dem Handy und funktioniert offline. Der NAS bekommt eine Kopie der aufbereiteten Daten und hält die komplette Historie für Langzeitanalysen.

## Stand

- [x] **Läufe**: Galerie mit Neon-Streckenformen, Monatsgruppen, Filter nach Lauftyp (automatisch klassifiziert), Detailansicht
- [x] **Schlaf**: Gapmin-Schlafscore auf Basis aktueller Studien ([Recherche & Konzept](docs/research/schlaf-score.md)), Hypnogramm, Teilscores, alle Nachtwerte, Verlauf
- [x] **Import**: Gadgetbridge-ZIP oder -Datenbank, inkrementell, GPX- und FIT-Strecken
- [x] **NAS**: FastAPI + PostgreSQL/TimescaleDB, Sync per Upload, Tages- und Wochenansichten in SQL
- [x] **Android**: Capacitor-App, APK-Build per GitHub Actions ([Anleitung](docs/ANDROID.md))
- [ ] Gesundheit: Tagesansicht für HRV-Status, Body Battery, Stress, Ruhepuls
- [ ] Trends und Langzeitverläufe, Trainingsbelastung
- [ ] Detailansicht für Läufe mit Pace-, HF- und Höhenprofil
- [ ] Lokale KI (Ollama) auf dem NAS

## Entwicklung

```bash
cd web && npm install && npm run dev          # App im Browser, unter „Daten“ Demo-Daten laden
TZ=Europe/Berlin npx vitest run               # Import-Kette mit dem Demo-Export testen

python3 scripts/make_demo_export.py           # Demo-Export neu erzeugen (synthetische Forerunner-265-Daten)
```

NAS-Server lokal:

```bash
cd server
cp .env.example .env    # Passwort + Token setzen
docker compose up -d    # API auf Port 8085
```

## Aufbau

| Pfad | Inhalt |
|---|---|
| `web/src/db/schema.ts` | Datenbankschema der App (SQLite) |
| `web/src/data/importGadgetbridge.ts` | Import aus dem Gadgetbridge-Export |
| `web/src/analysis/sleep.ts` | Nachterkennung, Schlafmetriken, Schlafscore |
| `web/src/views/` | Ansichten Läufe, Schlaf, Daten |
| `web/android/` | Android-Projekt (Capacitor) |
| `server/` | NAS-API, Migrationen (PostgreSQL/TimescaleDB), Docker Compose |
| `scripts/` | Demo-Export, App-Icons, Android-Signierschlüssel |
| `docs/DESIGN.md` | Stilregeln |
