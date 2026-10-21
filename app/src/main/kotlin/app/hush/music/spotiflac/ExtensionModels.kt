package app.hush.music.spotiflac

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
data class ExtensionSource(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String = id,
    @SerialName("display_name") val displayNameFromRegistry: String? = null,
    @SerialName("description") val description: String = "",
    @SerialName("version") val version: String = "",
    @SerialName("author") val author: String = "",
    @SerialName("icon") val icon: String? = null,
    @SerialName("icon_url") val iconUrl: String? = null,
    @SerialName("download_url") val downloadUrl: String? = null,
    @SerialName("sha256") val sha256: String? = null,
    @SerialName("min_app_version") val minAppVersion: String? = null,
    @SerialName("category") val category: String? = null,
    @SerialName("tags") val tags: List<String> = emptyList(),
    @SerialName("relay_url") val relayUrl: String? = null,
    @SerialName("provider_key") val providerKey: String? = null,
    @SerialName("repository_id") val repositoryId: String = "",
    @SerialName("enabled") val enabled: Boolean = true,
    @SerialName("priority") val priority: Int = 0,
    @SerialName("types") val types: List<String> = emptyList(),
    @SerialName("required_runtime_features") val requiredRuntimeFeatures: List<String> = emptyList(),
    /**
     * Which runtime plays this source, when the registry that published it is not a SpotiFLAC one.
     *
     * Empty is Hush's own runtime, which is what every entry in the two SpotiFLAC registries is.
     * `module_index` is a BitChord-style module: a JavaScript plugin the registry points at with a
     * `download` file rather than a signed extension package. The distinction is not cosmetic -
     * a module cannot be played without a JavaScript runtime Hush does not have, so a row that
     * offered it as a working source would be a promise nothing behind it keeps. It is carried on
     * the source rather than looked up from the repository because the source is what every surface
     * below reads: the list, the ordering, and the resolve candidates.
     */
    @SerialName("runtime") val runtime: String = "",
    /**
     * The base address of a *server* addon: a manifest whose source is an HTTP service, not a
     * script.
     *
     * A BitChord addon can be published as a manifest plus two endpoints - `search?q=` returns its
     * own rows and `stream/<id>` returns the file's URL - instead of as JavaScript the client runs.
     * Measured against `gglos.ggottes.deno.net`: `manifest.json` names `resources: [search, stream]`
     * and no module at all, `/search?q=taste` answers rows and `/stream/<id>` answers a FLAC URL.
     * Nothing in a registry format describes that, so the base is carried here and the addon is
     * played by [ServerAddonHost] the way a module is played by the module host.
     */
    @SerialName("addon_base_url") val addonBaseUrl: String? = null,
    /**
     * The settings a server addon's manifest declares, in the order it published them.
     *
     * Read from the manifest because the addon expects them back on every request and has no other
     * way to be told what the client wants ([AddonRequestSettings]). Stored under a key of Hush's
     * own (`addon_settings`) rather than the manifest's `settings`, because the same shape travels
     * in the cached registry next to SpotiFLAC entries, whose own `settings` key means something
     * else - a registry entry must not be dropped for carrying one.
     */
    @SerialName("addon_settings") val addonSettings: List<AddonSetting> = emptyList(),
) {
    val displayName: String get() = displayNameFromRegistry?.ifBlank { null } ?: name.ifBlank { id }

    /** True when this source is a JavaScript module rather than a SpotiFLAC extension package. */
    val needsModuleRuntime: Boolean
        get() = runtime.equals(ModuleRuntime, ignoreCase = true)

    /**
     * A JavaScript module Hush runs itself, in its own sandbox, instead of through the runtime.
     *
     * The entry point is the whole test, and it is the same test the upload of a module is held to
     * ([isSafeRegistryEntry], which has already refused anything that is not an https address): the
     * script *is* the source, so a module that names no script has nothing behind it. Modules only
     * became runnable at all when [SpotiFLACModuleHost] landed - a BitChord module advertises what
     * it can do as `resources` and exports `searchTracks`/`getTrackStreamUrl`, and every one of
     * those is now served by a host built on the JavaScript engine this project already ships.
     */
    val isHostedModule: Boolean
        get() = needsModuleRuntime && !downloadUrl.isNullOrBlank()

    /**
     * Whether the *native* SpotiFLAC runtime is what serves this source.
     *
     * Split from [isPlayable] because the two answer different questions and only one of them may
     * be handed to the runtime. The runtime takes source ids and walks them; a module id
     * (`bitchord-streaming-modules:archive-flac`) is not one of its extensions, so offering it would
     * ask the runtime about a source it has never heard of. The module's own candidates are walked
     * by [SpotiFLACModuleHost] and reach playback through the same playback cache.
     */
    val isNativePlayable: Boolean
        get() = !needsModuleRuntime && (downloadUrl != null || relayUrl != null || providerKey != null)

    /**
     * Whether Hush can actually play this source today.
     *
     * Two ways to be playable, and the difference matters to every caller: served by the runtime, or
     * served by the module host. A SpotiFLAC source with neither an installable package nor a relay
     * has nothing behind it either, so that half is still checked. This is the one predicate the
     * list, the ordering and the resolve candidates agree on, and a source that fails it is shown
     * rather than hidden - it is in the registry, and a user who cannot find the source they read
     * about in the list will go looking for it somewhere worse.
     */
    /**
     * A server addon: an HTTP service published as a manifest, played by [ServerAddonHost].
     *
     * Distinct from a module because there is no script to run and no sandbox involved - the
     * service does the searching and resolving, and Hush only asks it about a track.
     */
    val isServerAddon: Boolean
        get() = runtime.equals(AddonServerRuntime, ignoreCase = true) && !addonBaseUrl.isNullOrBlank()

    val isPlayable: Boolean
        get() = isNativePlayable || isPlayedOutsideRuntime

    /**
     * Whether Hush plays this source itself, without the SpotiFLAC runtime.
     *
     * A module runs in Hush's JavaScript sandbox and a server addon is an HTTP service Hush talks
     * to; neither has a package, a signed session or a gateway check. Every surface that asks "is
     * this the runtime's to verify, update or install" asks this rather than naming the two kinds
     * separately, because a surface that knew only about modules treated an addon as a runtime
     * source it could never check.
     */
    val isPlayedOutsideRuntime: Boolean
        get() = isHostedModule || isServerAddon

    /**
     * Whether playing this source goes through Hush's signed SpotiFLAC gateway session.
     *
     * This is what makes a verification apply to one source and not to another, and it is a
     * property of the source rather than of the list it is drawn in: the gateway session is issued
     * to a relay-backed provider, so a credential-less module, a lyrics provider and a built-in
     * that signs in with its own service all answer false and are never counted as needing a check.
     */
    val requiresSpotiFLACSession: Boolean
        get() = !needsModuleRuntime && relayUrl != null && providerKey != null

    val displayDescription: String get() = description.ifBlank { "No description" }

    /** Whether its registry entry pins the package it publishes, by digest. */
    val hasVerifiedPackageDigest: Boolean
        get() = sha256?.matches(Regex("[0-9a-fA-F]{64}")) == true

    /**
     * Whether the SpotiFLAC runtime can be given a package for this source.
     *
     * A package URL and not a module: a module index publishes a JavaScript file under the same
     * field name, and installing one as a SpotiFLAC package would hand the runtime an archive it
     * cannot read. The digest is deliberately *not* part of this any more.
     *
     * It used to be, and that made every registry which omits `sha256` unusable in a way nothing
     * said out loud: the entry arrived, was listed, its switch turned on, and every attempt failed
     * with `SpotiFLAC extension package is unavailable`. Measured on the reporting device with
     * `AlexRabbit/SpoitiLists`, whose entry publishes a `download_url` and no checksum - upstream
     * SpotiFLAC installs it (its store does not even read `sha256`), so Hush refusing it was Hush's
     * rule and not the format's. What a missing digest costs is [hasVerifiedPackageDigest]'s
     * question, and the row says so; the package is fetched and its structure verified either way.
     */
    val isRuntimeCompatible: Boolean
        get() = !needsModuleRuntime && !downloadUrl.isNullOrBlank()

    /** Registry metadata is accepted only when it cannot redirect Hush to an unsafe host. */
    val hasSafeNetworkMetadata: Boolean
        get() = listOf(downloadUrl, icon, iconUrl, relayUrl, addonBaseUrl)
            .filterNotNull()
            .all { value ->
                runCatching {
                    val uri = URI(value.trim())
                    uri.scheme.equals("https", ignoreCase = true) && uri.userInfo.isNullOrBlank()
                }.getOrDefault(false)
            }

    /**
     * True when the registry positively declares that this source does not serve audio.
     *
     * The same question [SpotiFLACExtensionRole] asks of an installed package, one step earlier: a
     * manifest is only readable once the package has been extracted, and a metadata provider's
     * package is not installed until something asks for it - so a sweep that waited for the manifest
     * had to reach the source before it could learn that the source could never answer. What the
     * registry publishes is what is known before that, and it says it plainly: every downloader in
     * the registries Hush reads carries `category: "download"`, and every metadata-only one carries
     * `"integration"` (measured against `spotiflacapp/spotiflac-extension`, whose `spotify-web`,
     * `apple-music`, `bugs-music`, `melon-music` and `genie-music` entries all answer this way while
     * `tidal-web`, `deezer`, `qobuz-web`, `amazon`, `soundcloud`, `jiosaavn` and `ytmusic-spotiflac`
     * carry `download`).
     *
     * Only a *positive* declaration answers true. A source whose entry says nothing - an unfamiliar
     * category, or a module index that publishes `resources` instead of roles - is left in every
     * list, because an id wrongly dropped is a source that is never asked, and the entries Hush
     * cannot classify are exactly the ones it should keep trying. A source that declares audio is
     * never metadata-only either: the registries Hush reads publish `metadata_provider` *and*
     * `download_provider` for every provider that does both (tidal, deezer, qobuz, amazon,
     * soundcloud, jiosaavn, ytmusic), and only a source with no audio role at all is skipped. A
     * source Hush runs itself is the exception: a module's `resources` *is* its complete declaration,
     * so one that never names `stream` cannot serve audio however unfamiliar its repository is.
     */
    val declaresMetadataOnly: Boolean
        get() {
            val servesAudio =
                declaredRoles.any { role ->
                    role.contains("download", ignoreCase = true) ||
                        role.contains("stream", ignoreCase = true)
                } ||
                    // `category: "download"` is the older way of saying "this one downloads", and it
                    // wins over a `types` list that forgot to repeat it - the same precedence
                    // [SpotiFLACExtensionRole.declaresNoDownloadProvider] gives it. A source that
                    // enriches metadata *and* serves audio is a download source with extra roles, so
                    // reading it as metadata-only would take a working provider out of the sweep.
                    category.equals("download", ignoreCase = true)
            if (servesAudio) return false
            return declaredRoles.isNotEmpty() ||
                (isPlayedOutsideRuntime && requiredRuntimeFeatures.isNotEmpty())
        }

    /**
     * What this source is for, in the terms the session list describes rows with.
     *
     * A registry entry carries no `types` list - that lives in the extension's own manifest, readable
     * only once the package has been extracted - so `category` and `tags` are what is known about a
     * source before anything is downloaded. Without this the session list had no role to go on and
     * described Apple Music and Spotify Web with the same sentence it uses for a download provider,
     * which is exactly what made them look like sources that had lost a session rather than ones that
     * never had one. Checked against the registries Hush reads: every download source publishes
     * `category: "download"`, and the two that cannot download publish `"integration"` - with
     * Apple Music carrying a `lyrics` tag and Spotify Web a plain metadata one.
     */
    val declaredRoles: List<String>
        get() =
            if (types.isNotEmpty()) {
                types
            } else {
                buildList {
                    val kind = category.orEmpty().trim()
                    when {
                        kind.equals("download", ignoreCase = true) -> add("download_provider")
                        kind.isNotEmpty() -> add("metadata_provider")
                    }
                    // A module index publishes no roles at all: a module advertises what it can do
                    // as `resources` (`search`, `stream`), and one that can stream is a provider in
                    // the only vocabulary the rest of the app has.
                    if (isPlayedOutsideRuntime &&
                        requiredRuntimeFeatures.any { it.equals("stream", ignoreCase = true) }
                    ) {
                        add("download_provider")
                    }
                    if (tags.any { it.equals("lyrics", ignoreCase = true) }) add("lyrics_provider")
                }
            }

    /**
     * The role, in one word, for the tag a source row shows beside its repository.
     *
     * Derived here rather than at the call site because three surfaces ask the same question - the
     * source list, the repository's own count, and the choice label in the ordering - and a role
     * that three places derive separately is three answers. A source can declare several, and the
     * first that describes playing something wins: what a user is looking for is where the audio
     * comes from, not every capability the entry advertised.
     */
    val primaryRole: String
        get() =
            when {
                declaredRoles.any { it.contains("download", ignoreCase = true) || it.contains("stream", ignoreCase = true) } ->
                    "Streams audio"
                declaredRoles.any { it.equals("lyrics_provider", ignoreCase = true) } -> "Lyrics"
                declaredRoles.any { it.equals("metadata_provider", ignoreCase = true) } -> "Metadata"
                else -> "Unknown role"
            }

    val isSafeRegistryEntry: Boolean
        get() {
            if (!id.matches(SafeIdPattern)) return false
            val packageUrl = downloadUrl?.trim()
            if (packageUrl != null && !isSafeHttpsUrl(packageUrl)) return false
            if (sha256 != null && !hasVerifiedPackageDigest) return false
            // A server addon's base is where every request goes, so it is held to the same rule as
            // a package URL: https, a host, no credentials.
            if (addonBaseUrl != null && !isSafeHttpsUrl(addonBaseUrl)) return false
            return listOf(icon, iconUrl, relayUrl).filterNotNull().all(::isSafeHttpsUrl)
        }

    private fun isSafeHttpsUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo.isNullOrBlank()
    }.getOrDefault(false)
}

/**
 * The `runtime` value a module-index entry is given when it is folded into a source list.
 *
 * A BitChord module index declares JavaScript files, not SpotiFLAC packages; naming the runtime on
 * the source is what lets every surface below say "this one needs a runtime this build does not
 * have" instead of offering a switch that cannot do anything.
 */
const val ModuleRuntime = "module_index"

/**
 * The `runtime` value a server addon's manifest is given when it is folded into a source list.
 *
 * A server addon publishes no script and no package: its manifest names a service that answers
 * `search` and `stream`. Naming the runtime is what routes it to [ServerAddonHost] rather than to
 * the SpotiFLAC engine or the JavaScript sandbox.
 */
const val AddonServerRuntime = "addon_server"

/**
 * The id shape a source may have.
 *
 * A colon is allowed because a module's id is *namespaced* by the repository that published it -
 * `bitchord-streaming-modules:bitchord-tidal-flac` - and a shape without one refused every module a
 * module index publishes, and refused them as unsafe. The shipped BitChord index then answered, was
 * read, and reported "every entry it declares was refused as unsafe", which reads as a broken
 * repository rather than as a check that had been written for registry ids alone.
 */
private val SafeIdPattern = Regex("[a-z0-9][a-z0-9._:-]{0,127}")

@Serializable
data class ExtensionRegistry(
    @SerialName("version") val version: Int = 1,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("extensions") val extensions: List<ExtensionSource> = emptyList(),
)

@Serializable
data class ExtensionRegistryResponse(
    @SerialName("version") val version: Int = 1,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("extensions") val extensions: List<ExtensionSource> = emptyList(),
)
