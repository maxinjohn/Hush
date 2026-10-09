/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.Rule
import org.junit.Test

/**
 * Measures the launch and the first scroll of the app on a device, with the baseline profile
 * required and with it left out.
 *
 * The two runs differ in nothing but `baselineProfileMode`, so the difference between them is the
 * profile: `Partial(Require)` compiles the app with it, and a run where the profile's rules are the
 * only missing input is `Partial(None)`.
 *
 * Run with `./gradlew :benchmark:connectedFossMobileUniversalBenchmarkAndroidTest`, or a single
 * method from the command line:
 * `-Pandroid.testInstrumentationRunnerArguments.class=app.hush.music.benchmark.StartupBenchmark`.
 */
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
            iterations = 5,
            startupMode = StartupMode.COLD,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
        }

    @Test
    fun coldStartupWithoutProfile() =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Disable),
            iterations = 5,
            startupMode = StartupMode.COLD,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
        }

    /**
     * The first scroll after a launch. This is where a first-run install feels worst: the feed's
     * composables have never run, so every frame is interpreted until the profile's rules have
     * been compiled in.
     */
    @Test
    fun homeScroll() =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
            iterations = 5,
            startupMode = StartupMode.COLD,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
            device.waitForIdle()
            repeat(3) {
                device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 20)
            }
            device.waitForIdle()
        }

    @Test
    fun homeScrollWithoutProfile() =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Disable),
            iterations = 5,
            startupMode = StartupMode.COLD,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
            device.waitForIdle()
            repeat(3) {
                device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 20)
            }
            device.waitForIdle()
        }
}
