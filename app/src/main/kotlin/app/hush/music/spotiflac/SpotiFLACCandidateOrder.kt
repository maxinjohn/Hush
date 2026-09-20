/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

/**
 * How the sweep orders the sources it will try.
 *
 * The rule is the user's, and it is deliberately a short one: **the order in Audio Sources
 * is the order the sweep tries, and a source that is switched off there is not tried at
 * all.** The list the user arranges is the only thing that decides inclusion and priority.
 *
 * The extension packages on disk are a separate fact that has to be reconciled with that
 * list, and the two disagree in both directions:
 *
 * - An enabled source whose package is not on the device cannot download. It is
 *   *demoted*, not removed: it is still tried once everything loadable has failed, which
 *   is the moment the user most wants every option exhausted. Dropping it would trade a
 *   certain waste for a possible regression, and only one of those is cheap.
 * - A package the user has *not* enabled must not be swept. Upstream installs extensions
 *   for itself, and an earlier version swept every installed package to make sure a
 *   newly-installed one was not missed - which meant switching a source off in Audio
 *   Sources did not actually stop it being tried.
 */
internal object SpotiFLACCandidateOrder {

    data class Ordered(
        /** Enabled sources whose extension package is on the device, in user order. */
        val loadable: List<String>,
        /** Enabled sources the registry offers whose package is not on the device, in user order. */
        val unavailable: List<String>,
        /**
         * Installed packages the user has not enabled, in installed order.
         *
         * Reported so a sweep can say what it deliberately left alone, never swept.
         */
        val notEnabled: List<String>,
    ) {
        /** The sweep order: what the user enabled, then anything enabled that cannot load. */
        val all: List<String> get() = loadable + unavailable
    }

    /**
     * The installed extension packages ordered by the priority the user arranged.
     *
     * This is the order to use in the window where the enabled list is not known yet -
     * immediately after a process start, before the registry sync has filled the live
     * source list in. The package directory is all that is left to fall back on there,
     * and enumerating it is *alphabetical*: measured on device, a user whose Audio
     * Sources order was `deezer,amazon,qobuz-web,tidal-web` had
     * `amazon,deezer,qobuz-web,tidal-web` handed to the runtime, and a sweep that starts
     * on a provider the user did not choose is also the sweep that reports that
     * provider's stall, its quality miss and its download.
     *
     * The sort is stable and every source the user has never seen keeps its relative
     * position at the back, so a freshly installed extension is still ordered last.
     */
    fun bySavedOrder(installed: List<String>, savedOrder: List<String>): List<String> {
        if (savedOrder.isEmpty() || installed.size < 2) return installed
        val rank = savedOrder.withIndex().associate { (index, id) -> id.lowercase() to index }
        return installed.sortedBy { rank[it.lowercase()] ?: Int.MAX_VALUE }
    }

    fun order(
        enabled: List<String>,
        installed: List<String>,
    ): Ordered {
        // An empty installed set means the packages have not been extracted yet (a
        // fresh install, or a runtime still starting). Filtering on it then would
        // hide every source, so the enabled list is taken at face value instead.
        val canLoad = installed.mapTo(HashSet(installed.size)) { it.lowercase() }
        val (loadable, unavailable) =
            enabled.partition { id -> canLoad.isEmpty() || id.lowercase() in canLoad }
        val notEnabled = installed.filterNot { id -> enabled.any { it.equals(id, ignoreCase = true) } }
        return Ordered(loadable = loadable, unavailable = unavailable, notEnabled = notEnabled)
    }
}
