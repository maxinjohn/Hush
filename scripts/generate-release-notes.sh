#!/usr/bin/env bash
# Generate GitHub release notes from commits since the previous version tag.
set -euo pipefail

OUTPUT_FILE="${1:-release_notes.md}"
MAX_ENTRIES="${MAX_ENTRIES:-250}"
RANGE="${RELEASE_RANGE:-}"
NEW_VERSION="${RELEASE_VERSION:-}"

resolve_previous_tag() {
  local current_tag="${1:-}"
  if [ -n "$current_tag" ]; then
    git tag -l 'v*' --sort=-v:refname | while IFS= read -r tag; do
      [ "$tag" = "$current_tag" ] && continue
      printf '%s' "$tag"
      break
    done
    return
  fi

  git describe --tags --abbrev=0 --match 'v*' 2>/dev/null || true
}

if [ -z "$RANGE" ]; then
  if [ -n "$NEW_VERSION" ]; then
    CURRENT_TAG="v${NEW_VERSION}"
    if git rev-parse "$CURRENT_TAG" >/dev/null 2>&1; then
      RANGE="${CURRENT_TAG}..HEAD"
    else
      PREVIOUS_TAG="$(resolve_previous_tag "$CURRENT_TAG")"
      if [ -n "$PREVIOUS_TAG" ]; then
        RANGE="${PREVIOUS_TAG}..HEAD"
      fi
    fi
  else
    PREVIOUS_TAG="$(resolve_previous_tag "")"
    if [ -n "$PREVIOUS_TAG" ]; then
      RANGE="${PREVIOUS_TAG}..HEAD"
    fi
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
  if [[ "$subject" =~ ^[Bb]ump\ to\ [0-9]+\.[0-9]+\.[0-9]+ ]]; then
    return 0
  fi
  return 1
}

normalize_subject() {
  local subject="$1"
  subject="${subject%; bump to *}"
  subject="${subject%; Bump to *}"
  subject="${subject% (release)}"
  printf '%s' "$subject"
}

categorize_commit() {
  local subject="$1"
  local lower
  lower="$(printf '%s' "$subject" | tr '[:upper:]' '[:lower:]')"

  if [[ "$lower" =~ ^fix ]] || [[ "$lower" =~ fix ]] || [[ "$lower" =~ bug ]]; then
    printf 'fixes'
  elif [[ "$lower" =~ ^feat ]] || [[ "$lower" =~ ^add ]] || [[ "$lower" =~ feature ]]; then
    printf 'features'
  elif [[ "$lower" =~ ^ui ]] || [[ "$lower" =~ polish ]] || [[ "$lower" =~ design ]]; then
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
    git log "$RANGE" --pretty=format:'%h %s' --no-merges --max-count="$MAX_ENTRIES"
  else
    git log --pretty=format:'%h %s' --no-merges --max-count="$MAX_ENTRIES"
  fi
}

declare -a FEATURES=()
declare -a FIXES=()
declare -a UI=()
declare -a ADAPTATIONS=()
declare -a PERFORMANCE=()
declare -a OTHER=()

while IFS= read -r line || [ -n "${line:-}" ]; do
  [ -z "$line" ] && continue
  hash="${line%% *}"
  subject="${line#"$hash "}"
  [ -z "$subject" ] && continue
  if is_version_only_commit "$subject"; then
    continue
  fi

  entry="$(normalize_subject "$subject") ($hash)"
  case "$(categorize_commit "$subject")" in
    features) FEATURES+=("$entry") ;;
    fixes) FIXES+=("$entry") ;;
    ui) UI+=("$entry") ;;
    adaptations) ADAPTATIONS+=("$entry") ;;
    performance) PERFORMANCE+=("$entry") ;;
    *) OTHER+=("$entry") ;;
  esac
done < <(collect_commits)

append_section() {
  local title="$1"
  shift
  if [ "$#" -eq 0 ]; then
    return
  fi
  echo "## $title"
  echo ""
  for item in "$@"; do
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

  [ "${#FEATURES[@]}" -gt 0 ] && append_section "Features" "${FEATURES[@]}"
  [ "${#FIXES[@]}" -gt 0 ] && append_section "Fixes" "${FIXES[@]}"
  [ "${#UI[@]}" -gt 0 ] && append_section "UI" "${UI[@]}"
  [ "${#ADAPTATIONS[@]}" -gt 0 ] && append_section "Adapted settings & upstream" "${ADAPTATIONS[@]}"
  [ "${#PERFORMANCE[@]}" -gt 0 ] && append_section "Performance" "${PERFORMANCE[@]}"
  [ "${#OTHER[@]}" -gt 0 ] && append_section "Other" "${OTHER[@]}"

  if [ "${#FEATURES[@]}" -eq 0 ] &&
    [ "${#FIXES[@]}" -eq 0 ] &&
    [ "${#UI[@]}" -eq 0 ] &&
    [ "${#ADAPTATIONS[@]}" -eq 0 ] &&
    [ "${#PERFORMANCE[@]}" -eq 0 ] &&
    [ "${#OTHER[@]}" -eq 0 ]; then
    echo "_No categorized commits found in this range. Showing raw history:_"
    echo ""
    if [ -n "$RANGE" ]; then
      git log "$RANGE" --pretty=format:'- %s (%h)' --no-merges --max-count="$MAX_ENTRIES" || true
    else
      git log --pretty=format:'- %s (%h)' --no-merges --max-count="$MAX_ENTRIES" || true
    fi
    echo ""
    echo ""
  fi

  echo "## Upstream credits"
  echo ""
  echo "Hush is built on [ArchiveTune](https://github.com/ArchiveTuneApp/ArchiveTune) and combines features, fixes, and UI from several open-source YouTube Music clients—including Metrolist, Vivi Music, and Echo Music. Those projects are credited below; their licenses and copyright notices are preserved in source."
  echo ""
  echo "| Project | Repository |"
  echo "| --- | --- |"
  echo "| [ArchiveTune](https://github.com/ArchiveTuneApp/ArchiveTune) | [ArchiveTuneApp/ArchiveTune](https://github.com/ArchiveTuneApp/ArchiveTune) |"
  echo "| [Metrolist](https://github.com/metrolistgroup/metrolist) | [metrolistgroup/metrolist](https://github.com/metrolistgroup/metrolist) |"
  echo "| [Vivi Music](https://github.com/vivizzz007/vivi-music) | [vivizzz007/vivi-music](https://github.com/vivizzz007/vivi-music) |"
  echo "| [Echo Music](https://github.com/EchoMusicApp/Echo-Music) | [EchoMusicApp/Echo-Music](https://github.com/EchoMusicApp/Echo-Music) |"
  echo ""
  echo "Thank you to the maintainers and contributors of every project listed above."
  echo ""
  echo "### Feature attribution"
  echo ""
  echo "See the full table in [README — Loot table](https://github.com/maxinjohn/Hush/blob/main/README.md#loot-table--who-donated-what)."
  echo ""
  echo "| Source | Examples integrated into Hush |"
  echo "| --- | --- |"
  echo "| ArchiveTune | Core playback, YT sync, lyrics, Cast, Together, local files |"
  echo "| Metrolist | Music alarms, loudness levels, playlist export, Android Auto settings |"
  echo "| Vivi Music | Playlist prefetch, auto-backup before update |"
  echo "| Echo Music | Settings search, IPv4/IPv6 network mode |"
  echo "| ViMusic / OuterTune / BetterLyrics | InnerTube base, UI patterns, synced lyrics |"
} > "$OUTPUT_FILE"

if [ ! -s "$OUTPUT_FILE" ]; then
  echo "release notes output is empty: $OUTPUT_FILE" >&2
  exit 1
fi
