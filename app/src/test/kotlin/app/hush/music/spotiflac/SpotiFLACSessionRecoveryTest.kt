/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import app.hush.music.spotiflac.SpotiFLACSessionRenewer.RenewResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins which sources a Renew run hands to the verifier.
 *
 * This is the one thing the shared action decides, and every direction of it is wrong in a way the
 * user feels: raising a check for a source the gateway renewed spends a browser trip on a working
 * session, and *not* raising one for a source that needs it leaves the button that promised to fix
 * things having visibly done nothing - which is the complaint this action exists to answer.
 *
 * The renewal's own answer is the input rather than the bridge's auth state, and that is deliberate:
 * the runtime's preflight is local, so a session the gateway has already thrown away still reads as
 * usable for a moment. Only the gateway can tell those apart.
 */
class SpotiFLACSessionRecoveryTest {

    private fun renewed(id: String) =
        RenewResult(
            extensionId = id,
            renewed = true,
            detail = "renewed",
            contactedGateway = true,
            sessionId = "sess_$id",
        )

    private fun needingCheck(id: String) =
        RenewResult(
            extensionId = id,
            renewed = false,
            detail = "no session to renew - verify this source",
            needsVerification = true,
            sessionId = "sess_$id",
        )

    private fun versionBump(id: String) =
        RenewResult(
            extensionId = id,
            renewed = false,
            detail = "minted as ${id}@1.0 but this source now signs as ${id}@2.0 - verify again",
            needsVerification = true,
            sessionId = "sess_$id",
        )

    private fun refused(id: String) =
        RenewResult(
            extensionId = id,
            renewed = false,
            detail = "gateway rejected the session signature (HTTP 401)",
            refused = true,
            contactedGateway = true,
            sessionId = "sess_$id",
        )

    private fun notDue(id: String) =
        RenewResult(
            extensionId = id,
            renewed = false,
            detail = "not due (355m left)",
            sessionId = "sess_$id",
        )

    @Test
    fun `a source the gateway renewed is not checked`() {
        // The renewal already made it usable; a check would only spend a Cloudflare trip on it.
        assertEquals(
            emptyList<String>(),
            SpotiFLACSessionRecovery.sourcesNeedingCheck(
                results = listOf(renewed("qobuz-web")),
                switchedOn = setOf("qobuz-web"),
            ),
        )
    }

    @Test
    fun `a source with no session at all is checked`() {
        // The common case after a long gap: there is nothing to rotate, so a check is the only fix.
        assertEquals(
            listOf("deezer"),
            SpotiFLACSessionRecovery.sourcesNeedingCheck(
                results = listOf(needingCheck("deezer")),
                switchedOn = setOf("deezer"),
            ),
        )
    }

    @Test
    fun `a session bound to an older extension version is checked`() {
        // Not renewable by design - the gateway answers a version swap with a bare 403 - so reporting
        // it as "needs the user" is the only honest answer, and the reason it must not be dropped.
        assertEquals(
            listOf("amazon"),
            SpotiFLACSessionRecovery.sourcesNeedingCheck(
                results = listOf(versionBump("amazon")),
                switchedOn = setOf("amazon"),
            ),
        )
    }

    @Test
    fun `a refusal to rotate is not checked`() {
        // 401/403/429: the session is intact and the gateway may rotate it later. Raising a check
        // here would ask a user to solve a Cloudflare challenge to fix something that needs time.
        assertEquals(
            emptyList<String>(),
            SpotiFLACSessionRecovery.sourcesNeedingCheck(
                results = listOf(refused("tidal-web")),
                switchedOn = setOf("tidal-web"),
            ),
        )
    }

    @Test
    fun `a session that is not due yet is not checked`() {
        assertEquals(
            emptyList<String>(),
            SpotiFLACSessionRecovery.sourcesNeedingCheck(
                results = listOf(notDue("deezer")),
                switchedOn = setOf("deezer"),
            ),
        )
    }

    @Test
    fun `a source that is switched off is not raised`() {
        // A check is a browser trip and a gateway round trip; spending one on a source the user has
        // disabled is work nobody asked for.
        assertEquals(
            emptyList<String>(),
            SpotiFLACSessionRecovery.sourcesNeedingCheck(
                results = listOf(needingCheck("deezer"), needingCheck("amazon")),
                switchedOn = setOf("qobuz-web"),
            ),
        )
    }

    @Test
    fun `an unknown set of enabled sources filters nothing`() {
        // The repository is built at app start. A run that reaches here before it exists must still
        // raise the checks, so a null answer means "no opinion" rather than "nothing is on".
        assertEquals(
            listOf("deezer", "amazon"),
            SpotiFLACSessionRecovery.sourcesNeedingCheck(
                results = listOf(needingCheck("deezer"), needingCheck("amazon")),
                switchedOn = null,
            ),
        )
    }

    @Test
    fun `an addon-only selection raises no check for a native source`() {
        // The reported install: two Tido addons on, every native source off. The *native* enabled
        // list is empty here, which is the answer that used to mean "nothing is known" - and it let
        // amazon and tidal-web through, which is why a song that could not be served ended with two
        // Cloudflare challenges for sources the user had switched off.
        assertEquals(
            emptyList<String>(),
            SpotiFLACSessionRecovery.sourcesNeedingCheck(
                results = listOf(needingCheck("amazon"), needingCheck("tidal-web")),
                switchedOn =
                    setOf(
                        "tido-tido-tido-dpdns-org-4bd14d1e2fa848febf1e9c03cdc237c0:com.yourname.tidal.hifi",
                        "tido-tido-tido-dpdns-org-a48bf403dcfc40ae94f668c562d08201:com.yourname.tidal.hifi",
                    ),
            ),
        )
    }

    @Test
    fun `nothing switched on raises nothing`() {
        assertEquals(
            emptyList<String>(),
            SpotiFLACSessionRecovery.sourcesNeedingCheck(
                results = listOf(needingCheck("deezer")),
                switchedOn = emptySet(),
            ),
        )
    }

    @Test
    fun `one source is named once however many records it has`() {
        // A source can hold a record per extension version it has signed with, and each is its own
        // renewal result - so one source can answer "needs a check" twice.
        assertEquals(
            listOf("qobuz-web"),
            SpotiFLACSessionRecovery.sourcesNeedingCheck(
                results = listOf(needingCheck("qobuz-web"), versionBump("qobuz-web")),
                switchedOn = setOf("qobuz-web"),
            ),
        )
    }

    @Test
    fun `the outcome line names what was raised`() {
        assertEquals(
            "Qobuz and Deezer need a new verification \u00b7 raised a check for deezer",
            SpotiFLACSessionRecovery.Outcome(
                summary = "Qobuz and Deezer need a new verification",
                checked = listOf("deezer"),
            ).line,
        )
    }

    @Test
    fun `the outcome line is the renewal's own words when nothing was raised`() {
        // A run that renewed everything has no checks to name, and inventing a sentence for that
        // would replace the precise per-source answer with a vaguer one.
        assertEquals(
            "Renewed qobuz-web",
            SpotiFLACSessionRecovery.Outcome(
                summary = "Renewed qobuz-web",
                checked = emptyList(),
            ).line,
        )
    }
}
