#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
APK="$SCRIPT_DIR/android-test/app/build/outputs/apk/debug/app-debug.apk"
LOCAL_PROPERTIES="$SCRIPT_DIR/android-test/local.properties"

if [[ -n "${ADB:-}" ]]; then
    ADB_BIN="$ADB"
elif command -v adb >/dev/null 2>&1; then
    ADB_BIN="$(command -v adb)"
else
    SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    if [[ -z "$SDK_DIR" && -f "$LOCAL_PROPERTIES" ]]; then
        SDK_DIR="$(sed -n 's/^sdk\.dir=//p' "$LOCAL_PROPERTIES" | head -n 1 | sed 's/\\ / /g')"
    fi
    if [[ -n "$SDK_DIR" && -x "$SDK_DIR/platform-tools/adb" ]]; then
        ADB_BIN="$SDK_DIR/platform-tools/adb"
    else
        echo "Erreur : adb introuvable. Ajoutez platform-tools au PATH ou définissez ANDROID_SDK_ROOT." >&2
        exit 1
    fi
fi

if [[ ! -f "$APK" ]]; then
    echo "Erreur : APK absent : $APK" >&2
    echo "Construisez-le avec : android-test/gradlew --no-daemon -p android-test :app:assembleDebug" >&2
    exit 1
fi

mapfile -t DEVICES < <("$ADB_BIN" devices | awk 'NR > 1 && $2 == "device" { print $1 }')
if [[ ${#DEVICES[@]} -eq 0 ]]; then
    echo "Erreur : aucun appareil ADB autorisé et prêt. Branchez le téléphone en USB et autorisez le débogage USB." >&2
    "$ADB_BIN" devices -l >&2
    exit 1
fi
if [[ ${#DEVICES[@]} -gt 1 ]]; then
    echo "Erreur : plusieurs appareils ADB sont prêts ; débranchez les autres pour éviter une installation ambiguë." >&2
    printf '  %s\n' "${DEVICES[@]}" >&2
    exit 1
fi

echo "Installation sur ${DEVICES[0]}…"
"$ADB_BIN" -s "${DEVICES[0]}" install -r "$APK"

# Import the developer token only on this personal debug device. It is never
# placed in the APK; the app encrypts it with an Android Keystore key at launch.
TOKEN_FILE="$SCRIPT_DIR/chaster.jeton"
if [[ -f "$TOKEN_FILE" ]]; then
    REMOTE_TOKEN="/data/local/tmp/chaster-token-$$"
    cleanup_token() { "$ADB_BIN" -s "${DEVICES[0]}" shell rm -f "$REMOTE_TOKEN" >/dev/null 2>&1 || true; }
    trap cleanup_token EXIT
    "$ADB_BIN" -s "${DEVICES[0]}" push "$TOKEN_FILE" "$REMOTE_TOKEN" >/dev/null
    if ! "$ADB_BIN" -s "${DEVICES[0]}" shell run-as com.test.test.test mkdir -p files; then
        echo "Erreur : création du répertoire privé impossible." >&2
        exit 1
    fi
    if ! "$ADB_BIN" -s "${DEVICES[0]}" shell run-as com.test.test.test cp "$REMOTE_TOKEN" files/chaster.jeton; then
        echo "Erreur : import privé du jeton impossible (l'APK doit être une build debug)." >&2
        exit 1
    fi
    "$ADB_BIN" -s "${DEVICES[0]}" shell run-as com.test.test.test chmod 600 files/chaster.jeton
    cleanup_token
    trap - EXIT
    "$ADB_BIN" -s "${DEVICES[0]}" shell am force-stop com.test.test.test >/dev/null
    echo "Jeton Chaster importé pour chiffrement au prochain démarrage."
else
    echo "Aucun chaster.jeton : la connexion Chaster restera indisponible."
fi
