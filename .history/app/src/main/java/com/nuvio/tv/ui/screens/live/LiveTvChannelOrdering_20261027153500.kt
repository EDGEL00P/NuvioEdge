package com.nuvio.tv.ui.screens.live

import com.nuvio.tv.data.iptv.IptvChannel
import com.nuvio.tv.data.local.LiveTvChannelSort
import java.util.Locale

internal fun sortLiveChannels(
    channels: List<IptvChannel>,
    sort: LiveTvChannelSort
): List<IptvChannel> = when (sort) {
    LiveTvChannelSort.PROVIDER_ORDER -> channels
    LiveTvChannelSort.NUMBER -> channels.withIndex()
        .sortedWith(
            compareBy<IndexedValue<IptvChannel>> { it.value.number == null }
                .thenBy { it.value.number ?: Int.MAX_VALUE }
                .thenBy { it.index }
        )
        .map { it.value }
    LiveTvChannelSort.NAME -> channels.withIndex()
        .sortedWith(
            compareBy<IndexedValue<IptvChannel>> { it.value.name.lowercase(Locale.ROOT) }
                .thenBy { it.index }
        )
        .map { it.value }
}

internal fun visibleLiveChannels(
    orderedChannels: List<IptvChannel>,
    favorites: Set<String>,
    selectedGroup: String
): List<IptvChannel> = when (selectedGroup) {
    LiveTvViewModel.GROUP_ALL -> orderedChannels
    LiveTvViewModel.GROUP_FAVORITES -> orderedChannels.filter { it.id in favorites }
    else -> orderedChannels.filter { it.group == selectedGroup }
}