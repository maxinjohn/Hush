# SpotiFLAC Go → Rust backend migration — plan

**Goal.** Move Hush off the pinned `go_backend` (`a34e2935`, gomobile AAR) and onto upstream's
`rust_backend` (UniFFI), then prove lossless playback still works against the **same extension
sessions** — no new Cloudflare verification for the user.

**Authority.** Upstream `spotiflacapp/SpotiFLAC-Mobile` at tag **v5.0.7** (in the gitignored
`SpotiFLAC-Mobile/` clone; `git fetch --tags` run 2026-10-07). That repository is now
[`mornyeapp/Mornye`](https://github.com/mornyeapp/Mornye) and the app is Mornye since v5.2.1 — what
that changes for this plan, and what it does not, is §7 (measured 2026-10-20).

> **Status: Phases 0–2 (feasibility spike) COMPLETE and PASSED, 2026-10-07.**
> The Rust backend **builds for both Android ABIs on this machine**, the UniFFI Kotlin bindings
> generate, and session carry-over is proven at both the address and the record-format level.
> Phases 3–6 are deliberately not started — see §6. Results are in §2b.

---

## 1. Verified facts (measured 2026-10-07, not assumed)

| Fact | Evidence |
| --- | --- |
| Hush consumes a **gomobile AAR** | `app/libs/gobackend.aar` = 67 MB of `libgojni.so` (arm64-v8a + armeabi-v7a) + 177 KB `classes.jar`, class `gobackend.Gobackend` |
| The Hush-side integration is large | `app/src/main/kotlin/app/hush/music/spotiflac/SpotiFLACNativeRuntimeBridge.kt` = **3236 lines** against that Go class |
| Upstream deleted `go_backend` | `git ls-tree v5.0.7` → 0 matches for `go_backend`; `rust_backend/` has **545 files** |
| Upstream's Rust FFI is **UniFFI**, not JNI | `rust_backend/Cargo.toml`: `uniffi = "=0.32.2"`, `crates/mobile/uniffi.toml`, workspace member `crates/bindgen` (their own binding generator) |
| The AAR is **not** the consumption mechanism upstream | `android/app/build.gradle.kts` uses `java.srcDir(rustBackendDir/"target/bindings/kotlin")` + `jniLibs.srcDir(rustBackendDir/"target/android/jniLibs")`, built by `scripts/build_rust_backend.sh android` |
| Toolchain requirement | `rust-version = "1.98.1"`, `edition = "2024"`, `resolver = "3"` |
| Native deps that cross-compile C | `rquickjs = { version = "=0.14.0", features = ["bindgen"] }` (QuickJS is compiled C via cc-rs; upstream's script sets `BINDGEN_EXTRA_CLANG_ARGS` per ABI); `ffmpeg.rs` in **both** `crates/mobile` and `crates/extensions` |
| Workspace crates | `core`, `network`, `providers`, `extensions`, `mobile`, `bindgen` + vendored `rustix-1.1.4` |

### Toolchain as found on this machine

| Needed | State |
| --- | --- |
| Android NDK | ✅ `android-ndk-r29` at `~/Library/Android/sdk/ndk/android-ndk-r29` — used successfully; the mismatch with upstream's pinned `29.0.14206865` **did not matter** |
| `go` | ✅ present (only needed to build the *current* backend) |
| `cargo` / `rustc` / `rustup` | ✅ **installed 2026-10-07** — rustup, `--no-modify-path --profile minimal`. Stable is 1.99.0; the worktree's own `rust-toolchain` makes the build use **1.98.1**, matching `rust-version` |
| `bindgen` / libclang | ✅ satisfied — `/Library/Developer/CommandLineTools/usr/lib/libclang.dylib`, exposed as `LIBCLANG_PATH`. `rquickjs`'s bindgen + cc-rs C compile **succeeded for both ABIs** |
| FFmpeg-for-Android | ✅ **not required** — see §2b |

---

## 2. The acceptance criterion: **sessions carry over** — verified

This was the one genuine unknown, and the news is good. The Rust signed-session store is deliberately
**layout-identical** to Go's — upstream says so in a comment: *"Go stores this record as private JSON;
preserve that format for the eventual ownership cutover."*

| | Go @ `a34e2935` (`go_backend/extension_signed_session.go`) | Rust @ v5.0.7 (`crates/extensions/src/signed_session/`) |
| --- | --- | --- |
| Directory | `filepath.Join(baseDir, "signed_sessions")` | `root.join("signed_sessions")` (`store.rs:102`) |
| Filename | `namespace + "-" + hex(sha256(scope))[:16] + ".json"` | `format!("{namespace}-{}.json", &hex(&Sha256::digest(scope))[..16])` (`protocol.rs:59`) |
| Scope string | `namespace \n lower(baseURL) \n lower(appVersion) \n lower(platform)` | same four fields, `.trim().to_lowercase()`, joined by `"\n"` ✅ |
| Namespace sanitize | `[a-z0-9-_.]` only, then trim `.-_` | `.trim_matches(['.', '-', '_'])` ✅ |
| Record fields | `session_id`, `session_secret`, `expires_at`, `install_id` | same, `serde_json` pretty, private JSON ✅ |

### Proof against real device state (stronger than code-reading)

The record directory was located on the test device (`eea86cae`) and the naming rule was then
reproduced from the four installed extensions' own manifests:

```
files/spotiflac/extension_data/signed_sessions/
  zarz-v2-97a0e1c05dfda829.json   <- amazon      amzn@2.3.11
  zarz-v2-57befc3493a9748e.json   <- deezer      deezer@1.3.5
  zarz-v2-18c071885b2355e3.json   <- qobuz-web   qobuz-web@1.2.18
  zarz-v2-f1889c8a7eb52c25.json   <- tidal-web   tidal-web@1.2.5
```

Computing `sha256(namespace + "\n" + lower(baseUrl) + "\n" + lower(appVersion) + "\n" + lower(platform))[:16]`
from each `manifest.json` `signedSession` block reproduced **all four filenames exactly**. So the rule
read out of the Go source is the rule the running runtime actually used, and Rust's implementation of
that same rule addresses **the same four files**. The four live sessions (~5.4 h remaining each) will
not need re-verification.

**The one variable left** is the `root` Hush passes to `RecordStore::open`. Verified on the device it
must be **`files/spotiflac/extension_data`** — *not* `files/spotiflac/extensions` (the installed `.sflx`
packages and their extracted `manifest.json`/`index.js`) and not `files/spotiflac/session_vault`
(Hush's own backup of the same records). The Rust API takes the data dir explicitly, so Hush controls
this — but it must be asserted by test. Get it wrong and every source reads as unverified, which looks
exactly like the migration having broken sessions.

---

## 2b. Spike results (Phases 0–2, 2026-10-07)

All commands run in an isolated worktree at **`/Users/maxin/Projects/SpotiFLAC-Rust-v5.0.7`**
(detached at v5.0.7 = `19cb7033`). The main `SpotiFLAC-Mobile/` clone is **still on the Go pin
`a34e2935` with `go_backend` present**, so the shipped AAR path is untouched.

That one-off worktree is now replaced by a checked-in script:
**`scripts/build-spotiflac-rust.sh`** materializes the pinned worktree itself (no pre-made
checkout required), builds the host library + UniFFI Kotlin bindings and both Android ABIs, and
stages `app/libs/spotiflac-rust/{jniLibs,kotlin}` for Gradle. `--checkout-only` stops after
verifying the pin; `--help` documents the rest. See §6.

The revisions themselves are stated once, in **`scripts/spotiflac-backend-pins.sh`**: this script and
`scripts/build-spotiflac-aar.sh` both read it (upstream URL, clone directory, the Go revision, the
Rust tag + SHA, the Android API level), so a bump cannot update one build path and leave the other
naming an older backend. `--checkout-only` on either script checks a checkout against that file —
the Go one answers in a second, which is the point of the flag.

A stage cannot outlive its pin either. The build writes `PROVENANCE` into
`app/libs/spotiflac-rust/` naming the revision it built from and the SHA-256 of every artifact it
copied, and the `verifySpotiFLACRustStage` Gradle task — on every assemble, beside the three source
guards — fails when that record does not match the pins file, when an artifact no longer hashes as
recorded, or when the stage holds artifacts the record does not describe. A stage that is absent is
not an error: it is not a build input until Phase 4 wires it. What this closes is the pin bump that
forgets to rebuild a gitignored directory, which no test of the app can see.

| Step | Result |
| --- | --- |
| Phase 0 — toolchain | Rust 1.99.0 installed (workspace selects 1.98.1); `aarch64-linux-android` + `armv7-linux-androideabi` targets added; libclang found |
| Phase 1a — host build | `cargo build --locked --release -p spotiflac-mobile` → `BUILD_EXIT=0`, **0 errors**, 1 m 14 s. Produced `libspotiflac_mobile.dylib` (15.8 MB) |
| Phase 1b — UniFFI Kotlin | `cargo run -p spotiflac-bindgen -- generate --language kotlin` → `GEN_EXIT=0`; one file, `com/spotiflac/backend/spotiflac_mobile.kt`, **8683 lines** |
| Phase 2 — Android | `bash scripts/build_rust_backend.sh android` → `ANDROID_EXIT=0`, **0 errors**, 1 m 02 s (after a cold dependency build). Both ABIs staged under `rust_backend/target/android/jniLibs/` |

### Three plan assumptions this corrected

1. **FFmpeg is not a runtime dependency.** `llvm-readelf -d` on the arm64 `.so` lists only
   `libdl.so`, `libm.so`, `libc.so` — **zero `libav*` entries**. `crates/mobile/src/ffmpeg.rs` and
   `crates/extensions/src/ffmpeg.rs` do not force a shipped FFmpeg, so the biggest risk in §5 is gone.
2. **The NDK version mismatch is a non-issue.** `android-ndk-r29` cross-compiled `ring`, `libusb1-sys`
   and `rquickjs` (all C/asm) cleanly with `armv7a-linux-androideabi24-clang`, and the link carried
   `-Wl,-z,max-page-size=16384`.
3. **The artifact gets materially smaller.** Measured, not assumed:

| ABI | Go (`libgojni.so`, today) | Rust (`libspotiflac_mobile.so`) |
| --- | --- | --- |
| arm64-v8a | 33.8 MB | **19.2 MB** |
| armeabi-v7a | 33.3 MB | **13.7 MB** |
| **total both** | **67.1 MB** | **32.9 MB (−51%)** |

That is a **67.1 → 32.9 MB raw-file** reduction for the two shipped ABIs, which reverses the "size is
not a motivation" line in §5: it is now a measurable one — though not at the size this table
suggests. Raw `.so` bytes are not APK bytes: in the shipped release APK the same two libs are stored
deflated, so the migration is worth **17.89 MB there, not ~34 MB**, and **rebuilding the committed Go
AAR with `-ldflags="-s -w"` recovers 14.65 MB of that (81.9%) without migrating.** See
[`spotiflac-go-aar-size-investigation.md`](spotiflac-go-aar-size-investigation.md) before quoting this
number as the plan's payoff.

### Session carry-over: now proven twice over

- **Address level:** 4/4 on-device record filenames reproduced exactly from the scope-hash rule (§2).
- **Format level:** a live record pulled from the device carries exactly
  `install_id`, `session_id`, `session_secret`, `expires_at` (+ the four scope fields), and Rust's
  `Record` deserializes those with `#[serde(default, deserialize_with = "string_or_null")]` — tolerant
  of nulls and absent fields.

The 553 exported `spotiflac`/`uniffi` symbols in the arm64 `.so` confirm the UniFFI surface is really
present in the Android artifact, not just the host one.

### 2c. What the generated Kotlin surface actually looks like

Inventory of `spotiflac_mobile.kt`, which changes the shape of Phase 3:

- **17 interfaces, 4 data classes, 187 plain functions, 0 `suspend` functions.** Everything is a
  blocking JNI call, so Hush keeps doing its own coroutine dispatching (it already does).
- The exported objects are **lower-level primitives**: `ExtensionRepositoryInterface`,
  `ExtensionManagerInterface`, `ExtensionEnvironmentInterface`, `JsExtensionInterface`,
  `DownloadStateInterface`, `DownloadDirectoryScopeInterface`, `DownloadProgressSubscriptionInterface`,
  `RequestLeaseInterface`, `CancellationRegistryInterface`, `FfmpegCommandsInterface`,
  `LogBufferInterface`, `UsbDirectOutputInterface`.
- **There is no single high-level "resolve this track to a file" call.** Hush's bridge is built around
  exactly that (`resolve(title, artist, album, durationMs, isrc, spotifyTrackId, quality, sourceIds,
  coverUrl, mediaId)` + `playbackFileForMediaId`). The Rust equivalent has to be **composed** from
  repository → manager → environment (the QuickJS runtime) → download state → progress subscription.

**Consequence for the plan: this is not a 1:1 API re-target.** Phase 3's mapping will contain several
rows where the honest answer is "compose these three primitives", and Phase 4 is therefore larger than
"rename the calls" — budget for it accordingly. This is the finding that most changes the estimate, and
it is the reason Phase 3 (a document) is worth doing before Phase 4 (a rewrite).

---

## 3. Why Phases 3–6 were not attempted

The build-side blockers are gone (see §2b). What remains is the *integration*, and the choice was
spike-only, so I stopped at Phase 2 rather than starting a multi-week rewrite unrequested:

1. ~~No Rust toolchain~~ → **resolved**, Phase 0.
2. ~~Cross-compiling C for two Android ABIs + an FFmpeg dependency~~ → **resolved**, Phase 2,
   `ANDROID_EXIT=0` with zero errors.
3. **The consumer mechanism changes**: the AAR disappears, `scripts/build-spotiflac-aar.sh` is replaced
   by a Rust build + UniFFI Kotlin codegen, and Gradle gains source-set + `jniLibs` wiring.
4. **3236 lines of Kotlin must be re-targeted** onto a different API surface (UniFFI `crates/mobile`)
   spanning a 545-file workspace. There is no shim, no migration guide, and no spec for the new surface
   from Hush's side.
5. **A partial swap is destructive.** The installed AAR is what is serving cached lossless files on the
   device right now, with four verified sessions and a populated playback cache. A half-migrated backend
   does not degrade gracefully — playback stops.

---

## 4. Phases

Each phase states its acceptance criterion and the command that proves it. The phases are **serially
dependent** — there are no independent tasks here, so this plan is not a candidate for parallel
execution.

### Phase 0 — Toolchain
Install Rust 1.98.1+ (`rustup`), FFmpeg for `aarch64-linux-android24` + `armv7a-linux-androideabi24`,
and a libclang that bindgen can use. Reconcile the NDK: upstream's script hard-requires
`ndk/29.0.14206865`, this machine has `android-ndk-r29`.
- *Acceptance:* `cargo --version` ≥ 1.98.1; `cargo metadata --locked` resolves the workspace.

### Phase 1 — Host build smoke test (the real feasibility gate)
`cargo build --locked --release -p spotiflac-mobile` for the host, then
`cargo run --locked -p spotiflac-bindgen -- generate --language kotlin --out-dir ... --no-format`.
- *Acceptance:* `libspotiflac_mobile.dylib` + a generated Kotlin binding directory exist.
- **If `rquickjs`/bindgen will not build here, stop and re-plan** — nothing downstream is worth
  attempting.

### Phase 2 — Android cross-build + session-root assertion
`bash scripts/build_rust_backend.sh android` with `SPOTIFLAC_RUST_ANDROID_ABIS=arm64-v8a,armeabi-v7a`.
- *Acceptance:* `rust_backend/target/android/jniLibs/{arm64-v8a,armeabi-v7a}/libspotiflac_mobile.so`
  exist; and a host-side test opens `RecordStore::open(files/spotiflac/extension_data, config)` against
  the device's pulled `signed_sessions/` and reads each `session_id` — **four non-empty records with the
  expected filenames and expiries is the proof that sessions carry over**. §2 lists the exact filenames,
  so the test asserts known-good values rather than just "something was read".

### Phase 3 — Integration design (spec before code)
Write the mapping document: every capability Hush's bridge exposes today (`resolve`,
`playbackFileForMediaId`, `removeCachedPlaybackForMediaId`, `cancelDownload`, `sourceAuthState`,
`downloadProgress`, extension load/update, miss memo) → its UniFFI equivalent, or *"missing, must be
reimplemented in Hush"*. This is the phase whose output should be reviewed **before** any Kotlin changes.
- *Acceptance:* a table with zero unmapped rows; every "missing" row has an owner decision.

### Phase 4 — Bridge re-target, behind a flag
Introduce the new bridge beside the old one, selected by a build flag, so the working AAR path stays
intact. Port surface-by-surface with the existing `SpotiFLACSessionNotice` and `SpotiFLAutoVerifier`
tests green at every step.
- *Acceptance:* flag off ⇒ behaviour identical to today; flag on ⇒ the bridge's own unit tests pass and
  `verifyRowControls` + `lint` are green.

### Phase 5 — Device verification (the user's criterion)
On `eea86cae`, install the flag-on build and prove from `files/spotiflac/diag.log`:
1. `session renew (app-start)` reports the **same four sources with the same remaining time** — no new
   challenge, no re-verification.
2. A track already in the playback cache is served **from disk** (`serving SpotiFLAC file … ahead of the
   Media3 cache`) with **zero provider requests**.
3. A cold, uncached track resolves losslessly end-to-end.
- *Acceptance:* all three logged and playback audible. **Do not claim the migration works from a
  successful build.**

### Phase 6 — Cut over
Delete `app/libs/gobackend.aar`, retire `scripts/build-spotiflac-aar.sh` in favour of
`scripts/build-spotiflac-rust.sh` (already written; stages `app/libs/spotiflac-rust/`), add the Gradle
`java.srcDir` + `jniLibs.srcDir` wiring it feeds, update `.freebuff/upstream-import-table.md`, and
re-run the landscape size sweep (player surfaces show the source label).

---

## 5. Risks and rollback

| Risk | Mitigation |
| --- | --- |
| ~~`rquickjs` + bindgen will not cross-compile cleanly~~ | ✅ **resolved in the spike** — built for both ABIs |
| ~~FFmpeg-for-Android is a large separate build~~ | ✅ **resolved** — no `libav*` in the ELF `NEEDED` list |
| ~~NDK mismatch (`android-ndk-r29` vs pinned `29.0.14206865`)~~ | ✅ **resolved** — r29 cross-compiled `ring`/`libusb1-sys`/`rquickjs` cleanly |
| Session root wrong | Asserted by test in Phase 2, before any Kotlin work |
| Rollback after a partial cutover | Keep the AAR on disk and the flag defaulting to the old bridge until Phase 5 passes |

Artifact size: **measured** in the spike — 32.9 MB vs 67.1 MB of raw `.so` for both ABIs (see §2b).
At the shipped release APK the same change is **17.89 MB**, and 14.65 MB of it is recoverable by
stripping the AAR Hush already ships (see
[`spotiflac-go-aar-size-investigation.md`](spotiflac-go-aar-size-investigation.md)).

---

## 6. Decisions taken and what is next

**Taken (2026-10-07):** install the Rust toolchain and run the Phase 1 build gate → done; and
**spike only for now** (Phases 0–2), then re-assess → done. Phases 3–6 were not started.

**Machine state left behind, for the next session:**

- Rust installed at `~/.cargo` / `~/.rustup` (no shell-profile edits were made). Add `~/.cargo/bin` to
  `PATH`, and set `LIBCLANG_PATH=/Library/Developer/CommandLineTools/usr/lib`.
- Worktree `/Users/maxin/Projects/SpotiFLAC-Rust-v5.0.7` (v5.0.7, ~GB of `target/`). Remove with
  `git -C SpotiFLAC-Mobile worktree remove /Users/maxin/Projects/SpotiFLAC-Rust-v5.0.7` when done.
- Build logs: `/Users/maxin/Projects/SpotiFLAC-Rust-v5.0.7/build-host.log` and `build-android.log`.
- Nothing was changed in the Hush repo, and nothing was committed.

**Next decision — the one that matters now:** the spike proved the Rust backend *builds* and that
sessions *carry over*. It did **not** prove the 3236-line bridge can be re-targeted cheaply; that is
Phase 3's job, and Phase 3 is the cheapest way to find out (a mapping document, no code).

**Is there a driver?** The 2026-10-07 upstream check found the pinned Go tree already carries every
4.9.5/4.9.6 runtime fix Hush depends on, and nothing in the 258 commits to v5.0.7 requires a Hush-side
change. The migration's concrete, currently-measurable payoff is a smaller APK plus leaving a
maintained backend. Recommend: run Phase 3 (cheap, answers the real question) before committing to
Phases 4–6.

**Sharpened (2026-10-07, second pass):** the follow-up search of the same 258 commits for a *functional*
driver — a fix or capability Hush needs that the pinned Go backend lacks — found **none**; see
[`spotiflac-rust-migration-driver-analysis.md`](spotiflac-rust-migration-driver-analysis.md). The Go
backend was frozen at the pin (only `aa997264` ever touches `go_backend`, and it deletes it), the
Hush-critical session/resolver/verification crates and the extension runtime-feature contract are
identical or near-identical across the migration, and every post-migration backend change is either a
migration-regression repair or a Rust-only perf/upstream-app feature outside the 22 backend methods
Hush consumes. Driver remains size + maintenance.

**Sharpened (2026-10-08, third pass):** the size driver was re-measured against the shipped artifact
rather than the raw `.so`s — see
[`spotiflac-go-aar-size-investigation.md`](spotiflac-go-aar-size-investigation.md). The migration is
worth **17.89 MB** on the release APK (49.15 → 31.26 MB), not ~34 MB, and **14.65 MB of that is a
single `-ldflags="-s -w"` on the AAR Hush already ships** (49.15 → 34.50 MB): the committed
`gobackend.aar` ships unstripped, with 9.6 MB of DWARF plus symbol tables per `.so`. Strip both sides
and the residual gap is **4.68 MB**. The maintenance driver is untouched by this and is now the
stronger of the two; the size argument for Phases 4–6 has to be made at 4.68 MB.

---

## 7. Upstream renamed itself to Mornye (measured 2026-10-20)

**What happened.** The app shipped as **Mornye** in **v5.2.1** on 2026-10-20 (`branding: Rename app and
native backend to Mornye`, `branding: Drop remaining SpotiFLAC references`) and its repository moved:
`spotiflacapp/SpotiFLAC-Mobile` now answers **301** to [`mornyeapp/Mornye`](https://github.com/mornyeapp/Mornye).
The last release under the old name is **v5.2.0**; the four tags after this plan's authority (v5.0.7)
are **v5.1.0, v5.1.1, v5.2.0, v5.2.1**. Its own gateway says the same thing in a config document
(`GET https://api.zarz.moe/v1/spotiflac-mobile/config` → `announcement.title: "SpotiFLAC Mobile is
now Mornye!"`, running 2026-10-20 → 2026-10-28), so the rename is a fact about the service, not only
about a repository label.

**Neither pin moves, and neither has to.** The Go pin `a34e2935` is a pre-rewrite commit that still
resolves in the renamed repository (checked through the API: `200`), so a fresh checkout from
`mornyeapp/Mornye` builds the AAR Hush ships today; and the Rust pin `v5.0.7` is the tree the spike
measured, which still calls itself SpotiFLAC throughout. `scripts/spotiflac-backend-pins.sh` now names
the new address and keeps the `SpotiFLAC-Mobile/` clone directory, because every existing checkout,
worktree and path in these documents is spelled that way.

| Question for the port | Answer, measured on v5.2.1 |
| --- | --- |
| Did the crates get renamed? | **Yes.** `spotiflac_core`/`_network`/`_providers`/`_mobile` → `mornye_*`, so `-p spotiflac-mobile` and `libspotiflac_mobile.so` no longer exist and the UniFFI Kotlin package moves with them. `scripts/build-spotiflac-rust.sh` refuses a tree past 5.1 with that sentence instead of letting cargo answer "package ID specification did not match". |
| Did the extension *package* format change? | **No, but it grew an alias.** `is_package_path` accepts `.mornye-ext` in addition to `.spotiflac-ext` and `.sflx`; all three are the same zip. Hush never gates on the suffix (it downloads and reads the archive), so nothing on this side changes. |
| Did the manifest grow anything a host must implement? | **One capability, host-optional.** `capabilities.accountDownloadMode` is now shape-checked (`{setting, value}`, ≤64 chars each) and `capabilities.actionTimeoutSeconds` is new (`1..120`, the JS action timeout). Neither is a runtime feature; the three that *are* — `accountForms@1`, `accountDownloadMode@1`, `directMedia@1` — are exactly the ones this plan already records as Rust-only, and `SpotiFLACRuntimeFeatures` already refuses a package that requires them. |
| Did the signed-session protocol change? | **No.** `scheme_label` is still `SPOTIFLAC-HMAC-V1`, the scope is still `namespace\nbaseUrl\nappVersion\nplatform`, the record filename rule and the `signed_sessions/` store are untouched, and every stored record therefore still addresses the same file. The one default that moved is the *callback* (`spotiflac://session-grant` → `mornye://session-grant`), which the host supplies anyway: Hush passes its own, and the gateway was measured echoing whichever registered scheme it is given (`spotiflac://` and `mornye://` both come back; `hush://` is still dropped), so no session was invalidated by the rename. |
| Does the live registry still fit this build? | **Yes, all twelve packages.** Downloaded and read rather than trusted to the registry: the newest requirements are `signedSession@3` (tidal-web, qobuz-web), `patternedFileTransform@1` (deezer), `preparedContext@1` (amazon, deezer, qobuz-web, soundcloud, jiosaavn) and `downloadSegments@1` (tidal-web, soundcloud) — every one in the pinned Go runtime's table — and no package uses the three Rust-only features. The registry itself is unchanged: same twelve ids, same `.sflx` downloads, `min_app_version` at most `4.9.5`. |

**Consequence for Hush:** the rename costs no runtime change, no session migration and no package
reinstall; it costs attribution and the guard above. The shipped feature keeps its own name
(*SpotiFLAC* in Settings → Audio Sources), because what it describes is the engine Hush wraps, not the
app upstream now ships — and that engine is the Go tree this build pins, which upstream deleted in
5.0 for reasons unrelated to the rename.
