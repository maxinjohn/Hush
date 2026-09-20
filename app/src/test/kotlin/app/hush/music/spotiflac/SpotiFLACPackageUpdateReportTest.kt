package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A startup check that updates a package without saying so is indistinguishable from a stale build,
 * and the log it used to be reported in is not somewhere a user looks. These pin the sentence the
 * Audio Sources list shows - and, more importantly, that a sweep which changed nothing stays silent
 * rather than announcing itself.
 */
class SpotiFLACPackageUpdateReportTest {

    private fun entry(id: String, name: String, from: String?, to: String?) =
        SpotiFLACPackageUpdateSummary.Entry(
            sourceId = id,
            displayName = name,
            from = from,
            to = to,
        )

    @Test
    fun `a sweep that changed nothing says so`() {
        // The question a version list raises is "am I current?". Staying silent leaves the user
        // unable to tell that from "the check never ran".
        assertEquals(
            "All 8 packages are up to date",
            SpotiFLACPackageUpdateReport.summary(SpotiFLACPackageUpdateSummary(8, emptyList())),
        )
        assertEquals(
            "The installed package is up to date",
            SpotiFLACPackageUpdateReport.summary(SpotiFLACPackageUpdateSummary(1, emptyList())),
        )
        // Nothing was checked at all: that is genuinely nothing to say.
        assertNull(SpotiFLACPackageUpdateReport.summary(SpotiFLACPackageUpdateSummary(0, emptyList())))
    }

    @Test
    fun `a package that could not be reached is never reported as current`() {
        // Being offline must not come out as "up to date", which is the one answer a check must
        // never give when it did not actually look.
        assertEquals(
            "Could not check any of the 8 packages",
            SpotiFLACPackageUpdateReport.summary(
                SpotiFLACPackageUpdateSummary(
                    checked = 8,
                    updates = emptyList(),
                    failed = listOf(
                        "amazon",
                        "deezer",
                        "qobuz-web",
                        "tidal-web",
                        "soundcloud",
                        "apple-music",
                        "spotify-web",
                        "ytmusic-spotiflac",
                    ),
                ),
            ),
        )
        assertEquals(
            "Could not check amazon, deezer of 8 packages",
            SpotiFLACPackageUpdateReport.summary(
                SpotiFLACPackageUpdateSummary(
                    checked = 8,
                    updates = emptyList(),
                    failed = listOf("amazon", "deezer"),
                ),
            ),
        )
        assertEquals(
            "Could not check the package",
            SpotiFLACPackageUpdateReport.summary(
                SpotiFLACPackageUpdateSummary(
                    checked = 1,
                    updates = emptyList(),
                    failed = listOf("amazon"),
                ),
            ),
        )
        // A partial sweep reports both halves rather than only the good news.
        assertEquals(
            "Updated 1 of 8 packages: Amazon Music 2.3.8 → 2.3.10 · deezer could not be checked",
            SpotiFLACPackageUpdateReport.summary(
                SpotiFLACPackageUpdateSummary(
                    checked = 8,
                    updates = listOf(entry("amazon", "Amazon Music", "2.3.8", "2.3.10")),
                    failed = listOf("deezer"),
                ),
            ),
        )
    }

    @Test
    fun `one update names the source and both versions`() {
        val line = SpotiFLACPackageUpdateReport.summary(
            SpotiFLACPackageUpdateSummary(
                checked = 8,
                updates = listOf(entry("amazon", "Amazon Music", "2.3.8", "2.3.10")),
            ),
        )
        assertEquals("Updated 1 of 8 packages: Amazon Music 2.3.8 → 2.3.10", line)
    }

    @Test
    fun `several updates stay one line, and the rest are counted`() {
        val two = SpotiFLACPackageUpdateReport.summary(
            SpotiFLACPackageUpdateSummary(
                checked = 8,
                updates = listOf(
                    entry("amazon", "Amazon Music", "2.3.8", "2.3.10"),
                    entry("deezer", "Deezer", "1.3.4", "1.3.5"),
                ),
            ),
        )
        assertEquals(
            "Updated 2 of 8 packages: Amazon Music 2.3.8 → 2.3.10, Deezer 1.3.4 → 1.3.5",
            two,
        )

        val five = SpotiFLACPackageUpdateReport.summary(
            SpotiFLACPackageUpdateSummary(
                checked = 8,
                updates = (1..5).map { index ->
                    entry("source-$index", "Source $index", "1.0.$index", "1.0.${index + 1}")
                },
            ),
        )
        // Named up to a limit, then counted: a summary that lists everything is not a summary.
        assertTrue(five!!.startsWith("Updated 5 of 8 packages: Source 1 1.0.1 → 1.0.2, "))
        assertTrue(five.endsWith(" and 2 more"))
        assertEquals(3, Regex("Source \\d+ 1\\.0").findAll(five).count())
    }

    @Test
    fun `a first install and a nameless source are still readable`() {
        val line = SpotiFLACPackageUpdateReport.summary(
            SpotiFLACPackageUpdateSummary(
                checked = 1,
                updates = listOf(entry("qobuz-web", "Qobuz", null, "1.2.15")),
            ),
        )
        assertEquals("Updated 1 of 1 packages: Qobuz to 1.2.15", line)

        val unnamed = SpotiFLACPackageUpdateReport.summary(
            SpotiFLACPackageUpdateSummary(
                checked = 1,
                updates = listOf(entry("soundcloud", "  ", "1.0.7", "1.0.8")),
            ),
        )
        // Falls back to the id rather than printing an empty name.
        assertEquals("Updated 1 of 1 packages: soundcloud 1.0.7 → 1.0.8", unnamed)
    }
}
