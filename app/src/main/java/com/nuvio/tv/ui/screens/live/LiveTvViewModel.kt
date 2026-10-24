package com.nuvio.tv.ui.screens.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.iptv.EpgProgramme
import com.nuvio.tv.data.iptv.IptvChannel
import com.nuvio.tv.data.iptv.IptvRepository
import com.nuvio.tv.data.iptv.IptvSource
import com.nuvio.tv.data.iptv.IptvSourceType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LiveGroup(val key: String, val title: String, val count: Int)

data class LiveTvUiState(
    val sourcesLoaded: Boolean = false,
    val sources: List<IptvSource> = emptyList(),
    val loading: Boolean = false,
    val errors: Map<String, String> = emptyMap(),
    val allChannels: List<IptvChannel> = emptyList(),
    val groups: List<LiveGroup> = emptyList(),
    val selectedGroup: String = LiveTvViewModel.GROUP_ALL,
    val favorites: Set<String> = emptySet(),
    val guide: Map<String, List<EpgProgramme>> = emptyMap(),
    val guideLoading: Boolean = false,
    val nowMs: Long = System.currentTimeMillis()
) {
    val channels: List<IptvChannel>
        get() = when (selectedGroup) {
            LiveTvViewModel.GROUP_ALL -> allChannels
            LiveTvViewModel.GROUP_FAVORITES -> allChannels.filter { it.id in favorites }
            else -> allChannels.filter { it.group == selectedGroup }
        }
}

data class AddSourceState(val saving: Boolean = false, val error: String? = null)

@HiltViewModel
class LiveTvViewModel @Inject constructor(
    private val repository: IptvRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LiveTvUiState())
    val uiState: StateFlow<LiveTvUiState> = _uiState.asStateFlow()

    private val _addState = MutableStateFlow(AddSourceState())
    val addState: StateFlow<AddSourceState> = _addState.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            repository.sources.distinctUntilChanged().collect { sources ->
                val changed = sources.map { it.contentKey to it.enabled } !=
                    _uiState.value.sources.map { it.contentKey to it.enabled }
                _uiState.update { it.copy(sources = sources, sourcesLoaded = true) }
                if (changed || _uiState.value.allChannels.isEmpty()) load(force = false)
            }
        }
        viewModelScope.launch {
            repository.favorites.collect { favs ->
                _uiState.update { state -> state.copy(favorites = favs).withGroups() }
            }
        }
        viewModelScope.launch {
            var ticks = 0
            while (isActive) {
                delay(30_000)
                _uiState.update { it.copy(nowMs = System.currentTimeMillis()) }
                // Every 30 minutes re-check sources; stale playlists and guides are re-validated with the server.
                if (++ticks % 60 == 0 && !_uiState.value.loading) load(force = false)
            }
        }
    }

    fun refresh() = load(force = true)

    private fun load(force: Boolean) {
        loadJob?.cancel()
        val sources = _uiState.value.sources
        if (sources.none { it.enabled }) {
            _uiState.update { it.copy(allChannels = emptyList(), guide = emptyMap(), errors = emptyMap(), loading = false).withGroups() }
            return
        }
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            val result = repository.loadChannels(sources, force)
            _uiState.update {
                it.copy(allChannels = result.channels, errors = result.errors, loading = false).withGroups()
            }
            _uiState.update { it.copy(guideLoading = true) }
            try {
                val guide = repository.loadGuide(sources, result.channels, force)
                _uiState.update { it.copy(guide = guide, nowMs = System.currentTimeMillis()) }
            } finally {
                _uiState.update { it.copy(guideLoading = false) }
            }
        }
    }

    fun selectGroup(key: String) = _uiState.update { it.copy(selectedGroup = key) }

    fun toggleFavorite(channel: IptvChannel) {
        viewModelScope.launch { repository.toggleFavorite(channel.id) }
    }

    fun removeSource(id: String) {
        viewModelScope.launch { repository.removeSource(id) }
    }

    fun setSourceEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { repository.setSourceEnabled(id, enabled) }
    }

    fun resetAddState() = _addState.update { AddSourceState() }

    fun addSource(
        name: String,
        type: IptvSourceType,
        url: String,
        username: String,
        password: String,
        epgUrl: String,
        onDone: () -> Unit
    ) {
        if (_addState.value.saving) return
        viewModelScope.launch {
            _addState.value = AddSourceState(saving = true)
            runCatching { repository.addSource(name, type, url, username, password, epgUrl) }
                .onSuccess {
                    _addState.value = AddSourceState()
                    onDone()
                }
                .onFailure { e -> _addState.value = AddSourceState(error = IptvRepository.userMessage(e)) }
        }
    }

    /** Remembers the list the user is zapping through so CH+/CH- in the player can move along it. */
    fun startZapSession(channel: IptvChannel) {
        val state = _uiState.value
        LiveZapSession.start(state.channels, channel, state.guide, state.allChannels)
    }

    private fun LiveTvUiState.withGroups(): LiveTvUiState {
        val groups = buildList {
            val favCount = allChannels.count { it.id in favorites }
            if (favCount > 0) add(LiveGroup(GROUP_FAVORITES, GROUP_FAVORITES, favCount))
            if (allChannels.isNotEmpty()) add(LiveGroup(GROUP_ALL, GROUP_ALL, allChannels.size))
            allChannels.groupingBy { it.group }.eachCount().forEach { (group, count) ->
                add(LiveGroup(group, group, count))
            }
        }
        val selected = selectedGroup.takeIf { key -> groups.any { it.key == key } } ?: GROUP_ALL
        return copy(groups = groups, selectedGroup = selected)
    }

    companion object {
        const val GROUP_ALL = "\u0001all"
        const val GROUP_FAVORITES = "\u0001favorites"
    }
}

/** In-memory only (never persisted): the channels behind the current live playback. */
object LiveZapSession {
    /** The list CH+/CH- moves through (the group you played from). */
    @Volatile private var channels: List<IptvChannel> = emptyList()
    /** Every channel, for number entry and backup copies. */
    @Volatile private var all: List<IptvChannel> = emptyList()
    @Volatile private var guide: Map<String, List<EpgProgramme>> = emptyMap()
    @Volatile private var index: Int = -1
    @Volatile private var current: IptvChannel? = null
    @Volatile private var previous: IptvChannel? = null
    /** Copies already tried for the channel that failed. */
    private val tried = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val _typedNumber = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val typedNumber: StateFlow<String?> = _typedNumber.asStateFlow()
    private val handler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val digitToken = Any()

    /** Set by the live player destination while it is on screen; opens a channel in its place. */
    @Volatile var navigator: ((IptvChannel) -> Unit)? = null

    fun start(
        list: List<IptvChannel>,
        channel: IptvChannel,
        guideMap: Map<String, List<EpgProgramme>>,
        allChannels: List<IptvChannel> = list
    ) {
        if (current != null && current?.id != channel.id) previous = current
        channels = list
        all = allChannels
        guide = guideMap
        index = list.indexOfFirst { it.id == channel.id }
        current = channel
        tried.clear()
    }

    /** The channel playing now (or last played), so lists can return focus to it. */
    fun currentId(): String? = current?.id

    /** Next/previous channel in the list (wrapping), or null when there is none. */
    fun step(forward: Boolean): IptvChannel? {
        val list = channels
        if (list.size < 2 || index !in list.indices) return null
        index = (index + if (forward) 1 else -1 + list.size) % list.size
        return list[index]
    }

    /** CH+/CH- from the player. Returns true when the key was handled. */
    fun zap(forward: Boolean): Boolean {
        if (navigator == null) return false
        val channel = step(forward) ?: return false
        return open(channel)
    }

    fun jumpToNumber(number: Int): Boolean {
        val channel = all.firstOrNull { it.number == number } ?: return false
        return open(channel)
    }

    /** Flip back to the channel watched before this one. */
    fun lastChannel(): Boolean = previous?.let(::open) ?: false

    /** Called when playback fails: switches to an untried copy of the same channel, if there is one. */
    fun failover(): Boolean {
        val go = navigator ?: return false
        val failed = current ?: return false
        tried += failed.id
        val backup = backupCopies(failed, all).firstOrNull { it.id !in tried } ?: return false
        tried += backup.id
        current = backup
        go(backup)
        return true
    }

    /** A digit pressed during live playback; the channel opens 1.5 s after the last digit. */
    fun onDigit(digit: Int): Boolean {
        if (navigator == null) return false
        _typedNumber.value = ((_typedNumber.value ?: "") + digit).takeLast(4)
        handler.removeCallbacksAndMessages(digitToken)
        handler.postAtTime({
            val number = _typedNumber.value?.toIntOrNull()
            _typedNumber.value = null
            if (number != null) jumpToNumber(number)
        }, digitToken, android.os.SystemClock.uptimeMillis() + 1_500)
        return true
    }

    fun nowTitle(channel: IptvChannel, nowMs: Long = System.currentTimeMillis()): String? =
        IptvRepository.nowAndNext(guide[channel.id], nowMs).first?.title?.takeIf { it.isNotBlank() }

    private fun open(channel: IptvChannel): Boolean {
        val go = navigator ?: return false
        if (current?.id != channel.id) previous = current
        current = channel
        channels.indexOfFirst { it.id == channel.id }.takeIf { it >= 0 }?.let { index = it }
        tried.clear()
        go(channel)
        return true
    }

    fun clear() {
        channels = emptyList()
        all = emptyList()
        guide = emptyMap()
        index = -1
        current = null
        previous = null
        tried.clear()
        _typedNumber.value = null
    }
}

const val LIVE_CONTENT_TYPE = "iptv"

fun liveChannelPlayerRoute(channel: IptvChannel, nowTitle: String?): String =
    com.nuvio.tv.ui.navigation.Screen.Player.createRoute(
        streamUrl = channel.streamUrl,
        title = channel.name,
        streamName = nowTitle ?: channel.name,
        headers = channel.headers.ifEmpty { null },
        contentType = LIVE_CONTENT_TYPE,
        contentName = channel.name,
        logo = channel.logoUrl,
        addonName = "Live TV",
        startFromBeginning = true
    )
