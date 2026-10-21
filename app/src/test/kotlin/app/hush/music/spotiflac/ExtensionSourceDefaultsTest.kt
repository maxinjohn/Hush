/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what a source's switch reads when the app has never been asked about it.
 *
 * The failure this exists for is quiet: an install adds a repository, every source it publishes is
 * listed switched off, and nothing on the screen says whether that is the repository being refused
 * or a default nobody chose. The three inputs - what can play, what the user chose, and what this
 * install has already listed - decide it, and each of the three has a case here, including the one
 * that must *not* change: an upgrade may not switch a source back on behind the user's back.
 */
class ExtensionSourceDefaultsTest {

    /** A source Hush can play: a package, or a relay-backed provider. */
    private val playable =
        ExtensionSource(
            id = "tidal-web",
            relayUrl = "https://api.zarz.moe/v2",
            providerKey = "tidal",
        )

    /** A module index entry Hush runs itself: a script, and with it a source that can play. */
    private val module =
        ExtensionSource(
            id = "bitchord-streaming-modules:bitchord-tidal-flac",
            downloadUrl =
                "https://raw.githubusercontent.com/OreoMuncher45/bitchord-streaming-modules/main/modules/bitchord-tidal-flac.js",
            runtime = ModuleRuntime,
        )

    /** A module entry whose index named no script: nothing to run, so nothing to play. */
    private val scriptless =
        ExtensionSource(
            id = "bitchord-streaming-modules:broken-entry",
            runtime = ModuleRuntime,
        )

    @Test
    fun `a fresh install switches on every source it can play`() {
        // Nothing has ever been stored: not a stored choice and not an empty one. A default that only
        // enabled Hush's built-ins left every repository-published source switched off - and with
        // them out of the resolve order - on the one install that had chosen nothing at all.
        assertTrue(sourceEnabledByDefault(playable, storedEnabledIds = null, knownIds = null))
        assertTrue(sourceEnabledByDefault(module, storedEnabledIds = null, knownIds = null))
        assertFalse(sourceEnabledByDefault(scriptless, storedEnabledIds = null, knownIds = null))
    }

    @Test
    fun `switching everything off is a choice and is not read as a fresh install`() {
        // The empty set is the key being present with nothing in it: the user turned every switch
        // off, and Hush may not turn them back on by treating that as "nothing stored".
        assertFalse(sourceEnabledByDefault(playable, storedEnabledIds = emptySet(), knownIds = null))
        assertFalse(sourceEnabledByDefault(playable, storedEnabledIds = emptySet(), knownIds = setOf(playable.id)))
        // A source this install has never listed is still a new repository's arrival, and still
        // arrives on - see `a source nobody has seen yet follows the repository that published it`.
        assertTrue(sourceEnabledByDefault(playable, storedEnabledIds = emptySet(), knownIds = emptySet()))
    }

    @Test
    fun `a source this build cannot play is never switched on`() {
        // Whatever is stored, and whether or not it has been listed: a switch that answers yes for a
        // source the resolve order refuses is a switch that lies.
        assertFalse(sourceEnabledByDefault(scriptless, storedEnabledIds = setOf(scriptless.id), knownIds = emptySet()))
        assertFalse(sourceEnabledByDefault(scriptless, storedEnabledIds = setOf(scriptless.id), knownIds = setOf(scriptless.id)))
        assertFalse(sourceEnabledByDefault(scriptless, storedEnabledIds = emptySet(), knownIds = null))
        assertFalse(scriptless.isPlayable)
    }

    @Test
    fun `a module Hush hosts follows the same three cases as any other source`() {
        // The host is what changed this: a module used to be a row whose switch could not move, and
        // now its default is decided by the same rule as a package's - a stored choice wins, and a
        // source nobody has seen arrives on.
        assertTrue(module.needsModuleRuntime)
        assertTrue(module.isHostedModule)
        assertFalse(module.isNativePlayable)
        assertTrue(sourceEnabledByDefault(module, storedEnabledIds = setOf(module.id), knownIds = setOf(module.id)))
        assertFalse(sourceEnabledByDefault(module, storedEnabledIds = emptySet(), knownIds = setOf(module.id)))
        assertTrue(sourceEnabledByDefault(module, storedEnabledIds = emptySet(), knownIds = emptySet()))
    }

    @Test
    fun `a source the user switched on stays on`() {
        assertTrue(
            sourceEnabledByDefault(playable, storedEnabledIds = setOf(playable.id), knownIds = setOf(playable.id)),
        )
    }

    @Test
    fun `a source the user switched off stays off`() {
        // Known, and not in the stored on set: a choice was made about it, and no sync may undo it.
        assertFalse(
            sourceEnabledByDefault(playable, storedEnabledIds = emptySet(), knownIds = setOf(playable.id)),
        )
        assertFalse(
            sourceEnabledByDefault(
                playable,
                storedEnabledIds = setOf("deezer"),
                knownIds = setOf(playable.id, "deezer"),
            ),
        )
    }

    @Test
    fun `a source nobody has seen yet follows the repository that published it`() {
        // The add-a-repository case: the id is not in the stored set because no choice exists, and it
        // is not in the known set because no sync has listed it yet, so it arrives switched on and in
        // the resolve order.
        assertTrue(
            sourceEnabledByDefault(
                playable,
                storedEnabledIds = setOf("deezer"),
                knownIds = setOf("deezer"),
            ),
        )
    }

    @Test
    fun `an install that has never listed anything is not changed by this`() {
        // Null is the upgrade path, not an empty set: nothing is known, so only the stored choice
        // decides - and a source that was off before stays off. Treating null as "nothing is known"
        // would switch every source on for every install that updates.
        assertFalse(sourceEnabledByDefault(playable, storedEnabledIds = emptySet(), knownIds = null))
        assertTrue(sourceEnabledByDefault(playable, storedEnabledIds = setOf(playable.id), knownIds = null))
    }
}
