package com.nuvio.tv.data.iptv

import java.util.Locale

/** Deterministic automatic guide resolution. Fuzzy suggestions are deliberately not assignments. */
class EpgMatcher(
    declaredChannelIds: Set<String>,
    channelIdsByName: Map<String, Set<String>>,
    private val nameIndexComplete: Boolean = true
) {
    private val declaredIds = declaredChannelIds.toSet()
    private val channelIdsByName = channelIdsByName.mapValues { (_, ids) -> ids.intersect(declaredIds).toSortedSet() }
    private val declaredIdsByFoldedId = declaredChannelIds
        .groupBy { foldGuideId(it) }
        .mapValues { (_, ids) -> ids.toSet() }

    fun resolve(providerId: String?, displayNames: List<String>, manualOverride: Boolean = false): EpgResolution {
        val canonicalId = canonicalDeclaredId(providerId)
        if (canonicalId != null) {
            return EpgResolution(canonicalId, if (manualOverride) EpgMatchKind.MANUAL else EpgMatchKind.EXACT_ID)
        }
        if (manualOverride) return EpgResolution(null, EpgMatchKind.UNMATCHED)
        val foldedCandidates = providerId?.let { declaredIdsByFoldedId[foldGuideId(it)] }.orEmpty()
        if (foldedCandidates.size > 1) {
            return EpgResolution(null, EpgMatchKind.AMBIGUOUS, foldedCandidates.toSortedSet())
        }
        if (!nameIndexComplete) return EpgResolution(null, EpgMatchKind.UNMATCHED)

        resolveNameKeys(displayNames.map { XmltvParser.normalizeChannelName(it) })?.let { return it }
        resolveNameKeys(displayNames.mapNotNull { XmltvParser.stripProviderPrefix(it) }
            .map { XmltvParser.normalizeChannelName(it) })?.let {
            return it
        }
        return EpgResolution(null, EpgMatchKind.UNMATCHED)
    }

    fun canonicalDeclaredId(id: String?): String? {
        val trimmed = id?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (trimmed in declaredIds) return trimmed
        return declaredIdsByFoldedId[foldGuideId(trimmed)]?.singleOrNull()
    }

    private fun resolveNameKeys(keys: List<String>): EpgResolution? {
        for (key in keys) {
            if (key.isEmpty()) continue
            val candidates = channelIdsByName[key].orEmpty()
            if (candidates.size > 1) return EpgResolution(null, EpgMatchKind.AMBIGUOUS, candidates)
            candidates.singleOrNull()?.let { return EpgResolution(it, EpgMatchKind.NAME) }
        }
        return null
    }

    companion object {
        /** Guide ids are opaque tokens: only surrounding whitespace and case are normalized. */
        fun foldGuideId(id: String): String = id.trim().lowercase(Locale.ROOT)
    }
}