package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.iptv.orEmptyOnReadError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class LiveTvStartScreen { CHANNELS, GUIDE }

enum class LiveTvChannelSort { PROVIDER_ORDER, NUMBER, NAME }

data class LiveTvSettings(
    val autoRefreshWhileVisible: Boolean = true,
    val startScreen: LiveTvStartScreen = LiveTvStartScreen.CHANNELS,
    val showChannelNumbers: Boolean = true,
    val channelSort: LiveTvChannelSort = LiveTvChannelSort.PROVIDER_ORDER,
    val wrapZap: Boolean = true,
    val showOsdAfterTune: Boolean = true
)

@Singleton
class LiveTvSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val autoRefreshKey = booleanPreferencesKey("auto_refresh_while_visible")
    private val startScreenKey = stringPreferencesKey("start_screen")
    private val showNumbersKey = booleanPreferencesKey("show_channel_numbers")
    private val channelSortKey = stringPreferencesKey("channel_sort")
    private val wrapZapKey = booleanPreferencesKey("wrap_zap")
    private val osdAfterTuneKey = booleanPreferencesKey("osd_after_tune")

    val settings: Flow<LiveTvSettings> = profileManager.activeProfileId
        .flatMapLatest { profileId ->
            factory.get(profileId, FEATURE).data.orEmptyOnReadError().map { preferences ->
                LiveTvSettings(
                    autoRefreshWhileVisible = preferences[autoRefreshKey] ?: true,
                    startScreen = preferences[startScreenKey].enumOrDefault(LiveTvStartScreen.CHANNELS),
                    showChannelNumbers = preferences[showNumbersKey] ?: true,
                    channelSort = preferences[channelSortKey].enumOrDefault(LiveTvChannelSort.PROVIDER_ORDER),
                    wrapZap = preferences[wrapZapKey] ?: true,
                    showOsdAfterTune = preferences[osdAfterTuneKey] ?: true
                )
            }
        }
        .distinctUntilChanged()

    suspend fun setAutoRefreshWhileVisible(enabled: Boolean) =
        store().edit { it[autoRefreshKey] = enabled }

    suspend fun setStartScreen(screen: LiveTvStartScreen) =
        store().edit { it[startScreenKey] = screen.name }

    suspend fun setShowChannelNumbers(show: Boolean) =
        store().edit { it[showNumbersKey] = show }

    suspend fun setChannelSort(sort: LiveTvChannelSort) =
        store().edit { it[channelSortKey] = sort.name }

    suspend fun setWrapZap(wrap: Boolean) =
        store().edit { it[wrapZapKey] = wrap }

    suspend fun setShowOsdAfterTune(show: Boolean) =
        store().edit { it[osdAfterTuneKey] = show }

    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    private inline fun <reified T : Enum<T>> String?.enumOrDefault(default: T): T =
        this?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    private companion object {
        const val FEATURE = "live_tv_settings"
    }
}