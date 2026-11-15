#!/usr/bin/env bash
# Build density-specific adaptive foregrounds and legacy launcher PNGs from the SVG.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
SVG="$ROOT_DIR/assets/lock-cylinder.svg"
RES_DIR="$ROOT_DIR/android-test/app/src/main/res"
PREVIEW="$ROOT_DIR/assets/lock-cylinder-preview.png"
ADAPTIVE_PREVIEW="$ROOT_DIR/assets/lock-cylinder-adaptive-preview.png"

if [[ ! -f "$SVG" ]]; then
    echo "Erreur : SVG absent : $SVG" >&2
    exit 1
fi
if ! command -v inkscape >/dev/null 2>&1; then
    echo "Erreur : Inkscape est requis pour exporter le groupe vectoriel artwork du SVG." >&2
    exit 1
fi
if command -v magick >/dev/null 2>&1; then
    MAGICK=(magick)
elif command -v convert >/dev/null 2>&1; then
    MAGICK=(convert)
else
    echo "Erreur : ImageMagick (magick ou convert) est requis pour centrer l'illustration dans la zone sûre Android." >&2
    exit 1
fi

TMP_ROOT="$ROOT_DIR/android-test/.local/tmp"
mkdir -p "$TMP_ROOT"
WORK_DIR="$(mktemp -d "$TMP_ROOT/icons.XXXXXX")"
trap 'rm -rf -- "$WORK_DIR"' EXIT

# Android adaptive icon canvas: 108 dp. Keep all artwork inside the central 66 dp safe area.
inkscape "$SVG" --export-id=artwork --export-id-only --export-area-drawing \
    --export-filename="$WORK_DIR/artwork.png" --export-width=264 >/dev/null
"${MAGICK[@]}" "$WORK_DIR/artwork.png" -resize 264x264 -gravity center \
    -background none -extent 432x432 "$WORK_DIR/foreground-xxxhdpi.png"

render_density() {
    local name="$1" pixels="$2" legacy="$3"
    local dir="$RES_DIR/mipmap-$name"
    mkdir -p "$dir"
    "${MAGICK[@]}" "$WORK_DIR/foreground-xxxhdpi.png" -filter Lanczos \
        -resize "${pixels}x${pixels}" "$dir/ic_launcher_foreground.png"
    "${MAGICK[@]}" -size "${legacy}x${legacy}" 'xc:#fbfaf7' \
        \( "$WORK_DIR/foreground-xxxhdpi.png" -filter Lanczos -resize "${legacy}x${legacy}" \) \
        -gravity center -compose over -composite "$dir/ic_launcher.png"
    cp "$dir/ic_launcher.png" "$dir/ic_launcher_round.png"
}

render_density mdpi 108 48
render_density hdpi 162 72
render_density xhdpi 216 96
render_density xxhdpi 324 144
render_density xxxhdpi 432 192

inkscape "$SVG" --export-filename="$PREVIEW" --export-width=512 --export-height=512 >/dev/null
"${MAGICK[@]}" -size 512x512 xc:none -fill '#fbfaf7' \
    -draw 'circle 256,256 256,0' "$WORK_DIR/circle-background.png"
"${MAGICK[@]}" "$WORK_DIR/circle-background.png" \
    \( "$WORK_DIR/foreground-xxxhdpi.png" -filter Lanczos -resize 512x512 \) \
    -gravity center -compose over -composite "$ADAPTIVE_PREVIEW"

echo "Icônes adaptatives et legacy générées dans $RES_DIR (zone sûre 66/108 dp)."
