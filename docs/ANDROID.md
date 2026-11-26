# Android-App

Native App in Kotlin mit Jetpack Compose (`android/`), Paket-ID `de.moritzbelli.gapmin`.
Bis Version 0.1 war die App eine in Capacitor verpackte Web-App. Paket-ID und Datenbankdatei (`files/gapmin.sqlite`)
sind gleich geblieben: Wird die neue Release-APK über die alte installiert (gleicher Signierschlüssel), bleiben alle
importierten Daten erhalten.

## Builds

| Auslöser | Ergebnis |
|---|---|
| Push auf einen Branch (Änderungen in `android/`) | Tests, Debug-APK als Workflow-Artefakt (14 Tage), Screenshots auf Branch `ci-screenshots`, **kein Release** |
| Actions → *Android* → *Run workflow*, `release` = an | signierte Release-APK als GitHub-Release |
| Tag `v1.2.3` pushen | signierte Release-APK als GitHub-Release `v1.2.3` |

Der Versionscode ist die Build-Nummer des Workflows, damit Android Updates akzeptiert.

## Einmalig: Signierschlüssel

Updates lassen sich nur installieren, wenn jede Release-APK mit **demselben** Schlüssel signiert ist.

```bash
./scripts/create-android-keystore.sh      # braucht keytool (JDK) und openssl
```

Die ausgegebenen Werte kommen als Repository-Secrets in GitHub (Settings → Secrets and variables → Actions):
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
Die `.jks`-Datei sicher aufbewahren (z. B. im Passwortmanager oder NAS-Backup) und nie committen.

## Lokal bauen

```bash
cd android
./gradlew assembleDebug        # Android SDK + JDK 17+ nötig, oder den Ordner android/ in Android Studio öffnen
```

## Auf dem Handy

1. APK aus dem GitHub-Release herunterladen und installieren (Installation aus unbekannten Quellen für den Browser erlauben).
2. In Gadgetbridge: Einstellungen → Automatisierungen → **Auto-Export (ZIP)** aktivieren, z. B. alle 3 Stunden.
3. In Gapmin unter **Daten → Export wählen** die ZIP-Datei wählen. Gapmin merkt sich die Datei und übernimmt bei jedem Öffnen der App automatisch neue Daten, wenn Gadgetbridge sie neu geschrieben hat. Alternativ eine Exportdatei in einer anderen App mit „Öffnen mit“ oder „Teilen“ an Gapmin geben.
4. Optional **NAS-Server** eintragen (`http://<NAS-IP>:8085` + Token). Nach jedem Import synchronisiert die App automatisch, wenn der NAS erreichbar ist.

Die App spricht den NAS per HTTP an (`usesCleartextTraffic`), gedacht für LAN oder WireGuard. Für den Zugriff über das Internet gehört ein Reverse-Proxy mit HTTPS davor.
