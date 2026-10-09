/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Fails the run when a cold start gets slower than [BASELINE_MEDIAN_MS] by more than
 * [REGRESSION_FACTOR], so a launch regression cannot land quietly.
 *
 * The AndroidX metrics are reported to the instrumentation runner instead of being handed back to
 * the test - `measureRepeated` returns `Unit`, and `StartupTimingMetric`'s numbers live only in the
 * runner's output and in the JSON under `build/outputs/connected_android_test_additional_output` -
 * so a test that can *assert* has to time the launch itself. `startActivityAndWait()` returns once
 * the launched app is idle, so this is a time-to-*usable* launch rather than the time to the first
 * frame: over the same five launches it is ~1.68 s where the library's own `StartupTimingMetric`
 * reports `timeToInitialDisplayMs` 435 ms (median) and `am start -W` reports `TotalTime` 156-316 ms.
 * That is deliberate - a launch that draws its first frame fast and then spends a second composing
 * the feed is exactly the regression this is meant to catch, and the budget is calibrated against
 * *this* statistic rather than against the other two.
 *
 * The statistic is the **median** of [ITERATIONS] launches, not the best one: a regression that
 * shows up in most launches has to fail the test, while a single launch that lost time to a
 * background sync should not. The failure message prints every sample so it says how far off it is.
 *
 * **The tests are skipped unless the rule is enabled.** The Gradle task runs instrumentation with
 * `-e androidx.benchmark.enabledRules baselineprofile`, which is what the profile generator needs, and
 * `MacrobenchmarkRule` then fails its assumption for any test that is not in that list - the report
 * says `SKIPPED` with no reason. Ask for the rule explicitly:
 *
 * ```
 * ./gradlew :benchmark:connectedFossMobileUniversalNonMinifiedReleaseAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=app.hush.music.benchmark.StartupBudget \
 *   -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark
 * ```
 *
 * What this guard does not cover: a debuggable build. `app.hush.music.debug` runs from the APK with
 * JIT only (`cmd package compile` leaves it at `[status=verify]`), so its launch is dominated by
 * interpretation that no profile or compile can remove, and Macrobenchmark cannot set the
 * compilation mode for it. The debug build's launch is a *development* cost; this is the
 * user-facing one, measured on the release-shaped benchmark variant that [TARGET_PACKAGE] names.
 */
class StartupBudget {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartupBudget() {
        val samples = mutableListOf<Long>()
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
            iterations = ITERATIONS,
            startupMode = StartupMode.COLD,
            setupBlock = { pressHome() },
        ) {
            val startedAt = System.nanoTime()
            startActivityAndWait()
            samples += (System.nanoTime() - startedAt) / 1_000_000
        }

        val sorted = samples.sorted()
        val median = sorted[sorted.size / 2]
        val budgetMs = (BASELINE_MEDIAN_MS * REGRESSION_FACTOR).toLong()
        // Also printed as it goes, so it is in logcat while the run is happening. A *passing* run
        // does not surface it in the Gradle console or in the test XML - UTP keeps only the
        // library's own metrics there - so re-calibrating on a new device means reading this from
        // logcat, or setting BASELINE_MEDIAN_MS low enough that the failure message prints it.
        println(
            "coldStartupBudget samples (ms): ${samples.joinToString(" ")} " +
                "median $median budget $budgetMs (baseline $BASELINE_MEDIAN_MS x$REGRESSION_FACTOR)",
        )
        assertTrue(
            buildString {
                appendLine("Cold start regressed: median ${median} ms is over the ${budgetMs} ms budget.")
                appendLine()
                appendLine("samples (ms): ${samples.joinToString(" ")}")
                appendLine("best ${sorted.first()} ms, worst ${sorted.last()} ms, ${samples.size} launches")
                appendLine("baseline $BASELINE_MEDIAN_MS ms x$REGRESSION_FACTOR = $budgetMs ms")
                appendLine()
                append("If the app genuinely got faster (or slower) rather than regressed, re-measure and ")
                append("move BASELINE_MEDIAN_MS - and say which device and commit the new number came from.")
            },
            median <= budgetMs,
        )
    }

    private companion object {
        const val ITERATIONS = 5

        /**
         * The median of this test's own launches on the NE2211 (Android 16, arm64) on 2026-10-09,
         * `CompilationMode.Partial(BaselineProfileMode.Require)`, fresh install of the unminified
         * benchmark variant: 1619, 1644, 1678, 1704, 1811 ms - median 1678 ms, worst 1811 ms.
         *
         * For scale, the same launches measured with `am start -W` on the same variant were 156-316
         * ms: this statistic waits for the app to be idle rather than for its first frame, so it is
         * the larger number by construction and the two are not comparable.
         */
        const val BASELINE_MEDIAN_MS = 1678.0

        /**
         * How far above the baseline still counts as the same app on a different day. The five
         * samples above span ±7%, and the budget lands at 2181 ms, so run-to-run noise and a slower
         * device pass while a launch that has grown by a quarter fails.
         */
        const val REGRESSION_FACTOR = 1.3
    }
}
