# Android-App

Die App ist die Web-App aus `web/`, verpackt mit [Capacitor](https://capacitorjs.com) (`web/android/`).
Paket-ID: `de.moritzbelli.gapmin`.

## Builds

| Auslöser | Ergebnis |
|---|---|
| Push auf `main` (Änderungen in `web/`) | Test-Build, Debug-APK als Workflow-Artefakt (14 Tage), **kein Release** |
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
cd web
npm ci && npm run build && npx cap sync android
cd android && ./gradlew assembleDebug        # Android SDK + JDK 21 nötig
# oder: npx cap open android                  # Android Studio
```

## Auf dem Handy

1. APK aus dem GitHub-Release herunterladen und installieren (Installation aus unbekannten Quellen für den Browser erlauben).
2. In Gadgetbridge: Einstellungen → Automatisierungen → **Auto-Export (ZIP)** aktivieren, z. B. alle 3 Stunden.
3. In Gapmin unter **Daten → Export importieren** die ZIP-Datei wählen. Folgeimporte übernehmen nur neue Daten.
4. Optional **NAS-Server** eintragen (`http://<NAS-IP>:8085` + Token). Nach jedem Import synchronisiert die App automatisch, wenn der NAS erreichbar ist.

Die App spricht den NAS per HTTP an (`usesCleartextTraffic`), gedacht für LAN oder WireGuard. Für den Zugriff über das Internet gehört ein Reverse-Proxy mit HTTPS davor.
