/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

/**
 * How the sweep orders the sources it will try.
 *
 * The registry is a catalogue and the installed extension packages are what the
 * runtime can actually load, and the two disagree in both directions: the
 * registry lists sources whose package is not on this device (measured:
 * `apple-music` and `pandora` were handed to every sweep on a device that has
 * neither, and could only fail), and it can also omit an extension the runtime
 * installed for itself (`ytmusic-spotiflac` was in the installed set and in no
 * sweep that used the registry list).
 *
 * So the two are reconciled here, with two rules that matter:
 *
 * - **Nothing is dropped.** A source whose package is missing is *demoted*, not
 *   removed: it is still tried once everything loadable has failed, which is the
 *   moment the user most wants every option exhausted. Dropping it would trade a
 *   certain waste for a possible regression, and only one of those is cheap.
 * - **User order is preserved inside each group**, so the priority list the user
 *   arranged still decides which source is tried first.
 */
internal object SpotiFLACCandidateOrder {

    data class Ordered(
        /** Sources whose extension package is on the device, in user order. */
        val loadable: List<String>,
        /** Sources the runtime installed that the registry does not list, in installed order. */
        val extras: List<String>,
        /** Sources the registry offers whose package is not on the device, in user order. */
        val unavailable: List<String>,
    ) {
        /** The sweep order: everything loadable, then the rest. */
        val all: List<String> get() = loadable + extras + unavailable
    }

    fun order(
        registry: List<String>,
        installed: List<String>,
    ): Ordered {
        // An empty installed set means the packages have not been extracted yet (a
        // fresh install, or a runtime still starting). Filtering on it then would
        // hide every source, so the registry is taken at face value instead.
        val canLoad = installed.mapTo(HashSet(installed.size)) { it.lowercase() }
        val (loadable, unavailable) =
            registry.partition { id -> canLoad.isEmpty() || id.lowercase() in canLoad }
        val extras = installed.filterNot { id -> registry.any { it.equals(id, ignoreCase = true) } }
        return Ordered(loadable = loadable, extras = extras, unavailable = unavailable)
    }
}
