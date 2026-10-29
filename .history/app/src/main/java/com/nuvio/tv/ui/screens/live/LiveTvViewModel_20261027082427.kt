package com.nuvio.tv.ui.screens.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.iptv.EpgSourceReport
import com.nuvio.tv.data.iptv.EpgProgramme
import com.nuvio.tv.data.iptv.IptvChannel
import com.nuvio.tv.data.iptv.IptvRepository
import com.nuvio.tv.data.iptv.IptvSource
import com.nuvio.tv.data.iptv.IptvSourceType
import com.nuvio.tv.data.iptv.LiveStreamGuard
import com.nuvio.tv.data.local.LiveTvSettings
import com.nuvio.tv.data.local.LiveTvSettingsDataStore
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
    val settingsLoaded: Boolean = false,
    val sources: List<IptvSource> = emptyList(),
    val loading: Boolean = false,
    val errors: Map<String, String> = emptyMap(),
    val allChannels: List<IptvChannel> = emptyList(),
    val orderedChannels: List<IptvChannel> = emptyList(),
    val groups: List<LiveGroup> = emptyList(),
    val selectedGroup: String = LiveTvViewModel.GROUP_ALL,
    val favorites: Set<String> = emptySet(),
    val guide: Map<String, List<EpgProgramme>> = emptyMap(),
    val guideReports: List<EpgSourceReport> = emptyList(),
    val guideLoading: Boolean = false,
    val settings: LiveTvSettings = LiveTvSettings(),
    val nowMs: Long = System.currentTimeMillis()
) {
    val channels: List<IptvChannel>
        get() = when (selectedGroup) {
            LiveTvViewModel.GROUP_ALL -> orderedChannels
            LiveTvViewModel.GROUP_FAVORITES -> orderedChannels.filter { it.id in favorites }
            else -> orderedChannels.filter { it.group == selectedGroup }
        }
}

data class AddSourceState(val saving: Boolean = false, val error: String? = null)
data class EpgMappingState(val saving: Boolean = false, val error: String? = null)

@HiltViewModel
class LiveTvViewModel @Inject constructor(
    private val repository: IptvRepository,
    private val settingsStore: LiveTvSettingsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(LiveTvUiState())
    val uiState: StateFlow<LiveTvUiState> = _uiState.asStateFlow()

    private val _addState = MutableStateFlow(AddSourceState())
    val addState: StateFlow<AddSourceState> = _addState.asStateFlow()

    private val _epgMappingState = MutableStateFlow(EpgMappingState())
    val epgMappingState: StateFlow<EpgMappingState> = _epgMappingState.asStateFlow()

    private var loadJob: Job? = null
    private var addJob: Job? = null

    /** True while the Live TV screen is on screen; the background refresh only runs then. */
    private val visible = MutableStateFlow(false)
    private var lastLoadMs = 0L

    init {
        viewModelScope.launch(liveErrors) {
            settingsStore.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings, settingsLoaded = true).withGroups() }
            }
        }
        viewModelScope.launch(liveErrors) {
            repository.sources.distinctUntilChanged().collect { sources ->
                val changed = sources.map { Triple(it.contentKey, it.enabled, it.manualEpgMappings) } !=
                    _uiState.value.sources.map { Triple(it.contentKey, it.enabled, it.manualEpgMappings) }
                _uiState.update { it.copy(sources = sources, sourcesLoaded = true) }
                if (changed || _uiState.value.allChannels.isEmpty()) load(force = false)
            }
        }
        viewModelScope.launch(liveErrors) {
            repository.favorites.collect { favs ->
                _uiState.update { state -> state.copy(favorites = favs).withGroups() }
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

    /** Called by the screen on start/stop: no guide work while the player or another screen is up. */
    fun setVisible(isVisible: Boolean) {
        visible.value = isVisible
        if (isVisible) onClockTick()
    }

    /** Moves "now" on, and every 30 minutes re-checks sources (unchanged files come back as HTTP 304). */
    private fun onClockTick() {
        val now = System.currentTimeMillis()
        _uiState.update { it.copy(nowMs = now) }
        val state = _uiState.value
        if (state.settings.autoRefreshWhileVisible && lastLoadMs != 0L &&
            now - lastLoadMs >= REFRESH_EVERY_MS && !state.loading
        ) load(force = false)
    }

    private fun load(force: Boolean) {
        loadJob?.cancel()
        val sources = _uiState.value.sources
        if (sources.none { it.enabled }) {
            _uiState.update {
                it.copy(
                    allChannels = emptyList(),
                    guide = emptyMap(),
                    guideReports = emptyList(),
                    errors = emptyMap(),
                    loading = false,
                    guideLoading = false
                ).withGroups()
            }
            return
        }
        lastLoadMs = System.currentTimeMillis()
        loadJob = viewModelScope.launch(liveErrors) {
            val job = coroutineContext[Job]
            _uiState.update { it.copy(loading = true) }
            try {
                val result = repository.loadChannels(sources, force)
                _uiState.update {
                    it.copy(
                        allChannels = result.channels,
                        guide = emptyMap(),
                        guideReports = emptyList(),
                        errors = result.errors,
                        loading = false,
                        guideLoading = true
                    ).withGroups()
                }
                val guide = repository.loadGuideWithReport(sources, result.channels, force)
                _uiState.update {
                    it.copy(
                        guide = guide.programmesByChannel,
                        guideReports = guide.sources,
                        nowMs = System.currentTimeMillis()
                    )
                }
            } finally {
                // Never leave a spinner up; a load replaced by a newer one leaves the newer one's alone.
                if (loadJob === job) _uiState.update { it.copy(loading = false, guideLoading = false) }
            }
        }
    }

    fun selectGroup(key: String) = _uiState.update { it.copy(selectedGroup = key) }

    fun toggleFavorite(channel: IptvChannel) {
        viewModelScope.launch(liveErrors) { repository.toggleFavorite(channel.id) }
    }

    fun removeSource(id: String) {
        viewModelScope.launch(liveErrors) { repository.removeSource(id) }
    }

    fun setSourceEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(liveErrors) { repository.setSourceEnabled(id, enabled) }
    }

    fun resetEpgMappingState() = _epgMappingState.update { EpgMappingState() }

    fun setManualEpgMapping(sourceId: String, channelId: String, guideId: String?, onDone: () -> Unit) {
        if (_epgMappingState.value.saving) return
        viewModelScope.launch(liveErrors) {
            _epgMappingState.value = EpgMappingState(saving = true)
            try {
                repository.setManualEpgMapping(sourceId, channelId, guideId)
                _epgMappingState.value = EpgMappingState()
                onDone()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _epgMappingState.value = EpgMappingState(error = IptvRepository.userMessage(e))
            }
        }
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
        addJob = viewModelScope.launch(liveErrors) {
            _addState.value = AddSourceState(saving = true)
            try {
                repository.addSource(name, type, url, username, password, epgUrl)
                _addState.value = AddSourceState()
                onDone()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _addState.value = AddSourceState(error = IptvRepository.userMessage(e))
            }
        }
    }

    fun updateSource(
        id: String,
        name: String,
        type: IptvSourceType,
        url: String,
        username: String,
        password: String,
        epgUrl: String,
        onDone: () -> Unit
    ) {
        if (_addState.value.saving) return
        addJob = viewModelScope.launch(liveErrors) {
            _addState.value = AddSourceState(saving = true)
            try {
                repository.updateSource(id, name, type, url, username, password, epgUrl)
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
    fun startZapSession(channel: IptvChannel) {
        val state = _uiState.value
        LiveZapSession.start(
            state.channels,
            channel,
            state.guide,
            state.allChannels,
            wrapAtEnds = state.settings.wrapZap,
            showChannelNumbers = state.settings.showChannelNumbers,
            showOsdAfterTune = state.settings.showOsdAfterTune
        )
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
        return copy(
            groups = groups,
            selectedGroup = selected,
            orderedChannels = sortLiveChannels(allChannels, settings.channelSort)
        )
    }

    companion object {
        private const val REFRESH_EVERY_MS = 30 * 60 * 1000L
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
    @Volatile private var wrapAtEnds = true
    @Volatile private var showChannelNumbers = true
    @Volatile private var showOsdAfterTune = true
    @Volatile private var failoverActive = false
    @Volatile private var revision = 0L
    private val _snapshot = kotlinx.coroutines.flow.MutableStateFlow<LiveZapSnapshot?>(null)
    val snapshot: StateFlow<LiveZapSnapshot?> = _snapshot.asStateFlow()
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
        allChannels: List<IptvChannel> = list,
        wrapAtEnds: Boolean = true,
        showChannelNumbers: Boolean = true,
        showOsdAfterTune: Boolean = true
    ) {
        if (current != null && current?.id != channel.id) previous = current
        // Played from search or another group: CH+/CH- then move through all channels.
        channels = if (list.any { it.id == channel.id }) list else allChannels
        val listChanged = all !== allChannels || backups?.isFor(allChannels) != true
        all = allChannels
        if (listChanged) {
            backups = null
            indexJob = indexScope.launch(liveErrors) {
                val built = BackupIndex(allChannels)
                if (all === allChannels) backups = built // a newer list may have arrived meanwhile
            }
        }
        guide = guideMap
        index = channels.indexOfFirst { it.id == channel.id }
        current = channel
        this.wrapAtEnds = wrapAtEnds
        this.showChannelNumbers = showChannelNumbers
        this.showOsdAfterTune = showOsdAfterTune
        failoverActive = false
        LiveStreamGuard.protect(channel.streamUrl)
        tried.clear()
        reconnects.reset()
        publishSnapshot()
    }

    /** The channel playing now (or last played), so lists can return focus to it. */
    fun currentId(): String? = current?.id

    /** Read-only channel-list snapshot for the in-player mini-guide. */
    fun snapshotChannels(): LiveZapSnapshot? = _snapshot.value

    /** Current and next guide entries without exposing the session's guide map. */
    fun programmesFor(channelId: String, nowMs: Long = System.currentTimeMillis()): Pair<EpgProgramme?, EpgProgramme?> =
        IptvRepository.nowAndNext(guide[channelId], nowMs)

    /** Next/previous channel in the list (wrapping), or null when there is none. */
    fun step(forward: Boolean): IptvChannel? {
        val list = channels
        if (list.size < 2 || index !in list.indices) return null
        val nextIndex = index + if (forward) 1 else -1
        if (!wrapAtEnds && nextIndex !in list.indices) return null
        index = Math.floorMod(nextIndex, list.size)
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

    /** Tune a mini-guide selection through the current in-place switcher. */
    fun tune(channel: IptvChannel): Boolean = open(channel)

    /** Called when playback fails: switches to an untried copy of the same channel, if there is one. */
    fun failover(): Boolean {
        val go = opener ?: return false
        val failed = current ?: return false
        tried += failed.id
        // Index still building (just opened a huge playlist): no backup yet, the caller reconnects instead.
        val backup = backups?.copiesOf(failed)?.firstOrNull { it.id !in tried } ?: return false
        tried += backup.id
        current = backup
        failoverActive = true
        LiveStreamGuard.protect(backup.streamUrl)
        publishSnapshot()
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
        failoverActive = false
        LiveStreamGuard.protect(channel.streamUrl)
        channels.indexOfFirst { it.id == channel.id }.takeIf { it >= 0 }?.let { index = it }
        tried.clear()
        reconnects.reset()
        publishSnapshot()
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
        failoverActive = false
        publishSnapshot()
    }

    fun clear() {
        channels = emptyList()
        all = emptyList()
        guide = emptyMap()
        index = -1
        current = null
        previous = null
        wrapAtEnds = true
        showChannelNumbers = true
        showOsdAfterTune = true
        failoverActive = false
        tried.clear()
        reconnects.reset()
        _typedNumber.value = null
        _snapshot.value = null
    }

    private fun publishSnapshot() {
        revision += 1L
        val safeChannels = java.util.Collections.unmodifiableList(ArrayList(channels))
        _snapshot.value = LiveZapSnapshot(
            channels = safeChannels,
            currentId = current?.id,
            current = current,
            revision = revision,
            showChannelNumbers = showChannelNumbers,
            showOsdAfterTune = showOsdAfterTune,
            failoverActive = failoverActive
        )
    }
}

data class LiveZapSnapshot internal constructor(
    val channels: List<IptvChannel>,
    val currentId: String?,
    val current: IptvChannel?,
    val revision: Long,
    val showChannelNumbers: Boolean,
    val showOsdAfterTune: Boolean,
    val failoverActive: Boolean = false
)

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
