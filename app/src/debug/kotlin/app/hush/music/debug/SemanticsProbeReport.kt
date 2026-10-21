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
 * @param textFit what became of [text] inside the box the node was drawn in; see [TextFit].
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
    val textFit: TextFit = TextFit.NOT_MEASURED,
    val fitNote: String = "",
) {
    val width: Int get() = right - left

    val height: Int get() = bottom - top
}

/**
 * What became of a node's label inside the box it was given.
 *
 * The tree cannot answer this and the geometry cannot either: a `Text` pinned to a width reports that
 * width whether or not the words fit inside it, so a clock drawn as "0:1" is a node whose text says
 * "0:01" and which measures *exactly* like one that fits. That is why a clipped label survived every
 * check in the size sweep - the box was the size the code asked for, and the missing characters were
 * simply not drawn.
 *
 * What separates the two cases is whether the label *says* it was shortened: an ellipsis is the app
 * choosing to shorten a label, and characters that are simply missing are a bug. Both are reported,
 * because only the second one is one.
 *
 * Only a *sideways* loss counts: a clock clipped from "0:01" to "0:1" is characters gone from the
 * end of the line, and that is the failure worth a token. A label that is merely taller than its box
 * is not a label that lost its words, and failing a window over one would make this check useless on
 * every screen it ran on - so the width is the question asked here. (A height overflow that drops
 * whole lines is a fault of its own kind - lines shortened and gone rather than characters - and it
 * would want its own token rather than being folded in here.)
 *
 * Where the question is asked matters as much as what is asked, and so does which number is read:
 * asking the wrong node, or asking for the wrong thing, is how this check first came back with two
 * fifths of a screen's labels "clipped" while every one of them was drawn complete. See
 * [SemanticsProbeReceiver.describe] for the node, and [SemanticsProbeReceiver.textFit] for the
 * measurement - the flag that reads like the answer to this question is true of almost every label
 * there is.
 */
enum class TextFit {
    /** No text, or no layout result to ask: nothing was measured. Never read as "it fits". */
    NOT_MEASURED,

    /** The label fits the width it was given, whole and untruncated. */
    FITS,

    /** The label did not fit and ends in an ellipsis: shortened on purpose, and visibly. */
    ELLIPSIZED,

    /** The label did not fit and does not say so: characters are missing with nothing to show it. */
    CLIPPED,
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

    /**
     * One line saying how every measured label fared, so a clipped one is a fact in the report and
     * not something the reader has to notice in a sample that may not list it.
     *
     * The two counts are kept apart on purpose: an ellipsized label was shortened by the app and
     * reads as shortened, and a clipped one lost characters silently. `clipped` is the column worth
     * failing a build over, so the line says what it means rather than leaving the reader to infer
     * it from the enum's name.
     */
    /**
     * What became of one label, from the two numbers Compose's own layout hands back.
     *
     * [TextFit] has why the width is the question and why an ellipsis is not a failure; this has the
     * two things that turned out to be the *wrong* way to ask it, both caught on device.
     *
     * The node: asked of a node that merges its children, the answer belongs to no single label -
     * see `SemanticsProbeReceiver.describe`. The number: `didOverflowWidth` reads like the answer and
     * is not, because it is true of any label that is not pinned to its exact width, which on a
     * player at a 1920x720dp window and font scale 2.0 was 24 of 40 labels, every one of them drawn
     * complete. What separates a cut label from those is whether the words *need* more room than the
     * layout was given - and only where the app has said the label must hold one line, since a
     * paragraph that is allowed to wrap folds into its box and needs far more than it was given on
     * purpose.
     */
    fun textFitOf(
        ellipsized: Boolean,
        cannotWrap: Boolean,
        needed: Float,
        given: Float,
    ): TextFit =
        when {
            ellipsized -> TextFit.ELLIPSIZED
            cannotWrap && needed > given -> TextFit.CLIPPED
            else -> TextFit.FITS
        }

    fun textFitLine(nodes: List<SemanticsProbeNode>): String {
        val measured = nodes.filter { it.textFit != TextFit.NOT_MEASURED }
        if (measured.isEmpty()) return "text fit: no label was measured"
        val clipped = measured.filter { it.textFit == TextFit.CLIPPED }
        val ellipsized = measured.count { it.textFit == TextFit.ELLIPSIZED }
        val fits = measured.count { it.textFit == TextFit.FITS }
        val worst = clipped.firstOrNull()
        return buildString {
            append("text fit: ${measured.size} labels measured, $fits fit, $ellipsized ellipsized, ")
            append("${clipped.size} clipped (characters missing and nothing to show it)")
            if (worst != null) {
                append(" - first clipped: \"${worst.text.take(30)}\" at ${worst.width}x${worst.height}px")
            }
        }
    }

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
            // Only the lossy case is marked on the line: a line per ellipsized label would be noise,
            // while a clipped one is a fault the reader has to be able to find in the sample. The
            // counts of both are in the report's own text-fit line.
            if (node.textFit == TextFit.CLIPPED) append(" clipped")
            // The numbers behind the verdict, so a clipped label can be read off the line it is on
            // rather than only counted. Unquoted, and before the label: the label is the last quoted
            // string on the line, and every reader of this report takes it from there.
            if (node.fitNote.isNotEmpty()) {
                append(" fit=(")
                append(node.fitNote)
                append(")")
            }
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
