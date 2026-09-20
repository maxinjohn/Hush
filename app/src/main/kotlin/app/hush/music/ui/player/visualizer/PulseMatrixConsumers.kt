package app.hush.music.ui.player.visualizer

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The set of live visualiser consumers, each identified by an opaque token.
 *
 * A bare counter cannot say *whose* registration a release is ending. When the mini player
 * is composed before the full player's `LaunchedEffect` is disposed — which happens on
 * every screen transition — the departing screen's release decremented the counter the
 * arriving screen had just incremented, so the engine stopped a moment later while the
 * visible screen still believed it held the visualiser. That is the "PulseMatrix stops by
 * itself after minimising" bug.
 *
 * Tokens make every release exact: a token is spent at most once, and a token that is not
 * live (never handed out, or already ended) changes nothing.
 *
 * [onEnded] is called with the remaining count after each release that actually ended a
 * registration, so the engine reacts to the *last* consumer leaving no matter which code
 * path released it — there is only one path, the token's own `release()`.
 */
class PulseMatrixConsumers(private val onEnded: (remaining: Int) -> Unit = {}) {
    private val nextId = AtomicLong(1)
    private val live = ConcurrentHashMap.newKeySet<Long>()

    /** Registers a new consumer and returns the token that ends it. */
    fun register(): PulseMatrixConsumerToken {
        val id = nextId.getAndIncrement()
        live.add(id)
        return PulseMatrixConsumerToken(id, this)
    }

    /** Drops [id]; returns whether it was actually live, so a stale release is visible. */
    internal fun release(id: Long): Boolean {
        if (!live.remove(id)) return false
        onEnded(live.size)
        return true
    }

    val count: Int get() = live.size

    val isEmpty: Boolean get() = live.isEmpty()

    val isNotEmpty: Boolean get() = live.isNotEmpty()

    fun clear() = live.clear()
}

/**
 * One registration with the PulseMatrix engine. [release] is idempotent: calling it twice,
 * or after the registration ended, does nothing at all.
 */
class PulseMatrixConsumerToken internal constructor(
    val id: Long,
    private val registry: PulseMatrixConsumers,
) {
    private val spent = AtomicBoolean(false)

    /** True the first time only. */
    internal fun spend(): Boolean = spent.compareAndSet(false, true)

    fun release() {
        if (spend()) registry.release(id)
    }
}
