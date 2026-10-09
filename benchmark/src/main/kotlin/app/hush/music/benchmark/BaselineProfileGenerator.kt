/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Captures the rules that become `app/src/main/baseline-prof.txt` by driving the app on a device.
 *
 * The journey is the launch itself plus the two interactions the launch is spent preparing for:
 * scrolling the home feed, and opening a full-screen surface and coming back. Those are the paths
 * that run interpreted on a first install and that this profile asks ART to compile ahead of time.
 *
 * Run it with `./gradlew :app:generateBaselineProfile`, or with the variant named explicitly:
 * `./gradlew :app:generateFossMobileUniversalBaselineProfile`. The plugin saves what this run
 * collects into `app/src/<variant>/generated/baselineProfiles/`; copy that file to
 * `app/src/main/baseline-prof.txt`, which is the one copy every variant merges - and the copy R8
 * rewrites against the release mapping before it is packaged. The variant-local copy is build
 * output and is gitignored, so the two cannot drift into a doubled profile.
 */
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun launchHomeScrollAndBack() =
        baselineProfileRule.collect(packageName = TARGET_PACKAGE) {
            // The cold start: everything the process does between fork and the first frame.
            startApp(TARGET_PACKAGE)
            device.waitForIdle()

            // The home feed, which is what the first seconds after the launch compose.
            repeat(3) {
                device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 20)
                device.waitForIdle()
            }
            device.swipe(device.displayWidth / 2, device.displayHeight / 4, device.displayWidth / 2, device.displayHeight * 3 / 4, 20)
            device.waitForIdle()

            // The mini player sits above the feed; opening it is the other half of the launch path.
            device.findObject(By.clickable(true))?.click()
            device.waitForIdle()
            device.pressBack()
            device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE)), 3_000)

            // And a warm start, which is a separate code path from the cold one.
            pressHome()
            startApp(TARGET_PACKAGE)
            device.waitForIdle()
        }
}
