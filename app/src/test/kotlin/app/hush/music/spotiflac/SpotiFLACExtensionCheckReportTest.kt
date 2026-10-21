package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-source check line is the only place that says *when* a package was last looked at and
 * which registry this install follows for it. A timestamp cannot be re-read off the screen later,
 * so these pin the wording - including the case that must never be silent: a source that has never
 * been checked at all.
 */
class SpotiFLACExtensionCheckReportTest {

    private val checked = SpotiFLACExtensionCheck(
        sourceId = "amazon",
        registry = "spotiflacapp",
        checkedAtMs = 1_000_000L,
        outcome = SpotiFLACExtensionCheck.Outcome.CURRENT,
        fromVersion = "2.3.10",
        toVersion = "2.3.10",
    )

    @Test
    fun `a registry URL is named by its owner, not its whole path`() {
        assertEquals(
            "spotiflacapp",
            SpotiFLACExtensionCheckReport.registryLabel(
                "https://raw.githubusercontent.com/spotiflacapp/spotiflac-extension/main/registry.json",
            ),
        )
        // The second published registry has to stay distinguishable from the first.
        assertEquals(
            "zarzet",
            SpotiFLACExtensionCheckReport.registryLabel(
                "https://raw.githubusercontent.com/zarzet/spotiflac-extension/main/registry.json",
            ),
        )
        // A bare host plus file can only be named by the host.
        assertEquals(
            "example.com",
            SpotiFLACExtensionCheckReport.registryLabel("https://example.com/registry.json"),
        )
        // A built-in entry has no registry at all, and that is not a name.
        assertNull(SpotiFLACExtensionCheckReport.registryLabel(null))
        assertNull(SpotiFLACExtensionCheckReport.registryLabel(""))
        assertNull(SpotiFLACExtensionCheckReport.registryLabel("   "))
    }

    @Test
    fun `elapsed time reads as a phrase at every scale`() {
        val base = 10_000_000_000L
        assertEquals("just now", SpotiFLACExtensionCheckReport.ago(base, base))
        assertEquals("just now", SpotiFLACExtensionCheckReport.ago(base, base + 59_000L))
        assertEquals("1 min ago", SpotiFLACExtensionCheckReport.ago(base, base + 60_000L))
        assertEquals("12 min ago", SpotiFLACExtensionCheckReport.ago(base, base + 12 * 60_000L))
        assertEquals("3 h ago", SpotiFLACExtensionCheckReport.ago(base, base + 3 * 3_600_000L))
        assertEquals("5 d ago", SpotiFLACExtensionCheckReport.ago(base, base + 5 * 86_400_000L))
        // A clock that moved backwards must not print a negative age.
        assertEquals("just now", SpotiFLACExtensionCheckReport.ago(base, base - 5_000L))
    }

    @Test
    fun `a current package says when it was checked and where it came from`() {
        assertEquals(
            "Checked 2 h ago · from spotiflacapp",
            SpotiFLACExtensionCheckReport.line(
                check = checked,
                registry = "spotiflacapp",
                nowMs = 1_000_000L + 2 * 3_600_000L,
            ),
        )
    }

    @Test
    fun `an updated package is described as updated, with its registry`() {
        assertEquals(
            "Updated 1 min ago · from zarzet",
            SpotiFLACExtensionCheckReport.line(
                check = checked.copy(
                    registry = "zarzet",
                    outcome = SpotiFLACExtensionCheck.Outcome.UPDATED,
                ),
                registry = "spotiflacapp",
                nowMs = 1_000_000L + 60_000L,
            ),
        )
    }

    @Test
    fun `a failed check keeps its reason instead of its registry`() {
        val reason = "Unable to resolve host \"raw.githubusercontent.com\""
        assertEquals(
            "Check failed 5 min ago: $reason",
            SpotiFLACExtensionCheckReport.line(
                check = checked.copy(
                    outcome = SpotiFLACExtensionCheck.Outcome.FAILED,
                    detail = reason,
                ),
                registry = "spotiflacapp",
                nowMs = 1_000_000L + 5 * 60_000L,
            ),
        )
        // Without a reason the registry is still worth naming.
        assertEquals(
            "Check failed 5 min ago · from spotiflacapp",
            SpotiFLACExtensionCheckReport.line(
                check = checked.copy(outcome = SpotiFLACExtensionCheck.Outcome.FAILED, detail = null),
                registry = null,
                nowMs = 1_000_000L + 5 * 60_000L,
            ),
        )
    }

    @Test
    fun `a source that was never checked says so, and still names its registry`() {
        // Silence here would read as "nothing to report" about a package that may never have been
        // looked at at all, so the source's own registry is used until a record exists.
        assertEquals(
            "Not checked yet · from spotiflacapp",
            SpotiFLACExtensionCheckReport.line(
                check = null,
                registry = "spotiflacapp",
                nowMs = 1_000_000L,
            ),
        )
        assertEquals(
            "Not checked yet · built-in",
            SpotiFLACExtensionCheckReport.line(check = null, registry = null, nowMs = 1_000_000L),
        )
        // A record without a label falls back to the source's, rather than printing nothing.
        assertTrue(
            SpotiFLACExtensionCheckReport.line(
                check = checked.copy(registry = null),
                registry = "zarzet",
                nowMs = 1_000_000L,
            )!!.endsWith("· from zarzet"),
        )
    }

    /**
     * The line that follows a source row which already names its repository.
     *
     * Measured on the device before this was pinned: every source row printed its repository twice,
     * and once a repository had been switched off - which moves a source to the other registry - the
     * two copies disagreed (`From spotiflacapp/spotiflac-ext…` above `Checked 8 min ago · from
     * zarzet`). One row, two repositories, and no way to tell which one to switch off.
     */
    @Test
    fun `a row that names its repository elsewhere does not name it again`() {
        val forRow = { check: SpotiFLACExtensionCheck?, registry: String? ->
            SpotiFLACExtensionCheckReport.line(
                check = check,
                registry = registry,
                nowMs = 1_000_000L + 2 * 3_600_000L,
                namesRepository = false,
            )
        }
        assertEquals("Checked 2 h ago", forRow(checked, "spotiflacapp"))
        assertEquals(
            "Updated 2 h ago",
            forRow(checked.copy(outcome = SpotiFLACExtensionCheck.Outcome.UPDATED), "zarzet"),
        )
        assertEquals(
            "Check failed 2 h ago",
            forRow(checked.copy(outcome = SpotiFLACExtensionCheck.Outcome.FAILED, detail = null), null),
        )
        // The reason is not an origin, so a failure that has one still says it.
        assertEquals(
            "Check failed 2 h ago: HTTP 403",
            forRow(
                checked.copy(
                    outcome = SpotiFLACExtensionCheck.Outcome.FAILED,
                    detail = "HTTP 403",
                ),
                "zarzet",
            ),
        )
        // And a source nothing has looked at still says so rather than going silent.
        assertEquals("Not checked yet", forRow(null, "spotiflacapp"))
        // The stored record that disagrees with the live merge is exactly what must not be printed:
        // with the flag on, it wins - which is the row this test exists to keep off the screen.
        assertEquals(
            "Checked 2 h ago · from spotiflacapp",
            SpotiFLACExtensionCheckReport.line(
                check = checked,
                registry = "spotiflacapp",
                nowMs = 1_000_000L + 2 * 3_600_000L,
            ),
        )
    }
}
