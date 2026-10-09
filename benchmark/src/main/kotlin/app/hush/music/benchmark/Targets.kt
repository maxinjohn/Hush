/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.benchmark

/**
 * The package the benchmark variant of the app installs as.
 *
 * `:app`'s `benchmark` build type appends `.bench` so a measurement build can live next to the
 * user's real Hush release without replacing it - the two are signed with different keys. Keep this
 * in sync with the `applicationIdSuffix` on that build type in `app/build.gradle.kts`.
 */
internal const val TARGET_PACKAGE = "app.hush.music.bench"
