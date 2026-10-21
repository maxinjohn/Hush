/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import java.util.Locale

/**
 * Which installed sources a verification is allowed to be raised for.
 *
 * The rule is one sentence - a source the user switched off is not asked to verify - and it used to
 * be written against `savedSourceOrder()`, the *native* candidate order. That list is empty for an
 * install whose Audio Sources list holds only a module or a server addon, because such a source is
 * not one the native runtime can walk. Empty was then read as "this install has never chosen",
 * which is the state that deliberately lets the filter through - so on the reporting device, with
 * two Tido addons on and every native source off, a playback that failed raised challenges for
 * `amazon` and `tidal-web`: browser trips, session records and "Verifying…" rows for two sources the
 * user had turned off.
 *
 * The two answers are therefore kept apart here rather than inferred from a list's emptiness:
 * [switchedOn] is null only when nothing has ever been recorded (a fresh install, whose defaults are
 * all on), and a non-null set - including an empty one - is a choice, whose members are the only
 * sources this may name. Collected into its own object because it is the sort of rule that gets
 * written twice: the same mistake was made in `renewalsFor` for sessions, and both are pinned by a
 * test that carries the reported install.
 */
internal object SpotiFLACVerificationScope {

    /**
     * [installedIds] narrowed to the sources that are switched on.
     *
     * [switchedOn] is the set from [ExtensionRepositoryManager.enabledExtensionIdsSnapshot], which
     * is already lowercased; the comparisons here are case-insensitive anyway because the ids on
     * disk come from a package directory and the ones in the registry from a manifest.
     */
    fun targetable(
        installedIds: List<String>,
        switchedOn: Set<String>?,
    ): List<String> {
        val distinct = installedIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (switchedOn == null) return distinct
        return distinct.filter { id -> id.lowercase(Locale.US) in switchedOn }
    }
}
