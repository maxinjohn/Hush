# SpotiFLAC 4.9.x → 5.0.x: is there a concrete driver for the Rust migration?

**Question.** Across the 258 commits `a34e2935..v5.0.7` and the 4.9.x-vs-5.0.x release notes, is
there any bug fix or capability that Hush actually needs and the pinned Go backend genuinely lacks?

**Verdict. No.** No 5.0.x fix or capability that Hush consumes is absent from the pinned Go backend.
The only measured drivers remain **APK size** and **leaving a maintained backend** — the argument in
`spotiflac-rust-migration-plan.md` §6 is unchanged, and is now stronger because the functional search
came back empty.

**Size revision (2026-10-08).** The "~34 MB" figure below is a raw-`.so` delta and does not survive
contact with the shipped artifact. At the release-APK level the migration buys **17.89 MB**, and
**rebuilding the committed Go AAR with `-ldflags="-s -w"` recovers 14.65 MB of it (81.9%)** by
removing DWARF and symbol tables that never needed to be in the APK. See
[`spotiflac-go-aar-size-investigation.md`](spotiflac-go-aar-size-investigation.md). Read every size
claim in this document as the raw-file comparison it is, not as an APK claim.

The rest of this document is the evidence, in the order it rules candidates out.

---

## 1. Evidence base

| Item | Value |
|---|---|
| Hush pin | `a34e2935` (2026-09-07 19:41), stated once in `scripts/spotiflac-backend-pins.sh` (`SPOTIFLAC_GO_BACKEND_SHA`); both build scripts read that file |
| Range searched | `a34e2935..v5.0.7` — 258 commits (252 non-merge); 43 touch `rust_backend` after the port |
| Rust migration commit | `aa997264` (2026-09-14) `feat(backend): migrate production backend to Rust` |
| Tag boundary | pin is **3 commits after `v4.9.6`** (2026-09-07) |
| 5.0.x release notes | **none exist** — `CHANGELOG.md` at `v5.0.7` still ends at `## [4.9.6]`; the commit log is the only record |
| Hush backend consumption | 22 Go method names referenced by name in `SpotiFLACNativeRuntimeBridge.kt`, of the 226 the AAR exposes (Phase 3 §3 counts 21 reflected methods incl. `invokeString`/`invokeVoid`; the 19-name list in F8 below over-counts — `completeGrant` is an *extension action string* passed to `invokeExtensionActionJSON`, not a backend method) |

---

## 2. Findings

### F1 — The Go backend is frozen at Hush's pin; Hush holds the *final* Go.

`git log a34e2935..v5.0.7 -- go_backend` returns **exactly one commit**: `aa997264`, the migration,
which **deletes** `go_backend/` (177 files removed) and adds `rust_backend/`. Nothing else in the
range touches Go.

```
$ git log --oneline --no-merges a34e2935..v5.0.7 -- go_backend
aa997264 feat(backend): migrate production backend to Rust
$ git cat-file -e aa997264:go_backend   # → absent
```

Consequence: there is no such thing as "Go fixes Hush missed." Every post-pin backend change is
Rust-only by construction. The question can only be answered by looking at what Rust *gained* 
after the port.

### F2 — Hush's pin already carries every 4.9.x fix.

`v4.9.6` is an ancestor of `a34e2935`; the pin is only 3 commits later (`259fb5b6`, `934c4af0`,
`a34e2935`). So the pin includes the entire 4.9.5 and 4.9.6 verification/session/download set Hush
depends on, notably:

| 4.9.6 fix | Hush-relevant? | In pin |
|---|---|---|
| Verification During Fallback (prepare each provider's session before availability; keep the requesting provider identity; pause fallback for its challenge) | yes — Hush's stall/refusal walk | yes |
| Verification Callbacks (shared challenge identity + callback retries under concurrency) | yes | yes |
| Download Timeouts (bound stalled stream resolution, not established transfers) | yes | yes |
| Track Matching (artist-credit order, punctuation, annotations; retain recording-version checks) | yes | yes |

### F3 — There is no 5.0.x changelog to mine.

`git show v5.0.7:CHANGELOG.md | grep '^## \['` still starts at `4.9.6`. The "5.0.x changelog" the
task refers to does not exist as a file; the Git history is the changelog. (The AltStore `apps.json`
still advertises `4.9.6`.)

### F4 — The backend surface Hush depends on is frozen since the migration.

Session, verification, resolver and the runtime-feature contract changed only cosmetically:

| Area | Post-migration change | Verdict |
|---|---|---|
| `signed_session/` | `exchange.rs` ±3, `protocol.rs` ±7 — both from `3e50dd75 chore(rust): update crypto…` | dependency bump only, no behaviour |
| `resolver/` | `fe88048d` *removes* code (`availability/cache.rs` −80, `urls.rs` −38) | dead-code removal |
| verification | **no commits** | frozen |
| `auth.rs` | `fe88048d` (−27), `3e50dd75` | dead-code + dep bump |

**Runtime-feature contract is byte-identical.** Go's `supportedRuntimeFeatures`
(`go_backend/extension_manager.go:275`) and Rust's `SUPPORTED_RUNTIME_FEATURES`
(`rust_backend/crates/extensions/src/environment.rs:28`, comment: *"Keep these contract versions
aligned with Go's"*) declare the same names at the same versions:

| feature | Go | Rust v5.0.7 |
|---|---|---|
| `signedSession` | 3 | 3 |
| `sessionRefresh` | 1 | 1 |
| `sessionGrant` | 1 | 1 |
| `globalAction` | 1 | 1 |
| `webviewAuth` | 1 | 1 |
| `downloadSegments` | 1 | 1 |
| `patternedFileTransform` | 1 | 1 |
| `preparedContext` | 1 | 1 |

### F5 — The extension ecosystem cannot force a migration.

`requiredRuntimeFeatures` and `minAppVersion` are enforced identically on both sides — Go at
`validateManifestGates` (`extension_manager.go:286`) against the map in F4, Rust at
`environment.rs:671`. A `.sflx` that installs on Rust installs on Go. There is no extension the Go
runtime would refuse today. **No ecosystem forcing function exists.**

### F6 — The first post-migration "fixes" were migration *regressions*, not Go gaps.

`eb717a6e fix(extensions): restore URL globals for provider link resolution` (2026-09-15, one day
after the port) *adds back* `URL`/`URLSearchParams` polyfills. Go already had them:

```
$ git grep -n 'Set("URL' a34e2935 -- go_backend/extension_runtime_polyfills.go
235:  vm.Set("URL", …)          # registerURLClass
328:  vm.Set("URLSearchParams", …)
```

The Rust port dropped them and had to re-add them. That is evidence the Rust backend started
**behind** Go, not ahead of it — the opposite of a driver.

### F7 — Every genuinely-new Rust-only change is outside what Hush consumes.

Post-migration commits that touch the extension runtime, download, network, or the mobile binding
surface, classified:

| Commit | Change | Hush relevance |
|---|---|---|
| `ae785194` | provider-failure logging (`Provider {id} failed during {stage}: {reason}`, tag `DownloadPlanner`) | **new diagnostic Go never had** — but Hush's `FAILURE_LINES` regex does not match it (F9); not consumed |
| `b2ea07c4` | download response/embedded tags prefer **source** artist credits over provider credits | Go prefers the *provider* artist (`exports_download.go:159` `artist := result.Artist; if artist=="" { artist = req.ArtistName }`); nuance only — Hush resolves its own track metadata |
| `097af153` | read tags/covers from MP4 files named `.opus` | no — Hush does not read tags through the backend |
| `41340a4a` | primary-artist tag mode in native writers | no — Hush does not call tag writers |
| `1bae8156`, `14ce4bc8` | Hi-Res authenticity analysis | no — Hush does not call analysis |
| `fe36f089`, `7ef0a187` | library scan pause / scan efficiency | no — Hush has its own library |
| `c5789df8` | SAF document-access retention for library metadata | no — Hush has its own SAF layer |
| `44b7ceb8` | connection reuse across probes/retries | Rust-only perf; not a capability Go lacks |
| `96b037bb` | skip FLAC rewrites for identical metadata | Rust-only perf |
| `1d53609b` | avoid blocked `statx` probes on legacy ARM32 | Rust-specific (`rustix`); Go's ARM32 fix (`Go 1.26.5`, 4.8.0) is already in the pin |
| `cb5ce3df`, `a9a395b9`, `2c4e3916`, `054041e4`, `26a97b44` | extension-bridge / JSON perf | Rust-only perf (behaviour-preserving) |
| `3e50dd75` | crypto/network/UniFFI dep bump | neither |

### F8 — Hush does not consume the backend surfaces most of 5.0.x churned.

The 19 method names Hush actually invokes:

```
cancelDownload · checkExtensionHealthJSON · checkExtensionUpgradeFromPath · completeGrant ·
downloadByStrategy · getAllPendingAuthRequestsJSON · getExtensionPendingAuthJSON ·
getInstalledExtensions · getLogsSince · initExtensionSystem · invokeExtensionActionJSON ·
loadExtensionFromPath · loadExtensionsFromDir · setAppVersion · setExtensionEnabledByID ·
setExtensionSessionGrantByID · setLoggingEnabled · setRuntimeState · waitForMultiProgressDelta
```

That is **download + extension lifecycle + signed session/auth + logs** and nothing else. There is no
resolve, search, metadata, tag-write, ReplayGain, hi-res, or library-scan call. Almost all of the
post-migration backend churn (F7) is therefore out of scope by construction.

### F9 — Migration would *regress* Hush's stall attribution.

Hush's walk parses **Go** log phrasings (`SpotiFLACRuntimeWalk.kt` L42–47):

```
Trying provider: <x>                              Provider temporarily unavailable for extension <x>
Provider <x> maps requested quality               Extension service '<x>' moved to priority front
Source extension <x> failed                       Track source is extension '<x>' matching selected provider
```

Phase 3 established that **0 of these 6** exist in the Rust backend. The `ae785194` addition
(`Provider {id} failed during {stage}: {reason}`) is *closest*, but Hush's `FAILURE_LINES`
(`([A-Za-z0-9._-]+)\s+failed\s*:`) needs `<token> failed:` immediately, and `deezer failed during
download:` has words between `failed` and `:` — so it does not match either. Migrating costs Hush
its provider attribution until the phrasings are re-derived from a Phase 5 capture (Phase 3 D2).

---

## 3. What a real driver would look like — and why none is present

| Candidate forcing function | Present today? | Evidence |
|---|---|---|
| A bug only fixed in Rust that Hush hits (download/verification/session) | **No** | F1, F4 — those crates are frozen; the only functional downloads.rs fixes are F7 `ae785194`/`b2ea07c4` |
| An extension requiring a runtime feature Go lacks | **No** | F4, F5 — feature maps identical |
| A provider/session contract change on the wire | **No** | Phase 3 §3: `downloadByStrategy` is 1:1; `runtime_state`/health payloads compatible |
| The Go AAR breaking on a newer ABI/NDK/Android | not observed | Go AAR intact (67,288,791 B) and building |
| Security fix reaching only Rust | **No** | 4.9.5 sandbox/credential/network hardening is Go-era (already in pin); no post-migration security commit |

---

## 4. Verdict and next decision

**No concrete functional driver exists.** The migration's justification remains what the plan stated:
**APK size** and **leaving an unmaintained backend** (Go is deleted upstream — `go_backend/` does not
exist at `v5.0.7`, so pinned Go receives no upstream fixes, for better or worse). The size half is
raw-file arithmetic that the APK does not collect on: arm64 19.2 vs 33.8 MB and armv7 13.7 vs 33.3 MB
is **17.89 MB at the release-APK level**, not ~34 MB, and **14.65 MB of that is one `-ldflags` flag
away without migrating at all** — see
[`spotiflac-go-aar-size-investigation.md`](spotiflac-go-aar-size-investigation.md). After stripping
both backends the residual gap is 4.68 MB, which is a real but much smaller number to weigh against
Phase 4–6.

Corollaries that follow from the evidence:

1. There is **no urgency**. Nothing broke, nothing is missing, and no extension is blocked. Phase 4–6
   are a size-and-maintenance project, not a bug-fix project.
2. If the migration proceeds, budget for the two **regressions** it introduces, both already in
   Phase 3: re-derive the 6 log phrasings (D2) and `getAllPendingAuthRequestsJSON` (only MISSING
   method — core has `ExtensionEnvironment::pending_auth`, not `#[uniffi::export]`ed). `fe88048d`
   further retired bridge entry points, so re-confirm the Phase 3 §3 count against a fresh binding.
3. Staying on Go is *safe* as long as nothing above changes. Set a revisit trigger rather than a
   date — e.g. the first time an extension declares a `requiredRuntimeFeatures` entry absent from
   the F4 table, or the first download/verification bug that must be fixed in the backend.
