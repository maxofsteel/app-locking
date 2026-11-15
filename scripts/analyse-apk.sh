#!/usr/bin/env bash
# Aucun téléchargement, installation système, lancement d'APK ou contact constructeur.
set -euo pipefail

if [[ $# -ne 4 ]]; then
    echo 'Usage: bash scripts/analyse-apk.sh APK JADX_JAR APKTOOL_JAR NOUVEAU_REPERTOIRE_SORTIE' >&2
    exit 2
fi

workspace=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
apk=$(realpath -e -- "$1")
jadx=$(realpath -e -- "$2")
apktool=$(realpath -e -- "$3")
output=$(realpath -m -- "$4")
for path in "$apk" "$jadx" "$apktool" "$output"; do
    case "$path" in
        "$workspace"/*) ;;
        *) echo "Chemin hors workspace refusé : $path" >&2; exit 2 ;;
    esac
done
if [[ -e "$output" ]]; then
    echo "La sortie existe déjà, choisir un nouveau répertoire : $output" >&2
    exit 2
fi

mkdir -p -- "$output/tmp" "$output/home" "$output/cache" "$output/config"
export TMPDIR="$output/tmp" XDG_CACHE_HOME="$output/cache" XDG_CONFIG_HOME="$output/config"
java_args=(-XX:-UsePerfData "-Djava.io.tmpdir=$output/tmp" "-Duser.home=$output/home")
{
    sha256sum -- "$apk" "$jadx" "$apktool"
    java "${java_args[@]}" -version 2>&1
    java "${java_args[@]}" -cp "$jadx" jadx.cli.JadxCLI --version
    java "${java_args[@]}" -jar "$apktool" --version
} > "$output/provenance.txt"

# JADX peut rendre des sources exploitables malgré des méthodes en échec.
# Conserver son statut, mais produire aussi le smali pour les vérifier.
jadx_status=0
java "${java_args[@]}" -cp "$jadx" jadx.cli.JadxCLI -d "$output/jadx" "$apk" > "$output/jadx.log" 2>&1 || jadx_status=$?
apktool_status=0
java "${java_args[@]}" -jar "$apktool" d -p "$output/framework" -o "$output/apktool" "$apk" > "$output/apktool.log" 2>&1 || apktool_status=$?
printf 'jadx_exit=%s\napktool_exit=%s\n' "$jadx_status" "$apktool_status" > "$output/status.txt"
cat "$output/status.txt"
if [[ "$apktool_status" -ne 0 ]]; then exit "$apktool_status"; fi
exit "$jadx_status"
