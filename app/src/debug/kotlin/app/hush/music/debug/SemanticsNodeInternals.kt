/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.debug

import androidx.compose.ui.semantics.SemanticsNode
import java.lang.reflect.Method

/**
 * The inputs Compose's accessibility bridge decides a node's fate from. **Debug builds only.**
 *
 * A node being in the semantics tree is not the same as the platform being able to act on it:
 * `AndroidComposeViewAccessibilityDelegateCompat` builds the tree an accessibility service reads,
 * and it drops nodes - marking them invisible, or never publishing them at all. When a whole screen
 * is visible to a person and absent to a service, the useful question is *which input* made the
 * bridge drop it, and every candidate is readable here:
 *
 *  - `placed` / `attached` — a layout node that was measured but never placed has no position to
 *    report, and the bridge has nothing to publish ([androidx.compose.ui.layout.LayoutInfo]).
 *  - `boundsInRoot` vs `unclipped` vs `window` — the bridge works from *adjusted* bounds (bounds
 *    clipped by every ancestor), and calls `setInvisibleIfEmptyBounds`, so a node that is laid out
 *    but clipped to nothing is indistinguishable from one that was never described.
 *  - `important` — the semantics configuration's own "worth reporting" flag.
 *  - `fake` — a node the tree uses internally to hold merging, not a node of its own.
 *  - `clearing` / `merging` — a node that clears its own semantics, or merges its descendants into
 *    itself, changes what the bridge publishes for everything beneath it.
 *
 * The three `$ui` members are Kotlin-internal, so they are read reflectively - the same bargain the
 * probe already makes for the semantics owner, and for the same reason: the alternative is not
 * asking at all.
 */
object SemanticsNodeInternals {

    private val reflective = HashMap<String, Method?>()

    /** One line naming every input, so a dropped node can be explained rather than only observed. */
    fun describe(node: SemanticsNode): String {
        val layout = runCatching { node.layoutInfo }.getOrNull()
        val parts = mutableListOf<String>()
        parts += "placed=${layout?.isPlaced ?: "?"}"
        parts += "attached=${layout?.isAttached ?: "?"}"
        parts += "rootBounds=${node.boundsInRoot}"
        parts += "unclipped=${call(node, "getUnclippedBoundsInRoot\$ui")}"
        parts += "windowBounds=${runCatching { node.boundsInWindow }.getOrNull()}"
        parts += "transparent=${call(node, "isTransparent\$ui")}"
        parts += "fake=${call(node, "isFake\$ui")}"
        parts += "important=${call(node.config, "containsImportantForAccessibility\$ui")}"
        parts += "clearing=${node.config.isClearingSemantics}"
        parts += "merging=${node.config.isMergingSemanticsOfDescendants}"
        parts += "parent=${runCatching { node.parent?.id }.getOrNull() ?: "-"}"
        return parts.joinToString(" ")
    }

    /** The chain of ancestor ids, so an exposed branch and a dropped one can be compared path by path. */
    fun ancestry(node: SemanticsNode): String {
        val ids = mutableListOf<Int>()
        var current: SemanticsNode? = node
        while (ids.size < ANCESTRY_LIMIT) {
            val walked = current ?: break
            ids += walked.id
            current = runCatching { walked.parent }.getOrNull()
        }
        return ids.joinToString("<-")
    }

    /** A Kotlin-internal member by its JVM name, cached; null when absent or not callable. */
    private fun call(
        target: Any,
        name: String,
    ): Any? {
        val method = synchronized(reflective) {
            if (reflective.containsKey(name)) {
                reflective[name]
            } else {
                runCatching { target.javaClass.getMethod(name) }
                    .getOrNull()
                    .also { reflective[name] = it }
            }
        } ?: return "?"
        return runCatching { method.invoke(target) }.getOrNull() ?: "?"
    }

    private const val ANCESTRY_LIMIT = 12

    /**
     * What the accessibility bridge itself is holding, read off its own state.
     *
     * Everything else the probe reports is an *input* to the bridge. This is the bridge's own book-keeping,
     * and it separates the two ways a visible screen can go missing: a node that was never registered
     * (the bridge never learned about it, an update was missed) versus one that is registered and then
     * left unpublished or marked invisible (a visibility or importance decision). Those need different
     * fixes, and from outside the process they look identical.
     *
     * The bridge is Kotlin-internal, so it is found by what it *is* - the field on the hosting view
     * whose value is named like the delegate - and then whatever map it keeps is reported by size,
     * with a sample of the ids in it.
     */
    fun delegateInventory(hostView: android.view.View): List<String> {
        val delegate = findDelegate(hostView) ?: return listOf("delegate: not reachable on ${hostView.javaClass.simpleName}")
        val out = mutableListOf("delegate: ${delegate.javaClass.simpleName}")
        var reported = 0
        for (field in delegate.javaClass.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
            val value = runCatching {
                field.isAccessible = true
                field.get(delegate)
            }.getOrNull() ?: continue
            val sizeOf = sizeAccessor(value)
            if (sizeOf == null) {
                out += "delegate field ${field.name}: ${value.javaClass.simpleName}"
                continue
            }
            val keys = mapKeys(value)
            out += "delegate field ${field.name}: ${value.javaClass.simpleName} size=$sizeOf" +
                if (keys == null) "" else " ids=$keys"
            if (++reported >= DELEGATE_FIELD_LIMIT) break
        }
        return out
    }

    private fun findDelegate(hostView: android.view.View): Any? {
        var type: Class<*>? = hostView.javaClass
        while (type != null) {
            for (field in type.declaredFields) {
                val name = field.type.name
                if (!name.contains("AccessibilityDelegateCompat")) continue
                runCatching {
                    field.isAccessible = true
                    val value = field.get(hostView)
                    // Lazy delegates are held in a wrapper; unwrap one level when asked for a value.
                    return value ?: runCatching {
                        value?.javaClass?.getMethod("getValue")?.let { getter ->
                            getter.isAccessible = true
                            getter.invoke(value)
                        }
                    }.getOrNull()
                }.onSuccess { if (it != null) return it }
            }
            type = type.superclass
        }
        return null
    }

    /** A collection's element count, whatever collection it is (kotlin.collections and android\u002ex are not the same type). */
    private fun sizeAccessor(value: Any): Int? {
        if (value is Collection<*>) return value.size
        if (value is Map<*, *>) return value.size
        for (name in listOf("size", "getSize")) {
            val size = runCatching {
                value.javaClass.getMethod(name).also { it.isAccessible = true }.invoke(value) as? Int
            }.getOrNull()
            if (size != null) return size
        }
        return null
    }

    /**
     * The ids a collection holds, when it can enumerate itself.
     *
     * `androidx.collection`'s int-keyed maps are not `Map`s to the JVM, but they do expose
     * `forEach(Function2)`, which is enough to ask what the accessibility bridge has published -
     * the one question that separates "never learned about it" from "learned and dropped it".
     */
    private fun mapKeys(value: Any): List<Int>? {
        val method = runCatching {
            value.javaClass.getMethod("forEach", kotlin.jvm.functions.Function2::class.java)
        }.getOrNull() ?: return null
        val keys = mutableListOf<Int>()
        // A Kotlin lambda *is* the Function2 the map expects, so it can be handed straight to the
        // reflective call; an explicit implementation is not needed for interfaces like this one.
        val collect: (Any?, Any?) -> Unit = { key, _ ->
            if (keys.size < KEY_SAMPLE_LIMIT && key is Int) keys += key
        }
        runCatching { method.invoke(value, collect) }
        return keys.sorted()
    }

    private const val DELEGATE_FIELD_LIMIT = 20
    private const val KEY_SAMPLE_LIMIT = 40
}
