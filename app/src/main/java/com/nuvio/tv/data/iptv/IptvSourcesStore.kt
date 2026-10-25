package com.nuvio.tv.data.iptv

import android.util.Base64
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
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
    private val favoritesKey = stringSetPreferencesKey("favorite_channel_ids")

    private fun store(profileId: Int) = factory.get(profileId, FEATURE)

    private fun purpose(profileId: Int) = "nuvio-live/sources/v1/profile:$profileId"

    val sources: Flow<List<IptvSource>> = profileManager.activeProfileId.flatMapLatest { pid ->
        store(pid).data.catch { e -> if (e is java.io.IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw e }.map { prefs -> readable(prefs, pid) }
    }.flowOn(kotlinx.coroutines.Dispatchers.Default) // Keystore calls and JSON decoding, never on the main thread

    val favorites: Flow<Set<String>> = profileManager.activeProfileId.flatMapLatest { pid ->
        store(pid).data.catch { e -> if (e is java.io.IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw e }.map { it[favoritesKey].orEmpty() }
    }

    suspend fun current(): List<IptvSource> = sources.first()

    /** Saved sources that can be decrypted now, including a parked list once it becomes readable. */
    private fun readable(prefs: Preferences, pid: Int): List<IptvSource> =
        (prefs[sourcesKey]?.let { unseal(it, pid) }.orEmpty() + prefs[parkedKey]?.let { unseal(it, pid) }.orEmpty())
            .distinctBy { it.id }

    /**
     * Changes the saved sources in one DataStore transaction (read and write together, so a
     * failed read aborts instead of saving over the list). If the saved list exists but can't be
     * decrypted right now (a Keystore hiccup), it is parked rather than overwritten, and merged
     * back as soon as it decrypts again.
     */
    suspend fun update(transform: (List<IptvSource>) -> List<IptvSource>) {
        val pid = profileManager.activeProfileId.value
        store(pid).edit { prefs ->
            val saved = prefs[sourcesKey]
            val current = saved?.let { unseal(it, pid) }
            val parked = prefs[parkedKey]
            val parkedList = parked?.let { unseal(it, pid) }
            if (saved != null && current == null && parked == null) prefs[parkedKey] = saved
            if (parkedList != null) prefs.remove(parkedKey)
            val base = (current.orEmpty() + parkedList.orEmpty()).distinctBy { it.id }
            prefs[sourcesKey] = seal(transform(base), pid)
        }
    }

    private fun seal(list: List<IptvSource>, pid: Int): String {
        val plain = json.encodeToString(listSerializer, list).toByteArray()
        val sealed = try {
            vault.seal(plain, purpose(pid))
        } finally {
            plain.fill(0)
        }
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
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
        val plain = vault.open(Base64.decode(encoded, Base64.NO_WRAP), purpose(pid))
        try {
            json.decodeFromString(listSerializer, String(plain))
        } finally {
            plain.fill(0)
        }
    } catch (e: Exception) {
        // Keystore key lost (device reset of credentials) or tampered data: sources must be re-entered.
        Log.w(TAG, "Saved live TV sources could not be decrypted", e)
        null
    }

    companion object {
        private const val TAG = "IptvSourcesStore"
        private const val FEATURE = "nuvio_live"
    }
}
