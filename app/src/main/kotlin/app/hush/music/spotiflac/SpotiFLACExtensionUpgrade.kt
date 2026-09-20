package app.hush.music.spotiflac

/**
 * Decides whether an installed extension package is behind what its registry publishes.
 *
 * A package file used to be written once and then trusted for the life of the install - the
 * download ran only when the file was *missing* - so a source whose registry published a newer
 * build kept the old one forever. Measured on device: `amazon` sat at 2.3.8 while both registries
 * published 2.3.10, with no code path that could ever replace it.
 *
 * Three versions are in play and they can disagree:
 *  - the **registry** version, which is what the source is entitled to,
 *  - the **stored** version, inside the `.sflx` Hush holds (what the runtime would be handed),
 *  - the **installed** version, which is the one the runtime actually loaded (it extracts each
 *    package into its own directory, so a refreshed package can sit on disk unloaded).
 *
 * Only those comparisons are made here; the swap itself is the runtime's own entry point. A
 * version this cannot parse is never treated as "newer", because a comparison that cannot be
 * trusted must not be allowed to replace a working package or to churn a download every resolve.
 */
object SpotiFLACExtensionUpgrade {

    enum class Action {
        /** Nothing to do: the registry has nothing this install is missing. */
        UP_TO_DATE,

        /** The package on disk is behind the registry and has to be fetched again. */
        DOWNLOAD,

        /** The package on disk is current but the runtime has not loaded it. */
        RELOAD,
    }

    /** True only when [candidate] is a higher version than [installed], both being readable. */
    fun isNewer(candidate: String?, installed: String?): Boolean {
        val newer = parse(candidate) ?: return false
        val current = parse(installed) ?: return false
        return compare(newer, current) > 0
    }

    /**
     * What to do for one extension, given the three versions.
     *
     * [installedVersion] may be null (the runtime has no build loaded); that is not an upgrade
     * signal, because the ordinary install-by-path path already handles an unloaded extension.
     */
    fun plan(
        registryVersion: String?,
        storedVersion: String?,
        installedVersion: String?,
    ): Action {
        val registry = parse(registryVersion) ?: return Action.UP_TO_DATE
        // No package, an unreadable one, or one this cannot compare: fetch it. A package that
        // fails to read is also how a truncated file is repaired.
        val stored = parse(storedVersion) ?: return Action.DOWNLOAD
        if (compare(registry, stored) > 0) return Action.DOWNLOAD
        val installed = parse(installedVersion) ?: return Action.UP_TO_DATE
        return if (compare(stored, installed) > 0) Action.RELOAD else Action.UP_TO_DATE
    }

    private data class Version(val core: List<Int>, val preRelease: List<String>)

    /**
     * Reads `1.2.3`, `v1.2.3`, `1.2.3-beta.1` and `1.2.3+build` alike; null for anything whose
     * leading numeric run cannot be read at all (for example `latest`).
     */
    private fun parse(raw: String?): Version? {
        val text = raw?.trim()?.removePrefix("v")?.removePrefix("V")?.substringBefore('+')?.trim()
        if (text.isNullOrEmpty()) return null
        val coreText = text.substringBefore('-').trim()
        val preReleaseText = if ('-' in text) text.substringAfter('-').trim() else null
        val core = coreText.split('.').map { segment ->
            segment.trim().toIntOrNull() ?: return null
        }
        if (core.isEmpty()) return null
        val preRelease = preReleaseText
            ?.split('.')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        return Version(core, preRelease)
    }

    private fun compare(a: Version, b: Version): Int {
        val segments = maxOf(a.core.size, b.core.size)
        for (index in 0 until segments) {
            val left = a.core.getOrElse(index) { 0 }
            val right = b.core.getOrElse(index) { 0 }
            if (left != right) return left.compareTo(right)
        }
        // Same core: a pre-release sorts below the release it precedes, which is how a registry
        // publishes a candidate build without it overtaking the stable one.
        if (a.preRelease.isEmpty() && b.preRelease.isEmpty()) return 0
        if (a.preRelease.isEmpty()) return 1
        if (b.preRelease.isEmpty()) return -1
        val identifiers = maxOf(a.preRelease.size, b.preRelease.size)
        for (index in 0 until identifiers) {
            val left = a.preRelease.getOrNull(index) ?: return -1
            val right = b.preRelease.getOrNull(index) ?: return 1
            val leftNumber = left.toIntOrNull()
            val rightNumber = right.toIntOrNull()
            val result = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                // A numeric identifier sorts below an alphanumeric one, as semver specifies.
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
            if (result != 0) return result
        }
        return 0
    }
}
