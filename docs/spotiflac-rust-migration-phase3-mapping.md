# SpotiFLAC Go → Rust migration — Phase 3: integration mapping

**Scope.** Every capability `SpotiFLACNativeRuntimeBridge` exposes today, mapped onto the Rust
backend's UniFFI surface at upstream **v5.0.7**, with each row marked as direct / folded / split /
Hush-local / missing / diverged. Written *before* any Kotlin change, per the plan's Phase 3.

**Authority.** Upstream `spotiflacapp/SpotiFLAC-Mobile` at tag **v5.0.7** (`19cb7033`), read from the
isolated worktree `/Users/maxin/Projects/SpotiFLAC-Rust-v5.0.7`. The *current* Hush integration is
read from the pinned Go tree `a34e2935` at
`/Users/maxin/Projects/Hush/SpotiFLAC-Mobile/go_backend/` plus the shipped
`app/libs/gobackend.aar`.

**Acceptance criterion (from the plan).** A table with **zero unmapped rows**; every "missing" row
carries an owner decision. This document closes that criterion — see §8.

---

## 0. Bottom line

**The migration is materially cheaper than §2c of the plan estimated, and it has one hard problem
the API surface alone could not reveal.**

1. **The high-level entry point exists and is Go-compatible.**
   `ExtensionManager.downloadByStrategy(requestJson)` is a **1:1** replacement for the Go
   `downloadByStrategy` Hush already calls — same name, same request JSON, same response contract
   (§6.1). §2c concluded from the interface list that *"there is no single high-level resolve"* and
   that Phase 4 would have to **compose** repository → manager → environment → download state →
   progress. **That conclusion is wrong**, and it is the single largest correction in this document:
   the resolve path is a call swap behind an object graph, not a composition to be invented.

2. **20 of the 21 reflected Go methods have an equivalent in the Rust surface** (§3) — 14 as a call
   swap, 3 folded into construction, 1 split across two calls, 2 equivalent-but-diverged. The one that
   has **none** is `getAllPendingAuthRequestsJSON` — and the *core* already has that function, it is
   simply not exported (§5.1).

3. **The one genuine reimplementation is not in the API surface: it is in the log text.**
   `SpotiFLACRuntimeWalk` and the provider-stall/refusal ingestion parse **six specific Go log
   phrasings** out of the runtime's log buffer. **None of the six exists anywhere in the Rust
   backend** (§5.2). This degrades silently, produces no compile error, and touches the watchdog that
   decides which provider stalled — so it is the highest-value item in this document.

4. **Two things that looked like blockers are non-issues, verified rather than assumed**: the
   storage *master key*, which Hush already holds in `SpotiFLACExtensionKeyStore` and which becomes a
   constructor argument (§3 rows 2–3), and the `runtime_state` payload schema (§6.3).

### Owner decisions required before Phase 4 starts

| # | Decision | Owner | Default if not taken |
| --- | --- | --- | --- |
| D1 | Reimplement "all pending auth" in Hush by polling per source, or ask upstream to export `ExtensionEnvironment::pending_auth` | Hush (small) / upstream (smaller) | Poll per enabled source in Hush |
| D2 | Re-derive the six log phrasings from Rust's actual output, verified against a captured device log | Hush | Ship degraded: stall attribution falls back to the requested source, refusals go unrecorded |
| D3 | Implement the FFmpeg command queue (needs an FFmpegKit dependency), or keep the pre-existing gap | Hush | Keep the gap — parity with today, but `embed_replaygain` stays inert (§5.3) |
| D4 | Adjust `summariseExtensionLoad` for Rust's report shape (`loaded: null`, no "already installed" skip entries) | Hush (cosmetic) | Diag line reads `skipped=0 errors=0` instead of `loaded=8 skipped=8` |

---

## 1. Method and evidence

Every count and every verdict below was measured on disk, not recalled:

| Fact | Command / file |
| --- | --- |
| The bridge's Go coupling is **reflection**, not a typed API | `SpotiFLACNativeRuntimeBridge.kt:81` `GOBACKEND_CLASS = "gobackend.Gobackend"`; `invokeString`/`invokeVoid` at `:3151`/`:3162` resolve by *name and arity* |
| **21** distinct Go methods are called | parsed every `invokeString(`/`invokeVoid(` call site in the bridge, with whitespace-tolerant matching so **multi-line** calls count — a naive grep misses `setExtensionStorageMasterKey` and `setProviderPriorityJSON`, both of which are formatted across lines |
| The Rust surface | generated `rust_backend/target/bindings/kotlin/com/spotiflac/backend/spotiflac_mobile.kt` (8683 lines) |
| Rust semantics | `rust_backend/crates/mobile/src/*.rs` (3696 lines total) + `crates/extensions/src/` |
| Upstream's own Rust consumer | `android/app/src/rust/kotlin/com/zarz/spotiflac/SelectedCoreBackend.kt` (835 lines) — the reference integration, and the source of several mapping verdicts |

**Caveat on §2c's "187 plain functions".** The generated file exposes **135 methods on 12 domain
interfaces** (plus 4 records, 1 enum, 11 error types, and 5 uniffi plumbing interfaces — 17 interfaces
in total, which is where §2c's "17 interfaces" comes from). I could not reproduce 187 under any
counting of the generated Kotlin: method declarations indented one level number **183**, and interface
members number **135**. Treat "135 domain methods" as the figure to plan against.

---

## 2. The three surfaces

| Surface | Size | Shape |
| --- | --- | --- |
| **Go, as Hush uses it** | 21 methods | static methods on `gobackend.Gobackend`, reached by reflection; every argument and return value crosses as `String`/`Long`/`Boolean` |
| **Rust (UniFFI)** | 12 domain interfaces, 135 methods, 0 `suspend` | **object handles** (`AutoCloseable`/`Disposable`) with typed arguments and typed errors; one top-level function (`sanitizeFilename`) |
| **Hush bridge** | 3236 lines: **36 public functions, 3 public properties**, 46 private helpers | the integration contract every Hush surface already talks to |

The migration uses **five** of the twelve Rust interfaces — `ExtensionManager`, `ExtensionRepository`,
`ExtensionEnvironment`, `DownloadState` and `LogBuffer` — plus `FfmpegCommands` only if D3 is taken up.
The rest (`UsbDirectOutput`, `JsExtension`, `RequestLease`/`CancellationRegistry` as direct handles,
and the lyrics/tags/usb block on `ExtensionManager`) are surfaces Hush does not consume today.

**The load-bearing difference** is static-vs-handle. Go was a singleton behind 21 static calls; Rust
gives objects that must be created in order, held, and closed. Hush must own a small object graph:

```
ExtensionManager.new(sourceDir, dataDir, masterKey, appVersion, timeoutMs)   // the owner
  ├─ ExtensionRepository.new(manager, cacheDir)        // registry + package download
  └─ manager.environment()                              // per-call handle
       ├─ downloadState()   -> DownloadState            // progress + cancellation
       ├─ logBuffer()       -> LogBuffer                // getLogsSince
       ├─ ffmpegCommands()  -> FfmpegCommands           // the queue Hush does not service (§5.3)
       └─ setRuntimeState / setSessionGrant / setAuthCode / grantDownloadDirectories…
```

That graph is the whole of what `initializeRuntime` (51 lines today) becomes. Everything else is a
call swap.

---

## 3. Backend method mapping — all 21 reflected Go methods

Verdict vocabulary: **DIRECT** = same call and semantics · **FOLDED** = same semantics, different
shape (a constructor argument rather than a setter) · **SPLIT** = one Go call becomes two Rust calls ·
**MISSING** = no Rust equivalent.

| # | Hush passes (Go, `gobackend.Gobackend`) | Rust (v5.0.7) | Verdict | Notes |
| --- | --- | --- | --- | --- |
| 1 | `initExtensionSystem(extensionsDir, dataDir)` | `ExtensionManager.new(source_directory, data_directory, master_key, app_version, timeout_ms)` | **FOLDED** | Construction *is* the init. Same two directories. `app_version` here replaces #3. |
| 2 | `setExtensionStorageMasterKey(base64)` | constructor arg `master_key` | **FOLDED** | Hush already holds this value: `SpotiFLACExtensionKeyStore.masterKeyBase64(context)`. No new secret to source. |
| 3 | `setAppVersion(v)` | constructor arg `app_version` | **FOLDED** | `RUNTIME_APP_VERSION` moves from a setter to an argument. It is part of the signed-session scope string, so it must not change (§6.2). |
| 4 | `setLoggingEnabled(true)` | `env.logBuffer().setEnabled(true)` | **DIRECT** | Handle rather than static. |
| 5 | `setRuntimeState(json)` | `env.setRuntimeState(raw)` | **DIRECT** | Schema verified compatible — §6.3. |
| 6 | `loadExtensionsFromDir(dir)` | `manager.loadAll()` | **DIVERGED** | Report shape differs cosmetically — §5.4 (D4). Directory must equal the manager's `source_directory` (upstream asserts this). |
| 7 | `loadExtensionFromPath(path)` | `manager.install(packagePath)` | **DIRECT** | "already installed" survives as a substring in Rust's error text, so Hush's `ALREADY_INSTALLED` match still works — §6.4. |
| 8 | `upgradeExtensionFromPath(path)` | `manager.upgrade(packagePath)` | **DIRECT** | |
| 9 | `checkExtensionUpgradeFromPath(path)` | `manager.checkUpgrade(packagePath)` | **DIRECT** | Response keys (`extension_id`, `current_version`, `new_version`, `can_upgrade`) verified present. |
| 10 | `getInstalledExtensions()` | `manager.installed()` | **DIRECT** | JSON array; each entry carries `id`, `name`, `version` — exactly the three keys `runtimeInstalledVersions` reads. |
| 11 | `setExtensionEnabledByID(id, on)` | `manager.setEnabled(extensionId, enabled)` | **DIRECT** | |
| 12 | `setExtensionSessionGrantByID(id, grant)` | `env.setSessionGrant(id, grant)` **+** `manager.invokeAction(id, "completeGrant")` | **SPLIT** | Hush's `deliverGrant` already documents this exact two-step as "the exact upstream handshake"; Rust makes the second step explicit. Upstream's `completeSessionGrant` does both. |
| 13 | `invokeExtensionActionJSON(id, action)` | `manager.invokeAction(extensionId, action)` | **DIRECT** | |
| 14 | `checkExtensionHealthJSON(id)` | `manager.checkExtensionHealthJson(extensionId)` | **DIRECT** | Spelling only (`JSON` → `Json`). |
| 15 | `getExtensionPendingAuthJSON(id)` | `manager.getExtensionPendingAuthJson(extensionId)` | **DIRECT** | |
| 16 | `getAllPendingAuthRequestsJSON()` | — | **MISSING** | §5.1. |
| 17 | `downloadByStrategy(requestJson)` | `manager.downloadByStrategy(requestJson)` | **DIRECT** | The whole resolve path. Request/response contracts are Go-compatible by construction — §6.1. |
| 18 | `cancelDownload(itemId)` | `env.downloadState().cancelDownload(itemId)` | **DIRECT** | |
| 19 | `waitForMultiProgressDelta(seq, 400)` | `env.downloadState().waitProgressDelta(since, timeoutMs)` | **DIRECT** | Same blocking-delta semantics; `DownloadState` is the one instance shared by the download manager and its listeners. |
| 20 | `setProviderPriorityJSON(jsonArray)` | `manager.setProviderPriority(kind, ids)` | **DIVERGED** | Go took a JSON array of ids; Rust takes `kind` **and** an id list. Hush must pass `kind = "download"`, which is what upstream passes for the download order. Trivial, but it is a call-shape change, not a rename. |
| 21 | `getLogsSince(0)` | `env.logBuffer().since(index)` | **DIRECT** | Contents are *not* equivalent — §5.2. |

**Result: 21/21 mapped — 14 DIRECT, 3 FOLDED, 1 SPLIT, 2 DIVERGED, 1 MISSING.** No backend method is
unaccounted for.

Two Go capabilities Hush does **not** use today, for the record — they exist in the AAR and are not in
the bridge's 21: the FFmpeg command queue (`GetAllPendingFFmpegCommandsJSON`,
`WaitForPendingFFmpegCommandsJSON`, `SetFFmpegCommandResultByID`). Rust exposes the same shape
(`FfmpegCommands.waitPending/getCommand/complete`). See §5.3 — this is a pre-existing gap, not one the
migration creates.

---

## 4. Capability mapping — all 36 public functions and 3 public properties

Grouped by the concern they serve. "Backend calls" is what the capability needs from the runtime,
resolved through §3. **Hush-local** means the capability involves no backend call at all and is
therefore *unaffected* by the migration — this is the majority, and it is why the migration is
smaller than the line count of the bridge suggests.

### 4.1 Runtime lifecycle (2 + 1)

| Capability | Backend calls | Verdict | Notes |
| --- | --- | --- | --- |
| `isRuntimeAvailable` | reflects class + `initializeRuntime` | **DIVERGED** | Becomes "is the `ExtensionManager` constructed and not shut down". The *reflection probe* disappears; a Rust build that fails to load `.so` fails at construction instead. Hush must keep a distinct reason for the UI (`lastUnavailableReason`). |
| `lastUnavailableReason` | — | **HUSH-LOCAL** | String Hush sets; unchanged. |
| `initializeRuntime` (private) | #1,#2,#3,#4,#5,#6 | **FOLDED** | The 51-line startup sequence becomes object construction + handle wiring (§2). |

### 4.2 Resolve and download (4)

| Capability | Backend calls | Verdict | Notes |
| --- | --- | --- | --- |
| `resolve(...)` | #11,#12,#17,#20 (+ progress #19) | **DIRECT** | The sweep, the cache-first short-circuit, the media-id second chance, the per-track mutex, the stall watchdog and the miss memo are **all Hush code** and all survive unchanged. Only `downloadViaExtension`'s single call swaps. |
| `downloadProgress` (property) | #19 | **DIRECT** | Hush's own `MutableStateFlow`, fed from `waitProgressDelta`. |
| `clearDownloadProgress` | — | **HUSH-LOCAL** | |
| `cancelDownload(mediaId)` | #18 | **DIRECT** | Note the id reshape: Hush passes `"hush-$mediaId"` to the runtime. Preserved verbatim. |

### 4.3 Playback file cache (10) — **all HUSH-LOCAL**

| Capability | Backend calls | Verdict | Notes |
| --- | --- | --- | --- |
| `playbackFileForMediaId` | — | **HUSH-LOCAL** | 11-line delegate to `SpotiFLACPlaybackCache` |
| `discardPlaybackFile` | — | **HUSH-LOCAL** | |
| `cachedPlaybackSourceForMediaId` | — | **HUSH-LOCAL** | |
| `cachedPlaybackEntryForMediaId` | — | **HUSH-LOCAL** | |
| `cachedPlaybackMediaIds` | — | **HUSH-LOCAL** | |
| `removeCachedPlaybackForMediaId` | — | **HUSH-LOCAL** | |
| `discardCachedPlaybackForMediaId` | — | **HUSH-LOCAL** | |
| `cacheStats` | — | **HUSH-LOCAL** | |
| `clearPlaybackCache` | — | **HUSH-LOCAL** | |
| `reconcilePlaybackCache` | — | **HUSH-LOCAL** | |

Every one is a two- to eleven-line delegate to Hush's own `SpotiFLACPlaybackCache` (an index on disk,
not a runtime structure). **Zero backend involvement** — 10 of the 36 public functions are untouched by
the migration. The only coupling is *indirect*: the runtime reuses an existing `output_path`
(`already_exists`), which `discardPlaybackFile` exists to defeat, and that behaviour is preserved in
Rust (`already_exists` is still emitted — §6.1).

### 4.4 Sessions and verification (14)

| Capability | Backend calls | Verdict | Notes |
| --- | --- | --- | --- |
| `pendingRuntimeAuth()` | #16 | **MISSING** | §5.1 — needs a Hush-side replacement. |
| `challengeOwnerFor(extensionId)` | — | **HUSH-LOCAL** | Reads the extension manifest. |
| `deliverGrant(grant, sourceIds)` | #12 | **SPLIT** | Two Rust calls; the owner-targeting rule (one grant belongs to one extension) is Hush logic and unchanged. |
| `pendingAuthFor(extensionId)` | #11,#15 | **DIRECT** | |
| `ensureChallenge(extensionId)` | #11,#14,#15 | **DIRECT** | The health probe raises the challenge; same flow upstream drives via `invokeAction`. |
| `isExtensionAuthenticated(id)` | — | **HUSH-LOCAL** | Deliberately *not* the runtime's own `isExtensionAuthenticatedByID` (documented as answering false for working sources). Unchanged. |
| `pendingRuntimeAuthUrl()` | #16 | **MISSING** | Composes `pendingRuntimeAuth()`; fixed with D1. |
| `sourceAuthState(sourceId)` | — | **HUSH-LOCAL** | Derived from the on-disk signed-session record (`signedSessionFileFor`/`hasSignedSession`), not a backend call. **This is why §2's session carry-over proof is sufficient**: the records Hush inspects are the same records, in the same place, in the same format. |
| `isSourceVerified(sourceId)` | — | **HUSH-LOCAL** | |
| `unverifiedDownloadSourceIds()` | — | **HUSH-LOCAL** | Reads manifests + records. |
| `sourcesNeedingUserVerification()` | — | **HUSH-LOCAL** | Restores from vault → renews → reports the remainder. All Hush HTTP + file work. |
| `renewDueSessions(reason)` | — | **HUSH-LOCAL** | `SpotiFLACSessionRenewer`'s own gateway calls. Independent of the backend. |
| `prepareForPlayback(sourceIds)` | #1,#6,#7,#11 | **DIRECT** | Warm-up sequence; same order, handle-based. |
| `purgeForeignSeededSessions()` | — | **HUSH-LOCAL** | Pure file pruning of records seeded by an older Hush build. |

### 4.5 Package and extension management (6)

| Capability | Backend calls | Verdict | Notes |
| --- | --- | --- | --- |
| `testSource(sourceId)` | #11,#14 (+#15) | **DIRECT** (one vocabulary nuance) | Payload verified compatible: Rust returns `extension_id`, `status`, `checks` — the keys Hush reads — with `online` among the values, which is the one `HEALTHY_STATUSES` already accepts. Rust additionally emits **`degraded`** and **`unknown`** (`backend/health.rs:141`–`:143`), which Hush's set does *not* accept, so a degraded source reads as a failed Test where Go may have said otherwise. Decide in Phase 4 whether that is the wanted answer. |
| `updateExtensionPackage(sourceId)` | #9,#8,#10 (+ repository) | **DIRECT** | |
| `extensionChecks()` | #10 | **DIRECT** | |
| `reconcileExtensionPackages(...)` | #8,#9,#10 | **DIRECT** | |
| `installedExtensionVersions(sourceIds)` | #10 | **DIRECT** | One `installed()` call for the whole list, as today. |
| `installedSweepSourceCount()` | #10 | **DIRECT** | |

### 4.6 Diagnostics and provider health (3)

| Capability | Backend calls | Verdict | Notes |
| --- | --- | --- | --- |
| `dumpRuntimeLogs(reason)` | #21 | **DIVERGED** | The *call* is a direct swap; the **contents are not equivalent** — §5.2. |
| `providerHealthNotes()` | — | **HUSH-LOCAL** | Reads Hush's own persisted demotions. |
| `sweepAttemptWindowMs(sourceIds)` | — | **HUSH-LOCAL** | Hush's own measured timings (`ProviderTimings`). |

### Coverage summary

Over the **39 public capabilities** (the §4.1 private `initializeRuntime` is counted separately, since
it is the startup sequence rather than a capability a Hush surface calls):

| Verdict | Count | Meaning for Phase 4 |
| --- | --- | --- |
| **HUSH-LOCAL** | **22** | No work at all — more than half the surface. |
| **DIRECT** | **12** | Call swap. |
| **DIVERGED** | 2 | `isRuntimeAvailable` (probe becomes construction) and `dumpRuntimeLogs` (content, §5.2). |
| **MISSING** | 2 | `pendingRuntimeAuth`, `pendingRuntimeAuthUrl` — one shared fix (D1). |
| **SPLIT** | 1 | `deliverGrant` — two Rust calls for one Go call. |
| | **39** | **Zero unmapped.** |

Plus one private helper, `initializeRuntime` — **FOLDED** into object construction (§2), 40 rows in
total in §4.

The two DIVERGED rows and the §5.2 log contract are the only places where Hush code changes for
reasons other than a renamed call; D1–D4 are the decisions they need.

---

## 5. What must be reimplemented in Hush

### 5.1 All-pending-auth — **MISSING** (D1)

`getAllPendingAuthRequestsJSON` is the only Go method with no Rust counterpart. The generated surface
offers per-extension `getExtensionPendingAuthJson(id)` only (and `ExtensionEnvironmentInterface`
exposes `resolveCallbackState`/`consumeCallbackState`, which are the *callback* half, not the
challenge list).

Worth knowing: the **core already has it** — `ExtensionEnvironment::pending_auth(&self, id)`
(`crates/extensions/src/environment.rs:608`) — it is simply not one of the `#[uniffi::export]`
methods. So the cheap fix may be upstream-side.

Two options, both small:

- **(a) Hush-side, no upstream dependency.** `pendingRuntimeAuth()` iterates the enabled source ids,
  calls `getExtensionPendingAuthJson(id)` for each, and returns the first non-empty — which is what
  `runtimePendingAuthFor` already does per source. Cost: N cheap calls instead of 1, on a path that
  runs when a download reports `verification_required`. Hush's existing `pendingAuthFor` already
  proves the shape works.
- **(b) Upstream-side, smaller in Hush.** Ask upstream to export `pending_auth` on
  `ExtensionEnvironment`, or to add an all-pending accessor on `ExtensionManager`.

**Recommended:** (a) now, so Phase 4 has no external dependency; note (b) upstream for later.

### 5.2 The log-scraping contract — **the real reimplementation** (D2)

`SpotiFLACRuntimeWalk` turns the runtime's free text into two decisions: *which provider the fallback
walk had reached* when an attempt stalled, and *which sources refused service* (used to demote a
rate-limited provider). It matches six Go phrasings:

```
Trying provider: <id>                                  Provider temporarily unavailable for extension <id>
Provider <id> maps requested quality                    Extension service '<id>' moved to priority front
Source extension <id> failed                            Track source is extension '<id>' matching selected provider
```

**Measured: all six are absent from the entire Rust backend (0/6).** So under the Rust backend:

- `providerOf(...)` returns `null` on every stall → Hush falls back to the *requested* source, and the
  stall is attributed to the wrong provider. This is not cosmetic: the stall demotion is what keeps a
  wedged provider from eating the sweep budget for the providers behind it.
- `recordServiceRefusals(...)` matches nothing → a provider that refuses never gets demoted, so the
  relay-block/all-providers-failed paths that depend on it lose their evidence.

**This cannot be fixed by re-targeting a call, and it does not fail at compile time.** Rust *does*
log around the walk — `Provider {id} failed during {stage}: {reason}`
(`crates/extensions/src/backend/downloads.rs:547`), `Fallback stopped by {service}: {reason}` (`:1747`),
`All providers failed. Last error: …` (`:376`) — but naming those strings is **not** sufficient
evidence that they are emitted on the paths Hush parses. **Owner decision D2:** in Phase 5, capture a
real Rust log from an induced stall and a real refusal on the device, then re-derive the patterns from
what is actually there; treat the pattern set as data, and add a test that fails if a pattern matches
nothing in a captured log.

### 5.3 FFmpeg command queue — pre-existing gap, **preserved** (D3)

Both backends require the **host** to service FFmpeg: Go exposes
`GetAllPendingFFmpegCommandsJSON`/`WaitForPendingFFmpegCommandsJSON`/`SetFFmpegCommandResultByID` and
blocks up to 5 minutes inside the extension runtime waiting for a result
(`go_backend/extension_runtime_ffmpeg.go:78`–`:134`); Rust exposes
`FfmpegCommands.waitPending/getCommand/complete`, and upstream's Android app services it from Kotlin
with `com.antonkarpenko:ffmpeg-kit-full` (`NativeFinalizerFFmpeg.kt`, 261 lines).

**Hush's bridge services neither** — none of the 21 reflected methods is FFmpeg-related. So the
migration *preserves* today's behaviour exactly: a source whose extension calls `ffmpeg.convert`
(a `rawFfmpeg` extension, or any path that honours `embed_replaygain`) waits on a queue nobody drains.
Hush sets `embed_replaygain` from a user preference (default off), `embed_metadata: false` and
`post_processing_enabled: false`, so the default path never queues a command.

**Owner decision D3:** either implement the loop (new dependency, a real feature), or keep the gap and
document it. The migration is not the cause and does not make it worse; it does make it *visible*,
because `FfmpegCommands` now sits plainly on the environment handle.

### 5.4 `loadAll` report shape — cosmetic (D4)

Rust's `load_all` returns `{"loaded": <array|null>, "errors": [String]}`. Two differences from Go that
only affect Hush's diagnostic line: `loaded` is **`null` when empty** (Hush's `as? JsonArray` then
yields nothing and drops the `loaded=` prefix), and Rust reports an *already-loaded* extension as a
success rather than an `"already installed"` entry inside `errors` (so Hush's skip-counting finds
nothing to count). Neither is an error path; `summariseExtensionLoad` needs a small adjustment or it
will read `skipped=0 errors=0` for a healthy load.

---

## 6. Risks, with the evidence that settles each

### 6.1 The download request/response contract is Go-compatible — **verified in source**

Hush builds a **31-key** request JSON (`buildRequest`, `contract_version: 1`). Rust's
`DownloadRequest` (`crates/core/src/downloads.rs:12`) declares the same snake_case field names, and
deserializes them through `go_deserialize!`, which **ignores unknown keys** and folds camelCase to
snake_case. Every key Hush sends is either a declared field or ignored:

- **Present and used:** `contract_version`, `isrc`, `service`, `download_provider`,
  `provider_track_id`, `spotify_id`, `track_name`, `artist_name`, `album_name`, `album_artist`,
  `cover_url`, `output_dir`, `output_path`, `output_ext`, `filename_format`, `quality`,
  `embed_metadata`, `embed_lyrics`, `embed_replaygain`, `post_processing_enabled`, `track_number`,
  `disc_number`, `total_tracks`, `release_date`, `item_id`, `duration_ms`, `source`,
  `use_extensions`, `use_fallback`, `output_fd`.
- **Ignored:** `storage_mode` (`"app"`) — Rust has no such field. The destination comes from
  `output_path`/`output_dir`, both of which Hush sets absolutely. **Phase 5 must confirm the file
  lands where it does today**; the field that used to decide it is gone.

Response shape is likewise deliberate parity: `download_by_strategy`'s own doc comment says *"Go's
strategy facade returns a JSON failure"*, and it emits `success`, `message`, `already_exists`,
`error_type` — including `error_type: "verification_required"`, which is exactly the string
`downloadViaExtension` keys the verification prompt off. `native_error_response` classifies failures
by the same message substrings Go did (`isp_blocked`, `cancelled`, `verification_required`, …).
`use_extensions: false` is refused explicitly ("built-in download providers have been retired"), and
Hush sends `true`.

### 6.2 The session record is the same record in the same place

§2 of the plan proved the filename rule and the record format against the live device. Nothing in this
mapping weakens that: `sourceAuthState`, `hasSignedSession` and `signedSessionFileFor` are
**Hush-local** file reads (§4.4), and Hush passes the same `data_directory`
(`files/spotiflac/extension_data`) that `initExtensionSystem` receives today. The manager's
constructor takes it explicitly, so the plan's requirement stands unchanged: **assert the root by
test**, because getting it wrong makes every source read as unverified.

One consequence worth stating: `app_version` is part of the signed-session *scope* string. It moves
from `setAppVersion` to a constructor argument (#3) — **its value must not change** during the
migration, or every session silently re-keys.

### 6.3 The `runtime_state` payload schema is compatible

Hush sends `{"v":1,"d":<installId>,"s":{}}`. Rust's `RuntimeHints` deserializer
(`crates/extensions/src/signed_session/store.rs:15`) reads `d` and `s`, ignores unknown keys (so `v`
is harmless), and normalizes. `d` is the install id, which is what keeps sessions bound to the same
gateway identity. **Compatible as-is — no change to `buildRuntimeStatePayload`.**

### 6.4 `install()` on an already-installed package still reads as "already installed"

Rust's `install_or_upgrade` returns the error text
`extension '<name>' v<version> is already installed`, which contains the `ALREADY_INSTALLED`
substring Hush matches. No change needed.

### 6.5 Threading: everything is blocking

Zero `suspend` functions in the generated surface, and the Rust docs are explicit — *"Use a background
native thread for operations that read packages or run extension code"*, *"Calls must run off the UI
thread"*. Hush already wraps every backend call in `withContext(Dispatchers.IO)`; that stays, and the
`resolveLocks`/`packageRefreshLocks` mutexes remain load-bearing.

---

## 7. What this changes in the plan

| Plan statement | Status after Phase 3 |
| --- | --- |
| §2c: *"There is no single high-level 'resolve this track to a file' call … has to be composed from repository → manager → environment → download state → progress subscription"* | **Corrected.** `downloadByStrategy` is that call, 1:1, with a Go-compatible JSON contract. Phase 4 needs the object graph of §2, not a composition. |
| §2c: *"187 plain functions"* | **Corrected to 135 domain methods** on 12 interfaces (183 method declarations counting impl bodies). |
| §3 item 4: *"3236 lines of Kotlin must be re-targeted onto a different API surface … no spec"* | **Reduced.** 22 of 39 capabilities are backend-free, 12 are pure call swaps, and the integration contract is now specified here. |
| §4 Phase 3 acceptance: *"a table with zero unmapped rows; every 'missing' row has an owner decision"* | **Met** — §3 (21/21) and §4 (39/39); the missing rows carry D1–D4. |
| §4 Phase 4: *"Port surface-by-surface with the existing tests green at every step"* | Stands, and is now concrete: the object graph first, then §3 in order, then §5.2's patterns. |
| §5 risk: *"3236 lines must be re-targeted"* | **Replaced by a different risk**: the line count is not the danger — the **log-text contract (§5.2)** is, because it is the one thing that fails silently. |

---

## 8. Phase 4 work items, in order

1. **Object graph behind a flag.** Construct `ExtensionManager` + `ExtensionRepository` in a new
   bridge, selected by a build flag, with the old bridge intact. Wire the §2 handles. Port the
   `signed_sessions` root assertion as a test *first*.
2. **§3 rows in the order above**, starting with #1–#6 (lifecycle) so the app has a runtime at all,
   then #17 (download) — the single call that makes playback work — then #18/#19 (progress and
   cancellation), then the management rows.
3. **§5.1** — the all-pending replacement (D1).
4. **§5.4** — the `summariseExtensionLoad` adjustment (D4).
5. **§5.2** — the log patterns, after a Phase 5 capture (D2).
6. **Do not** wire `FfmpegCommands` in this phase (D3); file the decision instead.

**Definition of done for Phase 4:** flag-off behaviour identical to today; flag-on, the bridge's unit
tests plus `verifyRowControls` and `lint` green, and the §5.2 patterns either re-derived or explicitly
recorded as degraded.

---

## 9. Acceptance check against the plan's Phase 3 criterion

| Criterion | Result |
| --- | --- |
| Every capability Hush's bridge exposes today is mapped | **39/39** — 36 public functions + 3 public properties, §4 |
| Every reflected backend method is mapped | **21/21**, §3 |
| Every "missing, must be reimplemented in Hush" row has an owner decision | **Yes** — D1 (all-pending auth), D2 (log phrasings), D3 (FFmpeg queue), D4 (load report shape), §0 |
| Written before any Kotlin change | **Yes** — no Hush source was modified for this document |
| Uses v5.0.7 as authority | **Yes** — `19cb7033`, worktree `/Users/maxin/Projects/SpotiFLAC-Rust-v5.0.7` |

**Not verified by this document** (deliberately out of scope; Phase 5's job): whether the Rust
backend's *behaviour* matches on a device — session renewal without re-verification, cache-first
playback with zero provider requests, and a cold lossless resolve. §5.2's capture belongs to that
phase, because it needs a real stall and a real refusal to read the log text from.
