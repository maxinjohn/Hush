package app.hush.music.spotiflac

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What a startup sweep of the installed extension packages found.
 *
 * The sweep exists because a package can be behind its registry without anything being wrong
 * locally, and an update that only shows up in the diagnostic log is an update no user ever sees.
 */
data class SpotiFLACPackageUpdateSummary(
    /** How many installable packages the sweep looked at. */
    val checked: Int,
    val updates: List<Entry>,
    /**
     * Sources whose package could not be checked or refreshed, by id.
     *
     * Kept separate from [updates] because the two must never be added together: a package that
     * could not be reached is not a package that is current, and reporting the two as the same
     * thing is exactly how a check tells the user "up to date" while it is offline.
     */
    val failed: List<String> = emptyList(),
) {
    data class Entry(
        val sourceId: String,
        val displayName: String,
        val from: String?,
        val to: String?,
    )

    val isEmpty: Boolean get() = updates.isEmpty()
}

/**
 * The one line Audio Sources shows when a startup check moved something.
 *
 * Built from the sweep's own record rather than from prose written at each call site, so the
 * sentence and the list it describes cannot disagree - and so the wording is pinned by tests
 * instead of by whoever reads the screen.
 */
object SpotiFLACPackageUpdateReport {

    /** Named updates before the rest are counted instead of listed, so the line stays one line. */
    private const val MAX_NAMED = 3

    /**
     * The line, or null when there is nothing to say (nothing was checked at all).
     *
     * A check that found nothing says so. Staying silent was the earlier behaviour, and it leaves
     * the user unable to tell "everything is current" from "the check never ran" - the question a
     * version list exists to answer. A check that could not reach a package says *that* instead, so
     * being offline is never reported as being current.
     */
    fun summary(summary: SpotiFLACPackageUpdateSummary): String? {
        val checked = summary.checked.coerceAtLeast(summary.updates.size)
        if (checked <= 0 && summary.failed.isEmpty()) return null
        val uncheckable = summary.failed
        if (summary.updates.isEmpty()) {
            return when {
                uncheckable.isEmpty() && checked == 1 -> "The installed package is up to date"
                uncheckable.isEmpty() -> "All $checked packages are up to date"
                uncheckable.size >= checked ->
                    "Could not check ${if (checked == 1) "the package" else "any of the $checked packages"}"
                else -> "Could not check ${describe(uncheckable)} of $checked packages"
            }
        }
        val named = summary.updates.take(MAX_NAMED).joinToString(", ") { entry ->
            val name = entry.displayName.ifBlank { entry.sourceId }
            val from = entry.from?.takeIf { it.isNotBlank() }
            val to = entry.to?.takeIf { it.isNotBlank() }
            when {
                to == null -> name
                from == null -> "$name to $to"
                else -> "$name $from → $to"
            }
        }
        val remaining = summary.updates.size - MAX_NAMED
        val tail = if (remaining > 0) " and $remaining more" else ""
        val failures =
            if (uncheckable.isEmpty()) "" else " · ${describe(uncheckable)} could not be checked"
        return "Updated ${summary.updates.size} of $checked packages: $named$tail$failures"
    }

    /** Up to three names, then a count - the same rule the update list follows. */
    private fun describe(ids: List<String>): String {
        val named = ids.take(MAX_NAMED).joinToString(", ")
        val remaining = ids.size - MAX_NAMED
        return if (remaining > 0) "$named and $remaining more" else named
    }
}

/**
 * The most recent check, whatever it found.
 *
 * In memory on purpose: the check runs at every app start, so a stored copy could only ever be a
 * claim about a run the current process did not make.
 *
 * Every completed check is recorded, including one that found nothing: "all packages are up to
 * date" is a result, and the line is the answer to the question the version list raises. A later
 * check replaces it, which is correct - it describes the state now, not the last thing that
 * happened to be interesting.
 */
object SpotiFLACPackageUpdateLog {
    private val _summary = MutableStateFlow<SpotiFLACPackageUpdateSummary?>(null)
    val summary: StateFlow<SpotiFLACPackageUpdateSummary?> = _summary.asStateFlow()

    /**
     * Records a check.
     *
     * [userInitiated] is a check the user asked for, which always answers - "up to date" is the
     * answer they pressed the button for. An incidental sweep (a service re-warm, a screen opening)
     * only publishes when it found something, or when nothing has been published yet: measured on
     * device, a startup check that updated amazon was followed two seconds later by two repeat
     * sweeps that found nothing, and the update was erased from the only place it was reported just
     * as the user arrived to read it.
     */
    fun record(summary: SpotiFLACPackageUpdateSummary, userInitiated: Boolean = false) {
        if (!userInitiated && summary.isEmpty && _summary.value != null) return
        _summary.value = summary
    }

    /** Test seam: forget the last line. */
    fun clear() {
        _summary.value = null
    }
}
