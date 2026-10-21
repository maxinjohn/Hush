/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These pin down two rules. The sweep order is the order the user arranged in Audio
 * Sources - nothing may be reordered around it - and a source the user switched off there
 * is not tried at all. Both were broken: a device with neither an `apple-music` nor a
 * `pandora` extension handed both to every sweep first, and an earlier version swept every
 * installed package, which meant switching a source off in Audio Sources did not stop it
 * being tried.
 */
class SpotiFLACCandidateOrderTest {

    private val enabled = listOf("tidal-web", "deezer", "qobuz-web", "amazon", "apple-music", "pandora")
    private val installed = listOf("amazon", "deezer", "qobuz-web", "tidal-web", "ytmusic-spotiflac")

    @Test
    fun `a source whose package is missing is tried last, not removed`() {
        val ordered = SpotiFLACCandidateOrder.order(enabled, installed)

        assertEquals(listOf("tidal-web", "deezer", "qobuz-web", "amazon"), ordered.loadable)
        assertEquals(listOf("apple-music", "pandora"), ordered.unavailable)
        // Still in the list: the sweep may reach them once nothing loadable worked.
        assertTrue(ordered.all.containsAll(listOf("apple-music", "pandora")))
        assertEquals(
            listOf("tidal-web", "deezer", "qobuz-web", "amazon", "apple-music", "pandora"),
            ordered.all,
        )
    }

    @Test
    fun `an installed source the user has not enabled is not swept`() {
        val ordered = SpotiFLACCandidateOrder.order(enabled, installed)

        assertEquals(listOf("ytmusic-spotiflac"), ordered.notEnabled)
        assertTrue(ordered.all.none { it == "ytmusic-spotiflac" })
    }

    @Test
    fun `user priority is preserved inside each group`() {
        val reversed = enabled.reversed()
        val ordered = SpotiFLACCandidateOrder.order(reversed, installed)

        assertEquals(listOf("amazon", "qobuz-web", "deezer", "tidal-web"), ordered.loadable)
        assertEquals(listOf("pandora", "apple-music"), ordered.unavailable)
    }

    @Test
    fun `nothing is extracted yet, so the enabled list is taken at face value`() {
        val ordered = SpotiFLACCandidateOrder.order(enabled, installed = emptyList())

        assertEquals(enabled, ordered.loadable)
        assertTrue(ordered.unavailable.isEmpty())
        assertTrue(ordered.notEnabled.isEmpty())
        assertEquals(enabled, ordered.all)
    }

    @Test
    fun `an empty enabled list yields no candidates rather than everything installed`() {
        val ordered = SpotiFLACCandidateOrder.order(enabled = emptyList(), installed = installed)

        assertTrue(ordered.all.isEmpty())
        assertEquals(installed, ordered.notEnabled)
    }

    @Test
    fun `a source id is matched regardless of case`() {
        val ordered = SpotiFLACCandidateOrder.order(enabled, installed = listOf("AMAZON", "Deezer"))

        assertEquals(listOf("deezer", "amazon"), ordered.loadable)
    }

    @Test
    fun `an enabled source is not added twice`() {
        val ordered = SpotiFLACCandidateOrder.order(listOf("amazon"), installed = listOf("amazon"))

        assertEquals(listOf("amazon"), ordered.all)
        assertTrue(ordered.notEnabled.isEmpty())
    }

    /**
     * The fallback order, for the window before the registry sync has filled the live
     * source list in. Enumerating the package directory there is alphabetical, which is
     * how a user whose order was `deezer,amazon,qobuz-web,tidal-web` ended up swept as
     * `amazon,deezer,qobuz-web,tidal-web` - measured in the app's own diag log.
     */
    @Test
    fun `installed packages are ordered by the user's priority, not by their names`() {
        val alphabetical = listOf("amazon", "deezer", "qobuz-web", "tidal-web")
        val userOrder = listOf("deezer", "amazon", "qobuz-web", "tidal-web")

        assertEquals(userOrder, SpotiFLACCandidateOrder.bySavedOrder(alphabetical, userOrder))
    }

    @Test
    fun `a source the user has not ranked is ordered after the ones they have`() {
        val installed = listOf("amazon", "deezer", "tidal-web", "ytmusic-spotiflac")
        val savedOrder = listOf("tidal-web", "deezer")

        assertEquals(
            listOf("tidal-web", "deezer", "amazon", "ytmusic-spotiflac"),
            SpotiFLACCandidateOrder.bySavedOrder(installed, savedOrder),
        )
    }

    @Test
    fun `an id is matched regardless of case when ordering`() {
        val installed = listOf("AMAZON", "Deezer")

        assertEquals(
            listOf("Deezer", "AMAZON"),
            SpotiFLACCandidateOrder.bySavedOrder(installed, listOf("deezer", "amazon")),
        )
    }

    @Test
    fun `with no recorded order the installed order is left alone`() {
        val installed = listOf("amazon", "deezer")

        assertEquals(installed, SpotiFLACCandidateOrder.bySavedOrder(installed, emptyList()))
    }

    // --- what one sweep actually walks, from everything that has a say in it -------------------

    private val downloaded = listOf("amazon", "deezer", "jiosaavn", "qobuz-web", "soundcloud", "tidal-web", "ytmusic-spotiflac")

    /** Metadata providers and modules: listed, switched on, and unable to download anything. */
    private fun cannotDownload(id: String) =
        id in
            setOf(
                "bugs-music",
                "melon-music",
                "genie-music",
                "bitchord:bitchord-tidal-flac",
                "bitchord:archive-flac",
            )

    private fun sweep(
        requested: List<String> = emptyList(),
        liveEnabled: List<String> = emptyList(),
        switches: SourceSwitches = SourceSwitches(emptyList(), known = true),
        installed: List<String> = downloaded,
    ) = SpotiFLACCandidateOrder.sweep(
        requested = requested,
        liveEnabled = liveEnabled,
        switches = switches,
        savedOrder = emptyList(),
        installed = installed,
        cannotDownload = ::cannotDownload,
    )

    /**
     * The bug this rule was extracted for, in the shape it was reported in.
     *
     * The reporting device had everything switched off except the BitChord modules and three
     * metadata providers, and the sweep walked all seven download packages - resolving the track
     * from `ytmusic-spotiflac`, the YouTube Music source the user had just switched off, while the
     * modules they *had* switched on were never asked.
     */
    @Test
    fun `an install switched on to nothing but metadata and modules sweeps nothing it switched off`() {
        val switches =
            SourceSwitches(
                enabledInOrder =
                    listOf(
                        "bugs-music",
                        "melon-music",
                        "genie-music",
                        "bitchord:bitchord-tidal-flac",
                        "bitchord:archive-flac",
                    ),
                known = true,
            )

        val result = sweep(liveEnabled = switches.enabledInOrder, switches = switches)

        assertTrue(result.candidates.isEmpty())
        assertEquals(downloaded, result.notEnabled)
        assertTrue(!result.fromInstalledFallback)
    }

    /**
     * And the same statement for the window a resolve actually races: a cold start, before the
     * registry sync has filled the live list in. The stored switches answer for it - which is the
     * other half of the fix, because the live list being empty used to be read as "nothing is known"
     * even when the user's own list was sitting on disk.
     */
    @Test
    fun `before the first sync the stored switches are the enabled list, not the package directory`() {
        val switches =
            SourceSwitches(enabledInOrder = listOf("tidal-web", "deezer"), known = true)

        val result = sweep(liveEnabled = emptyList(), switches = switches)

        assertEquals(listOf("tidal-web", "deezer"), result.candidates)
        // Both packages are on the device, so neither is demoted - the point of this test is that the
        // *list* came from the switches rather than from the directory, not that it loaded.
        assertTrue(result.unavailable.isEmpty())
        assertEquals(downloaded - setOf("tidal-web", "deezer"), result.notEnabled)
    }

    @Test
    fun `an install that has never recorded a choice still sweeps what is installed`() {
        val result = sweep(switches = SourceSwitches(emptyList(), known = false))

        assertEquals(downloaded, result.candidates)
        assertTrue(result.fromInstalledFallback)
    }

    /**
     * A fresh install has nothing stored *and* nothing on disk, which is not a reason to fall back
     * to a directory that is not there.
     */
    @Test
    fun `an install with no record and no packages sweeps nothing`() {
        val result = sweep(switches = SourceSwitches(emptyList(), known = false), installed = emptyList())

        assertTrue(result.candidates.isEmpty())
        assertTrue(!result.fromInstalledFallback)
    }

    /**
     * A caller that names its source outranks the switch list - the player asking for one source on
     * purpose - but it does not licence the package directory: a named source that cannot download
     * leaves the sweep empty rather than full.
     */
    @Test
    fun `a caller's own list is taken as it stands and never widened`() {
        val result =
            sweep(
                requested = listOf("bugs-music"),
                liveEnabled = listOf("bugs-music", "tidal-web"),
                switches = SourceSwitches(listOf("tidal-web"), known = true),
            )

        assertTrue(result.candidates.isEmpty())
        assertTrue(!result.fromInstalledFallback)
        assertEquals(listOf("bugs-music"), result.chosen)
    }

    @Test
    fun `the user's order is what the sweep walks`() {
        val switches =
            SourceSwitches(enabledInOrder = listOf("deezer", "amazon", "qobuz-web"), known = true)

        val result = sweep(liveEnabled = switches.enabledInOrder, switches = switches)

        assertEquals(listOf("deezer", "amazon", "qobuz-web"), result.candidates)
    }

    // --- the walk one resolve makes, across both kinds of source --------------------------------

    private val addonIds = listOf("gglos-ggottes-deno-net:gg.gglos.addon", "bitchord:bitchord-tidal-flac")

    /**
     * The order the user arranged is the order the walk makes, and a module keeps the place they
     * gave it. Measured on the reporting device, the list read `gglos` above `ytmusic-spotiflac` and
     * the track was still fetched from YouTube Music, because modules were only reachable after
     * every native source had failed.
     */
    @Test
    fun `a module keeps the place the user gave it, ahead of a native source`() {
        val chosen = listOf("gglos-ggottes-deno-net:gg.gglos.addon", "ytmusic-spotiflac", "tidal-web")

        assertEquals(
            chosen,
            SpotiFLACCandidateOrder.walk(
                chosen = chosen,
                native = listOf("ytmusic-spotiflac", "tidal-web"),
                addons = listOf("gglos-ggottes-deno-net:gg.gglos.addon"),
            ),
        )
    }

    @Test
    fun `a reorder of the same sources reorders the walk`() {
        val userOrder = listOf("deezer", "gglos-ggottes-deno-net:gg.gglos.addon", "tidal-web")
        val reordered = listOf("tidal-web", "gglos-ggottes-deno-net:gg.gglos.addon", "deezer")

        fun walk(order: List<String>) =
            SpotiFLACCandidateOrder.walk(
                chosen = order,
                native = order.filterNot { it in addonIds },
                addons = order.filter { it in addonIds },
            )

        assertEquals(userOrder, walk(userOrder))
        assertEquals(reordered, walk(reordered))
    }

    @Test
    fun `a source the walk cannot attempt is not in it`() {
        val walk =
            SpotiFLACCandidateOrder.walk(
                chosen = listOf("spoiti-lists", "deezer"),
                native = listOf("deezer"),
                addons = emptyList(),
            )

        assertEquals(listOf("deezer"), walk)
    }

    /**
     * The installed-package fallback: the list is the packages, so they keep their order, and the
     * modules this install holds follow them - nothing is lost, and nothing is reordered.
     */
    @Test
    fun `sources the chosen list does not name follow in their own order`() {
        val walk =
            SpotiFLACCandidateOrder.walk(
                chosen = emptyList(),
                native = listOf("amazon", "deezer"),
                addons = listOf("gglos-ggottes-deno-net:gg.gglos.addon"),
            )

        assertEquals(listOf("amazon", "deezer", "gglos-ggottes-deno-net:gg.gglos.addon"), walk)
    }

    /** An id is matched regardless of case, exactly as every other rule here matches one. */
    @Test
    fun `the walk matches ids regardless of case`() {
        val walk =
            SpotiFLACCandidateOrder.walk(
                chosen = listOf("Deezer", "TIDAL-WEB"),
                native = listOf("deezer", "tidal-web"),
                addons = emptyList(),
            )

        assertEquals(listOf("deezer", "tidal-web"), walk)
    }

    // --- what a parallel fetch is allowed to race ------------------------------------------------

    /**
     * Parallel fetch is a trade the user makes inside the third-party sweep, and it must not reach
     * past it. A race has no order, so it cannot honour a position: racing the YouTube engine against
     * the sources would answer "which engine is first" with "whichever replied sooner", which is the
     * one thing the priority list exists to decide. The sweep walks third-party sources only - the
     * other engine is consulted after it, in the resolve order - so a race confined to the walk is
     * confined to the third-party sources by construction, and this pins that it never leaves them.
     */
    @Test
    fun `a race covers the native sources at the head of the walk`() {
        val walk = listOf("deezer", "qobuz-web", "amazon", "gglos:addon", "tidal-web")

        assertEquals(
            listOf("deezer", "qobuz-web", "amazon"),
            SpotiFLACCandidateOrder.raceable(
                walk = walk,
                addonIds = setOf("gglos:addon"),
                nativeIds = listOf("deezer", "qobuz-web", "amazon", "tidal-web"),
            ),
        )
    }

    /** A module the user ranked first is asked first, whatever the parallel switch says. */
    @Test
    fun `a module at the top of the list is never raced`() {
        val walk = listOf("gglos:addon", "deezer", "qobuz-web")

        assertTrue(
            SpotiFLACCandidateOrder
                .raceable(walk = walk, addonIds = setOf("gglos:addon"), nativeIds = listOf("deezer", "qobuz-web"))
                .isEmpty(),
        )
    }

    /**
     * And an id the runtime cannot attempt is not raced either, even when it sits in the native run:
     * a module or addon id handed to the runtime is a source it has never heard of, and a raced
     * candidate that can only fail costs the winning attempt nothing but is still a wasted slot.
     */
    @Test
    fun `only the runtime's own candidates are raced`() {
        val walk = listOf("deezer", "bitchord:tidal-flac", "amazon")

        assertEquals(
            listOf("deezer", "amazon"),
            SpotiFLACCandidateOrder.raceable(
                walk = walk,
                addonIds = emptySet(),
                nativeIds = listOf("deezer", "amazon"),
            ),
        )
    }

    @Test
    fun `a walk of one source is nothing to race`() {
        assertEquals(
            listOf("deezer"),
            SpotiFLACCandidateOrder.raceable(
                walk = listOf("deezer"),
                addonIds = emptySet(),
                nativeIds = listOf("deezer"),
            ),
        )
        assertTrue(
            SpotiFLACCandidateOrder
                .raceable(walk = emptyList(), addonIds = emptySet(), nativeIds = listOf("deezer"))
                .isEmpty(),
        )
    }

    /** Case is ignored here as it is everywhere else a source id is matched. */
    @Test
    fun `the race matches ids and addon ids regardless of case`() {
        assertEquals(
            listOf("Deezer"),
            SpotiFLACCandidateOrder.raceable(
                walk = listOf("Deezer", "GGLOS:addon"),
                addonIds = setOf("gglos:addon"),
                nativeIds = listOf("deezer"),
            ),
        )
    }

    /** A switched-off source is left alone even when the live list is the caller's own list. */
    @Test
    fun `a source the user switched off is never in the sweep`() {
        val result =
            sweep(
                liveEnabled = listOf("tidal-web", "deezer"),
                switches = SourceSwitches(enabledInOrder = listOf("tidal-web", "deezer"), known = true),
            )

        assertTrue(result.candidates.none { it == "ytmusic-spotiflac" })
        assertTrue(result.notEnabled.contains("ytmusic-spotiflac"))
    }
}
