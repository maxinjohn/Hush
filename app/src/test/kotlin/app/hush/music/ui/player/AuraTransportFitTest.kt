/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what happens to the Aura transport when the column it is drawn in is narrower than the row.
 *
 * The bug this exists for was measured on a device, at a 600x400dp landscape window: previous and
 * play were drawn at their full 68dp and 88dp while **next** came out 48x68dp. The row was not
 * clipped and nothing overlapped - a Compose `Row` measures its children in order and gives each
 * what is left, so the last child simply came out smaller, and the row read as "the next button is
 * shrunk". The preset is picked from the column's *height*, so the column that cannot pay for it is
 * not a hypothetical: it is any window tall enough for the roomy preset and narrow enough for none
 * of them, which is exactly what a car head unit, a split screen or a desktop window can be.
 *
 * Both halves - the widths and the fit - are arithmetic, so both are pinned here rather than by
 * looking at a device. The on-device half is `scripts/aura-strip-size-sweep.py`, which asserts the
 * row's geometry by name at every window size in its matrix.
 */
class AuraTransportFitTest {

    /** The three presets, by the names the players use for them. */
    private val roomy = AuraTransportSizes.Roomy
    private val medium = AuraTransportSizes.Medium
    private val tight = AuraTransportSizes.Tight
    private val portrait = AuraTransportSizes.Full

    @Test
    fun `every row width is its parts, halo wrapper included`() {
        // The wrapper around the play button is a real child, so a fit that forgot it would leave
        // the row a halo wider than the width it was fitted to - and the last button would be
        // squeezed by exactly that much, which is how this class of bug hides.
        assertEquals(
            68.dp * 2 + 88.dp + 12.dp + 22.dp * 2,
            roomy.rowWidth,
        )
        assertEquals("portrait's row is unchanged", 56.dp * 2 + 76.dp + 14.dp + 20.dp * 2, portrait.rowWidth)
    }

    @Test
    fun `a column that can pay for the preset keeps it exactly`() {
        // Every portrait window and every wide landscape one lands here: nothing moves by a dp.
        assertEquals(roomy, roomy.fittedTo(roomy.rowWidth))
        assertEquals(roomy, roomy.fittedTo(roomy.rowWidth + 100.dp))
        assertEquals(medium, medium.fittedTo(452.dp))
        assertEquals(tight, tight.fittedTo(tight.rowWidth))
    }

    @Test
    fun `every row height is the play button's wrapper`() {
        // The same rule as the width: the wrapper around the play button is a real child and the
        // tallest one, so a fit that forgot the halo would deliver a row taller than the bound it
        // was fitted to - and the button would come out flattened by exactly that much.
        assertEquals(88.dp + 12.dp, roomy.rowHeight)
        assertEquals(76.dp + 14.dp, portrait.rowHeight)
        assertEquals(62.dp + 8.dp, tight.rowHeight)
    }

    @Test
    fun `a column too short for the preset scales the row instead of flattening the play button`() {
        // The measured case: on a 280x380dp portrait window the controls column measured the row at
        // 61dp of height, so the 76dp play button was drawn 76x61dp - an ellipse with the icon
        // undistorted inside it. Nothing clipped it; `Modifier.size` is coerced by the constraint.
        val column = 61.dp
        val fitted = portrait.fittedTo(300.dp, column)

        assertTrue("the row was not scaled", fitted.rowHeight < portrait.rowHeight)
        assertTrue("the fitted row is ${fitted.rowHeight}, column is $column", fitted.rowHeight <= column)
        // The button stays a circle: one value sizes both sides of it, and the wrapper follows it.
        assertEquals(fitted.playSize, fitted.rowHeight - fitted.haloReserve)
        assertEquals(
            portrait.playIcon / portrait.playSize,
            fitted.playIcon / fitted.playSize,
            0.0001f,
        )
        assertTrue("the side buttons are still tappable", fitted.prevNextSize > 24.dp)
    }

    @Test
    fun `the tighter of the two bounds is the one that sizes the row`() {
        // A window can be short and wide, or narrow and tall, and the row may only be as big as the
        // smaller of the two - fitting each axis on its own would leave it too big for the other.
        val byHeight = roomy.fittedTo(1000.dp, 50.dp)
        assertEquals(50.dp, byHeight.rowHeight)
        val byWidth = roomy.fittedTo(140.dp, 1000.dp)
        assertEquals(140.dp, byWidth.rowWidth)
        val both = roomy.fittedTo(140.dp, 50.dp)
        assertEquals(50.dp, both.rowHeight)
        assertTrue("the row is still within the width it was given", both.rowWidth <= 140.dp)
    }

    @Test
    fun `an unbounded measurement is not treated as a narrow column`() {
        // A row measured with no width limit - a wrapping parent - must keep its preset; scaling it
        // down there would shrink the transport for no reason at all.
        assertEquals(roomy, roomy.fittedTo(Dp.Infinity))
        assertEquals(portrait, portrait.fittedTo(Dp.Infinity))
        assertEquals("an unbounded height is not a short column either", roomy, roomy.fittedTo(2000.dp, Dp.Infinity))
    }

    @Test
    fun `a column too narrow for the preset scales the row instead of squeezing the last button`() {
        // The measured case: at a 600x400dp window the controls column gives the row 264dp, and the
        // roomy preset wants 280dp. Before the fit, `next` was 48x68dp against `previous`'s 68x68.
        val column = 264.dp
        val fitted = roomy.fittedTo(column)

        assertTrue("the row was not scaled", fitted.rowWidth < roomy.rowWidth)
        assertTrue("the fitted row is ${fitted.rowWidth}, column is $column", fitted.rowWidth <= column)
        // One value sizes both side buttons, which is what makes the measured asymmetry - next at
        // 48dp against previous's 68dp - unreachable rather than merely small.
        assertEquals(
            "the play button stays the biggest",
            roomy.playSize / roomy.prevNextSize,
            fitted.playSize / fitted.prevNextSize,
            0.0001f,
        )
        // The shrink the fix produces: the whole row, not one button.
        assertEquals(264.dp / 280.dp, fitted.prevNextSize / roomy.prevNextSize, 0.0001f)
        assertTrue("the side buttons are still tappable", fitted.prevNextSize > 40.dp)
    }

    @Test
    fun `scaling keeps the row's proportions`() {
        // The point of scaling rather than reflowing: the composition the presets were signed off
        // with is the composition every column gets. An icon that shrank faster than its button, or
        // a halo that did not shrank at all, would read as a different row at a different size.
        for (preset in listOf(portrait, roomy, medium, tight)) {
            for (factor in listOf(0.95f, 0.8f, 0.5f, 0.25f)) {
                val scaled = preset.scaled(factor)
                assertEquals((preset.rowWidth * factor).value, scaled.rowWidth.value, 0.0001f)
                assertEquals(
                    preset.prevNextIcon / preset.prevNextSize,
                    scaled.prevNextIcon / scaled.prevNextSize,
                    0.0001f,
                )
                assertEquals(
                    preset.playIcon / preset.playSize,
                    scaled.playIcon / scaled.playSize,
                    0.0001f,
                )
                assertEquals(
                    preset.haloReserve / preset.playSize,
                    scaled.haloReserve / scaled.playSize,
                    0.0001f,
                )
            }
        }
    }

    @Test
    fun `no width a real window can have leaves the row wider than its column`() {
        // The invariant in one line, over the range a controls column can actually be: whatever the
        // preset picked by height, the row that is drawn fits the width it was given. This is what
        // makes "the next button is smaller than the previous one" unreachable rather than merely
        // fixed at one window size.
        val windowWidths = (60..1280).map { it.dp }
        val windowHeights = (40..1280).map { it.dp }
        for (preset in listOf(portrait, roomy, medium, tight)) {
            for (width in windowWidths) {
                val fitted = preset.fittedTo(width)
                assertTrue(
                    "preset $preset in a ${width.value}dp column: row is ${fitted.rowWidth}",
                    fitted.rowWidth <= width + 0.001.dp,
                )
                assertTrue("a fitted row never grows", fitted.rowWidth <= preset.rowWidth)
                assertTrue("no negative sizes", fitted.prevNextSize >= 0.dp && fitted.spacing >= 0.dp)
            }
            for (height in windowHeights) {
                val fitted = preset.fittedTo(windowWidths.last(), height)
                assertTrue(
                    "preset $preset in a ${height.value}dp-tall column: row is ${fitted.rowHeight}",
                    fitted.rowHeight <= height + 0.001.dp,
                )
                assertTrue("a fitted row never grows", fitted.rowHeight <= preset.rowHeight)
            }
        }
    }

    @Test
    fun `the fit is idempotent, so a nested measurement cannot shrink the row twice`() {
        val once = roomy.fittedTo(200.dp)
        assertEquals(once, once.fittedTo(200.dp))
        // A column that can pay for the already-fitted row leaves it as it is rather than scaling it
        // again against the smaller of the two widths.
        assertEquals(once, once.fittedTo(260.dp))
        // The same on the other axis, and on both at once.
        val short = portrait.fittedTo(300.dp, 61.dp)
        assertEquals(short, short.fittedTo(300.dp, 61.dp))
        assertEquals(short, short.fittedTo(320.dp, 200.dp))
    }
}
