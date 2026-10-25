package com.nuvio.tv.ui.screens.live

/**
 * Anti-freeze policy for one multiview tile: a stall or error first restarts the stream at the live
 * edge with growing pauses; after [failoverAfter] restarts it moves to a backup copy of the channel.
 */
internal class TileRecovery(
    private val failoverAfter: Int = 2,
    private val maxDelayMs: Long = 30_000,
    /** With no backup, stop after this many restarts: a dead channel shouldn't retry all night. */
    private val giveUpAfter: Int = 10
) {
    var attempts: Int = 0
        private set

    sealed interface Action {
        data class Restart(val delayMs: Long) : Action
        data object Failover : Action
        data object GiveUp : Action
    }

    fun onProblem(hasBackup: Boolean): Action {
        attempts++
        if (hasBackup && attempts > failoverAfter) {
            attempts = 0
            return Action.Failover
        }
        if (attempts > giveUpAfter) return Action.GiveUp
        val delay = (1_000L shl (attempts - 1).coerceAtMost(5)).coerceAtMost(maxDelayMs)
        return Action.Restart(delay)
    }

    /** Playback is healthy again. */
    fun onPlaying() {
        attempts = 0
    }
}

/**
 * Detects a TV that can't keep up: more than [maxDropShare] of video frames dropped for
 * [sustainSeconds] seconds in a row. Fed once a second with that second's counts.
 */
internal class OverloadMonitor(
    private val maxDropShare: Double = 0.25,
    private val sustainSeconds: Int = 8
) {
    private var badSeconds = 0

    /** @return true once the overload has lasted long enough to act on. */
    fun onSecond(dropped: Int, rendered: Int): Boolean {
        val total = dropped + rendered
        badSeconds = if (total > 0 && dropped.toDouble() / total > maxDropShare) badSeconds + 1 else 0
        return badSeconds >= sustainSeconds
    }

    fun reset() {
        badSeconds = 0
    }
}

/** Allows at most [max] reconnects in any [windowMs] window, so a dead channel can't loop forever. */
internal class ReconnectBudget(private val max: Int = 3, private val windowMs: Long = 5 * 60_000L) {
    private val times = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(nowMs: Long): Boolean {
        while (times.isNotEmpty() && nowMs - times.first() >= windowMs) times.removeFirst()
        if (times.size >= max) return false
        times.addLast(nowMs)
        return true
    }

    @Synchronized
    fun reset() = times.clear()
}
