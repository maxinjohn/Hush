/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.text.AnnotatedString
import app.hush.music.BuildConfig
import java.io.File
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Reads the live composition's semantics tree, in-process, and says what it found. **Debug builds
 * only.**
 *
 * ## Why this exists
 *
 * On a OnePlus running Android 12, `uiautomator` reports the mini player and the bottom bars of this
 * app and *nothing else* - no node for the Home, Search or Library tab, not even the `scrollable`
 * node a `LazyColumn` always carries - while the same screens clearly render and respond to taps.
 * Every screen behind those tabs, Settings included, is therefore invisible to anything that drives
 * the app from outside, which is what makes a scripted check of them impossible.
 *
 * Two very different faults produce that symptom, and they need different fixes:
 *
 *  - the content genuinely carries no semantics (an app-side fault), or
 *  - it carries them and they never reach the accessibility bridge (a platform-side one).
 *
 * Nothing that reads the bridge can tell those apart, because the bridge is the thing in question.
 * `AndroidComposeView.semanticsOwner` is public API and answers it directly: the merged root is
 * exactly the tree an accessibility service reads, and the unmerged root shows what merging removed.
 * A probe that reads both, in the app's own process, is the only measurement that decides it.
 *
 * ## What it reports
 *
 * The two trees, split by a horizontal boundary into the content area and the player/bottom bars,
 * with the distribution across y bands, a sample of nodes from each side, and one machine-readable
 * verdict:
 *
 * ```
 * semantics-probe step=content label=home verdict=PRESENT merged=41 unmerged=96 boundary=2605 views=1
 * ```
 *
 * `verdict` is `PRESENT` (content nodes are in the merged tree), `MERGED_AWAY` (they exist but
 * merging removed all of them) or `ABSENT` (there are none at all). A probe that could not run says
 * `SKIPPED` and why, rather than passing quietly.
 *
 * ## Driving it
 *
 * ```
 * adb shell am broadcast -a app.hush.music.action.SEMANTICS_PROBE \
 *     --es label home --ei content-bottom 2605
 * adb shell run-as app.hush.music.debug cat files/debug/semantics-probe.txt
 * ```
 *
 * `scripts/semantics-probe.sh` wraps both, and computes the boundary from the device's own screen.
 *
 * ## Why the boundary is a parameter
 *
 * The bars are drawn *over* the content inside one Compose view, so the two are separated by
 * position and not by subtree. The probe therefore always states the boundary it used and reports
 * the nodes' distribution across the screen's y bands beside the counts - a boundary drawn in the
 * wrong place shows up as a cluster of nodes just below it, instead of quietly answering the wrong
 * question.
 */
class SemanticsProbeReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        Log.d(TAG, "received action=${intent.action} debug=${BuildConfig.DEBUG}")
        if (!BuildConfig.DEBUG) return
        if (intent.action != ACTION_PROBE) return

        val label = intent.getStringExtra(EXTRA_LABEL)?.trim().orEmpty()
        val activity = SemanticsProbeActivityRegistry.resumedActivity()
        if (activity == null) {
            // Not a pass and not a failure: a probe that ran before anything was on screen has
            // measured nothing, and saying so is the only honest answer.
            return report(
                context = context,
                label = label,
                lines = listOf("no activity is resumed: launch Hush, show the screen to probe, and run this again"),
                verdict = "SKIPPED",
                detail = "reason=no-resumed-activity",
            )
        }

        val window = activity.window ?: return report(
            context = context,
            label = label,
            lines = listOf("the resumed activity has no window"),
            verdict = "SKIPPED",
            detail = "reason=no-window",
        )
        val decor = window.decorView
        val screen = runCatching { decor.height to decor.width }.getOrElse { 0 to 0 }
        val screenHeight = if (screen.first > 0) screen.first else activity.resources.displayMetrics.heightPixels
        val boundary = intent.getIntExtra(EXTRA_CONTENT_BOTTOM, 0)
            .takeIf { it > 0 }
            ?: defaultContentBottom(screenHeight)

        val snapshot = runCatching { read(decor, screen, boundary) }.getOrElse { error ->
            return report(
                context = context,
                label = label,
                lines = listOf("reading the semantics tree failed: ${error::class.java.name}: ${error.message}"),
                verdict = "FAIL",
                detail = "reason=${error::class.java.simpleName}",
            )
        }

        report(
            context = context,
            label = label,
            lines = render(
                label = label,
                activity = activity,
                screen = screen,
                boundary = boundary,
                snapshot = snapshot,
                forceA11y = intent.getBooleanExtra(EXTRA_FORCE_A11Y, false),
            ),
            verdict = snapshot.verdict.name,
            detail = "merged=${snapshot.contentMerged} unmerged=${snapshot.contentUnmerged} boundary=$boundary views=${snapshot.viewCount}",
        )
    }

    /**
     * The default split between content and bars.
     *
     * The mini player plus the bottom bars occupy roughly the last fifth of the screen on every
     * layout Hush ships, so that is where the line is drawn when the caller does not name one. It is
     * a default and not a fact about the device: the report always prints the boundary it used and
     * the per-band distribution beside the counts, so the reader can see where the nodes really are.
     */
    private fun defaultContentBottom(screenHeight: Int): Int = (screenHeight * BARS_FRACTION).toInt()

    /** Everything the report is built from, measured once, on the main thread. */
    private data class Snapshot(
        val viewCount: Int,
        val nodes: List<SemanticsProbeNode>,
        val truncated: Boolean,
        val contentBottom: Int,
        val screenHeight: Int,
        val screenWidth: Int,
        /** Merged-tree ids to the inputs the accessibility bridge decides from. */
        val internals: Map<Int, String> = emptyMap(),
        /** Merged-tree ids to their ancestor chain, for comparing an exposed branch with a dropped one. */
        val ancestry: Map<Int, String> = emptyMap(),
        /** The highest semantics id seen; the provider is scanned past it, never short of it. */
        val maxNodeId: Int = 0,
    ) {
        val contentMerged: Int get() = nodes.count { it.merged && SemanticsProbeReport.isContent(it, contentBottom) }
        val contentUnmerged: Int get() = nodes.count { !it.merged && SemanticsProbeReport.isContent(it, contentBottom) }
        val mergedTotal: Int get() = nodes.count { it.merged }
        val unmergedTotal: Int get() = nodes.count { !it.merged }
        val verdict: ContentSemantics get() = SemanticsProbeReport.verdict(contentMerged, contentUnmerged)
    }

    /**
     * Walks every `AndroidComposeView` in the window and reads both of its semantics trees.
     *
     * Called from `onReceive`, which the platform delivers on the main thread - the thread Compose's
     * semantics tree belongs to. Reading it from anywhere else would be reading a tree being
     * rewritten underneath the reader.
     */
    private fun read(
        decor: View,
        screen: Pair<Int, Int>,
        boundary: Int,
    ): Snapshot {
        val composeViews = mutableListOf<View>()
        collectComposeViews(decor, composeViews)
        val nodes = mutableListOf<SemanticsProbeNode>()
        val internals = mutableMapOf<Int, String>()
        val ancestry = mutableMapOf<Int, String>()
        var truncated = false
        for (view in composeViews) {
            val owner = semanticsOwnerOf(view) ?: continue
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            for (tree in listOf(true to owner.rootSemanticsNode, false to owner.unmergedRootSemanticsNode)) {
                val walked = walk(
                    node = tree.second,
                    merged = tree.first,
                    depth = 0,
                    offsetX = location[0],
                    offsetY = location[1],
                    into = nodes,
                    internals = internals,
                    ancestry = ancestry,
                )
                if (!walked) truncated = true
            }
        }
        return Snapshot(
            viewCount = composeViews.size,
            nodes = nodes,
            truncated = truncated,
            contentBottom = boundary,
            screenHeight = screen.first,
            screenWidth = screen.second,
            internals = internals,
            ancestry = ancestry,
            maxNodeId = nodes.maxOfOrNull { it.id } ?: 0,
        )
    }

    /**
     * Every view that hosts a composition, nested ones included - a dialog or a popup hosts its own.
     *
     * The host class and the interface it implements are both Kotlin-internal to Compose, so a
     * probe in app code cannot name either. It can ask what a view *offers* instead: a view that
     * exposes a semantics owner is hosting a composition, whatever it calls itself.
     */
    private fun collectComposeViews(
        view: View,
        into: MutableList<View>,
    ) {
        if (semanticsOwnerOf(view) != null) into += view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) collectComposeViews(view.getChildAt(index), into)
        }
    }

    /**
     * The semantics owner a view exposes, or null when it hosts no composition.
     *
     * One reflected call is the whole cost of Compose keeping these types internal, and it is spent
     * once per view *class* rather than once per view: the method is cached by class name, so a
     * screenful of rows costs a map lookup each.
     */
    private fun semanticsOwnerOf(view: View): SemanticsOwner? {
        val name = view.javaClass.name
        val method = synchronized(ownerMethods) {
            if (ownerMethods.containsKey(name)) {
                ownerMethods[name]
            } else {
                runCatching { view.javaClass.getMethod(SEMANTICS_OWNER_METHOD) }
                    .getOrNull()
                    .also { ownerMethods[name] = it }
            }
        } ?: return null
        return runCatching { method.invoke(view) as? SemanticsOwner }.getOrNull()
    }

    /**
     * Flattens one semantics node and its descendants.
     *
     * Bounded, because the probe runs inside a broadcast receiver and a pathological tree must cost a
     * truncated report rather than a receiver the platform reclaims: a reclaimed run logs nothing at
     * all, which reads exactly like a probe that was never asked.
     *
     * @return false when the walk was cut short by [MAX_NODES].
     */
    private fun walk(
        node: SemanticsNode,
        merged: Boolean,
        depth: Int,
        offsetX: Int,
        offsetY: Int,
        into: MutableList<SemanticsProbeNode>,
        internals: MutableMap<Int, String>,
        ancestry: MutableMap<Int, String>,
    ): Boolean {
        if (into.size >= MAX_NODES) return false
        val bounds = node.boundsInRoot
        val described = describe(node.config)
        into += SemanticsProbeNode(
            merged = merged,
            id = node.id,
            depth = depth,
            left = (bounds.left + offsetX).toInt(),
            top = (bounds.top + offsetY).toInt(),
            right = (bounds.right + offsetX).toInt(),
            bottom = (bounds.bottom + offsetY).toInt(),
            text = described.first,
            keys = described.second,
            clickable = described.third,
        )
        // Only the merged tree is asked this: it is the one the bridge publishes from, and reading
        // two trees' internals would double the cost of the cheapest useful answer.
        if (merged) {
            internals[node.id] = SemanticsNodeInternals.describe(node)
            ancestry[node.id] = SemanticsNodeInternals.ancestry(node)
        }
        for (child in node.children) {
            if (!walk(child, merged, depth + 1, offsetX, offsetY, into, internals, ancestry)) return false
        }
        return true
    }

    /**
     * A node's own words and the semantics it carries.
     *
     * Read off the configuration's entries rather than through typed getters, because the probe has
     * to name *whatever* a node carries - a node whose only semantics is a scroll action is exactly
     * the evidence that a list is present, and a probe that only looked for text would miss it.
     */
    private fun describe(config: SemanticsConfiguration): Triple<String, String, Boolean> {
        var text = ""
        var clickable = false
        val keys = mutableListOf<String>()
        for ((key, value) in config) {
            when (key.name) {
                "Text" -> if (text.isEmpty()) text = textOf(value)
                "EditableText" -> if (text.isEmpty()) text = textOf(value)
                "ContentDescription" -> if (text.isEmpty()) text = textOf(value)
                "OnClick" -> clickable = true
                else -> keys += key.name
            }
        }
        return Triple(text, keys.joinToString("|"), clickable)
    }

    private fun textOf(value: Any?): String =
        when (value) {
            is AnnotatedString -> value.text
            is List<*> -> value.joinToString(" ") { element -> textOf(element) }
            else -> value?.toString().orEmpty()
        }

    /** The report body: what was measured, where the boundary was, and what each side held. */
    private fun render(
        label: String,
        activity: Activity,
        screen: Pair<Int, Int>,
        boundary: Int,
        snapshot: Snapshot,
        forceA11y: Boolean = false,
    ): List<String> {
        val contentNodes = snapshot.nodes.filter { SemanticsProbeReport.isContent(it, boundary) }

        val barNodes = snapshot.nodes.filterNot { SemanticsProbeReport.isContent(it, boundary) }
        val lines = mutableListOf<String>()
        lines += "label=${label.ifEmpty { "-" }}"
        lines += "time=${STAMP.format(Date())}"
        lines += "package=${BuildConfig.APPLICATION_ID}"
        lines += "device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.SDK_INT}"
        lines += "activity=${activity.javaClass.name}"
        // hasWindowFocus is reported because a probe can only read a live composition: a report whose
        // activity was not on screen may describe the screen the app was last showing.
        lines += "window=${screen.second}x${screen.first} boundary=$boundary focus=${activity.hasWindowFocus()} " +
            "(a node above $boundary counts as content)"
        lines += "composeViews=${snapshot.viewCount}"
        lines += "nodes: merged=${snapshot.mergedTotal} unmerged=${snapshot.unmergedTotal} truncated=${snapshot.truncated}"
        lines += "content (top < $boundary): merged=${snapshot.contentMerged} unmerged=${snapshot.contentUnmerged}"
        lines += "bars (top >= $boundary): merged=${snapshot.mergedTotal - snapshot.contentMerged} " +
            "unmerged=${snapshot.unmergedTotal - snapshot.contentUnmerged}"
        lines += "merged y bands: ${SemanticsProbeReport.bandLine(snapshot.nodes.filter { it.merged }, screen.first)}"
        lines += "unmerged y bands: ${SemanticsProbeReport.bandLine(snapshot.nodes.filterNot { it.merged }, screen.first)}"
        lines += "sample content nodes (max ${SemanticsProbeReport.SAMPLE_LIMIT}):"
        if (contentNodes.isEmpty()) {
            lines += "  none"
        } else {
            contentNodes.take(SemanticsProbeReport.SAMPLE_LIMIT).forEach { lines += "  " + SemanticsProbeReport.line(it) }
        }
        lines += "sample bar nodes (max ${BAR_SAMPLE_LIMIT}):"
        barNodes.take(BAR_SAMPLE_LIMIT).forEach { lines += "  " + SemanticsProbeReport.line(it) }
        lines += hostState(activity.window?.decorView)
        lines += whySection(contentNodes, barNodes, snapshot)
        lines += providerEvidence(activity.window?.decorView, boundary, snapshot, contentNodes, barNodes, forceA11y)
        return lines
    }

    /**
     * Why a node can be in the tree and yet unreachable to the platform.
     *
     * The bridge drops nodes rather than publishing them, so a screen that a person can see and an
     * accessibility service cannot is answered by naming the inputs each node presents - placement,
     * attachment, the three kinds of bounds, and the flags merging and clearing set. Printed for the
     * content that is supposed to be visible and for a bar node known to reach the platform, so the
     * two can be compared input by input.
     */
    private fun whySection(
        contentNodes: List<SemanticsProbeNode>,
        barNodes: List<SemanticsProbeNode>,
        snapshot: Snapshot,
    ): List<String> {
        val out = mutableListOf<String>()
        // The nodes that carry text are the ones worth explaining: a container tells nothing, while
        // "Your Library" failing to reach the platform is the whole complaint.
        val content = contentNodes.filter { it.merged && it.text.isNotEmpty() }.take(WHY_SAMPLE)
            .ifEmpty { contentNodes.filter { it.merged }.take(WHY_SAMPLE) }
        val bars = barNodes.filter { it.merged && it.text.isNotEmpty() }.take(2)
        // A node the bridge counts as coverage has to be important for accessibility itself.
        val importantIds = snapshot.internals.filterValues { it.contains("important=true") }.keys
        out += "why: content (in the tree, apparently not in the platform's)"
        if (content.isEmpty()) out += "  none in the tree either"
        content.forEach { node ->
            out += "  id=${node.id} \"${node.text.take(30)}\" ${snapshot.internals[node.id] ?: "internals-unavailable"}"
            out += "    ancestry=${snapshot.ancestry[node.id] ?: "-"}"
            val coverers = SemanticsProbeReport.coverers(
                target = node,
                all = snapshot.nodes,
                ancestorIds = SemanticsProbeReport.ancestryIds(snapshot.ancestry[node.id]),
                importantIds = importantIds,
            )
            // Named, not guessed: whatever is drawn over the content is what puts it out of the
            // accessibility tree, and the smallest such node is the most specific answer.
            coverers.forEach { cover ->
                out += "    covered-by ${SemanticsProbeReport.line(cover)} " +
                    "${snapshot.internals[cover.id] ?: ""}"
                // Naming the coverer by what it holds: the fix goes where this subtree is declared,
                // so an id and a set of keys are not enough to act on.
                val held = snapshot.nodes.filter { candidate ->
                    candidate.merged && candidate.id != cover.id &&
                        SemanticsProbeReport.ancestryIds(snapshot.ancestry[candidate.id]).contains(cover.id)
                }
                out += "      holds: " + (
                    held.filter { it.text.isNotEmpty() }.take(HOLDS_SAMPLE)
                        .joinToString("; ") { "id=${it.id} \"${it.text.take(26)}\"" }
                        .ifEmpty { "nothing that says anything (${held.size} nodes)" }
                    )
            }
            if (coverers.isEmpty()) out += "    covered-by: nothing important contains it"
        }
        out += "why: bars (in the tree and in the platform's - the control)"
        bars.forEach { node ->
            out += "  id=${node.id} \"${node.text.take(30)}\" ${snapshot.internals[node.id] ?: "internals-unavailable"}"
            out += "    ancestry=${snapshot.ancestry[node.id] ?: "-"}"
        }
        return out
    }

    /** The hosting view's own accessibility state, which decides the tree beneath it. */
    private fun hostState(decor: View?): List<String> {
        val view = findHostView(decor) ?: return listOf("host: no view in the window hosts a composition")
        return buildList {
            add(
                "host: view=${view.javaClass.name} importantForAccessibility=${view.importantForAccessibility} " +
                    "visibility=${view.visibility} alpha=${view.alpha} provider=${view.accessibilityNodeProvider != null} " +
                    "size=${view.width}x${view.height}",
            )
            addAll(SemanticsNodeInternals.delegateInventory(view))
        }
    }

    /**
     * What the platform's own view of this tree holds, asked through the provider it reads from.
     *
     * The merged tree says the content describes itself. Whether the *platform* can act on that is a
     * second question, asked one layer further out: `View.getAccessibilityNodeProvider()` is the
     * route `uiautomator` and every accessibility service go through, so asking it for the very ids
     * this probe just found separates "Compose described it" from "the platform received it".
     *
     * A control is included on purpose. Bar nodes are known to reach the platform - `uiautomator`
     * prints them - so if the control answers and the content does not, the difference is about the
     * nodes and not about how ids are addressed; if neither answers, the ids are being asked in the
     * wrong scheme and the run says so instead of blaming the content.
     */
    private fun providerEvidence(
        decor: View?,
        boundary: Int,
        snapshot: Snapshot,
        contentNodes: List<SemanticsProbeNode>,
        barNodes: List<SemanticsProbeNode>,
        forceA11y: Boolean,
    ): List<String> {
        val view = findHostView(decor) ?: return listOf("provider: no view in the window exposes one")
        val out = mutableListOf<String>()
        // Compose builds the platform tree from what it has published, which a screen reader normally
        // drives. Forcing that on removes the question of whether the delegate had been told to work
        // at all - the control Compose's own tests use.
        if (forceA11y) {
            val forced = runCatching {
                view.javaClass.getMethod(FORCE_A11Y_METHOD, Boolean::class.javaPrimitiveType).invoke(view, true)
            }
            out += "provider: $FORCE_A11Y_METHOD(true) ok=${forced.isSuccess}"
        }
        val provider = view.accessibilityNodeProvider
            ?: return out + "provider: the hosting view exposes none"
        // Wide enough to cover every id the tree itself reported, because an id the scan never asked
        // about reads exactly like a node the bridge dropped.
        val ceiling = maxOf(snapshot.maxNodeId + PROVIDER_ID_MARGIN, PROVIDER_ID_FLOOR).coerceAtMost(PROVIDER_ID_CEILING)
        val started = System.currentTimeMillis()
        val content = mutableListOf<String>()
        val bars = mutableListOf<String>()
        val failures = mutableListOf<String>()
        var resolved = 0
        var asked = 0
        for (id in 0 until ceiling) {
            if (System.currentTimeMillis() - started > PROVIDER_SCAN_BUDGET_MS) {
                out += "provider: scan stopped after ${PROVIDER_SCAN_BUDGET_MS}ms at id=$id"
                break
            }
            asked++
            val info = try {
                provider.createAccessibilityNodeInfo(id)
            } catch (error: Throwable) {
                if (failures.size < FAILURE_SAMPLE_LIMIT) {
                    failures += "id=$id ${error::class.java.name}: ${error.message?.take(80)}"
                }
                continue
            } ?: continue
            resolved++
            val bounds = android.graphics.Rect()
            info.getBoundsInScreen(bounds)
            val text = info.text?.toString().orEmpty().ifEmpty { info.contentDescription?.toString().orEmpty() }
            // A node the platform can act on has somewhere to be and something to say; an id the
            // provider knows nothing about comes back as an empty node rather than null, which is
            // exactly what has to be filtered out for these counts to mean anything.
            if (text.isEmpty() && bounds.isEmpty) continue
            val line = "bounds=$bounds text=\"${text.take(40)}\" visible=${info.isVisibleToUser}"
            if (bounds.top < boundary) content += "id=$id $line" else bars += "id=$id $line"
        }
        out += "provider: hostingView=${view.javaClass.name} window=0..$ceiling asked=$asked resolved=$resolved " +
            "failures=${failures.size} elapsedMs=${System.currentTimeMillis() - started}"
        failures.forEach { out += "  provider failure $it" }
        out += "provider: nodes the platform can act on - content=${content.size} bars=${bars.size}"
        content.take(PROVIDER_SAMPLE_LIMIT).forEach { out += "  provider content $it" }
        if (content.isEmpty()) out += "  provider content: none above $boundary"
        bars.take(PROVIDER_SAMPLE_LIMIT).forEach { out += "  provider bar $it" }
        // The cross-check that separates "the bridge dropped this node" from "this node was never
        // asked about": every content id the tree holds, asked of the provider by name.
        val contentIds = contentNodes.filter { it.merged }.map { it.id }
        val resolvedContentIds = contentIds.filter { id ->
            runCatching { provider.createAccessibilityNodeInfo(id) }.getOrNull() != null
        }
        out += "provider: merged content ids that resolve=${resolvedContentIds.size}/${contentIds.size}"
        contentNodes.filter { it.merged && it.text.isNotEmpty() }.take(PROVIDER_SAMPLE_LIMIT).forEach { node ->
            val info = runCatching { provider.createAccessibilityNodeInfo(node.id) }.getOrNull()
            val bounds = android.graphics.Rect()
            info?.getBoundsInScreen(bounds)
            out += "  provider byId id=${node.id} \"${node.text.take(28)}\" resolved=${info != null} " +
                "bounds=$bounds text=\"${info?.text ?: ""}\" visible=${info?.isVisibleToUser} " +
                "inBarsOfPlatformTree=${bars.any { it.startsWith("id=${node.id} ") }}"
        }
        return out
    }

    /** The view whose provider `uiautomator` reads: the composition host found earlier. */
    private fun findHostView(view: View?): View? {
        if (view == null) return null
        if (semanticsOwnerOf(view) != null) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findHostView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    /**
     * Writes the report and logs it.
     *
     * The file is the contract, not logcat: on the phone this probe was written for, an app's own
     * logcat output is dropped while the framework's still passes through, so a probe that only
     * logged would work and report nothing. `run-as` reads the file on any debug build, which is the
     * one build this receiver exists in.
     */
    private fun report(
        context: Context,
        label: String,
        lines: List<String>,
        verdict: String,
        detail: String,
    ) {
        val stamp = STAMP.format(Date())
        val body = buildString {
            append("Hush semantics probe\n")
            append("label=")
            append(label.ifEmpty { "-" })
            append('\n')
            append("time=")
            append(stamp)
            append('\n')
            lines.forEach { append(it).append('\n') }
            append("semantics-probe step=content label=")
            append(label.ifEmpty { "-" })
            append(" verdict=")
            append(verdict)
            append(' ')
            append(detail)
            append('\n')
            append("meaning=")
            append(
                runCatching { ContentSemantics.valueOf(verdict) }
                    .map { SemanticsProbeReport.explain(it) }
                    .getOrElse { "the probe did not reach a verdict; see the line above" },
            )
            append('\n')
        }
        Log.d(TAG, "semantics-probe step=content label=${label.ifEmpty { "-" }} verdict=$verdict $detail")
        // The write happens off the main thread but the receiver is not finished until it has, so a
        // shell that reads the file after the broadcast returns reads this run and never the last
        // one. The file matters more than logcat here: the device this probe was written for drops
        // an app's own log output while passing the framework's through.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching {
                    val file = reportFile(context)
                    file.parentFile?.mkdirs()
                    file.writeText(body)
                }.onFailure { Log.d(TAG, "could not write the report: ${it.message}") }
                Log.d(TAG, body)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "SemanticsProbe"

        /** Where the report lands, relative to the app's files directory (readable with `run-as`). */
        const val REPORT_PATH = "debug/semantics-probe.txt"

        const val ACTION_PROBE = "app.hush.music.action.SEMANTICS_PROBE"
        const val EXTRA_LABEL = "label"
        const val EXTRA_CONTENT_BOTTOM = "content-bottom"
        const val EXTRA_FORCE_A11Y = "force-a11y"

        private const val BAR_SAMPLE_LIMIT = 10

        /** The accessor Compose's view hosts expose. Public on the class, internal to Kotlin. */
        private const val SEMANTICS_OWNER_METHOD = "getSemanticsOwner"

        /** Cache of the accessor per view class name; null means the class does not offer one. */
        private val ownerMethods = HashMap<String, Method?>()

        /** How many samples of the platform's tree are quoted per region. */
        private const val PROVIDER_SAMPLE_LIMIT = 5

        /** How many nodes of each region are explained input by input. */
        private const val WHY_SAMPLE = 5

        /** How many of a coverer's own children are named, to identify it. */
        private const val HOLDS_SAMPLE = 8

        /** How many provider failures are quoted; the count is always reported. */
        private const val FAILURE_SAMPLE_LIMIT = 5

        /** Scanned past the tree's own highest id, so a node can never be missed by the window. */
        private const val PROVIDER_ID_MARGIN = 512

        private const val PROVIDER_ID_FLOOR = 2_000
        private const val PROVIDER_ID_CEILING = 8_000

        /** Kept inside what a broadcast receiver may hold, so the scan cannot outlive its run. */
        private const val PROVIDER_SCAN_BUDGET_MS = 2_500L

        /** Compose's own test hook for building the platform tree without a screen reader attached. */
        private const val FORCE_A11Y_METHOD = "forceAccessibilityForTesting"

        /** A tree this large is not a screen; the walk stops rather than holding the receiver open. */
        private const val MAX_NODES = 4_000

        /** Where the content area ends by default: the player and bars take the rest. */
        private const val BARS_FRACTION = 0.81

        private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        fun reportFile(context: Context): File = File(context.filesDir, REPORT_PATH)
    }
}
