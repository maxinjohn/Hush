#!/usr/bin/env bash
# Remove ALL build outputs without hanging on macOS duplicate folders ("name 2").
# Cleans every Gradle module's build directory to prevent stale class files
# causing "Type X is defined multiple times" D8 errors.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

./gradlew --stop 2>/dev/null || true

remove_path() {
  local path="$1"
  if [ -e "$path" ]; then
    echo "Removing $path"
    rm -rf "$path" || true
  fi
}

# Finder / interrupted builds sometimes leave "folder 2" copies that hang rm/clean forever.
while IFS= read -r dup; do
  remove_path "$dup"
done < <(find . -type d -name '* 2' -path '*/build/*' 2>/dev/null || true)

# Stale KSP backup trees from pre-rename builds can loop on delete.
while IFS= read -r stale; do
  remove_path "$stale"
done < <(find . -type d -path '*/kspCaches/backups/kotlin/moe' 2>/dev/null || true)

# Remove all Gradle module build directories (prevents duplicate class D8 errors).
for module in app core canvas lastfm moriextractor spotifycore shazamkit jiosaavn waze-shim build; do
  remove_path "./$module/build"
done

# Remove Gradle cache that can cause stale config issues.
remove_path .gradle/configuration-cache

echo "Build output cleared."
