package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val STILL_WATCHING_COUNTDOWN_SECONDS = 60
private const val STILL_WATCHING_COUNTDOWN_TICK_MS = 1_000L

internal fun shouldEnterStillWatchingPrompt(
    stillWatchingEnabled: Boolean,
    autoPlayNextEpisodeEnabled: Boolean,
    nextEpisodeHasAired: Boolean,
    consecutiveAutoPlayCount: Int,
    threshold: Int,
): Boolean = stillWatchingEnabled &&
    autoPlayNextEpisodeEnabled &&
    nextEpisodeHasAired &&
    consecutiveAutoPlayCount >= threshold

internal fun PlayerRuntimeController.enterStillWatchingPromptMode() {
    val nextInfo = _uiState.value.nextEpisode ?: return
    if (_uiState.value.postPlayMode is PostPlayMode.StillWatching && stillWatchingPromptJob?.isActive == true) {
        return
    }
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    pauseForStillWatchingPrompt()
    stillWatchingPromptJob?.cancel()
    _uiState.update {
        it.copy(
            postPlayMode = PostPlayMode.StillWatching(
                nextEpisode = nextInfo,
                countdownSec = STILL_WATCHING_COUNTDOWN_SECONDS,
            ),
            showControls = false,
        )
    }
    val deadlineMs = SystemClock.elapsedRealtime() + STILL_WATCHING_COUNTDOWN_SECONDS * STILL_WATCHING_COUNTDOWN_TICK_MS
    val job = scope.launch {
        try {
            while (_uiState.value.postPlayMode is PostPlayMode.StillWatching) {
                val remainingMs = (deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                val remaining = ((remainingMs + STILL_WATCHING_COUNTDOWN_TICK_MS - 1) /
                    STILL_WATCHING_COUNTDOWN_TICK_MS).coerceAtMost(STILL_WATCHING_COUNTDOWN_SECONDS.toLong()).toInt()
                _uiState.update { state ->
                    val current = state.postPlayMode as? PostPlayMode.StillWatching
                        ?: return@update state
                    state.copy(postPlayMode = current.copy(countdownSec = remaining))
                }
                if (remainingMs == 0L) {
                    exitFromStillWatching()
                    break
                }
                delay(minOf(STILL_WATCHING_COUNTDOWN_TICK_MS, remainingMs))
            }
        } finally {
            if (stillWatchingPromptJob === coroutineContext.job) {
                stillWatchingPromptJob = null
            }
        }
    }
    stillWatchingPromptJob = job
}

internal fun PlayerRuntimeController.onStillWatchingContinue() {
    if (_uiState.value.postPlayMode !is PostPlayMode.StillWatching) return
    stillWatchingPromptJob?.cancel()
    stillWatchingPromptJob = null
    consecutiveAutoPlayCount = 0
    _uiState.update { it.copy(postPlayMode = null) }
    playNextEpisode(userInitiated = true)
}

internal fun PlayerRuntimeController.onDismissStillWatchingPrompt() {
    if (_uiState.value.postPlayMode !is PostPlayMode.StillWatching) return
    exitFromStillWatching()
}

internal fun PlayerRuntimeController.exitFromStillWatching() {
    consecutiveAutoPlayCount = 0
    resetPostPlayOverlayState(clearEpisode = true)
    _uiState.update { it.copy(pendingExitReason = PlayerExitReason.StillWatchingPrompt) }
}
