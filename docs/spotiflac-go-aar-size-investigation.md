# Trimming the pinned Go AAR: does the ~34 MB payoff survive without migrating?

**Question.** The migration plan's one measured driver is APK size: the Rust backend's two `.so`s are
32.9 MB against the Go AAR's 67.1 MB, which the plan reads as "~34 MB off the APK". Before paying for
Phases 4–6, can the artifact Hush already ships be trimmed toward that same payoff — by dropping
unused gomobile exports, by splitting ABIs per release, or by some other lever?

**Verdict. Yes, and almost all of it is a debug-info strip.** The committed `gobackend.aar` ships
**unstripped**: 9.60 MB of DWARF and symbol tables per `.so`, 19.2 MB across the two ABIs. Rebuilding
it with `gomobile bind … -ldflags="-s -w"` takes the AAR from 33.33 MB to 18.47 MB and the shipped
release APK from **49.15 MB to 34.50 MB — a 14.65 MB cut that is 82% of the 17.89 MB the whole Rust
migration buys at the same place in the APK.** Nothing in the JNI surface changes: same
`classes.jar` byte-for-byte, same 1752 JNI exports, and every section the runtime reads is identical
in size.

The two levers the question names are not the levers. **"Stripping unused gomobile exports" is not
available** — `gomobile bind` has no export filter, and the bytes that dominate the library are
reached from the 22 methods Hush *does* call. **"Splitting ABIs per release" is already implemented**
(per-ABI product flavors and a `build-release.sh` ABI argument) — it is only unexercised because all
three CI matrix rows pass `abi: universal`.

---

## 1. Evidence base

Everything below is measured on this checkout on 2026-10-08, not estimated from source.

| Item | Value |
|---|---|
| Shipped artifact | `app/build/outputs/apk/fossMobileUniversal/release/hush-foss-mobile-universal-release.apk`, **49,153,928 B** (debug: 95,895,384 B) |
| AAR under test | `app/libs/gobackend.aar`, 33,333,337 B, sha256 `41cf4587…3c525ff` |
| Pin | `a34e2935` via `scripts/spotiflac-backend-pins.sh`; checkout verified at `a34e2935442e7ec661c539ab3c2df8fc0b23b1ff` |
| Build command | `gomobile bind -target=android/arm,android/arm64 -androidapi 24 [-ldflags="-s -w"] -o <out> .` — the same command `scripts/build-spotiflac-aar.sh` runs |
| Toolchain | go1.27.1, `golang.org/x/mobile@v0.0.0-20260908204917-8b95e45f8d3e` (the `go.mod`-pinned one), NDK android-ndk-r29 |
| Instrumentation | `llvm-readelf`/`llvm-nm`/`llvm-strip` from `$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin`; `zlib` level-6 for packaging arithmetic (calibrated, §4) |
| Stripped build written to | `/tmp/gobackend-s-w.aar` at investigation time — **the tracked AAR was not modified**. The recommendation has since been applied, and the tracked AAR is now that build (§9) |

---

## 2. Where the bytes actually are

The AAR is not one blob; it is two independently-compressed `.so`s plus 177 KB of generated Java
bindings. In the release APK the archive entries are stored deflated (`jniLibs { useLegacyPackaging =
true }`, `app/build.gradle.kts:278`), so the APK-level size is the *stored* size, not the raw size.

```
$ python3 -c "...zipfile.ZipFile(release_apk)..."
hush-foss-mobile-universal-release.apk  on-disk 49,153,928 B
entry                                      raw          stored       deflate%
lib/armeabi-v7a/libgojni.so           33,331,316      16,071,497        48.2%
lib/arm64-v8a/libgojni.so             33,779,616      15,573,718        46.1%
… 8 tiny androidx/datastore libs         73,584          31,099
```

| | raw | stored in APK |
|---|---|---|
| Go libs, both ABIs | 67,110,932 B | **31,645,215 B** |
| everything else in `lib/` | 73,584 B | 31,099 B |
| rest of the APK (dex, resources, assets, META-INF) | — | 17,296,772 B |

**The Go runtime is 64.4% of the shipped APK.** That is why the size lever is real, and also why it
is worth asking what inside those two files is doing work.

| ABI | raw | `.text` | `.gopclntab` | removed by stripping | share |
|---|---|---|---|---|---|
| arm64-v8a | 33,779,616 | 9,238,156 | 7,775,532 | 9,604,182 | 28.4% |
| armeabi-v7a | 33,331,316 | — | — | 9,302,624 | 27.9% |

The AAR is a **debug** build of the Go runtime: it carries full DWARF (`.debug_info`, `.debug_line`,
`.debug_loclists`, …), a `.symtab`/`.strtab`, and — because nothing ever stripped it — those bytes are
also in the committed git blob (33.3 MB) and in every APK ever shipped from it.

---

## 3. The strip, measured end to end

```
$ gomobile bind -target=android/arm,android/arm64 -androidapi 24 -ldflags="-s -w" \
      -o /tmp/gobackend-s-w.aar .
real 0m9.058s          # warm Go build cache
```

| Artifact | today | `-ldflags="-s -w"` | delta |
|---|---|---|---|
| `gobackend.aar` | 33,333,337 B | **18,472,589 B** | −14,860,748 B (−44.6%) |
| `jni/arm64-v8a/libgojni.so` | 33,779,616 B | 24,174,272 B | −9,605,344 B |
| `jni/armeabi-v7a/libgojni.so` | 33,331,316 B | 24,028,692 B | −9,302,624 B |
| `classes.jar` | 177,654 B | 177,654 B | **sha256 identical** (`b1f3d736…`) |

`-ldflags="-s -w"` is Go's own equivalent of a full strip, and it lands within a few hundred bytes of
what `llvm-strip -s` produces on the same file (24,174,272 vs 24,173,968 for arm64), so it stays
inside the supported build path — no post-processing of the AAR.

### What survives

Section-by-section diff of arm64 `libgojni.so`, tracked vs stripped:

```
sections: 47 → 32
REMOVED: .debug_abbrev .debug_addr .debug_aranges .debug_frame .debug_gdb_scripts .debug_info
         .debug_line .debug_loc .debug_loclists .debug_ranges .debug_rnglists .debug_str
         .note.gnu.build-id .strtab .symtab            → 9,604,182 B
ADDED  : (none)
```

Every section the loader or the Go runtime reads is present at the **same size**:

| section | purpose | tracked | stripped |
|---|---|---|---|
| `.text` | machine code | 9,238,156 | 9,238,156 |
| `.gopclntab` | Go tracebacks, panics, `runtime.Callers` | 7,775,532 | 7,775,532 |
| `.rodata` | string/type data | 386,952 | 386,952 |
| `.data.rel.ro` | itab / interface data | 1,965,616 | 1,965,616 |
| `.noptrdata` | Go globals | 1,915,041 | 1,915,041 |
| `.rela.dyn` | relocations | 2,065,464 | 2,065,464 |
| `.rela.plt`, `.got`, `.plt` | lazy binding | 1,416 / 15,816 / 976 | unchanged |
| `.dynsym` / `.dynstr` | exported JNI symbols | 135,168 / 282,238 | unchanged |
| `.go.buildinfo` | build metadata | 2,176 | 2,192 (+16) |

```
$ llvm-readelf --dyn-syms -W … | grep -c Java_gobackend_      # 1752 → 1752  (both ABIs)
$ llvm-readelf -d -W …        | grep -c NEEDED               #    4 →    4
$ llvm-readelf -l -W …        | grep -c '^  LOAD'            #    3 →    3
$ llvm-readelf -d -W …        | # dynamic entries            #   27 →   27
```

Only two things are lost, and both are worth naming: `.note.gnu.build-id` (symbolication of native
crashes from the shipped binary would need a kept unstripped copy) and the ability to see Go function
names in a native stack dump from the field. Panics and Go tracebacks still work — they read
`.gopclntab`, which is untouched.

---

## 4. What that does to the shipped APK

The projection needs one calibration: which deflate level AGP used for the `lib/` entries. Compressing
the *raw* arm64 lib at every level and comparing against the APK's actual stored size answers it
exactly:

| level | 1 | 3 | 5 | **6** | 7 | 9 |
|---|---|---|---|---|---|---|
| stored B | 16,405,570 | 16,067,655 | 15,643,630 | **15,573,718** | 15,556,413 | 15,538,769 |
| vs APK | +831,852 | +493,937 | +69,912 | **0** | −17,305 | −34,949 |

Level 6 reproduces the packaged size **to the byte**, so the same computation on the stripped lib is
not an estimate of the mechanism, only of the source file.

| entry | stored today | stored stripped | saving |
|---|---|---|---|
| `lib/arm64-v8a/libgojni.so` | 15,573,718 | 8,237,742 | −7,335,976 |
| `lib/armeabi-v7a/libgojni.so` | 16,071,497 | 8,753,038 | −7,318,459 |
| **total** | **31,645,215** | **16,990,780** | **−14,654,435** |

### The comparison the decision actually needs

| scenario | release APK (universal) | vs today |
|---|---|---|
| **today** (committed AAR) | **49.15 MB** | — |
| **Go AAR rebuilt with `-ldflags="-s -w"`** | **34.50 MB** | **−14.65 MB** |
| Rust migration, stage exactly as `build-spotiflac-rust.sh` produces it | 31.26 MB | −17.89 MB |
| Rust migration, stage stripped | 29.82 MB | −19.33 MB |

- The migration buys **17.89 MB** at the shipped artifact level — not ~34 MB. The plan's 34 MB figure
  is a raw-`.so` delta (67.1 MB → 32.9 MB); roughly half of the Go side of it is debug info that
  never needed to be in the APK.
- **Stripping the Go AAR captures 14.65 MB of that 17.89 MB, i.e. 81.9%.**
- With both sides stripped, migrating is worth **4.68 MB** — 9.5% of the APK — plus the maintenance
  argument, which is the *other* driver and is untouched by any of this.

---

## 5. Lever 2: "splitting ABIs per release" — already built, just not used

Per-ABI product flavors exist and the release tooling already takes an ABI:

- `app/build.gradle.kts:158-185` — four per-ABI flavors (`arm64` 166, `armeabi` 171, `x86` 176,
  `x86_64` 181), each with its own `abiFilters`; `:159-161` is the `universal` flavor.
- `scripts/build-release.sh` takes `[distribution] [device] [abi] [debug|release]`, supports
  `abi=all`, and ships `mobile-all`.
- The CI matrix, however, ships **only universal**: `.github/workflows/build.yml:18-35` has three rows
  (gms-mobile, gms-tv, foss-mobile) and every one sets `abi: universal` (L24, L29, L34).

Projected per-ABI release APKs (drop the other ABI's lib entirely):

| artifact | today | stripped | vs universal-stripped |
|---|---|---|---|
| universal | 49.15 MB | 34.50 MB | — |
| `arm64` only | 33.08 MB | **25.75 MB** | −8.75 MB |
| `armeabi` only | 33.58 MB | 26.26 MB | −8.24 MB |

So ABI splits are a real further step — but an **orthogonal distribution decision**, not a fix to the
AAR: it only pays if Hush stops publishing one universal APK, which is a deliberate compatibility
choice (the flavor exists precisely so a plain sideload cannot land on the wrong ABI). It is also
worth noting the two levers stack multiplicatively: universal-stripped (34.50 MB) is only 8.75 MB
worse than arm64-stripped (25.75 MB), whereas universal-unstripped (49.15 MB) is 16.07 MB worse than
arm64-unstripped (33.08 MB) — stripping makes the universal-vs-split question much smaller.

---

## 6. Lever 3: "stripping unused gomobile exports" — not a lever

The premise is true and striking: the AAR's Java surface is **226 methods** on `gobackend.Gobackend`
plus 123 other wrapper classes, and Hush's bridge references **22** of those methods by name
(intersection of string literals in `SpotiFLACNativeRuntimeBridge.kt` with `javap` output —
`cancelDownload`, `checkExtensionHealthJSON`, `checkExtensionUpgradeFromPath`, `downloadByStrategy`,
`getAllPendingAuthRequestsJSON`, `getExtensionPendingAuthJSON`, `getInstalledExtensions`,
`getLogsSince`, `initExtensionSystem`, `invokeExtensionActionJSON`, `isExtensionAuthenticatedByID`,
`loadExtensionFromPath`, `loadExtensionsFromDir`, `setAppVersion`, `setExtensionEnabledByID`,
`setExtensionSessionGrantByID`, `setExtensionStorageMasterKey`, `setLoggingEnabled`,
`setProviderPriorityJSON`, `setRuntimeState`, `upgradeExtensionFromPath`, `waitForMultiProgressDelta`).
The AAR also exports **1752** `Java_gobackend_*` dynamic symbols — one JNI entry point per Go method,
receiver method and constructor, which is what "unused exports" has to mean here.

Three measured facts say the idea cannot pay.

**(a) There is no flag.** `gomobile bind` accepts only `-javapkg`, `-prefix`, `-classpath`,
`-bootclasspath` plus standard build flags (`cmd/gomobile/bind.go:163-168`); it binds **every**
exported symbol of the bound package, and the generated JNI entry points make each one reachable, so
none of them is a candidate for Go's dead-code elimination. Reducing the surface means binding a
narrower *package*, which means either editing the tree at the pin or standing up a wrapper module
that re-declares the ~20 types its API mentions — and the wrapper must still depend on
`golang.org/x/mobile` and blank-import `golang.org/x/mobile/bind` (upstream's own
`mobile_deps.go` does exactly this, to survive `go mod tidy`).

**(b) The backend's own code is a small slice of the library.** Text symbol sizes from the arm64
symtab (29,831 symbols, 9,077,418 B):

| bucket | bytes | share |
|---|---|---|
| Go runtime + stdlib | 3,213,090 | 35.4% |
| JS engine for extensions (goja, regexp2) | 1,854,576 | 20.4% |
| other third-party deps | 1,328,376 | 14.6% |
| **`go_backend` itself** | **1,159,104** | **12.8%** |
| Go type checker + `x/tools` (linked by gomobile's bind code) | 500,128 | 5.5% |
| uTLS fingerprinting | 457,520 | 5.0% |
| cgo/JNI glue | 325,744 | 3.6% |
| gomobile bind runtime | 238,880 | 2.6% |

For calibration: a gomobile-bound package containing **one trivial function** builds to a 1,750,080 B
stripped `.so` (717,261 B stored) — that is the floor of runtime + bind + type checker, with no
backend at all.

**(c) The unused families are small, and the big buckets are all reachable from what Hush calls.**
Grouping `go_backend`'s 1,159,104 B of text by source file:

| family | bytes | share of backend |
|---|---|---|
| extension runtime — **used** (extensions are the product) | 481,968 | 41.6% |
| gomobile export wrappers — **kept** (this *is* the JNI surface) | 112,224 | 9.7% |
| tag editors / metadata / cover — mostly unused | 192,896 | 16.6% |
| lyrics + songlink — unused | 132,416 | 11.4% |
| library scan / duplicate index / cue — unused | 61,680 | 5.3% |
| deezer browse/cache clients — unused | 46,224 | 4.0% |
| other | 131,696 | 11.4% |

The genuinely-unused families sum to ~433 KB of the 9.08 MB of text — and that is an *upper* bound,
because their helpers (`httputil`, `dns_doh`, `json_util`, `ratelimit`, `goja` conversion) are shared
with the download path Hush does use. Meanwhile the 20.4% JS engine and the 36% runtime share are
reached directly by the 22 used methods (`initExtensionSystem`, `loadExtensionFromPath`,
`invokeExtensionActionJSON`, `downloadByStrategy` all execute extension JS; the download path is the
uTLS user). A proportional "22 of 226 methods" cut is not available: the reachable closure of those
22 already contains the parts that dominate the bytes.

**Verdict on lever 3: real but bounded at roughly 1–2 MB of APK, for a fork of the bind layer or a
wrapper package that has to reproduce the exact Java surface Hush resolves reflectively. The same
1–2 MB is free from `-s -w`.**

---

## 7. Recommendation

1. **Rebuild the AAR stripped.** *(Applied — see §9.)* Add `-ldflags="-s -w"` to the `gomobile bind` line in
   `scripts/build-spotiflac-aar.sh`, rebuild `app/libs/gobackend.aar`, and the shipped release APK
   drops 49.15 → 34.50 MB. Keep an unstripped build in CI artifacts if native crash symbolication
   matters (`.note.gnu.build-id` no longer travels with the shipped `.so`). The AAR and the artifact
   that is verified in CI must be replaced in the same change — a script that strips while the
   committed AAR is unstripped is the drift the pins file and the Rust stage guard exist to prevent.
2. **Do not pursue gomobile export pruning.** No flag, a wrapper module to maintain, ~1–2 MB.
3. **Treat ABI splits as a separate distribution decision.** arm64-only would take the release APK to
   25.75 MB, but that is the universal-APK compatibility choice, not a backend property.
4. **Re-scope the migration's size driver before starting Phase 4.** It is 17.89 MB today, 4.68 MB
   after stripping both sides. If Phases 4–6 are to be justified on size, they now need to be
   justified on 4.68 MB; the maintenance driver (Go is deleted upstream at `v5.0.7`) is unaffected
   and is on its own enough to keep the plan alive — it just should not be quoted as a 34 MB win.

### What would still justify migrating after this

- Leaving an unmaintained backend — unchanged by any of the above, and the strongest remaining
  reason.
- The 4.68 MB residual APK gap, if a smaller download is independently wanted.
- Re-deriving the six log phrasings and `getAllPendingAuthRequestsJSON` (Phase 3 D2) is the cost
  side; nothing here reduces it.

---

## 8. Limits of this investigation

- **No device run** *at investigation time.* The test device was off USB then, so the stripped AAR was
  never installed and no lossless download was exercised. It has since been installed and exercised
  (§10). What *was* proven structurally: identical
  `classes.jar`, identical 1752 JNI exports, identical `DT_NEEDED`, same number of `PT_LOAD` segments
  and dynamic entries, and byte-identical sizes for `.text`, `.gopclntab`, `.rodata`, `.data.rel.ro`,
  `.rela.dyn`, `.dynsym`. That was the state of the evidence then; §10 now carries the load,
  download and playback pass on device.
- **The APK figure was a projection, not a built APK.** It was produced by measuring the stripped
  `.so`s at the deflate level AGP demonstrably used (level 6 reproduces the existing APK's stored
  size exactly), not by swapping the AAR and running an assemble — replacing the tracked AAR was out
  of scope for an investigation. §9 records the assemble that has since been run: it reproduces the
  Go-lib contribution **to the byte**, and the total differs only by unrelated working-tree changes.
- **The text attribution is a symtab heuristic.** Closures are attributed to their file's stem;
  19,872 B of `go_backend` symbols were unmapped.
- **`zlib` level 6 == AGP's level** is established for the raw arm64 entry only; the v7a and stripped
  projections assume the same setting, which the arm64/v7a agreement on the raw files supports.
- At investigation time the stripped AAR lived in `/tmp` and nothing in `app/` or `scripts/` was
  changed. That is no longer the tree's state — see §9.

---

## 9. The recommendation, applied and re-measured

The strip landed as predicted. `-ldflags="-s -w"` was added to the `gomobile bind` line in
`scripts/build-spotiflac-aar.sh`, the AAR was rebuilt through the script itself (checkout verified at
the pin, `a34e2935`), and the release APK was assembled for real rather than projected.

| check | predicted (§3, §4) | measured after applying | match |
|---|---|---|---|
| `gobackend.aar` | 18,472,589 B | **18,472,589 B** (sha256 `1a127cee…`) | exact |
| `jni/arm64-v8a/libgojni.so` | 24,174,272 B | 24,174,272 B | exact |
| `jni/armeabi-v7a/libgojni.so` | 24,028,692 B | 24,028,692 B | exact |
| `classes.jar` sha256 | `b1f3d736…` (unchanged) | `b1f3d736…` | exact |
| `Java_gobackend_*` exports | 1752 | 1752 (both ABIs) | exact |
| `DT_NEEDED` / `PT_LOAD` | 4 / 3 | 4 / 3 (both ABIs) | exact |
| `.debug_*`/`.symtab`/`.strtab` | removed | 0 remaining (both ABIs) | exact |
| Go libs stored in APK | 16,990,780 B | **16,990,780 B** | exact |
| release APK, universal | 34.50 MB | **34,779,056 B** (34.78 MB) | **+279,563 B** |

- The `AAR` was rebuilt by `scripts/build-spotiflac-aar.sh` (12.2 s, warm cache), 33,333,337 →
  18,472,589 B. `classes.jar` is byte-identical, so the Java/Kotlin surface the reflective bridge
  resolves is untouched.
- `:app:assembleFossMobileUniversalRelease` completed `BUILD SUCCESSFUL in 6m 23s` — so the stripped
  AAR passes R8, `shrinkResources` and `lintVital` at the real release gate, not just a bind.
- **The Go libs in the built APK are 16,990,780 B stored — the projection in §4 exactly** (8,237,743 +
  8,753,037; the per-entry pair is ±1 B from deflate rounding, the total identical).
- The APK total is 279,563 B above the projection, and **none of it is the AAR**. It is entirely in
  "everything else": dex/resources/assets grew 17,296,772 → 17,576,045 B (+279,273) with 290 B more
  zip overhead, all of it uncommitted working-tree code that is not part of this change. Holding the
  code fixed, swapping the old AAR back in gives 34,779,056 − 16,990,780 + 31,645,215 = 49,433,491 B,
  i.e. the same +279,563 B against the 49,153,928 B baseline — so the strip's effect on an identical
  tree is the 14,654,435 B of §4.
- Two build outputs moved as a side effect: `app/libs/gobackend-sources.jar` (a 134,586 B companion
  gomobile always emits beside `-o`, untracked and not gitignored) was produced and removed, and the
  `fossMobileUniversal/release` APK was overwritten with the stripped build.
- The AAR was rebuilt and packaged here, and has since been installed and exercised on device (§10).
  The `.note.gnu.build-id` symbolication caveat still stands.

---

## 10. On device: the stripped AAR loads, downloads and plays

Run on the reporting device once it was back on USB — a OnePlus NE2211 (Android 16, arm64-v8a). Factor
builds of **both** variants were installed, both built from the stripped AAR (§9): release
`app.hush.music` (34,779,056 B, signed with `app/keystore/release.keystore`, cert SHA-256 `fbd0b596…`
— the same cert the pre-existing install carried, so it updated in place with data intact) and debug
`app.hush.music.debug` (debug keystore, `8058de3f…`). The `.so` the device actually extracted is
**24,174,272 B** in both, i.e. the stripped build, in `lib/arm64/libgojni.so`.

**It loads.** The one thing a strip could break is the loader, and it does not:

```
D/nativeloader: Load …/base.apk!/lib/arm64-v8a/libgojni.so … : ok
I/SpotiFLACDiag: loadExtensionsFromDir: loaded=8 skipped=8 errors=0
```

No `UnsatisfiedLinkError`, no `dlopen` failure, no `JNI DETECTED ERROR`, and the extension runtime runs
(nine `GoLog` lines of goja-driven extension loading).

**It downloads losslessly, through two different providers.** Driven with the debug-only
`SpotiFLACDebugReceiver` (`app/src/debug`, in nothing that ships), which runs the app's real download
path and reports what it produced:

| source | fresh? | bytes | container | codec | bits/rate | integrity | elapsed |
|---|---|---|---|---|---|---|---|
| `qobuz-web` | `fromCache=false` | 28,180,468 | `flac` | `flac` | 16 / 44100 | OK | 17,986 ms |
| `deezer` | `fromCache=false` | 21,024,273 | `flac` | `flac` | 16 / 44100 | OK | 10,981 ms |

Both files were then read back and played through the app's own media3 stack:

```
bytes … container=flac encrypted=false verdict=OK
bytes head=664c6143…            # "fLaC" — a real FLAC stream, not a renamed container
play … ready=true mime=audio/flac rate=44100 channels=2 advanceMs=1923 verdict=PASS
```

`advanceMs=1923` is the decoder actually consuming samples, so the file is not merely recognised — it
plays. A `resolve` that returned `fromCache=true` in 40 ms on a track already on disk is reported too,
because a cache hit is not evidence of a download and the difference is exactly what `fromCache` is for.

One failure was observed and attributed, and it is upstream: `qobuz-web` on a second track returned
`provider qobuz-web timed out without progress for 12612ms (stuck on resolving_stream)` after
23,285 ms, while the same source succeeded on the next track. A provider-side stall, with no native
component in it.

**No native faults anywhere in the session.** A full logcat dump of the run contains no
`UnsatisfiedLinkError`, no `dlopen` failure, no `JNI DETECTED ERROR`, no tombstone, `SIGSEGV` or
`SIGABRT`, and no `FATAL EXCEPTION`; both processes were still alive at the end.

Two things this does not cover, both unchanged: `.note.gnu.build-id` no longer travels with the
shipped `.so`, so symbolication of a native crash from this build would need a kept unstripped copy;
and the 4 sources that need verification were verified from the device (the automatic WebView route
reached `VERIFIED` for `deezer` and `qobuz-web`; `amazon` and `tidal-web` remain
`NEEDS_VERIFICATION`) — session state is unrelated to the AAR.

One artifact-level oddity worth recording: the **debug** APK's file size did not shrink (95,895,384 B
before and after), because AGP's incremental packaging reserved the space the unstripped libs had
occupied and left ~14.75 MB of zero padding after each `libgojni.so`. It is packaging padding, not
data — the entry the device extracts is the real 24,174,272 B — and a clean build removes it. The
release APK, which is what ships, shows the full reduction.
