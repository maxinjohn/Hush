/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & 5
 */

package app.hush.music.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the report the probe writes for the windows besides the activity's.
 *
 * The section exists because a Compose menu is a window of its own: reading only the activity's
 * decor view reported an open menu as an empty screen, which is the one question a menu raises -
 * *what is in it* - left unanswered inside the process. Two of these tests are about the answer,
 * and two are about the two ways a run can end up looking like an answer when it is not: a scan
 * that could not look, and a window that says nothing. Neither may print like "no menu is open".
 */
class SemanticsProbeWindowSectionTest {

    private fun node(
        id: Int,
        top: Int,
        text: String,
        depth: Int = 4,
        merged: Boolean = true,
        clickable: Boolean = true,
    ) = SemanticsProbeNode(
        merged = merged,
        id = id,
        depth = depth,
        left = 64,
        top = top,
        right = 564,
        bottom = top + 192,
        text = text,
        keys = "",
        clickable = clickable,
    )

    /** A menu exactly as the device reported one: three items, one window, one composition. */
    private fun openMenu() =
        SemanticsProbeReceiver.WindowSnapshot(
            viewClass = "androidx.compose.ui.window.PopupLayout",
            title = "Pop-up window",
            width = 500,
            height = 640,
            focus = true,
            composeViews = 1,
            nodes = listOf(
                node(id = 586, top = 1088, text = "", depth = 3, clickable = false),
                node(id = 587, top = 1088, text = "Test this source"),
                node(id = 590, top = 1280, text = "Move up"),
                node(id = 593, top = 1472, text = "Move down"),
                // The unmerged tree holds the same words; sampling it would double every line.
                node(id = 588, top = 1088, text = "Test this source", depth = 5, merged = false),
            ),
        )

    private fun render(scan: SemanticsProbeReceiver.WindowScan, limit: Int = 60) =
        SemanticsProbeReceiver().renderWindows(scan, limit)

    @Test
    fun `an open menu is read as what it offers`() {
        val lines = render(
            SemanticsProbeReceiver.WindowScan(roots = 2, windows = listOf(openMenu()), failure = null),
        )
        assertEquals("windows: roots=2 besideTheActivity=1", lines.first())
        assertEquals(
            "window #1: view=androidx.compose.ui.window.PopupLayout title=\"Pop-up window\" " +
                "size=500x640 focus=true composeViews=1",
            lines[1],
        )
        assertEquals("  nodes: merged=4 unmerged=1", lines[2])
        // The order the items are drawn in, which is the order they are tapped in.
        assertEquals("  says: \"Test this source\" | \"Move up\" | \"Move down\"", lines[3])
        assertTrue(
            "the merged nodes are sampled with their geometry",
            lines.any { it.startsWith("    [merged] id=590") && it.contains("Move up") },
        )
        assertTrue(
            "the unmerged tree is not sampled as well",
            lines.none { it.contains("[unmerged]") },
        )
    }

    @Test
    fun `no other window is a measurement, and says so`() {
        val lines = render(SemanticsProbeReceiver.WindowScan(roots = 1, windows = emptyList(), failure = null))
        assertEquals("windows: roots=1 besideTheActivity=0", lines.first())
        assertTrue(
            "an absent window must be stated, not left as a missing section: $lines",
            lines[1].startsWith("no other window is attached"),
        )
    }

    @Test
    fun `a scan that could not look reports why`() {
        val lines = render(
            SemanticsProbeReceiver.WindowScan(
                roots = 0,
                windows = emptyList(),
                failure = "NoSuchFieldException: mViews",
            ),
        )
        assertTrue(
            "a failed scan must not read like 'no menu is open': $lines",
            lines.single().startsWith("windows: scan failed reason=NoSuchFieldException: mViews"),
        )
    }

    @Test
    fun `a window with nothing to say says that`() {
        val silent = openMenu().copy(nodes = listOf(node(id = 600, top = 100, text = "", clickable = false)))
        val lines = render(SemanticsProbeReceiver.WindowScan(roots = 2, windows = listOf(silent), failure = null))
        assertTrue(
            "a window whose nodes carry no words must be reported as wordless: $lines",
            lines.any { it.startsWith("  says nothing") },
        )
    }

    @Test
    fun `the node sample is capped by the caller`() {
        val lines = render(
            SemanticsProbeReceiver.WindowScan(roots = 2, windows = listOf(openMenu()), failure = null),
            limit = 2,
        )
        assertEquals("  sample (max 2):", lines[4])
        assertEquals(2, lines.count { it.startsWith("    [merged]") })
    }
}
