#!/usr/bin/env bash
#
# Byte-level proof that a SpotiFLAC track occupies exactly ONE copy on disk, and that
# seeking on it neither re-downloads nor re-caches it.
#
# Background: the playback data-source chain used to route on the media item's URI (a
# bare song id, with no scheme) and only then resolve. A resolved SpotiFLAC file was
# therefore fed through Media3's CacheDataSource, which wrote a second copy of the same
# audio into the song cache. The chain now resolves first and routes on the result, so a
# local file never reaches a cache.
#
# Checks, all against the device's real filesystem:
#
#   1. size-collision scan - no other file in the app data dir has the exact byte size
#      of the SpotiFLAC file (a copied file has an identical size)
#   2. cache-index scan    - the media id is not a key in Media3's song-cache database.
#      An entry that predates the app's current install is reported as residue rather
#      than a failure: this fix is not retroactive, and entries written by an older build
#      cannot be evidence about this one. Entries written *after* the install are.
#   3. cache-dir growth    - the song cache does not grow while the track is seeked
#   4. seek check          - each seek lands at the requested position, read from Hush's
#      own MediaSession (scoped - the dump lists every session on the device)
#
# Usage:
#   scripts/verify-spotiflac-single-copy.sh [package] [mediaId ...]
#
# With no media ids, every track in the SpotiFLAC playback cache is checked. The seek
# check only runs for the first media id, since it needs a single track to seek in.
set -uo pipefail

PKG="${1:-app.hush.music.debug}"
shift || true
MEDIA_IDS=("$@")

SPOTIFLAC_DIR="files/spotiflac/playback"
INDEX_FILE="files/spotiflac/playback_cache_index.json"
DB_FILE="databases/exoplayer_internal.db"
SESSION_MARKER="androidx.media3.session.id"

pass=0
fail=0
warn=0
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

ok()   { echo "  PASS  $*"; pass=$((pass + 1)); }
bad()  { echo "  FAIL  $*"; fail=$((fail + 1)); }
note() { echo "  WARN  $*"; warn=$((warn + 1)); }
info() { echo "        $*"; }

as_app() { adb exec-out run-as "$PKG" "$@" 2>/dev/null; }

# adb occasionally returns an empty result when it is busy with another call.
installed=0
for _ in 1 2 3; do
    installed="$(adb shell pm list packages 2>/dev/null | tr -d '\r' | grep -c "^package:${PKG}$")" || installed=0
    [[ "$installed" -gt 0 ]] && break
    sleep 2
done
echo "=== SpotiFLAC single-copy verification on $PKG ==="
if [[ "$installed" -eq 0 ]]; then
    echo "  FAIL  $PKG is not installed on the connected device"
    exit 2
fi

# When this build was installed, in epoch ms. Cache entries older than this were written
# by some earlier build and say nothing about the current one.
INSTALLED_AT_MS="$(
    adb shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r' |
        awk -F= '/lastUpdateTime=/{print $2; exit}' |
        python3 -c 'import sys,datetime
raw=sys.stdin.read().strip()
if not raw: print(0); raise SystemExit
print(int(datetime.datetime.strptime(raw,"%Y-%m-%d %H:%M:%S").timestamp()*1000))' 2>/dev/null
)"
[[ -n "$INSTALLED_AT_MS" ]] || INSTALLED_AT_MS=0
info "build installed at : $INSTALLED_AT_MS (epoch ms)"

# The song cache has no fixed location: the Storage screen can move it between the app's
# cache dir, its files dir and external storage. Measure whichever one actually holds data.
find_song_cache_dir() {
    as_app sh -c 'for d in files/exoplayer cache/exoplayer files/download cache/download; do
            [ -d "$d" ] && du -sk "$d" 2>/dev/null
        done' | sort -k1,1n | tail -1 | awk '{print $2}' | tr -d '\r'
}
SONG_CACHE_DIR="$(find_song_cache_dir)"
cache_dir_kb() {
    [[ -n "$SONG_CACHE_DIR" ]] || return 0
    as_app du -sk "$SONG_CACHE_DIR" | awk '{print $1}' | tr -d '\r'
}

# Each SimpleCache store has its own uid, named by the `<uid>.uid` file inside its
# directory. The one in the song-cache directory identifies the tables that belong to
# the song cache - and only those. The database also holds the *download* store's tables,
# whose entries are legitimate downloads and have nothing to do with this check.
SONG_CACHE_UID="$(as_app sh -c "ls \"$SONG_CACHE_DIR\"/*.uid 2>/dev/null" | head -1 | xargs -r basename | sed 's/\.uid$//' | tr -d '\r')"
info "song cache uid     : ${SONG_CACHE_UID:-<unknown>}"

# "state=PAUSED(2) position=120000" for Hush's own session. The dump lists every session
# on the device, so it has to be scoped to ours or it reports some other app's position.
session_state() {
    adb shell dumpsys media_session 2>/dev/null | tr -d '\r' |
        awk -v marker="$PKG/$SESSION_MARKER" '
            index($0, marker) { found = 1 }
            found && /PlaybackState \{state=/ { print; exit }
        ' |
        sed -e 's/.*state=\([A-Za-z]*\)(\([0-9]*\)), position=\([0-9]*\).*/state=\1(\2) position=\3/' |
        tr -d '\r'
}
session_position_ms() {
    session_state | sed -n 's/.*position=\([0-9]*\).*/\1/p'
}

cat > "$tmp/parse_index.py" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
by_key = {e["trackKey"]: e for e in data.get("entries", [])}
for media_id, key in (data.get("mediaIds") or {}).items():
    entry = by_key.get(key)
    if entry:
        print(f"{media_id}\t{entry['filePath']}\t{entry.get('sizeBytes', 0)}")
PY

# Prints "<table> <id> <newest span timestamp ms>" for a cached key, or nothing.
cat > "$tmp/scan_cache.py" <<'PY'
import re, sqlite3, sys
db, media_id = sys.argv[1], sys.argv[2]
uid = sys.argv[3] if len(sys.argv) > 3 else ""
con = sqlite3.connect(db)
newest = None
hit = None
# With a uid, exactly that store's table; without one, every store (reported below).
pattern = f"ExoPlayerCacheIndex{uid}" if uid else "ExoPlayerCacheIndex%"
for (table,) in con.execute(
    "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE ?", (pattern,)
):
    rows = con.execute(f'SELECT id, key FROM "{table}" WHERE key = ?', (media_id,)).fetchall()
    if not rows:
        continue
    for row_id, _key in rows:
        hit = (table, row_id)
        meta_table = table.replace("ExoPlayerCacheIndex", "ExoPlayerCacheFileMetadata")
        try:
            names = [n for (n,) in con.execute(f'SELECT name FROM "{meta_table}"')]
        except sqlite3.Error:
            continue
        for name in names:
            parts = name.split(".")
            if len(parts) < 3 or parts[0] != str(row_id):
                continue
            try:
                ts = int(parts[2])
            except ValueError:
                continue
            if newest is None or ts > newest:
                newest = ts
if hit:
    print(f"{hit[0]} {hit[1]} {newest if newest is not None else 0}")
PY

echo
echo "--- spotiflac playback cache ---"
as_app cat "$INDEX_FILE" > "$tmp/index.json"
if [[ ! -s "$tmp/index.json" ]]; then
    echo "  FAIL  no playback cache index at $INDEX_FILE (play a track via SpotiFLAC first)"
    exit 2
fi

# No `mapfile`: macOS ships bash 3.2, which does not have it.
INDEXED=()
while IFS= read -r line; do
    [[ -n "$line" ]] && INDEXED+=("$line")
done < <(python3 "$tmp/parse_index.py" "$tmp/index.json")

if [[ ${#MEDIA_IDS[@]} -eq 0 ]]; then
    MEDIA_IDS=()
    while IFS= read -r line; do
        [[ -n "$line" ]] && MEDIA_IDS+=("$line")
    done < <(printf '%s\n' "${INDEXED[@]+${INDEXED[@]}}" | cut -f1)
fi
if [[ ${#MEDIA_IDS[@]} -eq 0 ]]; then
    echo "  FAIL  the playback cache index has no media ids"
    exit 2
fi

for id in "${MEDIA_IDS[@]}"; do
    row="$(printf '%s\n' "${INDEXED[@]}" | awk -F'\t' -v id="$id" '$1==id')"
    echo
    echo "--- $id ---"
    if [[ -z "$row" ]]; then
        bad "$id is not in the SpotiFLAC playback cache"
        continue
    fi
    path="$(cut -f2 <<<"$row")"
    size="$(as_app stat -c %s "$path" | tr -d '\r')"
    info "spotiflac file : $path"
    info "size           : ${size:-0} bytes"
    if [[ -z "$size" || "$size" == "0" ]]; then
        bad "$id has no readable SpotiFLAC file"
        continue
    fi
    ok "$id audio is present once as a local file ($size bytes)"

    # ---- check 1: no other file in the data dir has the same byte size ----------
    echo "  check 1: size-collision scan (a copied file has an identical size)"
    collisions="$(
        as_app find . -type f -size +1M -exec stat -c '%s %n' {} + 2>/dev/null |
            tr -d '\r' | awk -v s="$size" '$1==s {print $2}'
    )"
    others="$(printf '%s\n' "$collisions" | grep -v '^$' | grep -v "^./$SPOTIFLAC_DIR/" || true)"
    if [[ -n "$others" ]]; then
        bad "another file has the same byte size as the SpotiFLAC copy: $others"
    else
        ok "no second file with $size bytes anywhere in the app data dir"
    fi

    # ---- check 2: the media id is not a key in Media3's song cache -------------
    echo "  check 2: media3 song-cache index"
    as_app cat "$DB_FILE" > "$tmp/cache.db"
    if [[ ! -s "$tmp/cache.db" ]]; then
        ok "song cache has no database yet - nothing was ever cached"
    else
        hit="$(python3 "$tmp/scan_cache.py" "$tmp/cache.db" "$id" "$SONG_CACHE_UID")"
        if [[ -z "$hit" ]]; then
            ok "the media id appears nowhere in the song-cache index"
        else
            table="$(cut -d' ' -f1 <<<"$hit")"
            cached_at="$(cut -d' ' -f3 <<<"$hit")"
            if [[ "$INSTALLED_AT_MS" -gt 0 && "$cached_at" -gt 0 && "$cached_at" -lt "$INSTALLED_AT_MS" ]]; then
                note "pre-fix residue: $id is a song-cache key, last written $(python3 -c "
import datetime,sys
print(datetime.datetime.fromtimestamp(int(sys.argv[1])/1000).strftime('%Y-%m-%d %H:%M:%S'))" "$cached_at"), before this build was installed"
                info "this entry cannot be rewritten by the current code; the LRU evictor will reclaim it"
            else
                bad "the media id is a song-cache key written by the current build: $table (at $cached_at)"
            fi
        fi
    fi
done

# ---- checks 3 + 4: seeking does not re-copy, and actually seeks ---------------
echo
echo "--- check 3+4: seeking ---"
info "song cache dir    : ${SONG_CACHE_DIR:-<not found>}"
before_kb="$(cache_dir_kb)"
info "song cache before : ${before_kb:-?} KB"
info "session state     : $(session_state)"

seeks=(60000 150000 20000)
for position_ms in "${seeks[@]}"; do
    adb shell am broadcast \
        -a app.hush.music.WAZE_COMMAND \
        -p "$PKG" \
        --es command seek \
        --el position "$position_ms" >/dev/null 2>&1
    sleep 3
    landed="$(session_position_ms)"
    if [[ -z "$landed" ]]; then
        bad "no transport position after seeking to ${position_ms} ms"
        continue
    fi
    delta=$((landed - position_ms))
    if [[ $delta -ge -5000 && $delta -le 15000 ]]; then
        ok "seek to ${position_ms} ms landed at ${landed} ms"
    else
        bad "seek to ${position_ms} ms landed at ${landed} ms"
    fi
done

after_kb="$(cache_dir_kb)"
info "song cache after  : ${after_kb:-?} KB"
if [[ -n "$before_kb" && -n "$after_kb" ]]; then
    growth=$((after_kb - before_kb))
    # Only the cache's own database/journal and index writes are allowed. A duplicated
    # song would add tens of megabytes, not tens of kilobytes.
    if [[ $growth -gt 512 ]]; then
        bad "song cache grew by ${growth} KB during seeking"
    else
        ok "song cache grew by only ${growth} KB during seeking (no duplicated audio)"
    fi
fi

echo
echo "=== $pass passed, $fail failed, $warn warn ==="
[[ $fail -eq 0 ]] || exit 1
