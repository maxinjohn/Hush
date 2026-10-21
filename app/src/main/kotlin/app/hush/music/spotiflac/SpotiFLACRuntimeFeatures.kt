/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 and Section 5
 */

package app.hush.music.spotiflac

/**
 * The runtime features the SpotiFLAC backend Hush ships actually provides.
 *
 * A package names the runtime capabilities it needs in its own `requiredRuntimeFeatures`, and the
 * runtime refuses to load one it cannot provide. Hush installs packages itself
 * ([SpotiFLACExtensionPackageVerifier]), so it can answer that question at the point of install
 * instead of leaving a source switched on with a load error behind it.
 *
 * The list is the pinned Go backend's own `supportedRuntimeFeatures`
 * (`go_backend/extension_manager.go` at `a34e2935`, the last revision that has one). It is copied
 * rather than discovered because the AAR exposes no call for it, and it is checked here rather than
 * trusted to the runtime because the two disagree at exactly the moment it matters: upstream's
 * `rust_backend` has since added `accountForms`, `accountDownloadMode` and `directMedia` (5.1.x),
 * and a package that starts requiring one is a package this build cannot run at all - not a package
 * that will fail on its first track. Measured against the live registry, all twelve published
 * packages are compatible today; the newest requirement is `signedSession@3`, which this build
 * provides.
 *
 * The wording is the runtime's, so a user who later meets the same refusal from the backend reads
 * the same sentence in both places.
 */
object SpotiFLACRuntimeFeatures {
    /** Feature name to the highest version this build's runtime implements. */
    private val supported =
        mapOf(
            "signedSession" to 3,
            "sessionRefresh" to 1,
            "sessionGrant" to 1,
            "globalAction" to 1,
            "webviewAuth" to 1,
            "downloadSegments" to 1,
            "patternedFileTransform" to 1,
            "preparedContext" to 1,
        )

    /** One declared requirement, `name@version`, with `@1` assumed when the version is omitted. */
    data class Requirement(
        val name: String,
        val version: Int,
    )

    /**
     * The requirement [raw] states, or null when it states nothing.
     *
     * Parsed the runtime's own way: the last `@` separates the version, a version that is not a
     * positive integer leaves it at 1, and an empty entry is skipped rather than counted as an
     * unknown feature.
     */
    fun requirementOf(raw: String): Requirement? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val at = text.lastIndexOf('@')
        if (at <= 0) return Requirement(text, 1)
        val version = text.substring(at + 1).trim().toIntOrNull()?.takeIf { it > 0 } ?: 1
        return Requirement(text.substring(0, at).trim(), version)
    }

    /**
     * The refusal sentence for the first requirement this build cannot meet, or null when it can
     * meet all of them.
     *
     * "Cannot meet" is two cases, and they read differently on purpose: a feature this build has
     * never heard of (the runtime grew a capability Hush's pin predates), and a version of one it
     * does have but not high enough.
     */
    fun missing(features: List<String>): String? {
        for (raw in features) {
            val requirement = requirementOf(raw) ?: continue
            val have = supported[requirement.name]
                ?: return "requires runtime feature \"${requirement.name}\" this app build does not provide"
            if (have < requirement.version) {
                return "requires runtime feature ${requirement.name}@${requirement.version} " +
                    "(app provides @$have)"
            }
        }
        return null
    }
}
