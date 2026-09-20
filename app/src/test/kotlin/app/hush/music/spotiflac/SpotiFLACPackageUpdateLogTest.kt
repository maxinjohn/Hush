package app.hush.music.spotiflac

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The line in Audio Sources is the only place a package update is reported to a user, and the sweeps
 * that feed it fire more than once per start: measured on device, the startup check that updated
 * amazon was followed two seconds later by two repeat sweeps that found nothing. Recording every
 * sweep therefore erased the update exactly as the user arrived to read it - these pin the rule that
 * keeps that from happening while still answering "up to date" when that really is the answer.
 */
class SpotiFLACPackageUpdateLogTest {

    private fun update(id: String, from: String, to: String) =
        SpotiFLACPackageUpdateSummary(
            checked = 8,
            updates = listOf(
                SpotiFLACPackageUpdateSummary.Entry(
                    sourceId = id,
                    displayName = id,
                    from = from,
                    to = to,
                ),
            ),
        )

    private val upToDate = SpotiFLACPackageUpdateSummary(checked = 8, updates = emptyList())

    @After
    fun tearDown() {
        SpotiFLACPackageUpdateLog.clear()
    }

    @Test
    fun `the first check publishes even when it found nothing`() {
        // A fresh start has nothing to show, so "up to date" is what the user needs to see - the
        // line must never be blank just because nothing needed doing.
        SpotiFLACPackageUpdateLog.record(upToDate)
        assertEquals(upToDate, SpotiFLACPackageUpdateLog.summary.value)
        assertEquals(
            "All 8 packages are up to date",
            SpotiFLACPackageUpdateReport.summary(SpotiFLACPackageUpdateLog.summary.value!!),
        )
    }

    @Test
    fun `a repeat sweep does not erase an update the user has not read yet`() {
        SpotiFLACPackageUpdateLog.record(update("amazon", "2.3.8", "2.3.10"))
        // Two incidental sweeps land seconds later and find nothing.
        SpotiFLACPackageUpdateLog.record(upToDate)
        SpotiFLACPackageUpdateLog.record(upToDate)
        assertEquals(update("amazon", "2.3.8", "2.3.10"), SpotiFLACPackageUpdateLog.summary.value)
    }

    @Test
    fun `a later update still replaces an earlier one`() {
        SpotiFLACPackageUpdateLog.record(update("amazon", "2.3.8", "2.3.10"))
        SpotiFLACPackageUpdateLog.record(update("deezer", "1.3.4", "1.3.5"))
        assertEquals(update("deezer", "1.3.4", "1.3.5"), SpotiFLACPackageUpdateLog.summary.value)
    }

    @Test
    fun `a check the user asked for always answers`() {
        SpotiFLACPackageUpdateLog.record(update("amazon", "2.3.8", "2.3.10"))
        // Pressing "Check for updates" and being told nothing at all would leave the button's own
        // result in doubt, which is the whole reason the button exists.
        SpotiFLACPackageUpdateLog.record(upToDate, userInitiated = true)
        assertEquals(upToDate, SpotiFLACPackageUpdateLog.summary.value)
    }

    @Test
    fun `a failed check is published as a check, not as an update`() {
        SpotiFLACPackageUpdateLog.record(update("amazon", "2.3.8", "2.3.10"))
        val failed = SpotiFLACPackageUpdateSummary(
            checked = 8,
            updates = emptyList(),
            failed = listOf("amazon"),
        )
        // Being offline is a result of the check the user just asked for, and reporting the older
        // update instead would claim something that is no longer known to be true.
        SpotiFLACPackageUpdateLog.record(failed, userInitiated = true)
        assertEquals(failed, SpotiFLACPackageUpdateLog.summary.value)
    }
}
