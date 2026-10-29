package com.nuvio.tv.data.iptv

import java.io.IOException
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.util.Base64
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-profile Nuvio Live settings. The source list (playlist URLs, Xtream logins, guide URLs)
 * is stored only as AES-256-GCM ciphertext sealed by the Android Keystore key, bound to the
 * profile id so one profile's blob can't be replayed into another.
 *
 * Not part of Nuvio cloud sync (ProfileSettingsSyncService syncs an explicit feature allowlist)
 * and the app has android:allowBackup="false", so these never leave the device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class IptvSourcesStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
    private val vault: IptvVault
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSerializer = ListSerializer(IptvSource.serializer())
    private val sourcesKey = stringPreferencesKey("sources_sealed_v1")
    /** A saved list that couldn't be decrypted when it was about to be replaced; kept for recovery. */
    private val parkedKey = stringPreferencesKey("sources_sealed_v1_unreadable")
    private val mappingsKey = stringPreferencesKey("epg_mappings_sealed_v1")
    private val favoritesKey = stringSetPreferencesKey("favorite_channel_ids")

    @Serializable
    private data class EpgMappingsDocument(
        val version: Int = MAPPINGS_VERSION,
        val mappings: Map<String, String> = emptyMap()
    )

    private data class LegacySources(
        val sources: List<IptvSource>,
        val mappings: Map<String, String>
    )

    private fun store(profileId: Int) = factory.get(profileId, FEATURE)

    private fun purpose(profileId: Int) = "nuvio-live/sources/v1/profile:$profileId"
    private fun mappingsPurpose(profileId: Int) = "nuvio-live/epg-mappings/v$MAPPINGS_VERSION/profile:$profileId"

    val sources: Flow<List<IptvSource>> = profileManager.activeProfileId.flatMapLatest { pid ->
        flow {
            if (!ensureMappingsMigrated(pid)) {
                throw IptvException("Saved live TV mappings could not be opened; existing data was preserved")
            }
            emitAll(store(pid).data.orEmptyOnReadError().map { prefs -> readable(prefs, pid) })
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.Default) // Keystore calls and JSON decoding, never on the main thread

    val favorites: Flow<Set<String>> = profileManager.activeProfileId.flatMapLatest { pid ->
        store(pid).data.orEmptyOnReadError().map { it[favoritesKey].orEmpty() }
    }

    suspend fun current(): List<IptvSource> = sources.first()

    /** Saved sources that can be decrypted now, including a parked list once it becomes readable. */
    private fun readable(prefs: Preferences, pid: Int): List<IptvSource> {
        val mappings = prefs[mappingsKey]?.let {
            unsealMappings(it, pid)
                ?: throw IptvException("Saved live TV mappings could not be opened; existing data was preserved")
        }.orEmpty()
        return (prefs[sourcesKey]?.let { unseal(it, pid) }.orEmpty() + prefs[parkedKey]?.let { unseal(it, pid) }.orEmpty())
            .distinctBy { it.id }
            .map { source ->
                val prefix = "${source.id}::"
                source.copy(manualEpgMappings = mappings.filterKeys { it.startsWith(prefix) })
            }
    }

    /**
     * Changes the saved sources in one DataStore transaction (read and write together, so a
    * failed read aborts instead of saving over the list). Unreadable ciphertext is preserved;
    * previously parked sources are merged back only when every saved blob can be opened.
     */
    suspend fun update(transform: (List<IptvSource>) -> List<IptvSource>) {
        val pid = profileManager.activeProfileId.value
        if (!ensureMappingsMigrated(pid)) {
            throw IptvException("Saved live TV mappings could not be opened; existing data was preserved")
        }
        store(pid).edit { prefs ->
            val saved = prefs[sourcesKey]
            val current = saved?.let { unseal(it, pid) }
            val parked = prefs[parkedKey]
            val parkedList = parked?.let { unseal(it, pid) }
            if ((saved != null && current == null) || (parked != null && parkedList == null)) {
                throw IptvException("Saved live TV sources could not be opened; existing data was preserved")
            }
            val base = readable(prefs, pid)
            if (parkedList != null) prefs.remove(parkedKey)
            val updated = transform(base)
            prefs[sourcesKey] = seal(updated.map { it.copy(manualEpgMappings = emptyMap()) }, pid)
            prefs[mappingsKey] = sealMappings(updated.flatMap { it.manualEpgMappings.entries }.associate { it.key to it.value }, pid)
        }
    }

    /** Migrates legacy source-embedded mappings without replacing ciphertext that cannot be opened. */
    private suspend fun ensureMappingsMigrated(pid: Int): Boolean {
        var ready = false
        try {
            store(pid).edit { prefs ->
                val savedMappings = prefs[mappingsKey]
                if (savedMappings != null) {
                    ready = unsealMappings(savedMappings, pid) != null
                    return@edit
                }

                val sourceBlob = prefs[sourcesKey]
                val parkedBlob = prefs[parkedKey]
                val sourceData = sourceBlob?.let { unsealLegacy(it, pid) }
                val parkedData = parkedBlob?.let { unsealLegacy(it, pid) }
                if ((sourceBlob != null && sourceData == null) || (parkedBlob != null && parkedData == null)) {
                    return@edit
                }

                val currentMappings = sourceData?.mappings.orEmpty()
                val parkedMappings = parkedData?.mappings.orEmpty()
                if (currentMappings.any { (id, guide) -> id in parkedMappings && parkedMappings[id] != guide }) {
                    return@edit
                }
                val mappings = currentMappings + parkedMappings
                prefs[mappingsKey] = sealMappings(mappings, pid)
                sourceData?.let { prefs[sourcesKey] = seal(it.sources, pid) }
                parkedData?.let { prefs[parkedKey] = seal(it.sources, pid) }
                ready = true
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Saved live TV mappings could not be migrated")
        }
        return ready
    }

    private fun unsealLegacy(encoded: String, pid: Int): LegacySources? {
        val plain = try {
            vault.open(Base64.getDecoder().decode(encoded), purpose(pid))
        } catch (e: Exception) {
            Log.w(TAG, "Saved live TV sources could not be opened for migration")
            return null
        }
        return try {
            val rawSources = json.parseToJsonElement(String(plain)).jsonArray
            val sources = json.decodeFromString(listSerializer, String(plain))
            val mappings = LinkedHashMap<String, String>()
            rawSources.forEachIndexed { index, element ->
                sources.getOrNull(index) ?: return null
                val rawMappings = element.jsonObject["manualEpgMappings"] ?: return@forEachIndexed
                val legacyMappings = rawMappings as? kotlinx.serialization.json.JsonObject ?: return null
                legacyMappings.forEach { (channelId, value) ->
                    val primitive = value as? JsonPrimitive ?: return null
                    if (!primitive.isString) return null
                    val previous = mappings.put(channelId, primitive.content)
                    if (previous != null && previous != primitive.content) return null
                }
            }
            LegacySources(sources, mappings)
        } catch (e: Exception) {
            Log.w(TAG, "Saved live TV sources could not be decoded for migration")
            null
        } finally {
            plain.fill(0)
        }
    }

    private fun sealMappings(mappings: Map<String, String>, pid: Int): String {
        val plain = json.encodeToString(EpgMappingsDocument.serializer(), EpgMappingsDocument(mappings = mappings)).toByteArray()
        val sealed = try {
            vault.seal(plain, mappingsPurpose(pid))
        } finally {
            plain.fill(0)
        }
        return Base64.getEncoder().encodeToString(sealed)
    }

    private fun unsealMappings(encoded: String, pid: Int): Map<String, String>? = try {
        val plain = vault.open(Base64.getDecoder().decode(encoded), mappingsPurpose(pid))
        try {
            val document = json.decodeFromString(EpgMappingsDocument.serializer(), String(plain))
            if (document.version == MAPPINGS_VERSION) document.mappings else null
        } finally {
            plain.fill(0)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Saved live TV mappings could not be decrypted")
        null
    }

    private fun seal(list: List<IptvSource>, pid: Int): String {
        val plain = json.encodeToString(listSerializer, list).toByteArray()
        val sealed = try {
            vault.seal(plain, purpose(pid))
        } finally {
            plain.fill(0)
        }
        return Base64.getEncoder().encodeToString(sealed)
    }

    /** Drops favourites that belonged to a removed source (channel ids start with "sourceId::"). */
    suspend fun removeFavoritesOf(sourceId: String) {
        store(profileManager.activeProfileId.value).edit { prefs ->
            prefs[favoritesKey]?.let { favs -> prefs[favoritesKey] = favs.filterNot { it.startsWith("$sourceId::") }.toSet() }
        }
    }

    suspend fun toggleFavorite(channelId: String) {
        store(profileManager.activeProfileId.value).edit { prefs ->
            val current = prefs[favoritesKey].orEmpty()
            prefs[favoritesKey] = if (channelId in current) current - channelId else current + channelId
        }
    }

    private fun unseal(encoded: String, pid: Int): List<IptvSource>? = try {
        val plain = vault.open(Base64.getDecoder().decode(encoded), purpose(pid))
        try {
            json.decodeFromString(listSerializer, String(plain))
        } finally {
            plain.fill(0)
        }
    } catch (e: Exception) {
        // Keystore key lost (device reset of credentials) or tampered data: sources must be re-entered.
        Log.w(TAG, "Saved live TV sources could not be decrypted")
        null
    }

    companion object {
        private const val TAG = "IptvSourcesStore"
        private const val FEATURE = "nuvio_live"
        private const val MAPPINGS_VERSION = 1
    }
}

/** DataStore reports a damaged or unreadable file as IOException: read it as empty instead of crashing. */
internal fun Flow<Preferences>.orEmptyOnReadError(): Flow<Preferences> =
    catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
