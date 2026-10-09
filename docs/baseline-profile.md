# Shipping a baseline profile with Hush: what it buys, and where it does not

**Question.** A first launch of Hush runs the whole launch surface — app startup, the home feed, the
mini player — on a device that has never run any of it, so those paths are interpreted. Can a
generated baseline profile be shipped with the release build, and does it actually move the number a
user feels on a first install?

**Verdict. Yes to shipping, and the win is real but conditional.** A profile generated from a
first-run journey (cold start → home feed scroll → mini player open/back → warm start) is committed
at `app/src/main/baseline-prof.txt` — **51,996 rules** — and every variant merges it. The release
build does not ship those rules verbatim: R8 rewrites them against the release mapping (a
57,456-rule merge becomes 32,795 obfuscated rules) and packages
`assets/dexopt/baseline.prof` (**25,942 B**, plus a 4,041 B `.profm`) into
`hush-foss-mobile-universal-release.apk`.

Measured on the test device (Android 16, arm64), cold start of the **first** launch after install drops
from **470.5 ms to 305.5 ms (−35%)**, and repeat cold starts from **359.5 ms to 234.8 ms (−35%)**,
in a two-cycle interleaved A/B whose arms are byte-identical APKs apart from the profile.

**The condition is the one that matters in production:** the gain only exists once something
compiles the app *with* the profile. Installed normally, this device leaves the package at
`Dexopt state: [status=verify] [reason=install]` — no AOT compilation at all — and in that state the
same A/B shows **no difference** (first launch 426 ms without, 458 ms with; janky frames 0.84%
without, 0.98% with, all within run-to-run noise). The profile is an instruction to the compiler, not
a thing the app can execute; it pays off where the platform acts on it (Play install-time compilation
on supported devices, or a `speed-profile` compile after background dexopt), and buys nothing where
the platform only verifies.

---

## 1. How the profile is built and wired

| Piece | Where |
|---|---|
| Generator (device-driven journey) | `benchmark/src/main/kotlin/app/hush/music/benchmark/BaselineProfileGenerator.kt` |
| A/B on a device | `.../benchmark/StartupBenchmark.kt` (`Require` vs `Disable`) |
| Benchmark module | `benchmark/build.gradle.kts` (`com.android.test`, flavours mirroring `:app`) |
| Committed profile | `app/src/main/baseline-prof.txt` — 51,996 rules, 5.3 MB |
| Plugin | `androidx.baselineprofile` 1.5.0 (`gradle/libs.versions.toml`), applied and fed `baselineProfile(project(":benchmark"))` in `app/build.gradle.kts` |
| Install target for the run | `:app`'s `benchmark` build type: release-shaped, `isMinifyEnabled = false`, debug-signed, id `app.hush.music.bench` |

Two build-type details exist because of how the tooling behaves on a real phone:

- The app's `benchmark` build type is **release-shaped but unminified**. The generator walks the app
  on the device, and the profile it captures names the classes and methods it saw; R8 later rewrites
  those names for the minified release, which is the only way the shipped rules can match the shipped
  code. Measuring an unminified build is also what makes the A/B installable without the release
  keystore.
- The profile plugin creates its own generation build type, `nonMinifiedRelease`, in its own
  `afterEvaluate`. Left alone it inherits the release **application id** while being signed with the
  debug key: capturing a profile would then install over a real release install, and the tooling
  removes the package it drove when the run ends. `app/build.gradle.kts` gives it the same `.bench`
  suffix, so the two never touch each other. (This is not hypothetical — the first generation run
  installed over the release app id before the suffix was added.)

The plugin also drops its own copy of the collected profile into
`app/src/<variant>/generated/baselineProfiles/`. That path is build output and is gitignored; the one
copy the merge is meant to read is `app/src/main/baseline-prof.txt`. Verified by deleting the
variant copy and rebuilding: the release merge re-ran and produced 57,456 rules (51,996 app + 5,459
library) instead of the 109,453 it had produced while both copies were present — the app's rules,
counted twice — i.e. the committed file alone feeds the release build.

## 2. What the release actually ships

The release build was run twice from the same sources — once with `app/src/main/baseline-prof.txt`
present and once with it moved aside — and the difference is entirely in the profile:

| Stage | Without the file | With the file |
|---|---|---|
| `mergeFossMobileUniversalReleaseArtProfile` rules | 5,459 | 57,456 (5,459 library + 51,996 app) |
| `minifyFossMobileUniversalReleaseWithR8` rules (rewritten) | 13,881 | **32,795** |
| `assets/dexopt/baseline.prof` in the APK | 14,504 B | **25,942 B** |
| `assets/dexopt/baseline.profm` in the APK | 2,097 B | 4,041 B |
| `hush-foss-mobile-universal-release.apk` | 34,796,784 B | 34,808,200 B |

Both assets are written by `compileFossMobileUniversalReleaseArtProfile` under
`intermediates/binary_art_profile/fossMobileUniversalRelease/`; the `.profm` carries the
library-provided fragments.

The rules R8 emits are obfuscated, as they must be for a minified build, and the rewriting is
visible in the intermediate: `r8_art_profile/fossMobileUniversalRelease/minifyFossMobileUniversalReleaseWithR8/baseline-prof.txt`
(32,795 rules) contains entries such as `Lmi0;` / `SPLmi0;-><init>(Lfy6;)V` alongside kept names like
`SPLgi7;->F()Lapp/hush/music/App;`. `assets/dexopt/baseline.prof` in the APK is exactly that file,
compiled to the binary format.

## 3. The measurement

Two APKs of the **same** variant — `fossMobileUniversalBenchmark` (`app.hush.music.bench`,
non-debuggable, unminified, debug-signed) — built from the same sources and differing only in
`assets/dexopt/baseline.prof`: **15,040 B** from the library modules alone, versus **42,412 B** with
the committed app profile merged in. That "only" is checked, not assumed: of the 1,801 zip entries
in each APK, 1,796 are byte-identical and the five that differ are the two profile assets plus the
three `META-INF` signature files that have to follow from them.

Method, per arm, interleaved across two cycles so a warming device cannot favour one:

1. `adb install -r` the APK, then `pm clear` — the app's data is empty, which is the first-run state
   the question is about.
2. Let the install's own dexopt settle, then `cmd package compile -m speed-profile -f`, retried until
   `dumpsys package` reports `[status=speed-profile]`. **This step is the measurement**: without it
   both arms sit at `[status=verify]` and run identical code paths (§4).
3. Three cold starts per arm, each preceded by `am force-stop`, timing `am start -W`'s `TotalTime`.

`TotalTime` (ms), test device / Android 16:

| Cycle | Arm | Launch 1 | Launch 2 | Launch 3 |
|---|---|---|---|---|
| 1 | profile absent | 485 | 183 | 447 |
| 1 | profile present | **316** | **156** | **270** |
| 2 | profile absent | 456 | 371 | 437 |
| 2 | profile present | **295** | **262** | **251** |

| Statistic | Absent | Present | Change |
|---|---|---|---|
| First launch (mean of cycles) | 470.5 ms | 305.5 ms | **−35.1%** |
| All six launches (mean) | 396.5 ms | 258.3 ms | **−34.8%** |
| Repeat cold starts (4 launches, mean) | 359.5 ms | 234.8 ms | **−34.7%** |
| Best launch | 183 ms | 156 ms | −14.8% |

The first launch is the largest and most consistent effect, in both cycles (485→316, 456→295) — which
is the shape to expect: that launch is the only one whose work is entirely un-compiled, and the
profile's rules name exactly that work.

## 4. Why the first attempt measured nothing

The same A/B run **without** the forced compile, three cycles, produced no signal at all:

| Arm | First launch (3 cycles) | Repeat cold start | Janky frames (first scroll) |
|---|---|---|---|
| profile absent | 401, 454, 423 (mean 426) | 240, 287, 453 (mean 327) | 0.82%, 0.66%, 1.05% (mean 0.84%) |
| profile present | 455, 454, 464 (mean 458) | 209, 318, 447 (mean 325) | 0.87%, 0.71%, 1.36% (mean 0.98%) |

Both arms installed as `arm64: [status=verify] [reason=install] [primary-abi]`: this device does not
AOT-compile at install, so neither APK had anything compiled ahead of time and the profile's rules
were never consulted. An A/B run this way measures the *packaging* difference (a few KB of asset)
and nothing about the profile. Anyone repeating this test needs step 2 above, and needs to confirm
`[status=speed-profile]` before believing the numbers.

## 5. Debug against release: the part no profile can remove

The release APK, signed with the project's release keystore, installed as `app.hush.music`
(versionName 13.14.6, md5 `54e2bdafeca95b53c5fed6814688c19a`, shipping the 25,942 B profile) against the
debug install (`app.hush.music.debug`, the same sources, unminified, `debuggable=true`). Four cold
starts each, measured one app at a time with a 20 s settle before each block, `am start -W`'s
`TotalTime`, test device / Android 16:

| Build (state) | Cold start, four launches | Mean |
|---|---|---|
| release, `[status=verify] [reason=install]` | 404, 393, 419, 400 | **404 ms** |
| release, `[status=speed-profile]` | 350, 358, 344, 372 | **356 ms** |
| debug, `[status=verify]` | 1795, 1804, 1754, 1784 | **1784 ms** |

The debug build costs **~1.4 s more per cold launch**, and it does that while carrying *more* work
than the release install does (the debug app holds a scanned library and configured accounts; the
release install is fresh and shows the default mood feed). Both aliases resolve to the same
`MainActivity`, so the runtime work being measured is the same activity.

Two things are worth reading out of that:

- **A debuggable build cannot be compiled out of interpretation here.** `cmd package compile -m
  speed -f` on `app.hush.music.debug` leaves it at `[status=verify]`; earlier runs showed
  `[status=run-from-apk]`. ART runs debuggable apps from the APK with JIT only. So the ~1.4 s is not
  something a profile, a device setting or a reinstall can recover on the debug build.
- **The profile is worth ~12% once the device compiles with it** (404 → 356 ms), on top of the
  build-type difference. It is invisible at `verify`, which is what an install leaves behind here.

Where a "2.3 s" debug launch goes, then: at least ~1.4 s is the debug build itself (JIT-only
interpretation, no R8, an 89 MB APK against 35 MB), and ~0.4–0.9 s is launch work the release build
also does — library reads, network, the first composition. Those are not the same data, so the split
is a bound rather than a decomposition; the same-code A/B in §3, where nothing but the profile
differs, puts the interpreted-vs-compiled share at ~35% of a fresh launch.

## 6. The Room open, off the main thread

The database was the one piece of launch work that ran inline on the main thread:
`InternalDatabase.newInstance` forces `openHelper.writableDatabase` so it can catch and repair a
corrupt or half-migrated file before handing the app a database, and Hilt resolved that singleton
for `MainActivity`'s injection in `onCreate` — before the content view existed. Measured on the
test device, the open itself. The first step kept the open eager and only moved it off the main thread:

| The open, timed around `openHelper.writableDatabase` | warm database | fresh install (schema created) |
|---|---|---|
| before: on the calling (main) thread | 41 ms | 39 ms |
| after: on `hush-database-open`, started from `App.onCreate` | 7-10 ms | 152 ms |

The `after` numbers are not a like-for-like improvement over the `before` ones: the same work now
runs *concurrently* with the rest of startup (BotGuard, the canvas cache, preference loading) instead
of owning the main thread, and the two sessions were hours apart on a device that had been running
benchmarks throughout. What is comparable is the thread the work runs on, and the wait it leaves
behind: 7-13 ms on a warm database, 176 ms on a fresh install against a 175 ms open - i.e. the
injection waits for essentially the whole open, because it happens milliseconds after the opener
starts.

`DatabaseOpener` now owns the open: `App.onCreate` starts it on its own thread, the Hilt provider
waits for that future, and `MainActivity` never performs the open. The repair path is untouched — the
whole of `newInstance`, repair included, simply runs on the opener thread.

**What that does and does not buy.** The main thread no longer *does* the open; it *waits* for the
remainder, and on a fresh install the remainder is nearly all of it. Launch time is therefore
unchanged by this in any way `StartupBudget` can see - the launch totals measured around it were
512-937 ms against a 1239 ms baseline, all inside the run-to-run spread of a phone that had been
benchmarking for hours. What it does remove is the *work* from the frame-critical thread, and it
leaves the one place the database is produced in one place, which is what the next step needs.

### Second step: the open leaves the launch path

Waiting for the opener still meant the calling thread *waited* for the open, and on a fresh install
that wait is the whole open. So the block was removed rather than moved:

- `InternalDatabase.newInstance` now **builds without opening**. It no longer touches
  `openHelper.writableDatabase`, so Room opens the file on first *use*, on the executors set on the
  builder - the query executor, which is what the UI's suspend reads and Flow queries run on.
- The repair moved with it, into `RepairingOpenHelperFactory`: a wrapper around Room's own helper that
  runs the sequence `newInstance` used to run - repair the file in place, retry, and if it still will
  not open, delete and recreate - when the open throws one of the identity-check failures. The helper
  is the one place guaranteed to run before any query, so the old guarantee holds: a corrupt or
  half-migrated file is repaired (or replaced) before a query ever sees it.
- `DatabaseOpener` remains, narrowed to the *build*: cheap, no disk, on a thread of its own started
  from `App.onCreate`, and the provider waits only for that.

Where the two steps land, from the same instrumentation (test device):

| On the calling thread | First step (eager, off-main) | Second step (lazy) |
|---|---|---|
| provider's wait | 7-10 ms warm, 152 ms fresh - effectively the whole open | **75 ms and 193 ms** in two runs, 34 ms in an earlier one - the build only, no disk |
| the open itself | on `hush-database-open`, concurrently but before the provider returns | **143 ms, ~1.1 s after the build**, on Room's query executor |

**Who waits, measured on the final build.** The work runs off the main thread, but the main thread
still waits for it - and the ordering is not what the design intended. The first request for the
database does not come from `App.onCreate` at all: Hilt resolves it during the pre-`onCreate` phase
(provider installation), so `await` runs there and blocks for the whole build. Markers added for one
run, test device:

| Time | Thread | What |
|---|---|---|
| 16:50:59.394 | main | `await` entered - stack `AppModule.provideDatabase` / `...ProvideDatabaseFactory` / `DoubleCheck` |
| 16:50:59.394 | `hush-database-open` | build body starts |
| 16:50:59.469 | `hush-database-open` | build done - 75 ms |
| 16:50:59.472 | main | `App.onCreate` entered, 78 ms after the build began |
| 16:50:59.480 | main | `DatabaseOpener.start` from `App.onCreate` - after the build had already finished |

So the build is off the main thread, and the prefetch in `App.onCreate` is a no-op: `task()` returns
the future `await` already created. What the change reliably removed from the launch is the **open** -
the file I/O, schema check and possible repair - which now happens on Room's query executor
(`pool-4-thread-1`) about 1.6 s after launch, where the same work used to sit inline on the main
thread. The build itself is still paid for by the launch, once, on whichever thread asks first.

That ordering also explains a red herring: `DatabaseOpener`'s own `Timber.d` lines never appear in
logcat, because the build runs before `App.onCreate` plants the first `Timber` tree.

The repair still works, checked the way the eager path was: the identity hash in the file's header was
tampered with, the app launched, and the open failed exactly as it used to - `Database open failed,
attempting schema repair` came out on an executor thread (not the main thread), the tampered hash was
rewritten to the expected value, the tables were intact, and the app rendered. The fallback - delete
the file and let Room recreate it - is unchanged.

## 7. Limitations

- **One device.** Android 16 / arm64, for every number here.
- **The −35% is measured on the unminified benchmark variant**, the −12% on the signed release (§5).
  Neither pair holds the app's data constant: the release install is fresh and the debug install
  holds a real library, so the debug/release split is a bound, not a decomposition. A like-for-like
  run needs either the debug app's data cleared or the release app pointed at the same library.
- **Jank did not move.** First-scroll janky-frame percentages stayed inside run-to-run noise
  (0.66–0.82% absent versus 0.71–1.36% present in the un-compiled run). The profile compiles methods
  the launch calls; it does not change what the home feed draws per frame.
- **Coverage is the journey that was walked.** Launch, home feed scroll, mini player open/back, warm
  start — see `BaselineProfileGenerator`. Nothing deeper (search, library, settings, player) is in
  the profile until the generator walks it, and a bigger journey is not free: the rules are what gets
  merged and rewritten on every release build.
- **A single `am start -W` run is not the macrobenchmark.** `StartupBenchmark` (5 iterations, `Require`
  vs `Disable`) is the more precise instrument and is committed for that reason; the numbers above
  come from the interleaved shell A/B because it runs on one phone without the benchmark module's
  extra installs.

## 8. Regenerating and re-measuring

```
# Regenerate on a connected device (writes the collected profile into the app module)
./gradlew :app:generateFossMobileUniversalBaselineProfile
cp app/src/<variant>/generated/baselineProfiles/baseline-prof.txt app/src/main/baseline-prof.txt

# Measure on a connected device, profile required vs absent, in the recorded JSON output.
./gradlew :benchmark:connectedFossMobileUniversalNonMinifiedReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark

# Fail the run when a cold start regresses (StartupBudget; ~3.5 min, five launches)
./gradlew :benchmark:connectedFossMobileUniversalNonMinifiedReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.hush.music.benchmark.StartupBudget \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark

# What CI builds; ships the R8-rewritten profile with no device attached
./gradlew :app:assembleFossMobileUniversalRelease
```

Regenerate when the launch path changes materially, not on every release: the profile is a
snapshot of a journey, and stale rules are dead weight in a 6 MB merge, not a hazard.

**`androidx.benchmark.enabledRules` is not optional in that command.** The Gradle task runs the
instrumentation with `-e androidx.benchmark.enabledRules baselineprofile` - what the profile
*generator* needs - and `MacrobenchmarkRule` then fails its assumption for every test not in that
list. The run reports `SKIPPED` with no reason in the console or the XML, so a macrobenchmark that is
not measuring anything looks exactly like one that passed. The same trap catches [StartupBenchmark]
and [StartupBudget] alike.

### The launch guard

`benchmark/src/main/kotlin/app/hush/music/benchmark/StartupBudget.kt` asserts the median of five cold
launches against `BASELINE_MEDIAN_MS` (1678 ms, measured here) times `REGRESSION_FACTOR` (1.3), so a
launch that has grown by a quarter fails the build.

Its statistic is `startActivityAndWait()`, which returns once the app is **idle**, not at first
frame - a time-to-usable launch. The three numbers for the same five launches, on the same build:

| Statistic | Value |
|---|---|
| `startActivityAndWait()` (what the guard asserts) | median **1678 ms**, 1619-1811 ms |
| `StartupTimingMetric`'s `timeToInitialDisplayMs`, from the run's JSON | median **435 ms** |
| `am start -W`'s `TotalTime` (§3, fresh install of the same variant) | 156-316 ms |

Waiting for idle is the more useful budget: a launch that paints quickly and then spends a second
composing the feed is the regression worth failing on, and only this statistic sees it. The first
five samples spread ±7%, which is what `REGRESSION_FACTOR` has to clear.

A debuggable build cannot be guarded this way at all - Macrobenchmark will not set a compilation
mode for one, and on this device `cmd package compile` leaves the debug build at `[status=verify]`,
JIT-only. The 1.4 s the debug build adds (§5) is a development cost with no user-facing budget to
guard.
