#!/usr/bin/env bash
# Optimize app assets: convert large PNGs to WebP and subset fonts to only
# include glyphs actually referenced in string resources.
#
# Run whenever drawable or font assets are added/updated.
# Idempotent — safe to re-run against already-optimized assets.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

RES_DIR="app/src/main/res"
FONT_DIR="$RES_DIR/font"
DRAWABLE_DIR="$RES_DIR/drawable"
DRAWABLE_NODPI_DIR="$RES_DIR/drawable-nodpi"
WEBP_QUALITY=80
PNG_MIN_SIZE_KB=10

# ── helpers ────────────────────────────────────────────────────────────────

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

say()  { echo -e "${GREEN}[optimize-assets]${NC} $*"; }
warn() { echo -e "${YELLOW}[optimize-assets] WARNING:${NC} $*"; }
err()  { echo -e "${RED}[optimize-assets] ERROR:${NC} $*" >&2; }

check_cmd() {
  if ! command -v "$1" &>/dev/null; then
    # macOS/Linux: pip3 installs to user-local paths, not always on PATH.
    # Search common locations and prepend to PATH so subsequent calls work.
    local found
    found=$(find "$HOME/Library/Python" "$HOME/.local/bin" -name "$1" -type f 2>/dev/null | head -1)
    if [ -n "$found" ] && [ -x "$found" ]; then
      PATH="$(dirname "$found"):$PATH"
      export PATH
      return 0
    fi
    err "'$1' not found. Install it (pip3 install fonttools) and re-run."
    return 1
  fi
}

file_size_bytes() {
  # Portable file size in bytes — macOS (BSD stat) and Linux (GNU stat).
  stat -f%z "$1" 2>/dev/null || stat -c%s "$1" 2>/dev/null
}

# ── Step 1: PNG → WebP ────────────────────────────────────────────────────

convert_pngs_to_webp() {
  say "Step 1/2: Converting PNG → WebP (quality=$WEBP_QUALITY, min ${PNG_MIN_SIZE_KB} KB)..."

  check_cmd cwebp || return 1

  local total_before=0 total_after=0 converted=0

  while IFS= read -r -d '' png; do
    local png_size
    png_size=$(file_size_bytes "$png")
    local png_kb=$((png_size / 1024))

    if [ "$png_kb" -lt "$PNG_MIN_SIZE_KB" ]; then
      continue
    fi

    local webp="${png%.png}.webp"

    if cwebp -q "$WEBP_QUALITY" -quiet "$png" -o "$webp" 2>/dev/null; then
      local webp_size
      webp_size=$(file_size_bytes "$webp")
      local saved_kb=$(((png_size - webp_size) / 1024))
      say "  $(basename "$png")  ${png_kb}K → $(($webp_size / 1024))K  (saved ${saved_kb}K)"
      rm "$png"
      total_before=$((total_before + png_size))
      total_after=$((total_after + webp_size))
      converted=$((converted + 1))
    else
      warn "Failed to convert $png — skipping"
    fi
  done < <(find "$DRAWABLE_DIR" "$DRAWABLE_NODPI_DIR" -maxdepth 1 -name '*.png' -type f -print0 2>/dev/null)

  if [ "$converted" -eq 0 ]; then
    say "  No PNGs above ${PNG_MIN_SIZE_KB} KB found — nothing to convert."
  else
    local saved_mb
    saved_mb=$(echo "scale=1; ($total_before - $total_after) / 1048576" | bc)
    say "  Converted $converted file(s). Total saved: ~${saved_mb} MB"
  fi
}

# ── Step 2: Font subsetting ────────────────────────────────────────────────

subset_fonts() {
  say "Step 2/2: Subsetting large fonts..."

  check_cmd pyftsubset || return 1
  check_cmd python3 || return 1

  if [ ! -d "$FONT_DIR" ]; then
    warn "No font directory found at $FONT_DIR — skipping."
    return 0
  fi

  # Collect every unique character from all string XML files into a temp file.
  local glyphs_file
  glyphs_file=$(mktemp /tmp/hush-glyphs.XXXXXX)
  # Clean up temp file on script exit
  trap 'rm -f "'"$glyphs_file"'"' EXIT

  # Use Python to parse Android string resource files and collect unique chars
  # from every <string> element across all locale directories.
  python3 - "$glyphs_file" "$RES_DIR" <<'PYEOF'
import sys, os, glob
from xml.etree import ElementTree as ET

out_path  = sys.argv[1]
res_dir   = sys.argv[2]

chars = set()

# 1. Collect characters from every <string> element in all values*/strings*.xml
for f in glob.glob(os.path.join(res_dir, 'values*', 'strings*.xml')):
    try:
        for elem in ET.parse(f).iter('string'):
            if elem.text:
                chars.update(elem.text)
    except Exception:
        pass

# Also scan hush_strings.xml files
for f in glob.glob(os.path.join(res_dir, 'values*', 'hush_strings.xml')):
    try:
        for elem in ET.parse(f).iter('string'):
            if elem.text:
                chars.update(elem.text)
    except Exception:
        pass

# 2. Always include full ASCII printable range
chars.update(chr(c) for c in range(0x20, 0x7F))

# 3. Common punctuation / symbols used in UI
chars.update('\u2013\u2014\u2018\u2019\u201c\u201d\u2022\u2026'
             '\u00A0\u00B0\u00B7\u00D7'
             '\u25CF\u2605\u2713\u2717\u2192\u2190\u2191\u2193')

# Sort for deterministic output
result = ''.join(sorted(chars))

with open(out_path, 'w', encoding='utf-8') as fh:
    fh.write(result)

print(f"Collected {len(result)} unique glyphs from string resources.")
PYEOF

  local glyph_count
  glyph_count=$(wc -c < "$glyphs_file" | tr -d ' ')
  say "  Glyph collection complete ($glyph_count unique characters)."

  local total_before=0 total_after=0 subsetted=0
  local MIN_FONT_KB=100  # only subset fonts larger than this

  for ttf in "$FONT_DIR"/*.ttf "$FONT_DIR"/*.otf; do
    [ -f "$ttf" ] || continue

    local ttf_size
    ttf_size=$(file_size_bytes "$ttf")
    local ttf_kb=$((ttf_size / 1024))

    if [ "$ttf_kb" -lt "$MIN_FONT_KB" ]; then
      say "  $(basename "$ttf")  ${ttf_kb}K — below ${MIN_FONT_KB}K threshold, skipping."
      continue
    fi

    local subset="${ttf%.*}_subset.${ttf##*.}"

    if pyftsubset "$ttf" \
        --text-file="$glyphs_file" \
        --layout-features+=kern,liga,calt,clig \
        --output-file="$subset" \
        --name-IDs+=* \
        --ignore-missing-glyphs \
        --notdef-outline \
        2>/dev/null; then

      local subset_size
      subset_size=$(file_size_bytes "$subset")
      local saved_kb=$(((ttf_size - subset_size) / 1024))

      say "  $(basename "$ttf")  ${ttf_kb}K → $(($subset_size / 1024))K  (saved ${saved_kb}K)"

      # Replace original with subset
      mv "$subset" "$ttf"
      total_before=$((total_before + ttf_size))
      total_after=$((total_after + subset_size))
      subsetted=$((subsetted + 1))
    else
      warn "Failed to subset $(basename "$ttf") — leaving original untouched."
      rm -f "$subset"
    fi
  done

  if [ "$subsetted" -eq 0 ]; then
    say "  No fonts above ${MIN_FONT_KB} KB found — nothing to subset."
  else
    local saved_mb
    saved_mb=$(echo "scale=1; ($total_before - $total_after) / 1048576" | bc)
    say "  Subsetted $subsetted font(s). Total saved: ~${saved_mb} MB"
  fi
}

# ── Main ────────────────────────────────────────────────────────────────────

say "Optimizing app assets..."
convert_pngs_to_webp
echo ""
subset_fonts
echo ""
say "Done. Run a build to verify the assets compile correctly."
