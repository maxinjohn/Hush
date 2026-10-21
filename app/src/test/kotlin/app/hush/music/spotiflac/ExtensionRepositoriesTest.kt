/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the three decisions a repository list makes.
 *
 * Adding a registry is the one action in Audio Sources that reaches a host the user named rather
 * than one Hush ships, so the rules that decide what is fetched, what a row is called, and what
 * such a registry is allowed to change about a source Hush already knows are the whole safety of
 * it - and all three are decisions, not requests, which is why they are pinned here instead of by
 * opening the screen.
 */
class ExtensionRepositoriesTest {

    private val bundled = ExtensionRepositories.Bundled

    private fun source(
        id: String,
        repositoryId: String = "",
        downloadUrl: String? = null,
        sha256: String? = null,
        relayUrl: String? = null,
        providerKey: String? = null,
        description: String = "",
        version: String = "",
    ) = ExtensionSource(
        id = id,
        description = description,
        version = version,
        downloadUrl = downloadUrl,
        sha256 = sha256,
        relayUrl = relayUrl,
        providerKey = providerKey,
        repositoryId = repositoryId,
    )

    @Test
    fun `only an https address with a host is a repository`() {
        assertEquals(
            "https://example.com/registry.json",
            ExtensionRepositories.canonical("  https://example.com/registry.json  "),
        )
        // A URL copied out of a browser can carry a fragment, and a fragment is never sent to a
        // server - dropping it is friendlier than refusing the paste.
        assertEquals(
            "https://example.com/registry.json",
            ExtensionRepositories.canonical("https://example.com/registry.json#readme"),
        )
        // A scheme a browser hides, and a scheme a host has since outgrown, both name the same
        // file: neither is a reason to make the user retype the address.
        assertEquals(
            "https://example.com/registry.json",
            ExtensionRepositories.canonical("http://example.com/registry.json"),
        )
        assertEquals(
            "https://example.com/registry.json",
            ExtensionRepositories.canonical("example.com/registry.json"),
        )
        // Wrappers a link arrives in: a share sheet's quotes, an angle bracket, a markdown link.
        assertEquals(
            "https://example.com/registry.json",
            ExtensionRepositories.canonical("<https://example.com/registry.json>"),
        )
        assertEquals(
            "https://example.com/registry.json",
            ExtensionRepositories.canonical("[the registry](https://example.com/registry.json)"),
        )
        assertNull(ExtensionRepositories.canonical("ftp://example.com/registry.json"))
        assertNull(ExtensionRepositories.canonical("https:///registry.json"))
        assertNull(ExtensionRepositories.canonical(""))
        // Credentials in the authority would hand the registry a login Hush never agreed to make.
        assertNull(ExtensionRepositories.canonical("https://user:pass@example.com/registry.json"))
    }

    @Test
    fun `a repository is called by the part that tells it apart`() {
        // Both repositories Hush ships are raw.githubusercontent.com, so the host alone would draw
        // two identical rows.
        assertEquals("spotiflacapp/spotiflac-extension", ExtensionRepositories.label(bundled[0]))
        assertEquals("zarzet/spotiflac-extension", ExtensionRepositories.label(bundled[1]))
        assertEquals("my-registry.example", ExtensionRepositories.label("https://my-registry.example/registry.json"))
        assertEquals("my-registry.example", ExtensionRepositories.label("https://www.my-registry.example/r.json"))
        // Not a URL at all: the string is its own best label.
        assertEquals("not a url", ExtensionRepositories.label("not a url"))
    }

    @Test
    fun `the effective list is the bundled ones first, then what was added, once each`() {
        val custom = listOf("https://mine.example/registry.json")

        assertEquals(
            bundled + custom,
            ExtensionRepositories.effective(bundled, custom),
        )
        // Re-adding a bundled address must not fetch it twice: the first registry to answer for an
        // id fills the gaps, and a second pass over the same document would double its provenance.
        assertEquals(
            bundled,
            ExtensionRepositories.effective(bundled, listOf(bundled[0])),
        )
        // An unusable address never reaches the fetcher, however it got into the list.
        assertEquals(
            bundled,
            ExtensionRepositories.effective(bundled, listOf("not a url", "", "  ")),
        )
        // An http address is fetched as https rather than dropped: the two name the same file, and
        // silently omitting it would look like a repository that never got added.
        assertEquals(
            bundled + "https://mine.example/r.json",
            ExtensionRepositories.effective(bundled, listOf("http://mine.example/r.json")),
        )
    }

    @Test
    fun `a repository the user added can introduce a source of its own, digest and all`() {
        val merged =
            ExtensionRepositories.merge(
                bundled = listOf(source("tidal-web", relayUrl = "https://api.zarz.moe/v2")),
                remote = listOf(
                    source(
                        id = "my-source",
                        repositoryId = "https://mine.example/registry.json",
                        downloadUrl = "https://mine.example/my-source.aar",
                        sha256 = "a".repeat(64),
                    ),
                ),
                customRepositoryUrls = setOf("https://mine.example/registry.json"),
            )

        val added = merged.first { it.id == "my-source" }
        assertEquals("https://mine.example/registry.json", added.repositoryId)
        assertEquals("https://mine.example/my-source.aar", added.downloadUrl)
        assertTrue(added.hasVerifiedPackageDigest)
    }

    /**
     * A registry that publishes a package without a checksum is installable, which is the state a
     * real one was in.
     *
     * `AlexRabbit/SpoitiLists` publishes a `download_url` and no `sha256`, and Hush used to read that
     * as "nothing can be installed here": the source arrived, listed, switched on, and failed every
     * attempt with `SpotiFLAC extension package is unavailable` - measured on the reporting device.
     * Upstream installs such an entry, so the refusal was Hush's rule and not the format's, and what
     * the row says instead is that nothing pinned the package.
     */
    @Test
    fun `a package its registry published without a checksum is still installable`() {
        val unpinned =
            source(
                id = "spoiti-lists",
                repositoryId = "https://mine.example/registry.json",
                downloadUrl = "https://mine.example/spoiti-lists.spotiflac-ext",
            )

        assertTrue(unpinned.isRuntimeCompatible)
        assertEquals(false, unpinned.hasVerifiedPackageDigest)
        // The exception is a digest that is present and cannot be used: that registry meant to pin the
        // package, so the entry is refused when the repository is read.
        val malformed = unpinned.copy(sha256 = "deadbeef")
        assertEquals(false, malformed.isSafeRegistryEntry)
    }

    @Test
    fun `a repository the user added cannot repoint a source Hush ships`() {
        val merged =
            ExtensionRepositories.merge(
                bundled = listOf(
                    source(
                        "tidal-web",
                        relayUrl = "https://api.zarz.moe/v2",
                        providerKey = "tidal",
                        description = "Lossless audio from Tidal",
                        version = "1.0",
                    ),
                ),
                remote = listOf(
                    source(
                        id = "tidal-web",
                        repositoryId = "https://mine.example/registry.json",
                        downloadUrl = "https://mine.example/tidal-web.aar",
                        sha256 = "b".repeat(64),
                        relayUrl = "https://mine.example/api",
                        providerKey = "not-tidal",
                        description = "Better description",
                        version = "9.9",
                    ),
                ),
                customRepositoryUrls = setOf("https://mine.example/registry.json"),
            )

        val tidal = merged.first { it.id == "tidal-web" }
        // The package and the endpoints stay Hush's own. A third-party registry must not be able to
        // change which binary is downloaded and run for a source the user never chose to change -
        // which is exactly what an id collision would otherwise achieve.
        assertNull(tidal.downloadUrl)
        assertNull(tidal.sha256)
        assertEquals("https://api.zarz.moe/v2", tidal.relayUrl)
        assertEquals("tidal", tidal.providerKey)
        assertEquals("", tidal.repositoryId)
        // Metadata is not a risk, and refusing it would make an id collision a worse row than the
        // one it describes.
        assertEquals("Better description", tidal.description)
        assertEquals("9.9", tidal.version)
    }

    @Test
    fun `a repository the user added cannot take authorship of a source a bundled registry declared`() {
        val merged =
            ExtensionRepositories.merge(
                bundled = listOf(source("amazon", relayUrl = "https://api.zarz.moe/v2", providerKey = "amazon")),
                remote = listOf(
                    // The registry Hush ships with, declaring the package for the source it owns.
                    source(
                        id = "amazon",
                        repositoryId = bundled[0],
                        downloadUrl = "https://github.com/spotiflacapp/spotiflac-extension/releases/download/v1/amzn.sflx",
                        sha256 = "d".repeat(64),
                    ),
                    // A repository the user added, using the same id.
                    source(
                        id = "amazon",
                        repositoryId = "https://mine.example/registry.json",
                        description = "Mine",
                    ),
                ),
                customRepositoryUrls = setOf("https://mine.example/registry.json"),
            )

        val amazon = merged.first { it.id == "amazon" }
        // Attribution is what the row prints ("from zarzet") and what the repository list counts
        // against - so it follows the package that is actually installed, not the last registry to
        // mention the id.
        assertEquals(bundled[0], amazon.repositoryId)
        // And the description is still taken: only provenance and the package are held back.
        assertEquals("Mine", amazon.description)
    }

    @Test
    fun `a bundled registry still fills in the package for a bundled source`() {
        val merged =
            ExtensionRepositories.merge(
                bundled = listOf(source("deezer", relayUrl = "https://api.zarz.moe/v2", providerKey = "deezer")),
                remote = listOf(
                    source(
                        id = "deezer",
                        repositoryId = bundled[0],
                        downloadUrl = "https://github.com/spotiflacapp/spotiflac-extension/releases/download/v1/deezer.aar",
                        sha256 = "c".repeat(64),
                        version = "2.3.10",
                    ),
                ),
                customRepositoryUrls = emptySet(),
            )

        val deezer = merged.first { it.id == "deezer" }
        assertEquals("2.3.10", deezer.version)
        assertEquals(bundled[0], deezer.repositoryId)
        assertTrue(deezer.isRuntimeCompatible)
    }

    @Test
    fun `a repository is identified by what it answers, not by its address`() {
        // Both formats are a JSON file at an address, and both of Hush's own registries are
        // `registry.json` on the same host a module index is a `manifest.json` on - so the file name
        // carries nothing. The shape is the only thing that can decide.
        val registry = """{"version":1,"extensions":[{"id":"tidal-web"}]}"""
        val moduleIndex = """{"id":"m","resources":["search"],"category:music":[]}"""

        assertEquals(RepositoryKind.SPOTIFLAC_REGISTRY, ExtensionRepositories.detect(registry))
        assertEquals(RepositoryKind.MODULE_INDEX, ExtensionRepositories.detect(moduleIndex))
        // A login page, a 404 body, an empty object: none of those is a repository, and every one of
        // them would otherwise be read as a registry that declares nothing - which is the answer the
        // add flow turns into "declares no sources" instead of "is not a repository".
        assertNull(ExtensionRepositories.detect("<html><body>Sign in</body></html>"))
        assertNull(ExtensionRepositories.detect("{}"))
        assertNull(ExtensionRepositories.detect(""))
        assertNull(ExtensionRepositories.read("https://mine.example/manifest.json", "<html>"))
    }

    @Test
    fun `a module index is read as sources, with the runtime that would have to play them`() {
        val url = ExtensionRepositories.DefaultRemovable.first()
        val body =
            """
            {
              "id": "bitchord-streaming-modules",
              "name": "BitChord Streaming Modules",
              "resources": ["search", "stream"],
              "types": ["music"],
              "category:music": [
                {
                  "id": "bitchord-tidal-flac",
                  "name": "Tidal FLAC (tracks.monochrome.st)",
                  "author": "community",
                  "version": "0.4.0",
                  "type": "MODULE",
                  "description": "Credentialless Tidal FLAC search and playback",
                  "tags": ["TIDAL", "LOSSLESS", "FLAC", "NO LOGIN"],
                  "download": "modules/bitchord-tidal-flac.js",
                  "trusted": false,
                  "featured": true
                },
                {
                  "id": "archive-flac",
                  "name": "Internet Archive FLAC (archive.org)",
                  "author": "community",
                  "version": "1.0.0",
                  "type": "MODULE",
                  "description": "Credentialless live/bootleg FLAC via archive.org",
                  "tags": ["ARCHIVE", "LOSSLESS"],
                  "download": "modules/archive-flac.js"
                }
              ]
            }
            """

        val document = ExtensionRepositories.read(url, body)!!

        assertEquals(RepositoryKind.MODULE_INDEX, document.kind)
        assertEquals("BitChord Streaming Modules", document.name)
        assertEquals(2, document.sources.size)
        val module = document.sources.first { it.name.startsWith("Tidal") }
        // Namespaced by the repository, because module ids are chosen by whoever wrote the module:
        // two indexes that both ship `archive-flac` must be two rows, or disabling one disables the
        // other in silence.
        assertEquals("bitchord-streaming-modules:bitchord-tidal-flac", module.id)
        assertEquals(url, module.repositoryId)
        assertEquals("0.4.0", module.version)
        assertEquals("community", module.author)
        assertEquals(listOf("TIDAL", "LOSSLESS", "FLAC", "NO LOGIN"), module.tags)
        // A module is not a package: no digest, so the row cannot show the verified-package mark for
        // a file nothing has checked, and no install path.
        assertNull(module.sha256)
        // Its entry point *is* carried, resolved against the index that published it: an index names
        // its scripts relative to itself (`modules/x.js`) and an address relative to nothing is the
        // one thing no runtime can fetch - nor a row name.
        assertEquals(
            "https://raw.githubusercontent.com/OreoMuncher45/bitchord-streaming-modules/main/modules/bitchord-tidal-flac.js",
            module.downloadUrl,
        )
        assertEquals(false, module.isRuntimeCompatible)
        // What it can do arrives as the index's own `resources`, which is a provider of audio in the
        // only vocabulary the rest of the app has.
        assertEquals("Streams audio", module.primaryRole)
        // And every module it publishes survives the entry check. This is the assertion that was
        // missing: the shipped BitChord index read correctly and then had every one of its modules
        // refused as unsafe, because the id shape a source was held to allowed no namespace colon -
        // so the repository answered, was read, and reported "every entry it declares was refused as
        // unsafe" on the screen.
        assertTrue(document.sources.all { it.isSafeRegistryEntry })
        assertEquals(listOf(true, true), document.sources.map { it.isSafeRegistryEntry })
        // And what would play it is named on the source. A module with an entry point is playable -
        // Hush runs it in its own sandbox now - but it is *not* a candidate the native runtime can
        // walk, and that distinction is what the resolve order and the verify list turn on: a module
        // id handed to the runtime is a source it has never heard of.
        assertTrue(module.needsModuleRuntime)
        assertTrue(module.isHostedModule)
        assertTrue(module.isPlayable)
        assertEquals(false, module.isNativePlayable)
        assertEquals(false, module.requiresSpotiFLACSession)
    }

    @Test
    fun `a registry entry and a module are distinguished by what plays them`() {
        // The three cases the whole screen turns on: a package Hush installs, a relay source that
        // needs its own signed session, and a module or a credential-less helper that needs neither.
        val packageOnly =
            ExtensionSource(id = "p", downloadUrl = "https://mine.example/p.sflx", sha256 = "e".repeat(64))
        val relayed =
            ExtensionSource(
                id = "tidal-web",
                relayUrl = "https://api.zarz.moe/v2",
                providerKey = "tidal",
                downloadUrl = "https://mine.example/t.sflx",
                sha256 = "f".repeat(64),
            )
        val bare = ExtensionSource(id = "apple-music", relayUrl = "https://api.zarz.moe/v2")

        assertTrue(packageOnly.isPlayable)
        assertEquals(false, packageOnly.requiresSpotiFLACSession)
        assertTrue(relayed.isPlayable)
        assertTrue(relayed.requiresSpotiFLACSession)
        // A relay with no provider key is Hush's own proxy entry: it plays, but no source session is
        // issued for it, so a check never applies.
        assertTrue(bare.isPlayable)
        assertEquals(false, bare.requiresSpotiFLACSession)
        // Nothing behind it at all - shown, not hidden, and not playable.
        val empty = ExtensionSource(id = "nothing")
        assertEquals(false, empty.isPlayable)
        assertEquals(false, empty.requiresSpotiFLACSession)

        // A module is the fourth case, and the one whose answer changed when Hush grew a host for
        // them: with its script it plays (here, not in the runtime), without one it is what it
        // always was - a row with nothing behind it.
        val hosted =
            ExtensionSource(
                id = "bitchord-streaming-modules:archive-flac",
                runtime = ModuleRuntime,
                downloadUrl =
                    "https://raw.githubusercontent.com/OreoMuncher45/bitchord-streaming-modules/main/modules/archive-flac.js",
            )
        assertTrue(hosted.isHostedModule)
        assertTrue(hosted.isPlayable)
        assertEquals(false, hosted.isNativePlayable)
        assertEquals(false, hosted.requiresSpotiFLACSession)
        val scriptless = ExtensionSource(id = "index:dead", runtime = ModuleRuntime)
        assertEquals(false, scriptless.isHostedModule)
        assertEquals(false, scriptless.isPlayable)
    }

    @Test
    fun `a module index Hush ships is a row the user can remove`() {
        // Shipped, so the user can see what the other format looks like without hunting for a URL;
        // removable, because its sources are not ones Hush ships and cannot vouch for.
        val shipped = ExtensionRepositories.DefaultRemovable
        assertTrue(shipped.isNotEmpty())
        assertEquals(shipped.size, ExtensionRepositories.Defaults.size - ExtensionRepositories.Bundled.size)
        shipped.forEach { url ->
            assertEquals(url, ExtensionRepositories.canonical(url))
            assertEquals(false, ExtensionRepositories.isBundled(url))
            assertTrue(ExtensionRepositories.isDefaultRemovable(url))
        }
        // And the bundled two are still Hush's own: switchable, not removable.
        ExtensionRepositories.Bundled.forEach { url ->
            assertEquals(false, ExtensionRepositories.isDefaultRemovable(url))
        }
    }

    @Test
    fun `the repositories a working install needs are shipped, and only while they answer`() {
        // An address a user has to paste before the list does anything is one most installs never
        // have, so the repositories Hush reads beyond its own registries are part of the build. They
        // stay rows like any other - switchable, renamable, removable - because what they publish is
        // not Hush's to vouch for; shipping one only means the first launch does not start empty.
        val shipped = ExtensionRepositories.DefaultRemovable.map { ExtensionRepositories.canonical(it) }
        assertTrue(shipped.contains("https://raw.githubusercontent.com/OreoMuncher45/bitchord-streaming-modules/main/manifest.json"))
        assertTrue(shipped.contains("https://raw.githubusercontent.com/AlexRabbit/SpoitiLists/main/registry.json"))
        assertTrue(shipped.contains(ExtensionRepositories.TidoUrl))
        assertTrue(shipped.contains(ExtensionRepositories.TidoLosslessUrl))
        // Two server addons were shipped until 2026-10-20 and are not any more: the Qobuz addon
        // answers 402 and gglos answers 404. A shipped row that cannot answer holds a place in the
        // list for a source that will never play, so the rule is that an address ships only while
        // it answers - not that a particular address always does.
        val addresses = shipped.filterNotNull()
        assertFalse(addresses.any { it.contains("qobuz-addon.vercel.app") })
        assertFalse(addresses.any { it.contains("gglos.ggottes.deno.net") })
    }

    /**
     * A retired addon is dropped from an install that still stores one, and nothing else is.
     *
     * Removing an address from the shipped list is not enough on its own: a build that shipped it -
     * or a user who added it while it worked - has it written into the stored list, and a stored row
     * outlives the build that shipped it. Measured on the reporting device after the removal: the
     * two addresses were still rows, reported `origin=added kind=unknown declared=unread`.
     */
    @Test
    fun `a retired addon is dropped, and a different addon is not`() {
        assertTrue(ExtensionRepositories.isRetired("https://qobuz-addon.vercel.app/manifest.json"))
        assertTrue(ExtensionRepositories.isRetired("https://GGLOs.ggottes.deno.net/manifest.json"))
        // Not shipped any more, and not addable through the default list either.
        assertFalse(
            ExtensionRepositories.isDefaultRemovable("https://qobuz-addon.vercel.app/manifest.json"),
        )
        // The rule is about those two addresses, not about the format: the addons Hush still ships,
        // and any addon a user adds, are untouched.
        assertFalse(ExtensionRepositories.isRetired(ExtensionRepositories.TidoUrl))
        assertFalse(ExtensionRepositories.isRetired("https://addons.example/manifest.json"))
        assertFalse(ExtensionRepositories.isRetired("https://addons.example/a/5145a183579346ca9102d80f40e38481/manifest.json"))
    }

    /**
     * Two profiles of one addon, and the address is the only thing that tells them apart.
     *
     * `tido.tido.tido.dpdns.org` publishes the same addon at several directories: same `id`, same
     * name, same version, differing in the quality its own settings default to. Keyed by host alone
     * both manifests produced one source id, so adding the second profile silently replaced the
     * first and the list held one Tido where the user had added two. The directory is part of the
     * id now, and the name says which profile it is in the addon's own words.
     */
    @Test
    fun `two profiles of one addon are two sources, named for the tier they default to`() {
        val top =
            ExtensionRepositories.read(
                ExtensionRepositories.TidoUrl,
                tidoManifest(defaultQuality = "MAX"),
            )!!.sources.single()
        val lossless =
            ExtensionRepositories.read(
                ExtensionRepositories.TidoLosslessUrl,
                tidoManifest(defaultQuality = "LOSSLESS"),
            )!!.sources.single()

        // Two rows, and neither is the other's id: this is what the merge keeps apart.
        assertNotEquals(top.id, lossless.id)
        assertEquals("com.yourname.tidal.hifi", top.id.substringAfter(':'))
        assertEquals("com.yourname.tidal.hifi", lossless.id.substringAfter(':'))
        // The provider name a listener knows is on both, and the profile is the qualifier.
        assertEquals("Tido", top.name)
        assertEquals("Tido · Lossless", lossless.name)
        // Each is served from its own directory, which is the address it was published at.
        assertEquals("https://tido.tido.tido.dpdns.org/4bd14d1e2fa848febf1e9c03cdc237c0", top.addonBaseUrl)
        assertEquals(
            "https://tido.tido.tido.dpdns.org/a48bf403dcfc40ae94f668c562d08201",
            lossless.addonBaseUrl,
        )
        // And the order Hush ships names the ids these two manifests actually publish.
        assertTrue(ExtensionRepositories.DefaultSourceOrder.contains(top.id))
        assertTrue(ExtensionRepositories.DefaultSourceOrder.contains(lossless.id))
        assertEquals(true, top.isServerAddon)
        assertEquals(true, lossless.isServerAddon)
    }

    /**
     * An addon at its host root is the addon, not a profile of one - so its name is its own.
     *
     * `gglos` publishes a real choice of tiers and defaults to lossless, exactly as the Tido
     * lossless profile does. It is still called `gglos`: there is one of it, and appending the tier
     * to every addon that defaults below its top would rename rows that were never ambiguous. The
     * qualifier belongs to a *profile* - a manifest in a directory, one of several addresses the
     * same addon is published at.
     */
    @Test
    fun `an addon at its host root keeps its name however it defaults`() {
        val url = "https://gglos.ggottes.deno.net/manifest.json"
        val body =
            """
            {
              "id": "gg.gglos.addon",
              "name": "gglos",
              "version": "1.0.0",
              "resources": ["search", "stream"],
              "settings": [
                {
                  "key": "quality",
                  "type": "select",
                  "default": "lossless",
                  "options": [
                    { "value": "hi_res_lossless", "label": "Hi-Res Lossless (24-bit)" },
                    { "value": "lossless", "label": "Lossless (16-bit)" },
                    { "value": "high", "label": "High" },
                    { "value": "low", "label": "Low" }
                  ]
                }
              ]
            }
            """
        val source = ExtensionRepositories.read(url, body)!!.sources.single()

        assertEquals("gglos", source.name)
        // Root manifest: the id is exactly what it was before the directory joined the namespace.
        assertEquals("gglos-ggottes-deno-net:gg.gglos.addon", source.id)
    }

    /** The Tido manifest, cut to the fields a source is read from. */
    private fun tidoManifest(defaultQuality: String): String =
        """
        {
          "id": "com.yourname.tidal.hifi",
          "name": "Tido",
          "version": "1.33.5",
          "resources": ["search", "stream", "catalog", "settings", "isrc", "resolve"],
          "settings": [
            {
              "key": "quality",
              "type": "select",
              "label": "Audio quality",
              "default": "$defaultQuality",
              "options": [
                { "value": "HIRES", "label": "Max (Hi-Res 24-bit up to 192 kHz, no Atmos)" },
                { "value": "MAX", "label": "Max + Dolby Atmos (Hi-Res 24-bit up to 192 kHz)" },
                { "value": "LOSSLESS", "label": "Lossless 16-bit / 44.1 kHz FLAC" }
              ]
            }
          ]
        }
        """

    /**
     * A server addon is still added from its own address after the two shipped ones were retired.
     *
     * This is the half that matters for the next one: the address an addon publishes is a
     * *directory* (`…/manifest.json` is one spelling of it), a manifest that declares `resources`
     * is read as a playable source, and none of that was tied to the two addresses that stopped
     * answering. So the removal is two strings, not a format Hush lost.
     */
    @Test
    fun `a server addon is still added from its own address`() {
        val token = "5145a183579346ca9102d80f40e38481"
        // A bare host root resolves to the file addons are published as, and keeps the address as
        // typed last, so a host that answers at its root still works.
        assertEquals(
            listOf(
                "https://addons.example/manifest.json",
                "https://addons.example/registry.json",
                "https://addons.example",
            ),
            ExtensionRepositories.addCandidates("https://addons.example"),
        )
        // A per-account addon is the same shape one level down - the directory is the profile, and
        // the manifest is what is inside it.
        assertEquals(
            "https://addons.example/a/$token/manifest.json",
            ExtensionRepositories.canonical("https://addons.example/a/$token"),
        )
        // A query string is part of the directory's answer, so it survives the expansion.
        assertEquals(
            "https://addons.example/a/$token/manifest.json?profile=lossless",
            ExtensionRepositories.canonical("https://addons.example/a/$token?profile=lossless"),
        )
        // An address that already names a file is left as it is: expanding it would invent a path
        // the user did not type.
        assertEquals(
            listOf("https://addons.example/manifest.json"),
            ExtensionRepositories.addCandidates("https://addons.example/manifest.json"),
        )
        // And what comes back is one playable source, served by the addon host.
        val document =
            ExtensionRepositories.read(
                "https://addons.example/manifest.json",
                """{"id":"com.example.addon","name":"Example","version":"1.0.0",
                   "resources":["search","stream"]}""",
            )!!
        assertEquals(RepositoryKind.SERVER_ADDON, document.kind)
        val addon = document.sources.single()
        assertTrue(addon.isServerAddon)
        assertTrue(addon.isPlayable)
        assertEquals("https://addons.example", addon.addonBaseUrl)
    }

    /**
     * A server addon's source id is `<namespace>:<manifest id>`, and the namespace is derived from
     * the address it is fetched from - so the ids the default order names are only correct while
     * the two agree. A host change would otherwise rename the source silently and leave the default
     * order pointing at an id nothing publishes.
     */
    @Test
    fun `the default order names the Tido sources the shipped addresses publish`() {
        listOf(ExtensionRepositories.TidoUrl, ExtensionRepositories.TidoLosslessUrl).forEach { url ->
            // The directory a profile lives in is part of its namespace, which is what keeps two
            // profiles of one addon two sources.
            val namespace = ExtensionRepositories.addonNamespaceFor(url)
            assertTrue(
                "${ExtensionRepositories.DefaultSourceOrder} should name $namespace",
                ExtensionRepositories.DefaultSourceOrder.contains("$namespace:com.yourname.tidal.hifi"),
            )
        }
    }

    /**
     * The position a source Hush ships gets on an install whose order is already written down - the
     * state every install reaches the moment its owner moves one row. The Tido profile that defaults
     * to Atmos has to land above the lossless one rather than below everything, and the user's own
     * arrangement must come out of this untouched, or the feature would reorder the list it was
     * added to.
     */
    @Test
    fun `a shipped source the user has not placed goes in front of the one it precedes`() {
        val top = "tido-tido-tido-dpdns-org-4bd14d1e2fa848febf1e9c03cdc237c0:com.yourname.tidal.hifi"
        val lossless = "tido-tido-tido-dpdns-org-a48bf403dcfc40ae94f668c562d08201:com.yourname.tidal.hifi"
        val stored =
            listOf(
                "tidal-web",
                "deezer",
                "jiosaavn",
                lossless,
                "bitchord-streaming-modules:bitchord-tidal-flac",
                "pandora",
            )
        val available = stored + top
        assertEquals(
            listOf(
                "tidal-web",
                "deezer",
                "jiosaavn",
                top,
                lossless,
                "bitchord-streaming-modules:bitchord-tidal-flac",
                "pandora",
            ),
            ExtensionRepositories.orderWithDefaults(storedOrder = stored, available = available),
        )
    }

    /**
     * A list nobody has arranged is not rearranged by this: an unarranged source is named by the
     * default order but has none of the user's rows to sit in front of, so it keeps the place the
     * sync published it in.
     */
    @Test
    fun `an unarranged list keeps the order the sync published`() {
        val published =
            listOf(
                "tidal-web",
                "deezer",
                "jiosaavn",
                "tido-tido-tido-dpdns-org-a48bf403dcfc40ae94f668c562d08201:com.yourname.tidal.hifi",
                "tido-tido-tido-dpdns-org-4bd14d1e2fa848febf1e9c03cdc237c0:com.yourname.tidal.hifi",
            )
        assertEquals(
            published,
            ExtensionRepositories.orderWithDefaults(
                storedOrder = listOf("tidal-web", "deezer"),
                available = published,
            ),
        )
    }

    /** A source that is not listed at all - a repository switched off - is dropped, not kept as a hole. */
    @Test
    fun `an order for sources that are gone keeps only what is there`() {
        assertEquals(
            listOf("deezer", "tido-tido-tido-dpdns-org-4bd14d1e2fa848febf1e9c03cdc237c0:com.yourname.tidal.hifi"),
            ExtensionRepositories.orderWithDefaults(
                storedOrder =
                    listOf(
                        "tidal-web",
                        "deezer",
                        "bitchord-streaming-modules:gone",
                        "tido-tido-tido-dpdns-org-4bd14d1e2fa848febf1e9c03cdc237c0:com.yourname.tidal.hifi",
                    ),
                available =
                    listOf(
                        "deezer",
                        "tido-tido-tido-dpdns-org-4bd14d1e2fa848febf1e9c03cdc237c0:com.yourname.tidal.hifi",
                    ),
            ),
        )
    }

    @Test
    fun `the bundled list is the source of truth for what ships`() {
        // A row is only drawn as built-in when the address is one of these, and the remove action
        // is only offered for the others - so a bundled URL that ever stopped matching would be
        // offered for removal and could not be put back.
        assertEquals(2, bundled.size)
        bundled.forEach { url ->
            assertTrue(ExtensionRepositories.isBundled(url))
            assertEquals(url, ExtensionRepositories.canonical(url))
        }
        assertEquals(false, ExtensionRepositories.isBundled("https://mine.example/registry.json"))
    }

    /**
     * The link a browser offers for a file is its *page*, and that page answers with HTML.
     *
     * Measured on the device: pasting `github.com/AlexRabbit/SpoitiLists/blob/main/registry.json`
     * fetched the page, read it as not-a-repository and rejected the add - so an address the user
     * was looking at, on the host the README links to, could not be added at all. The same file's
     * raw address was already in the list. These are the spellings that have to name one file.
     */
    @Test
    fun `a forge file page is read as the file it names`() {
        assertEquals(
            "https://raw.githubusercontent.com/AlexRabbit/SpoitiLists/main/registry.json",
            ExtensionRepositories.canonical("https://github.com/AlexRabbit/SpoitiLists/blob/main/registry.json"),
        )
        // GitHub's own Raw button writes the same page with `raw` in place of `blob`.
        assertEquals(
            "https://raw.githubusercontent.com/AlexRabbit/SpoitiLists/main/registry.json",
            ExtensionRepositories.canonical("https://github.com/AlexRabbit/SpoitiLists/raw/main/registry.json"),
        )
        // Codeberg/Gitea and GitLab spell it differently and both are read the same way.
        assertEquals(
            "https://codeberg.org/me/mods/raw/branch/main/manifest.json",
            ExtensionRepositories.canonical("https://codeberg.org/me/mods/src/branch/main/manifest.json"),
        )
        assertEquals(
            "https://gitlab.com/me/mods/-/raw/main/registry.json",
            ExtensionRepositories.canonical("https://gitlab.com/me/mods/-/blob/main/registry.json"),
        )
        // The same page for one of Hush's own registries becomes that registry, so adding it is
        // refused as "one of Hush's own" rather than stored as a second copy of it.
        assertTrue(ExtensionRepositories.isBundled(ExtensionRepositories.canonical(bundled[0].replace("raw.githubusercontent.com/spotiflacapp/spotiflac-extension/main", "github.com/spotiflacapp/spotiflac-extension/blob/main"))!!))
    }

    /**
     * A branch with slashes cannot be told from a directory path by looking at the URL.
     *
     * GitHub resolves it against the refs it knows; Hush does not have them, so every reading is
     * offered and the fetch decides. The first reading stays the common one - a one-segment branch -
     * because that is what the dialog validates and what a stored address shows.
     */
    @Test
    fun `a blob link with a slashed branch keeps the whole remainder`() {
        // Nothing has to decide where the branch ends: the raw host takes everything after `blob/`
        // verbatim, so a branch named `feature/new` resolves exactly as GitHub showed it.
        assertEquals(
            listOf("https://raw.githubusercontent.com/me/mods/feature/new/sub/manifest.json"),
            ExtensionRepositories.addCandidates(
                "https://github.com/me/mods/blob/feature/new/sub/manifest.json",
            ),
        )
        assertEquals(
            listOf("https://raw.githubusercontent.com/me/mods/main/manifest.json"),
            ExtensionRepositories.addCandidates("https://github.com/me/mods/blob/main/manifest.json"),
        )
    }

    /**
     * One file has several addresses, and Hush has to know it is looking at one file.
     *
     * Measured on the device while testing the add flow: the same repository was added four times -
     * `main`, `refs/heads/main` and `HEAD` spellings of one registry - so the list grew a row for
     * each, two of them copies of Hush's own bundled registry. The identity is the owner, the
     * repository and the file; `HEAD` stands for whatever the default branch is, and
     * `refs/heads/x` is the same branch as `x`.
     */
    @Test
    fun `the same file under different refs is one repository`() {
        val main = "https://raw.githubusercontent.com/AlexRabbit/SpoitiLists/main/registry.json"
        assertTrue(
            ExtensionRepositories.sameRepositoryDocument(
                main,
                "https://raw.githubusercontent.com/AlexRabbit/SpoitiLists/refs/heads/main/registry.json",
            ),
        )
        assertTrue(
            ExtensionRepositories.sameRepositoryDocument(
                main,
                "https://raw.githubusercontent.com/AlexRabbit/SpoitiLists/HEAD/registry.json",
            ),
        )
        // A different file, or a different repository, is a different document.
        assertEquals(
            false,
            ExtensionRepositories.sameRepositoryDocument(
                main,
                "https://raw.githubusercontent.com/AlexRabbit/SpoitiLists/main/manifest.json",
            ),
        )
        assertEquals(
            false,
            ExtensionRepositories.sameRepositoryDocument(
                main,
                "https://raw.githubusercontent.com/me/SpoitiLists/main/registry.json",
            ),
        )
        // Hosts whose path shape Hush does not know are compared literally, never guessed at.
        assertEquals(
            false,
            ExtensionRepositories.sameRepositoryDocument(main, "https://mine.example/registry.json"),
        )
        // And a known document resolves to the spelling Hush already ships, which is what turns a
        // pasted repository page into "one of Hush's own" rather than a second copy of one.
        assertEquals(
            bundled[1],
            ExtensionRepositories.knownAddress(
                "https://raw.githubusercontent.com/zarzet/spotiflac-extension/HEAD/registry.json",
            ),
        )
        assertEquals(
            bundled[1],
            ExtensionRepositories.canonical(
                "https://github.com/zarzet/spotiflac-extension/blob/main/registry.json",
            )?.let { ExtensionRepositories.knownAddress(it) },
        )
    }

    @Test
    fun `a copy-link address is stored as the branch it names`() {
        assertEquals(
            "https://raw.githubusercontent.com/AlexRabbit/SpoitiLists/main/registry.json",
            ExtensionRepositories.canonical(
                "https://raw.githubusercontent.com/AlexRabbit/SpoitiLists/refs/heads/main/registry.json",
            ),
        )
    }

    /**
     * A repository page names no file, so the two documents a repository publishes are probed.
     *
     * `HEAD` rather than `main`: the default branch is whatever the repository says it is, and
     * pinning `main` would refuse every repository that calls it something else.
     */
    @Test
    fun `a repository page is probed for the documents a repository publishes`() {
        assertEquals(
            listOf(
                "https://raw.githubusercontent.com/me/mods/HEAD/registry.json",
                "https://raw.githubusercontent.com/me/mods/HEAD/manifest.json",
            ),
            ExtensionRepositories.addCandidates("https://github.com/me/mods"),
        )
        // A tree page names a branch, so the probe keeps it.
        assertEquals(
            listOf(
                "https://raw.githubusercontent.com/me/mods/dev/registry.json",
                "https://raw.githubusercontent.com/me/mods/dev/manifest.json",
            ),
            ExtensionRepositories.addCandidates("https://github.com/me/mods/tree/dev"),
        )
    }

    /**
     * One malformed entry must not take a repository's other sources with it.
     *
     * A third-party registry is written by someone else and can carry a field of a type Hush has
     * never seen. Decoding the document as one object made every entry depend on every other, and
     * the failure was swallowed into "declares no sources" - which the add flow reports as the
     * repository being useless rather than as one entry being wrong.
     */
    @Test
    fun `a registry with one broken entry still publishes the others`() {
        val body =
            """
            {
              "name": "Mine",
              "extensions": [
                {"id": "good-one", "download_url": "https://mine.example/good.sflx"},
                {"name": "no id at all"},
                {"id": "also-good", "download_url": "https://mine.example/also.sflx"}
              ]
            }
            """

        val document = ExtensionRepositories.read("https://mine.example/registry.json", body)!!

        assertEquals("Mine", document.name)
        assertEquals(listOf("good-one", "also-good"), document.sources.map { it.id })
    }
}
