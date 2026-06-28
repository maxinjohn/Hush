#!/usr/bin/env bash
# Regenerate release_notes/v{version}.md and CHANGELOG.md (README is edited manually).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

GRADLE_FILE="app/build.gradle.kts"
if [ ! -f "$GRADLE_FILE" ]; then
  echo "Missing $GRADLE_FILE" >&2
  exit 1
fi

VERSION_NAME="$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' "$GRADLE_FILE" | head -n 1)"
VERSION_CODE="$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' "$GRADLE_FILE" | head -n 1)"

if [ -z "$VERSION_NAME" ] || [ -z "$VERSION_CODE" ]; then
  echo "Could not read versionName/versionCode from $GRADLE_FILE" >&2
  exit 1
fi

mkdir -p release_notes
NOTES_FILE="release_notes/v${VERSION_NAME}.md"
CURRENT_TAG="v${VERSION_NAME}"

chmod +x scripts/generate-release-notes.sh
if git rev-parse "$CURRENT_TAG" >/dev/null 2>&1 && [ -f "$NOTES_FILE" ]; then
  echo "Keeping published notes in $NOTES_FILE ($CURRENT_TAG already exists)"
else
  RELEASE_VERSION="$VERSION_NAME" bash scripts/generate-release-notes.sh "$NOTES_FILE"
fi

if ! grep -q '^- ' "$NOTES_FILE"; then
  tmp="$(mktemp)"
  awk 'NR==3 { print ""; print "- No new commits since the last release tag."; print "" } { print }' "$NOTES_FILE" > "$tmp"
  mv "$tmp" "$NOTES_FILE"
fi

# CHANGELOG.md — index of all version files
CHANGELOG_FILE="CHANGELOG.md"
{
  echo "# Changelog"
  echo ""
  echo "What's new in each release. Feature attribution lives in the [README](README.md#where-features-came-from)."
  echo ""
  echo "| Version | Notes |"
  echo "| --- | --- |"
  note_files=()
  while IFS= read -r file; do
    [ -n "$file" ] && note_files+=("$file")
  done < <(python3 - <<'PY'
import glob
import re
from pathlib import Path

def version_key(path: Path) -> list[int]:
    match = re.search(r"v(.+)\.md$", path.name)
    if not match:
        return [0]
    return [int(part) for part in match.group(1).split(".")]

for path in sorted(
    (Path(p) for p in glob.glob("release_notes/v*.md")),
    key=version_key,
    reverse=True,
):
    print(path)
PY
)
  for file in "${note_files[@]}"; do
    version="$(basename "$file" .md | sed 's/^v//')"
    label="[${version}](${file})"
    if [ "$version" = "$VERSION_NAME" ]; then
      label="${label} · **current**"
    fi
    echo "| ${version} | ${label} |"
  done
  echo ""
  echo "Feature map: [README — Where features came from](README.md#where-features-came-from)"
} > "$CHANGELOG_FILE"

echo "Synced release docs for v${VERSION_NAME} (${VERSION_CODE})"
echo "  - ${NOTES_FILE}"
echo "  - ${CHANGELOG_FILE}"
