package com.nuvio.tv.ui.screens.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.iptv.EpgProgramme
import com.nuvio.tv.data.iptv.IptvChannel
import com.nuvio.tv.data.iptv.IptvRepository
import com.nuvio.tv.data.iptv.IptvSource
import com.nuvio.tv.data.iptv.IptvSourceType
import com.nuvio.tv.data.iptv.LiveStreamGuard
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
    val nowMs: Long = System.currentTimeMillis(),
    /** The selected group's channels, looked up in [ChannelIndex] (never filtered on the UI thread). */
    val channels: List<IptvChannel> = emptyList()
)

/** Channels split by group once per list or favourites change, off the main thread. */
internal class ChannelIndex(
    val groups: List<LiveGroup>,
    private val all: List<IptvChannel>,
    private val favorites: List<IptvChannel>,
    private val byGroup: Map<String, List<IptvChannel>>
) {
    fun channelsOf(key: String): List<IptvChannel> = when (key) {
        LiveTvViewModel.GROUP_ALL -> all
        LiveTvViewModel.GROUP_FAVORITES -> favorites
        else -> byGroup[key].orEmpty()
    }

    fun has(key: String) = key == LiveTvViewModel.GROUP_ALL || (key == LiveTvViewModel.GROUP_FAVORITES && favorites.isNotEmpty()) || byGroup.containsKey(key)

    companion object {
        val EMPTY = ChannelIndex(emptyList(), emptyList(), emptyList(), emptyMap())

        fun build(all: List<IptvChannel>, favoriteIds: Set<String>): ChannelIndex {
            val byGroup = LinkedHashMap<String, MutableList<IptvChannel>>()
            val favorites = ArrayList<IptvChannel>()
            for (channel in all) {
                byGroup.getOrPut(channel.group) { ArrayList() }.add(channel)
                if (channel.id in favoriteIds) favorites.add(channel)
            }
            val groups = buildList {
                if (favorites.isNotEmpty()) add(LiveGroup(LiveTvViewModel.GROUP_FAVORITES, LiveTvViewModel.GROUP_FAVORITES, favorites.size))
                if (all.isNotEmpty()) add(LiveGroup(LiveTvViewModel.GROUP_ALL, LiveTvViewModel.GROUP_ALL, all.size))
                byGroup.forEach { (group, list) -> add(LiveGroup(group, group, list.size)) }
            }
            return ChannelIndex(groups, all, favorites, byGroup)
        }
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
    private var addJob: Job? = null
    private var indexJob: Job? = null
    private var focusJob: Job? = null
    private var pendingGroup: String? = null
    private var index: ChannelIndex = ChannelIndex.EMPTY

    /** True while the Live TV screen is on screen; the background refresh only runs then. */
    private val visible = MutableStateFlow(false)
    /** Set once the Live TV screen has been shown; until then only the source list is kept. */
    private var wantsChannels = false
    private var lastLoadMs = 0L

    init {
        viewModelScope.launch(liveErrors) {
            repository.sources.distinctUntilChanged().collect { sources ->
                val changed = sources.map { it.filterKey to it.enabled } !=
                    _uiState.value.sources.map { it.filterKey to it.enabled }
                _uiState.update { it.copy(sources = sources, sourcesLoaded = true) }
                if (wantsChannels && (changed || _uiState.value.allChannels.isEmpty())) load(force = false)
            }
        }
        viewModelScope.launch(liveErrors) {
            repository.favorites.collect { favs ->
                _uiState.update { state -> state.copy(favorites = favs) }
                rebuildIndex()
            }
        }
        viewModelScope.launch(liveErrors) {
            while (isActive) {
                delay(30_000)
                if (visible.value) onClockTick()
            }
        }
    }

    fun refresh() = load(force = true)

    /** Deletes the downloaded lists and guides, then downloads them again. */
    fun clearDownloads() {
        viewModelScope.launch(liveErrors) {
            repository.clearCache()
            load(force = true)
        }
    }

    /** Called by the screen on start/stop: no guide work while the player or another screen is up. */
    fun setVisible(isVisible: Boolean) {
        visible.value = isVisible
        if (isVisible && !wantsChannels) {
            // First time on screen: load the channels. Settings uses this view model for sources
            // only and never pays for parsing the lists.
            wantsChannels = true
            if (_uiState.value.sourcesLoaded && _uiState.value.allChannels.isEmpty()) load(force = false)
        }
        if (isVisible) onClockTick()
    }

    /** Moves "now" on, and every 30 minutes re-checks sources (unchanged files come back as HTTP 304). */
    private fun onClockTick() {
        val now = System.currentTimeMillis()
        _uiState.update { it.copy(nowMs = now) }
        if (lastLoadMs != 0L && now - lastLoadMs >= REFRESH_EVERY_MS && !_uiState.value.loading) load(force = false)
    }

    private fun load(force: Boolean) {
        loadJob?.cancel()
        val sources = _uiState.value.sources
        if (sources.none { it.enabled }) {
            _uiState.update { it.copy(allChannels = emptyList(), guide = emptyMap(), errors = emptyMap(), loading = false) }
            rebuildIndex()
            return
        }
        lastLoadMs = System.currentTimeMillis()
        loadJob = viewModelScope.launch(liveErrors) {
            val job = coroutineContext[Job]
            _uiState.update { it.copy(loading = true) }
            try {
                val result = repository.loadChannels(sources, force)
                _uiState.update {
                    it.copy(allChannels = result.channels, errors = result.errors, loading = false)
                }
                rebuildIndex()
                _uiState.update { it.copy(guideLoading = true) }
                val guide = repository.loadGuide(sources, result.channels, force)
                _uiState.update { it.copy(guide = guide, nowMs = System.currentTimeMillis()) }
            } finally {
                // Never leave a spinner up; a load replaced by a newer one leaves the newer one's alone.
                if (loadJob === job) _uiState.update { it.copy(loading = false, guideLoading = false) }
            }
        }
    }

    fun selectGroup(key: String) {
        focusJob?.cancel()
        pendingGroup = null
        if (key == _uiState.value.selectedGroup) return
        _uiState.update { it.copy(selectedGroup = key, channels = index.channelsOf(key)) }
    }

    /**
     * A group got focus: show its channels once the D-pad rests on it, so holding Down through
     * the groups doesn't rebuild the channel list at every step.
     */
    fun focusGroup(key: String) {
        focusJob?.cancel()
        pendingGroup = key
        focusJob = viewModelScope.launch(liveErrors) {
            delay(GROUP_FOCUS_SETTLE_MS)
            selectGroup(key)
        }
    }

    /** Right pressed on a group before it settled: show its channels now. True when there was one. */
    fun flushGroupFocus(): Boolean {
        val key = pendingGroup ?: return false
        selectGroup(key)
        return true
    }

    private fun rebuildIndex() {
        indexJob?.cancel()
        val state = _uiState.value
        val all = state.allChannels
        val favorites = state.favorites
        indexJob = viewModelScope.launch(liveErrors) {
            val built = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ChannelIndex.build(all, favorites) }
            index = built
            _uiState.update { current ->
                val selected = current.selectedGroup.takeIf(built::has) ?: GROUP_ALL
                current.copy(groups = built.groups, selectedGroup = selected, channels = built.channelsOf(selected))
            }
        }
    }

    fun toggleFavorite(channel: IptvChannel) {
        viewModelScope.launch(liveErrors) { repository.toggleFavorite(channel.id) }
    }

    fun removeSource(id: String) {
        viewModelScope.launch(liveErrors) { repository.removeSource(id) }
    }

    fun setSourceEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(liveErrors) { repository.setSourceEnabled(id, enabled) }
    }

    fun setSourceLanguages(id: String, languages: List<String>) {
        viewModelScope.launch(liveErrors) { repository.setSourceLanguages(id, languages) }
    }

    fun resetAddState() = _addState.update { AddSourceState() }

    fun addSource(
        name: String,
        type: IptvSourceType,
        url: String,
        username: String,
        password: String,
        epgUrl: String,
        languages: List<String>,
        onDone: () -> Unit
    ) {
        if (_addState.value.saving) return
        addJob = viewModelScope.launch(liveErrors) {
            _addState.value = AddSourceState(saving = true)
            try {
                repository.addSource(name, type, url, username, password, epgUrl, languages)
                _addState.value = AddSourceState()
                onDone()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _addState.value = AddSourceState(error = IptvRepository.userMessage(e))
            }
        }
    }

    /** Stops checking a new source (Cancel or Back while it downloads). */
    fun cancelAdd() {
        addJob?.cancel()
        addJob = null
        _addState.value = AddSourceState()
    }

    /** Remembers the list the user is zapping through so CH+/CH- in the player can move along it. */
    fun startZapSession(channel: IptvChannel, fromAll: Boolean = false) {
        val state = _uiState.value
        LiveZapSession.start(if (fromAll) state.allChannels else state.channels, channel, state.guide, state.allChannels)
    }

    companion object {
        private const val REFRESH_EVERY_MS = 30 * 60 * 1000L
        private const val GROUP_FOCUS_SETTLE_MS = 160L
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
    /** Reconnects of the same stream when no backup copy is left. */
    private val reconnects = ReconnectBudget()
    /** Backup copies of every channel, built in the background when the channel list changes. */
    @Volatile private var backups: BackupIndex? = null
    @Volatile private var indexJob: kotlinx.coroutines.Job? = null
    private val indexScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    private val _typedNumber = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val typedNumber: StateFlow<String?> = _typedNumber.asStateFlow()
    private val handler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val digitToken = Any()
    private val notFoundToken = Any()

    /** Set by the live player destination while it is on screen; opens a channel in its place. */
    @Volatile var navigator: ((IptvChannel) -> Unit)? = null

    /** Set by the live player itself: switches the stream in place (faster than a new player screen). */
    @Volatile var switcher: ((IptvChannel) -> Unit)? = null

    private val opener: ((IptvChannel) -> Unit)? get() = switcher ?: navigator

    private val _notFound = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    /** A typed channel number that matched nothing, shown briefly. */
    val notFound: StateFlow<Int?> = _notFound.asStateFlow()

    fun start(
        list: List<IptvChannel>,
        channel: IptvChannel,
        guideMap: Map<String, List<EpgProgramme>>,
        allChannels: List<IptvChannel> = list
    ) {
        if (current != null && current?.id != channel.id) previous = current
        // Played from search or another group: CH+/CH- then move through all channels.
        channels = if (list.any { it.id == channel.id }) list else allChannels
        val listChanged = all !== allChannels || backups?.isFor(allChannels) != true
        all = allChannels
        if (listChanged) {
            backups = null
            indexJob?.cancel()
            indexJob = indexScope.launch(liveErrors) {
                val built = BackupIndex(allChannels)
                if (all === allChannels) backups = built // a newer list may have arrived meanwhile
            }
        }
        guide = guideMap
        index = channels.indexOfFirst { it.id == channel.id }
        current = channel
        LiveStreamGuard.protect(channel.streamUrl)
        tried.clear()
        reconnects.reset()
    }

    /** The channel playing now (or last played), so lists can return focus to it. */
    fun currentId(): String? = current?.id

    /** Next/previous channel in the list (wrapping), or null when there is none. */
    fun step(forward: Boolean): IptvChannel? {
        val list = channels
        if (list.size < 2 || index !in list.indices) return null
        val delta = if (forward) 1 else list.size - 1
        index = (index + delta) % list.size
        return list[index]
    }

    /** CH+/CH- from the player. Returns true when the key was handled. */
    fun zap(forward: Boolean): Boolean {
        if (opener == null) return false
        val channel = step(forward) ?: return false
        return open(channel)
    }

    /** Channel [number] by its playlist number; playlists without numbers count channels in order. */
    fun jumpToNumber(number: Int): Boolean {
        val list = all
        val channel = if (list.any { it.number != null }) list.firstOrNull { it.number == number }
        else list.getOrNull(number - 1)
        if (channel == null) {
            _notFound.value = number
            handler.postAtTime({ _notFound.value = null }, notFoundToken, android.os.SystemClock.uptimeMillis() + 2_000)
            return false
        }
        return open(channel)
    }

    /** Flip back to the channel watched before this one. */
    fun lastChannel(): Boolean = previous?.let(::open) ?: false

    /** Called when playback fails: switches to an untried copy of the same channel, if there is one. */
    fun failover(): Boolean {
        val go = opener ?: return false
        val failed = current ?: return false
        tried += failed.id
        // Index still building (just opened a huge playlist): no backup yet, the caller reconnects instead.
        val backup = backups?.copiesOf(failed)?.firstOrNull { it.id !in tried } ?: return false
        tried += backup.id
        current = backup
        LiveStreamGuard.protect(backup.streamUrl)
        go(backup)
        return true
    }

    /**
     * Live playback failed or froze: switch to an untried copy of the channel, otherwise reconnect
     * the same stream (at most 3 times in 5 minutes, so a dead channel ends on the error screen).
     */
    fun recover(nowMs: Long = android.os.SystemClock.elapsedRealtime()): Boolean {
        if (failover()) return true
        val go = opener ?: return false
        val channel = current ?: return false
        if (!reconnects.tryAcquire(nowMs)) return false
        go(channel)
        return true
    }

    /** A digit pressed during live playback; the channel opens 1.5 s after the last digit. */
    fun onDigit(digit: Int): Boolean {
        if (opener == null) return false
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
        val go = opener ?: return false
        if (current?.id != channel.id) previous = current
        current = channel
        LiveStreamGuard.protect(channel.streamUrl)
        channels.indexOfFirst { it.id == channel.id }.takeIf { it >= 0 }?.let { index = it }
        tried.clear()
        reconnects.reset()
        go(channel)
        return true
    }

    /** Tests only: waits for the background backup index, which failover needs. */
    internal fun awaitBackupIndex() = kotlinx.coroutines.runBlocking { indexJob?.join() }

    /** Live playback ended: drop the channel lists and guide (they can be large), keep the last channel. */
    fun releaseLists() {
        channels = emptyList()
        all = emptyList()
        guide = emptyMap()
        backups = null
        index = -1
    }

    fun clear() {
        channels = emptyList()
        all = emptyList()
        guide = emptyMap()
        index = -1
        current = null
        previous = null
        tried.clear()
        reconnects.reset()
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

/** A live channel as a player stream, for switching channels inside the running player. */
fun liveChannelStream(channel: IptvChannel): com.nuvio.tv.domain.model.Stream =
    com.nuvio.tv.domain.model.Stream(
        name = LiveZapSession.nowTitle(channel) ?: channel.name,
        title = channel.name,
        description = null,
        url = channel.streamUrl,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = channel.headers.takeIf { it.isNotEmpty() }?.let { headers ->
            com.nuvio.tv.domain.model.StreamBehaviorHints(
                notWebReady = null,
                bingeGroup = null,
                countryWhitelist = null,
                proxyHeaders = com.nuvio.tv.domain.model.ProxyHeaders(request = headers, response = null)
            )
        },
        addonName = "Live TV",
        addonLogo = channel.logoUrl
    )
