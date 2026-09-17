/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * What these protect is one specific confusion: a cancelled lookup being reported as "this
 * source had nothing". That reading is what made a skipped track keep sweeping the network,
 * and it is invisible in a normal test because the same code path handles genuine failures.
 */
class SpotiFLACResolveResultTest {

    @Test
    fun `a value becomes a success`() {
        val result = resolveResultOf { 41 + 1 }

        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `an ordinary failure is still reported as a failure`() {
        val boom = SpotiFLACException("All providers failed")

        val result = resolveResultOf<Int> { throw boom }

        assertSame(boom, result.exceptionOrNull())
    }

    @Test
    fun `a cancellation is rethrown rather than captured`() {
        val cancellation = CancellationException("skipped")

        val thrown =
            try {
                resolveResultOf<Int> { throw cancellation }
                null
            } catch (e: CancellationException) {
                e
            }

        assertSame(cancellation, thrown)
    }

    @Test
    fun `a suspend lookup that is cancelled propagates through the helper`() = runTest {
        val started = CompletableDeferred<Unit>()
        val job = Job()
        var sawCancellation = false

        val worker =
            launch(job) {
                try {
                    resolveResultOf {
                        started.complete(Unit)
                        // Suspends until the job is cancelled, which is what a skipped
                        // track does to the lookup it was waiting on.
                        delay(60_000)
                        "never"
                    }
                } catch (e: CancellationException) {
                    sawCancellation = true
                    throw e
                }
            }

        started.await()
        job.cancel()
        worker.join()

        assertTrue("a cancelled lookup must surface as cancellation", sawCancellation)
        assertTrue("the helper must not swallow cancellation", worker.isCancelled)
    }

    @Test
    fun `an error thrown for a real reason still reaches the caller as a failure`() = runTest {
        val result =
            coroutineScope {
                async { resolveResultOf { throw IllegalStateException("relay returned encrypted response") } }.await()
            }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }
}
