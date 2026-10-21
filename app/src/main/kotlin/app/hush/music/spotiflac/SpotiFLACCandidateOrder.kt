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

    /**
     * What one sweep will walk, and the two things it deliberately left behind.
     *
     * [notEnabled] and [unavailable] are carried out rather than recomputed by the caller, because
     * every one of these lists is a statement worth logging and the sweep's log is the only place a
     * user's "why did it use a source I switched off" can be answered from.
     */
    data class Sweep(
        /** The sources to try, in order. Empty means this install has nothing to sweep. */
        val candidates: List<String>,
        /** What the switch list said, before the sources that cannot download were dropped. */
        val chosen: List<String>,
        /** Enabled sources whose extension package is not on the device. */
        val unavailable: List<String>,
        /** Installed packages the user has not switched on. Never swept. */
        val notEnabled: List<String>,
        /**
         * True when the candidate list came from the package directory, which happens only on an
         * install that has never recorded a choice - see [sweep].
         */
        val fromInstalledFallback: Boolean,
    )

    /**
     * The sources one sweep walks, from everything that has a say in it.
     *
     * The rule is short and is entirely made of exclusions: **the sources the user switched on, in
     * the order they arranged, minus the ones that cannot download.** The package directory decides
     * only whether one of them can load, never who is in the list.
     *
     * There are exactly two lists of switched-on sources, because they are the same answer at two
     * moments: the live list the Audio Sources screen draws ([liveEnabled]), and the stored switches
     * ([switches]) that answer while the registry sync has not landed yet. [requested] is a caller
     * that already knows what it wants - the player asking for one source on purpose.
     *
     * The one place [installed] is ever a candidate list is an install that has *never* recorded a
     * choice ([SourceSwitches.known] false, no caller list, nothing live): a fresh install whose
     * switch list has never been written, where failing with "no sources are enabled" over a
     * directory full of working packages would be a regression from the behaviour that install had
     * before switches were stored. An empty list on an install that *has* chosen is that choice -
     * everything switched off, or only sources that cannot download - and it is not a reason to sweep
     * the packages the user switched off. Measured on the reporting device, where only the BitChord
     * modules and three metadata providers were on: the sweep walked every installed package and
     * served the track from `ytmusic-spotiflac`, the YouTube Music source the user had switched off.
     */
    fun sweep(
        requested: List<String>,
        liveEnabled: List<String>,
        switches: SourceSwitches,
        savedOrder: List<String>,
        installed: List<String>,
        cannotDownload: (String) -> Boolean,
    ): Sweep {
        val chosen = requested.ifEmpty { liveEnabled }.ifEmpty { switches.enabledInOrder }
        val enabled =
            chosen
                .filter { it.isNotBlank() }
                .distinct()
                .filterNot(cannotDownload)
        val switchKnown = requested.isNotEmpty() || switches.known
        if (enabled.isEmpty() && !switchKnown && installed.isNotEmpty()) {
            val ordered = order(enabled = bySavedOrder(installed, savedOrder), installed = installed)
            return Sweep(
                candidates = ordered.all,
                chosen = chosen,
                unavailable = ordered.unavailable,
                notEnabled = ordered.notEnabled,
                fromInstalledFallback = true,
            )
        }
        val ordered = order(enabled = enabled, installed = installed)
        return Sweep(
            candidates = ordered.all,
            chosen = chosen,
            unavailable = ordered.unavailable,
            notEnabled = ordered.notEnabled,
            fromInstalledFallback = false,
        )
    }

    /**
     * The one walk a resolve makes: the user's list, with every kind of source at its own place.
     *
     * A resolve used to be two walks with a hard boundary between them: the runtime's extensions
     * were asked first, and only once all of them had failed was a module or server addon asked at
     * all. That is not the list the user arranged. Their Audio Sources order mixes the two kinds -
     * measured on the reporting device it reads `tidal-web, deezer, …, gglos, bitchord-tidal-flac,
     * bitchord-archive-flac, ytmusic-spotiflac, …` - and a module they moved above a runtime source
     * was still reached only after every native source had been paid for, so a track the module
     * could serve came from a source the user had ranked below it. That is the same complaint as
     * "switching a source off did not stop it being tried", one step further out: the list has to
     * be the walk.
     *
     * So the walk is [chosen] - the switched-on sources in the order the user arranged, whatever
     * kind each one is - and [native] and [addons] only decide *what* an entry is, never where it
     * goes. Anything the caller supplies that the chosen list does not name (the installed-package
     * fallback on an install that has never recorded a choice) follows in the order it arrived in.
     */
    fun walk(
        chosen: List<String>,
        native: List<String>,
        addons: List<String>,
    ): List<String> {
        val nativeByKey = native.associateBy { it.trim().lowercase() }
        val addonByKey = addons.associateBy { it.trim().lowercase() }
        val walked = LinkedHashSet<String>()
        for (id in chosen) {
            val key = id.trim().lowercase()
            if (key.isEmpty()) continue
            // The spelling from the list that can actually attempt it, so a dispatch lookup on the
            // id does not have to guess the case the runtime or the module index uses.
            walked += nativeByKey[key] ?: addonByKey[key] ?: continue
        }
        walked += native
        walked += addons
        return walked.toList()
    }

    /**
     * The sources a parallel fetch may race: the run of runtime sources at the head of the walk.
     *
     * Racing has no order - the fastest source wins - so it may only cover the part of the list
     * where order is the runtime's own fallback rather than the user's arrangement. That is the run
     * of native sources before the first module or server addon: a module the user put at the top of
     * their list is still asked first, because a race cannot honour a position, and a race that
     * included it would quietly overrule the list the user arranged.
     *
     * Two more things this deliberately cannot return:
     *
     * - A source that is not in [nativeIds]. The walk is made of the switched-on sources by kind, and
     *   only the runtime's own ids are its to attempt - a module or addon id handed to the runtime is
     *   a source it has never heard of.
     * - Anything from the other engine. YouTube is not a candidate here at all: the sweep walks the
     *   third-party sources and the YouTube engine is consulted after the sweep, in the resolve order,
     *   one engine at a time. A parallel fetch inside the sweep therefore cannot race the two engines
     *   against each other, which is the whole point of keeping this rule inside the sweep.
     *
     * [addonIds] are the ids played outside the runtime (lowercased), so the scan can stop at the
     * first one without knowing what kind each entry is.
     */
    fun raceable(
        walk: List<String>,
        addonIds: Set<String>,
        nativeIds: List<String>,
    ): List<String> =
        walk
            .takeWhile { id -> id.trim().lowercase() !in addonIds }
            .filter { id -> nativeIds.any { it.equals(id, ignoreCase = true) } }

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
