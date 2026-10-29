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