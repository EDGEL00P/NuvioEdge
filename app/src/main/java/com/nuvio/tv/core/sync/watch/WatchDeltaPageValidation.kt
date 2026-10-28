package com.nuvio.tv.core.sync.watch

import java.util.Locale

internal fun <T> validateWatchDeltaPage(
    events: List<T>,
    afterEventId: Long,
    requestedLimit: Int,
    eventId: (T) -> Long,
    operation: (T) -> String
) {
    require(requestedLimit > 0)
    require(events.size <= requestedLimit) { "Watch-state delta page exceeded the requested limit" }

    var previousEventId = afterEventId
    events.forEach { event ->
        val currentEventId = eventId(event)
        require(currentEventId > previousEventId) { "Watch-state delta event IDs must be strictly increasing" }
        require(operation(event).trim().lowercase(Locale.ROOT) in SUPPORTED_WATCH_DELTA_OPERATIONS) {
            "Watch-state delta contains an unsupported operation"
        }
        previousEventId = currentEventId
    }
}

private val SUPPORTED_WATCH_DELTA_OPERATIONS = setOf("upsert", "delete")
