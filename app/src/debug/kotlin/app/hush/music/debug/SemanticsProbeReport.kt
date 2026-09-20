/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.debug

/**
 * One node of a live composition's semantics tree, read in-process. **Debug builds only.**
 *
 * Deliberately free of every Android and Compose type: what a probe collects is data, and the rules
 * that read it ([SemanticsProbeReport]) belong in a unit test rather than on a device.
 *
 * @param merged true for the tree an accessibility service reads (merging applied), false for the
 *   unmerged tree, which still holds nodes that merging removed.
 * @param top the node's top edge in screen pixels, boundaries included.
 */
data class SemanticsProbeNode(
    val merged: Boolean,
    val id: Int,
    val depth: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val text: String,
    val keys: String,
    val clickable: Boolean,
) {
    val width: Int get() = right - left

    val height: Int get() = bottom - top
}

/**
 * What a screen's content area had to say for itself.
 *
 * The three states are the three answers that mean different things, which is the whole point of the
 * probe: only one of them is "the app never described this screen".
 */
enum class ContentSemantics {
    /** Content nodes are in the merged tree - what an accessibility service reads. */
    PRESENT,

    /** Content nodes exist but merging removed all of them from the merged tree. */
    MERGED_AWAY,

    /** No content nodes at all, merged or unmerged. */
    ABSENT,
}

/**
 * The rules a semantics snapshot is read by, kept separate from reading it.
 *
 * The classification the probe exists to settle is exactly two comparisons - is a node above the
 * player's bar, and did anything survive merging - so pinning them in a unit test is what keeps a
 * verdict from being a coincidence of one device's layout.
 */
object SemanticsProbeReport {

    /** How many nodes of a region are quoted in the report. */
    const val SAMPLE_LIMIT = 25

    /** How many y bands the distribution is reported in. */
    const val BAND_COUNT = 8

    /**
     * Whether a node belongs to the screen's content rather than to the player and the bottom bars.
     *
     * The two live in one Compose view - the bars are drawn over the content, not in a window of
     * their own - so the split has to be positional. The boundary is always reported alongside the
     * counts, so a boundary that cut in the wrong place shows up in the output as the bands the
     * nodes actually occupy rather than as a wrong answer.
     */
    fun isContent(
        node: SemanticsProbeNode,
        contentBottom: Int,
    ): Boolean = node.top < contentBottom

    /**
     * The verdict, from the two content counts.
     *
     * A merged node is one an accessibility service (and `uiautomator`) can read; the unmerged tree
     * is asked as the tie-breaker, because "the app composed nothing" and "the app composed it and
     * merging removed it" need different fixes and must not read the same.
     */
    fun verdict(
        contentMerged: Int,
        contentUnmerged: Int,
    ): ContentSemantics =
        when {
            contentMerged > 0 -> ContentSemantics.PRESENT
            contentUnmerged > 0 -> ContentSemantics.MERGED_AWAY
            else -> ContentSemantics.ABSENT
        }

    /** One line saying what a verdict means, so a report is readable without this file. */
    fun explain(verdict: ContentSemantics): String =
        when (verdict) {
            ContentSemantics.PRESENT ->
                "the content describes itself and an accessibility service can read it; " +
                    "anything that cannot see it is reading the accessibility bridge, not the app"

            ContentSemantics.MERGED_AWAY ->
                "content nodes exist but none reaches the merged tree an accessibility service reads"

            ContentSemantics.ABSENT ->
                "the content has no semantics at all: nothing on it can be read or driven by anything outside the app"
        }

    /**
     * How many nodes start in each horizontal band of the screen.
     *
     * The bands are what make a wrong boundary visible: a content area that is genuinely empty
     * leaves its bands at zero, while a boundary drawn through the middle of the content shows a
     * cluster of nodes just below it.
     */
    fun bands(
        nodes: List<SemanticsProbeNode>,
        screenHeight: Int,
        count: Int = BAND_COUNT,
    ): List<Int> {
        if (screenHeight <= 0 || count <= 0) return emptyList()
        val band = screenHeight.toDouble() / count
        val counts = IntArray(count)
        for (node in nodes) {
            val index = (node.top / band).toInt().coerceIn(0, count - 1)
            counts[index]++
        }
        return counts.toList()
    }

    /** Renders the bands with the pixel range each covers, because the numbers alone are unreadable. */
    fun bandLine(
        nodes: List<SemanticsProbeNode>,
        screenHeight: Int,
        count: Int = BAND_COUNT,
    ): String {
        val counts = bands(nodes, screenHeight, count)
        if (counts.isEmpty()) return "unavailable"
        val band = screenHeight.toDouble() / count
        return counts.withIndex().joinToString(" ") { (index, value) ->
            "%d-%d:%d".format((index * band).toInt(), ((index + 1) * band).toInt(), value)
        }
    }

    /**
     * The important nodes whose bounds contain [target], smallest first - whatever is drawn over it.
     *
     * This is the shape of the bridge's real rule. The tree an accessibility service reads is built
     * from the *uncovered* semantics nodes: every node important for accessibility adds its own
     * bounds to a covered region, and a node that ends up entirely inside that region is left out -
     * even though it is still in the semantics tree, still laid out, and still on screen if the thing
     * over it is transparent.
     *
     * So a screen a person can see and a service cannot is usually a screen with something drawn over
     * it, and the something is findable rather than guessable. Ancestors are excluded because a
     * parent legitimately contains its children; only siblings and other branches can cover one.
     */
    fun coverers(
        target: SemanticsProbeNode,
        all: List<SemanticsProbeNode>,
        ancestorIds: Set<Int>,
        importantIds: Set<Int>,
        limit: Int = 3,
    ): List<SemanticsProbeNode> =
        all.asSequence()
            .filter { it.merged && it.id != target.id && it.id !in ancestorIds }
            .filter { it.id in importantIds }
            .filter { cover ->
                cover.left <= target.left && cover.top <= target.top &&
                    cover.right >= target.right && cover.bottom >= target.bottom
            }
            .sortedBy { cover -> cover.width.toLong() * cover.height.toLong() }
            .take(limit)
            .toList()

    /** The ids in an ancestor chain rendered by [SemanticsNodeInternals.ancestry]. */
    fun ancestryIds(chain: String?): Set<Int> =
        chain.orEmpty()
            .split("<-")
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()

    /** One node, as a report line: where it is, what it is, and what it says. */
    fun line(node: SemanticsProbeNode): String {
        val text = node.text.take(60).replace('\n', ' ')
        return buildString {
            append("[")
            append(if (node.merged) "merged" else "unmerged")
            append("] id=")
            append(node.id)
            append(" d=")
            append(node.depth)
            append(" at=")
            append(node.top)
            append(",")
            append(node.left)
            append(" ")
            append(node.width)
            append("x")
            append(node.height)
            if (node.clickable) append(" clickable")
            if (node.keys.isNotEmpty()) {
                append(" keys=")
                append(node.keys.take(80))
            }
            if (text.isNotEmpty()) {
                append(" \"")
                append(text)
                append("\"")
            }
        }
    }
}
