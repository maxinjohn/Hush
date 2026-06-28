#!/usr/bin/env bash
# Regenerate release_notes/v{version}.md, CHANGELOG.md, and README version line.
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

# README — current version line (between markers)
README_FILE="README.md"
export VERSION_NAME VERSION_CODE
python3 <<'PY'
import os
import re
from pathlib import Path

version_name = os.environ["VERSION_NAME"]
version_code = os.environ["VERSION_CODE"]
readme_path = Path("README.md")
block = f"""<!-- hush-release-docs-start -->
**Current version:** {version_name} ({version_code}) · [Release notes](release_notes/v{version_name}.md) · [All releases](https://github.com/maxinjohn/Hush/releases/latest)

_Auto-updated on push to `dev`/`main` and when **Bump to new version** runs in GitHub Actions._
<!-- hush-release-docs-end -->"""
text = readme_path.read_text(encoding="utf-8")
if "<!-- hush-release-docs-start -->" in text:
    text = re.sub(
        r"<!-- hush-release-docs-start -->.*?<!-- hush-release-docs-end -->",
        block,
        text,
        count=1,
        flags=re.DOTALL,
    )
else:
    text = text.replace("# Hush\n\n", f"# Hush\n\n{block}\n\n", 1)
readme_path.write_text(text, encoding="utf-8")
PY

# CHANGELOG.md — index of all version files
CHANGELOG_FILE="CHANGELOG.md"
{
  echo "# Changelog"
  echo ""
  echo "Release notes are generated automatically from git history when you push to \`dev\` or \`main\`, and when the [**Bump to new version**](.github/workflows/release.yml) workflow publishes a release."
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
  echo "Upstream feature map: [README — Adapted features by source](README.md#adapted-features-by-source)"
} > "$CHANGELOG_FILE"

echo "Synced release docs for v${VERSION_NAME} (${VERSION_CODE})"
echo "  - ${NOTES_FILE}"
echo "  - ${CHANGELOG_FILE}"
echo "  - ${README_FILE}"
