/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probe's verdict is the whole answer it exists to give, so the two rules behind it are pinned
 * here rather than only exercised on a phone: which nodes count as the screen's content, and what a
 * pair of content counts means.
 *
 * The distinction that matters most is between [ContentSemantics.ABSENT] and
 * [ContentSemantics.MERGED_AWAY]: they describe faults on opposite sides of the app - no semantics
 * were written, versus semantics that never reached the accessibility tree - and a probe that
 * conflated them would send the fix to the wrong place.
 */
class SemanticsProbeReportTest {

    private fun node(
        top: Int,
        merged: Boolean = true,
        id: Int = 1,
    ) = SemanticsProbeNode(
        merged = merged,
        id = id,
        depth = 1,
        left = 0,
        top = top,
        right = 100,
        bottom = top + 100,
        text = "",
        keys = "",
        clickable = false,
    )

    @Test
    fun `a node above the boundary is content and one below it is not`() {
        val boundary = 2605
        assertTrue(SemanticsProbeReport.isContent(node(top = 0), boundary))
        assertTrue(SemanticsProbeReport.isContent(node(top = boundary - 1), boundary))
        assertFalse(SemanticsProbeReport.isContent(node(top = boundary), boundary))
        assertFalse(SemanticsProbeReport.isContent(node(top = 3200), boundary))
    }

    @Test
    fun `content in the merged tree is present`() {
        assertEquals(ContentSemantics.PRESENT, SemanticsProbeReport.verdict(contentMerged = 41, contentUnmerged = 96))
    }

    @Test
    fun `content nodes that merging removed are their own verdict, not absent`() {
        assertEquals(ContentSemantics.MERGED_AWAY, SemanticsProbeReport.verdict(contentMerged = 0, contentUnmerged = 14))
    }

    @Test
    fun `nothing at all is absent`() {
        assertEquals(ContentSemantics.ABSENT, SemanticsProbeReport.verdict(contentMerged = 0, contentUnmerged = 0))
    }

    @Test
    fun `each verdict explains itself and they do not read alike`() {
        val explanations = ContentSemantics.entries.map { SemanticsProbeReport.explain(it) }
        assertEquals(explanations.size, explanations.toSet().size)
        // Which side of the app the fault is on is the one thing an explanation must not fudge.
        assertTrue(SemanticsProbeReport.explain(ContentSemantics.PRESENT).contains("bridge"))
        assertTrue(SemanticsProbeReport.explain(ContentSemantics.ABSENT).contains("no semantics"))
    }

    @Test
    fun `every node lands in exactly one band`() {
        val screen = 3200
        val nodes = listOf(node(top = 0), node(top = 0), node(top = 3199), node(top = 1600))
        val bands = SemanticsProbeReport.bands(nodes, screen)
        assertEquals(SemanticsProbeReport.BAND_COUNT, bands.size)
        assertEquals(nodes.size, bands.sum())
        assertEquals(2, bands.first())
    }

    @Test
    fun `a node past the last band still lands in it`() {
        val bands = SemanticsProbeReport.bands(listOf(node(top = 99_999)), screenHeight = 3200)
        assertEquals(1, bands.last())
        assertEquals(1, bands.sum())
    }

    @Test
    fun `bands of a zero height screen are reported as unavailable rather than crashing`() {
        assertTrue(SemanticsProbeReport.bands(listOf(node(top = 10)), screenHeight = 0).isEmpty())
        assertEquals("unavailable", SemanticsProbeReport.bandLine(listOf(node(top = 10)), screenHeight = 0))
    }

    private fun rect(
        id: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        text: String = "",
    ) = SemanticsProbeNode(
        merged = true,
        id = id,
        depth = 3,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        text = text,
        keys = "",
        clickable = false,
    )

    @Test
    fun `a full-screen important node covers the content drawn under it`() {
        val content = rect(id = 372, left = 64, top = 424, right = 416, bottom = 564, text = "Energize")
        val sheetHost = rect(id = 26, left = 0, top = 0, right = 1440, bottom = 3216)
        assertEquals(
            listOf(26),
            SemanticsProbeReport.coverers(
                target = content,
                all = listOf(sheetHost, content),
                ancestorIds = emptySet(),
                importantIds = setOf(26),
            ).map { it.id },
        )
    }

    @Test
    fun `a node that is not important for accessibility is not coverage`() {
        val content = rect(id = 372, left = 64, top = 424, right = 416, bottom = 564)
        val sheetHost = rect(id = 26, left = 0, top = 0, right = 1440, bottom = 3216)
        assertTrue(
            SemanticsProbeReport.coverers(
                target = content,
                all = listOf(sheetHost, content),
                ancestorIds = emptySet(),
                importantIds = emptySet(),
            ).isEmpty(),
        )
    }

    @Test
    fun `an ancestor is never reported as covering its own descendant`() {
        val content = rect(id = 372, left = 64, top = 424, right = 416, bottom = 564)
        val parent = rect(id = 227, left = 0, top = 0, right = 1440, bottom = 3216)
        assertTrue(
            SemanticsProbeReport.coverers(
                target = content,
                all = listOf(parent, content),
                ancestorIds = setOf(227),
                importantIds = setOf(227),
            ).isEmpty(),
        )
    }

    @Test
    fun `the most specific claimant is reported first`() {
        val content = rect(id = 372, left = 64, top = 424, right = 416, bottom = 564)
        val wholeScreen = rect(id = 26, left = 0, top = 0, right = 1440, bottom = 3216)
        val card = rect(id = 300, left = 0, top = 400, right = 800, bottom = 700)
        assertEquals(
            listOf(300, 26),
            SemanticsProbeReport.coverers(
                target = content,
                all = listOf(wholeScreen, card, content),
                ancestorIds = emptySet(),
                importantIds = setOf(26, 300),
            ).map { it.id },
        )
    }

    @Test
    fun `a chain of ancestors reads back as its ids`() {
        assertEquals(
            setOf(372, 229, 227, 148, 19, 16, 1),
            SemanticsProbeReport.ancestryIds("372<-229<-227<-148<-19<-16<-1"),
        )
        assertEquals(emptySet<Int>(), SemanticsProbeReport.ancestryIds(null))
    }

    @Test
    fun `a clipped label is marked on its line and a fitting one is not`() {
        val clipped = node(top = 120, id = 7).copy(text = "0:01", textFit = TextFit.CLIPPED)
        val fits = clipped.copy(textFit = TextFit.FITS)
        val ellipsized = clipped.copy(textFit = TextFit.ELLIPSIZED)
        assertTrue(SemanticsProbeReport.line(clipped).contains(" clipped"))
        // The two that are not a fault must not be marked: an ellipsized label is the app saying the
        // label was shortened, and a label that fits has nothing to report at all.
        assertFalse(SemanticsProbeReport.line(fits).contains("clipped"))
        assertFalse(SemanticsProbeReport.line(ellipsized).contains("clipped"))
    }

    @Test
    fun `a label nothing measured is not counted as one that fits`() {
        // The distinction the whole check rests on: "not measured" and "it fits" must not read
        // alike, or a probe that failed to ask would report a clean screen.
        val line = SemanticsProbeReport.textFitLine(listOf(node(top = 10), node(top = 20)))
        assertTrue(line.contains("no label was measured"))
        assertFalse(line.contains("0 labels measured"))
    }

    @Test
    fun `the text-fit line counts fits, ellipses and silent clips apart`() {
        val nodes = listOf(
            node(top = 10).copy(text = "0:01", textFit = TextFit.FITS),
            node(top = 20).copy(text = "a long title", textFit = TextFit.ELLIPSIZED),
            node(top = 30).copy(text = "2:48", textFit = TextFit.CLIPPED),
            node(top = 40),
        )
        val line = SemanticsProbeReport.textFitLine(nodes)
        assertTrue(line.contains("3 labels measured"))
        assertTrue(line.contains("1 fit"))
        assertTrue(line.contains("1 ellipsized"))
        assertTrue(line.contains("1 clipped"))
        // The clipped label is quoted, because the point of the line is to name what was lost.
        assertTrue(line.contains("2:48"))
    }

    @Test
    fun `a one-line label narrower than its words is clipped, and one that fills its box is not`() {
        // The negative control that proved the check, as a test: a clock pinned to a 30dp box needed
        // 200px and was given 136, and on the device it drew "0:1".
        assertEquals(
            TextFit.CLIPPED,
            SemanticsProbeReport.textFitOf(
                ellipsized = false, cannotWrap = true, needed = 200f, given = 136f,
            ),
        )
        // The other half, and the one that kept the check honest: a label measured to its own
        // intrinsic width comes back with `given` at or above `needed`, and a tenth of a pixel of
        // slack must not read as a lost character. 40 labels on a player at a 1920x720dp window sat
        // at exactly this boundary, the tightest of them with no slack at all.
        assertEquals(
            TextFit.FITS,
            SemanticsProbeReport.textFitOf(
                ellipsized = false, cannotWrap = true, needed = 273f, given = 273f,
            ),
        )
        assertEquals(
            TextFit.FITS,
            SemanticsProbeReport.textFitOf(
                ellipsized = false, cannotWrap = true, needed = 272f, given = 273f,
            ),
        )
    }

    @Test
    fun `a label allowed to wrap is not a label that lost its words`() {
        // A wrapped paragraph needs far more width than the box it folded into - that is the layout
        // working. Reading it as a clip is how a check fires on every screen it is run against.
        assertEquals(
            TextFit.FITS,
            SemanticsProbeReport.textFitOf(
                ellipsized = false, cannotWrap = false, needed = 2250f, given = 695f,
            ),
        )
    }

    @Test
    fun `an ellipsis outranks a clip even when the words are wider than the box`() {
        // Both are true of a label the app chose to shorten and had to squeeze; the visible kind is
        // the one to report, so it is the one that decides.
        assertEquals(
            TextFit.ELLIPSIZED,
            SemanticsProbeReport.textFitOf(
                ellipsized = true, cannotWrap = true, needed = 1322f, given = 695f,
            ),
        )
    }

    @Test
    fun `a line names the tree, the place and the words`() {
        val line = SemanticsProbeReport.line(
            node(top = 120, id = 7).copy(
                merged = false,
                text = "Chandanamani",
                keys = "ScrollBy|OnClick",
                clickable = true,
            ),
        )
        assertTrue(line.contains("unmerged"))
        assertTrue(line.contains("at=120,0"))
        assertTrue(line.contains("clickable"))
        assertTrue(line.contains("Chandanamani"))
        assertTrue(line.contains("ScrollBy|OnClick"))
    }
}
