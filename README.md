# Gapmin

Selbst gehostetes Fitness-Dashboard für Garmin-Uhren (getestet für Forerunner 265): ohne Garmin Connect, ohne Konto, ohne Cloud.

```
Uhr ──BLE──▶ Gadgetbridge ──ZIP-Export──▶ Gapmin-App (native Android-App, lokale SQLite)
                                              │  wenn erreichbar (LAN / WireGuard)
                                              ▼
                                   NAS: Gapmin-API ──▶ PostgreSQL + TimescaleDB
                                                       (Archiv, Langzeitauswertung, lokale KI)
```

Die App liest den Gadgetbridge-Export direkt auf dem Handy und funktioniert offline. Der NAS bekommt eine Kopie der aufbereiteten Daten und hält die komplette Historie für Langzeitanalysen.

## Stand

- [x] **Läufe**: Galerie mit Neon-Streckenformen, Monatsgruppen, Filter nach Lauftyp (automatisch klassifiziert), Detailansicht mit Pace-, HF- und Höhenprofil, Wischen zwischen Läufen
- [x] **Schlaf**: Gapmin-Schlafscore auf Basis aktueller Studien ([Recherche & Konzept](docs/research/schlaf-score.md)), Hypnogramm, Teilscores, alle Nachtwerte, Verlauf
- [x] **Import**: Gadgetbridge-ZIP oder -Datenbank, inkrementell, GPX- und FIT-Strecken; die App merkt sich die Exportdatei und übernimmt neue Daten beim Öffnen automatisch, „Öffnen mit“/„Teilen“ aus anderen Apps
- [x] **NAS**: FastAPI + PostgreSQL/TimescaleDB, Sync per Upload, Tages- und Wochenansichten in SQL
- [x] **Android**: native App in Kotlin und Jetpack Compose, APK-Build per GitHub Actions ([Anleitung](docs/ANDROID.md))
- [ ] Gesundheit: Tagesansicht für HRV-Status, Body Battery, Stress, Ruhepuls
- [ ] Trends und Langzeitverläufe, Trainingsbelastung
- [ ] Lokale KI (Ollama) auf dem NAS

## Entwicklung

```bash
cd android && ./gradlew assembleDebug                                 # App bauen (Android SDK + JDK 17+)
./gradlew testDebugUnitTest --tests 'de.moritzbelli.gapmin.core.*'    # Import-Kette mit dem Demo-Export testen
./gradlew testDebugUnitTest --tests '*ScreenshotTest*'                # Screenshots nach app/build/screenshots

python3 scripts/make_demo_export.py           # Demo-Export neu erzeugen (synthetische Forerunner-265-Daten)
```

Die frühere Web-Version liegt noch in `web/` (Browser-Prototyp, `npm run dev`); die Android-App hängt nicht mehr davon ab, nutzt aber den Demo-Export aus `web/public/demo/`.

NAS-Server lokal:

```bash
cd server
cp .env.example .env    # Passwort + Token setzen
docker compose up -d    # API auf Port 8085
```

## Aufbau

| Pfad | Inhalt |
|---|---|
| `android/app/src/main/java/de/moritzbelli/gapmin/core/` | Plattformunabhängiger Kern: Schema, Gadgetbridge-Import, GPX/FIT, Läufe, Schlafscore |
| `android/app/src/main/java/de/moritzbelli/gapmin/data/` | SQLite (gebündelt), NAS-Sync |
| `android/app/src/main/java/de/moritzbelli/gapmin/ui/` | Compose-Oberfläche: Läufe, Detail, Schlaf, Daten, Design-Tokens (`Theme.kt`) |
| `server/` | NAS-API, Migrationen (PostgreSQL/TimescaleDB), Docker Compose |
| `scripts/` | Demo-Export, App-Icons, Android-Signierschlüssel |
| `web/` | Frühere Web-Version (Browser-Prototyp) |
| `docs/DESIGN.md` | Stilregeln |
