/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import kotlin.coroutines.cancellation.CancellationException

/**
 * `runCatching` without erasing the caller's cancellation.
 *
 * Every SpotiFLAC lookup is a suspend network call, and the pipeline deliberately treats
 * "this source had nothing" (try the next source) as a different answer from "nobody is
 * waiting for this any more" (stop now) - see the `attempt` helper in
 * `SpotiFLACPlaybackResolver`, which documents the distinction but cannot enforce it for a
 * cancellation its callee has already folded into a `Result`.
 *
 * Plain `runCatching` catches `Throwable`, so a cancelled look-up came back as
 * `Result.failure(CancellationException)` and read as an empty result: the source and
 * quality loops then kept querying the network for a track the user had already skipped
 * past. Rethrowing keeps the two answers distinct, and lets a cancelled call stop the
 * pipeline the way cancellation is supposed to.
 */
internal inline fun <T> resolveResultOf(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        Result.failure(throwable)
    }
