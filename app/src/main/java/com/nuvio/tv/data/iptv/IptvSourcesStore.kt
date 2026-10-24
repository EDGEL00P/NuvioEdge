package com.nuvio.tv.data.iptv

import android.util.Base64
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
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
    private val favoritesKey = stringSetPreferencesKey("favorite_channel_ids")

    private fun store(profileId: Int) = factory.get(profileId, FEATURE)

    private fun purpose(profileId: Int) = "nuvio-live/sources/v1/profile:$profileId"

    val sources: Flow<List<IptvSource>> = profileManager.activeProfileId.flatMapLatest { pid ->
        store(pid).data.map { prefs -> prefs[sourcesKey]?.let { unseal(it, pid) }.orEmpty() }
    }

    val favorites: Flow<Set<String>> = profileManager.activeProfileId.flatMapLatest { pid ->
        store(pid).data.map { it[favoritesKey].orEmpty() }
    }

    suspend fun current(): List<IptvSource> = sources.first()

    suspend fun save(list: List<IptvSource>) {
        val pid = profileManager.activeProfileId.value
        val plain = json.encodeToString(listSerializer, list).toByteArray()
        val sealed = try {
            vault.seal(plain, purpose(pid))
        } finally {
            plain.fill(0)
        }
        store(pid).edit { it[sourcesKey] = Base64.encodeToString(sealed, Base64.NO_WRAP) }
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
