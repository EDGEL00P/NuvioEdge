package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvSettingsDataStoreTest {
    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private val activeProfile = MutableStateFlow(1)
    private val stores = mutableMapOf<Pair<Int, String>, MemoryPreferences>()
    private val factory = mockk<ProfileDataStoreFactory> {
        every { get(any(), any()) } answers {
            stores.getOrPut(firstArg<Int>() to secondArg<String>()) { MemoryPreferences() }
        }
    }
    private val profileManager = mockk<ProfileManager> {
        every { activeProfileId } returns this@LiveTvSettingsDataStoreTest.activeProfile
    }

    private fun newStore() = LiveTvSettingsDataStore(factory, profileManager)

    @Test
    fun `defaults match the current Live TV behavior`() = runTest {
        assertEquals(LiveTvSettings(), newStore().settings.first())
    }

    @Test
    fun `settings persist independently by profile and store recreation`() = runTest {
        val store = newStore()
        store.setAutoRefreshWhileVisible(false)
        store.setStartScreen(LiveTvStartScreen.GUIDE)
        store.setShowChannelNumbers(false)
        store.setChannelSort(LiveTvChannelSort.NUMBER)
        store.setWrapZap(false)
        store.setShowOsdAfterTune(false)

        val configured = newStore().settings.first()
        assertFalse(configured.autoRefreshWhileVisible)
        assertEquals(LiveTvStartScreen.GUIDE, configured.startScreen)
        assertFalse(configured.showChannelNumbers)
        assertEquals(LiveTvChannelSort.NUMBER, configured.channelSort)
        assertFalse(configured.wrapZap)
        assertFalse(configured.showOsdAfterTune)

        activeProfile.value = 2
        assertEquals(LiveTvSettings(), newStore().settings.first())
        newStore().setStartScreen(LiveTvStartScreen.CHANNELS)
        activeProfile.value = 1
        assertEquals(configured, newStore().settings.first())
    }

    @Test
    fun `unknown enum values fall back to safe defaults`() = runTest {
        val preferences = stores.getOrPut(1 to "live_tv_settings") { MemoryPreferences() }
        preferences.updateData { it.toMutablePreferences().apply {
            this[stringPreferencesKey("start_screen")] = "NOT_A_SCREEN"
            this[stringPreferencesKey("channel_sort")] = "NOT_A_SORT"
        }.toPreferences() }

        val restored = newStore().settings.first()
        assertEquals(LiveTvStartScreen.CHANNELS, restored.startScreen)
        assertEquals(LiveTvChannelSort.PROVIDER_ORDER, restored.channelSort)
        assertTrue(restored.wrapZap)
    }
}