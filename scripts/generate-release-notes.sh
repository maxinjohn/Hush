#!/usr/bin/env bash
# Generate GitHub release notes from commits since the previous version tag.
set -euo pipefail

OUTPUT_FILE="${1:-release_notes.md}"
MAX_ENTRIES="${MAX_ENTRIES:-250}"
RANGE="${RELEASE_RANGE:-}"

if [ -z "$RANGE" ]; then
  LAST_TAG="$(git describe --tags --abbrev=0 --match 'v*' 2>/dev/null || true)"
  if [ -n "$LAST_TAG" ]; then
    RANGE="${LAST_TAG}..HEAD"
  fi
fi

is_version_only_commit() {
  local subject="$1"
  if [[ "$subject" =~ ^[Bb]ump(\ the)?\ version ]]; then
    return 0
  fi
  if [[ "$subject" =~ ^[Vv]ersion\ bump ]]; then
    return 0
  fi
  if [[ "$subject" =~ \([0-9]+\.[0-9]+\.[0-9]+ ]]; then
    return 0
  fi
  return 1
}

categorize_commit() {
  local subject="$1"
  local lower
  lower="$(printf '%s' "$subject" | tr '[:upper:]' '[:lower:]')"

  if [[ "$lower" =~ ^fix(\(|:|\ ) ]] || [[ "$lower" =~ fix ]] || [[ "$lower" =~ bug ]]; then
    printf 'fixes'
  elif [[ "$lower" =~ ^feat(\(|:|\ ) ]] || [[ "$lower" =~ ^add(\(|:|\ ) ]] || [[ "$lower" =~ feature ]]; then
    printf 'features'
  elif [[ "$lower" =~ ^ui(\(|:|\ ) ]] || [[ "$lower" =~ polish ]] || [[ "$lower" =~ design ]]; then
    printf 'ui'
  elif [[ "$lower" =~ adapt ]] || [[ "$lower" =~ upstream ]] || [[ "$lower" =~ archivetune ]] || [[ "$lower" =~ metrolist ]] || [[ "$lower" =~ cherry-pick ]]; then
    printf 'adaptations'
  elif [[ "$lower" =~ perf ]] || [[ "$lower" =~ optimi ]] || [[ "$lower" =~ speed ]]; then
    printf 'performance'
  else
    printf 'other'
  fi
}

collect_commits() {
  if [ -n "$RANGE" ]; then
    git log "$RANGE" --pretty=format:'%s' --no-merges --max-count="$MAX_ENTRIES"
  else
    git log --pretty=format:'%s' --no-merges --max-count="$MAX_ENTRIES"
  fi
}

declare -a FEATURES=()
declare -a FIXES=()
declare -a UI=()
declare -a ADAPTATIONS=()
declare -a PERFORMANCE=()
declare -a OTHER=()

while IFS= read -r subject; do
  [ -z "$subject" ] && continue
  if is_version_only_commit "$subject"; then
    continue
  fi

  case "$(categorize_commit "$subject")" in
    features) FEATURES+=("$subject") ;;
    fixes) FIXES+=("$subject") ;;
    ui) UI+=("$subject") ;;
    adaptations) ADAPTATIONS+=("$subject") ;;
    performance) PERFORMANCE+=("$subject") ;;
    *) OTHER+=("$subject") ;;
  esac
done < <(collect_commits)

append_section() {
  local title="$1"
  shift
  local -a items=("$@")
  if [ "${#items[@]}" -eq 0 ]; then
    return
  fi
  echo "## $title"
  echo ""
  for item in "${items[@]}"; do
    echo "- $item"
  done
  echo ""
}

{
  echo "# Changelog"
  echo ""
  if [ -n "$RANGE" ]; then
    echo "Changes in $RANGE:"
  else
    echo "Recent changes:"
  fi
  echo ""

  append_section "Features" "${FEATURES[@]:-}"
  append_section "Fixes" "${FIXES[@]:-}"
  append_section "UI" "${UI[@]:-}"
  append_section "Adapted settings & upstream" "${ADAPTATIONS[@]:-}"
  append_section "Performance" "${PERFORMANCE[@]:-}"
  append_section "Other" "${OTHER[@]:-}"

  echo "## Upstream credits"
  echo ""
  echo "Hush is based on [ArchiveTune](https://github.com/ArchiveTuneApp/ArchiveTune) and may cherry-pick from other open-source clients over time."
  echo ""
  echo "| Project | Repository |"
  echo "| --- | --- |"
  echo "| [ArchiveTune](https://github.com/ArchiveTuneApp/ArchiveTune) | [ArchiveTuneApp/ArchiveTune](https://github.com/ArchiveTuneApp/ArchiveTune) |"
  echo "| [Metrolist](https://github.com/metrolistgroup/metrolist) | [metrolistgroup/metrolist](https://github.com/metrolistgroup/metrolist) |"
  echo "| [Vivi Music](https://github.com/vivizzz007/vivi-music) | [vivizzz007/vivi-music](https://github.com/vivizzz007/vivi-music) |"
  echo "| [Echo Music](https://github.com/EchoMusicApp/Echo-Music) | [EchoMusicApp/Echo-Music](https://github.com/EchoMusicApp/Echo-Music) |"
  echo ""
  echo "Thank you to all upstream maintainers and contributors."
} > "$OUTPUT_FILE"

if [ ! -s "$OUTPUT_FILE" ]; then
  echo "release notes output is empty: $OUTPUT_FILE" >&2
  exit 1
fi
