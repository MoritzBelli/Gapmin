#!/usr/bin/env bash
# Creates the release signing key for the Gapmin Android app and prints the values
# for the GitHub secrets. Run once, keep gapmin-release.jks somewhere safe (password
# manager / NAS backup): every future update must be signed with the same key.
#
#   ./scripts/create-android-keystore.sh
#
# Then in GitHub: Settings → Secrets and variables → Actions → New repository secret
#   ANDROID_KEYSTORE_BASE64   (printed below)
#   ANDROID_KEYSTORE_PASSWORD
#   ANDROID_KEY_ALIAS         gapmin
#   ANDROID_KEY_PASSWORD      (same as keystore password)
set -euo pipefail

OUT="${1:-gapmin-release.jks}"
if [ -e "$OUT" ]; then echo "$OUT existiert bereits, breche ab." >&2; exit 1; fi
PASS="$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-24)"

keytool -genkeypair -v -keystore "$OUT" -alias gapmin -keyalg RSA -keysize 4096 -validity 36500 \
  -storepass "$PASS" -keypass "$PASS" -dname "CN=Gapmin, O=Gapmin, C=DE" >/dev/null

echo
echo "Keystore: $OUT  (sicher aufbewahren, NICHT ins Repo committen)"
echo
echo "ANDROID_KEYSTORE_PASSWORD = $PASS"
echo "ANDROID_KEY_PASSWORD      = $PASS"
echo "ANDROID_KEY_ALIAS         = gapmin"
echo "ANDROID_KEYSTORE_BASE64   = (Inhalt der Datei $OUT.base64)"
base64 -w0 "$OUT" > "$OUT.base64" 2>/dev/null || base64 -i "$OUT" -o "$OUT.base64"
echo
if command -v gh >/dev/null && gh auth status >/dev/null 2>&1; then
  read -r -p "Secrets direkt mit gh im Repo setzen? [j/N] " yn
  if [[ "$yn" =~ ^[jJyY]$ ]]; then
    gh secret set ANDROID_KEYSTORE_BASE64 < "$OUT.base64"
    gh secret set ANDROID_KEYSTORE_PASSWORD --body "$PASS"
    gh secret set ANDROID_KEY_PASSWORD --body "$PASS"
    gh secret set ANDROID_KEY_ALIAS --body gapmin
    echo "Secrets gesetzt."
  fi
fi
