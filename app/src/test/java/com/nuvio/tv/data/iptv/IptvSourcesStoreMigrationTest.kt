package com.nuvio.tv.data.iptv

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.crypto.KeyGenerator

class IptvSourcesStoreMigrationTest {
    private lateinit var directory: File
    private lateinit var activeProfile: MutableStateFlow<Int>
    private lateinit var profileManager: ProfileManager
    private lateinit var factory: ProfileDataStoreFactory
    private lateinit var vault: IptvVault
    private val dataStores = mutableMapOf<Int, DataStore<Preferences>>()
    private val jobs = mutableMapOf<Int, kotlinx.coroutines.Job>()

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("iptv_store_test").toFile()
        activeProfile = MutableStateFlow(1)
        profileManager = mockk { every { activeProfileId } returns this@IptvSourcesStoreMigrationTest.activeProfile }
        factory = mockk {
            every { get(any(), any()) } answers { dataStoreFor(firstArg()) }
        }
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        vault = IptvVault(SoftwareAesGcmKey(key), File(directory, "keys"))
    }

    @After
    fun tearDown() {
        runBlocking {
            closeStores()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `legacy mappings migrate once into encrypted profile key and survive restart`() = runBlocking {
        val legacyJson = """[{"id":"source-a","name":"Local","type":"M3U","url":"https://user:secret@example.test/list.m3u","manualEpgMappings":{"source-a::1":"guide-a"}}]"""
        val legacyBlob = Base64.getEncoder().encodeToString(
            vault.seal(legacyJson.toByteArray(), "nuvio-live/sources/v1/profile:1")
        )
        dataStoreFor(1).edit { it[stringPreferencesKey("sources_sealed_v1")] = legacyBlob }

        val migrated = newStore().sources.first().single()
        assertEquals(mapOf("source-a::1" to "guide-a"), migrated.manualEpgMappings)
        val prefs = dataStoreFor(1).data.first()
        val storedSources = vault.open(
            Base64.getDecoder().decode(prefs[stringPreferencesKey("sources_sealed_v1")]),
            "nuvio-live/sources/v1/profile:1"
        )
        assertFalse(String(storedSources).contains("manualEpgMappings"))
        assertFalse(prefs[stringPreferencesKey("sources_sealed_v1")]!!.contains("secret"))
        val mappingCiphertext = prefs[stringPreferencesKey("epg_mappings_sealed_v1")]!!
        assertFalse(mappingCiphertext.contains("guide-a"))

        closeStores()
        val restored = newStore().sources.first().single()
        assertEquals(mapOf("source-a::1" to "guide-a"), restored.manualEpgMappings)
    }

    @Test
    fun `corrections persist separately by profile and are cleared with source deletion`() = runBlocking {
        val store = newStore()
        store.update { listOf(source("source-a", "source-a::1", "guide-one")) }
        activeProfile.value = 2
        assertTrue(store.sources.first().isEmpty())
        store.update { listOf(source("source-b", "source-b::1", "guide-two")) }

        activeProfile.value = 1
        assertEquals(mapOf("source-a::1" to "guide-one"), store.sources.first().single().manualEpgMappings)
        store.update { emptyList() }
        assertTrue(store.sources.first().isEmpty())
        val mappingText = vault.open(
            Base64.getDecoder().decode(dataStoreFor(1).data.first()[stringPreferencesKey("epg_mappings_sealed_v1")]),
            "nuvio-live/epg-mappings/v1/profile:1"
        ).decodeToString()
        assertFalse(mappingText.contains("guide-one"))
        closeStores()
        assertTrue(newStore().current().isEmpty())
        activeProfile.value = 2
        assertEquals(mapOf("source-b::1" to "guide-two"), newStore().current().single().manualEpgMappings)
    }

    @Test
    fun `corrupted separate mapping ciphertext blocks writes without replacing it`() = runBlocking {
        val store = newStore()
        store.update { listOf(source("source-a", "source-a::1", "guide-a")) }
        val key = stringPreferencesKey("epg_mappings_sealed_v1")
        dataStoreFor(1).edit { it[key] = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)) }
        val corrupted = dataStoreFor(1).data.first()[key]

        assertThrows(IptvException::class.java) {
            runBlocking { store.update { it } }
        }
        assertEquals(corrupted, dataStoreFor(1).data.first()[key])
    }

    @Test
    fun `readable parked sources and their mappings survive the next update`() = runBlocking {
        val sourceKey = stringPreferencesKey("sources_sealed_v1")
        val parkedKey = stringPreferencesKey("sources_sealed_v1_unreadable")
        val store = newStore()
        store.update { listOf(source("source-a", "source-a::1", "guide-a")) }
        dataStoreFor(1).edit {
            it[parkedKey] = it[sourceKey]!!
            it.remove(sourceKey)
        }

        store.update { it + source("source-b", "source-b::1", "guide-b") }

        assertEquals(setOf("source-a", "source-b"), store.current().map { it.id }.toSet())
        assertEquals(mapOf("source-a::1" to "guide-a"), store.current().first().manualEpgMappings)
        assertFalse(dataStoreFor(1).data.first().contains(parkedKey))
    }

    @Test
    fun `unreadable legacy ciphertext prevents migration and remains intact`() = runBlocking {
        val sourceKey = stringPreferencesKey("sources_sealed_v1")
        val blob = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        dataStoreFor(1).edit { it[sourceKey] = blob }

        assertThrows(IptvException::class.java) { runBlocking { newStore().update { emptyList() } } }

        val prefs = dataStoreFor(1).data.first()
        assertEquals(blob, prefs[sourceKey])
        assertFalse(prefs.contains(stringPreferencesKey("epg_mappings_sealed_v1")))
    }

    @Test
    fun `mapping ciphertext cannot be replayed into another profile`() = runBlocking {
        val store = newStore()
        store.update { listOf(source("source-a", "source-a::1", "guide-a")) }
        val key = stringPreferencesKey("epg_mappings_sealed_v1")
        val blob = dataStoreFor(1).data.first()[key]!!
        dataStoreFor(2).edit { it[key] = blob }
        activeProfile.value = 2

        assertThrows(IptvException::class.java) { runBlocking { store.current() } }
        assertThrows(IptvException::class.java) { runBlocking { store.update { emptyList() } } }
        assertEquals(blob, dataStoreFor(2).data.first()[key])
    }

    @Test
    fun `unsupported mapping version preserves both blobs`() = runBlocking {
        val store = newStore()
        store.update { listOf(source("source-a", "source-a::1", "guide-a")) }
        val key = stringPreferencesKey("epg_mappings_sealed_v1")
        dataStoreFor(1).edit {
            it[key] = Base64.getEncoder().encodeToString(vault.seal(
                """{"version":2,"mappings":{"source-a::1":"guide-a"}}""".toByteArray(),
                "nuvio-live/epg-mappings/v1/profile:1"
            ))
        }
        val before = dataStoreFor(1).data.first()

        assertThrows(IptvException::class.java) { runBlocking { store.update { emptyList() } } }
        assertEquals(before, dataStoreFor(1).data.first())
    }

    @Test
    fun `invalid or conflicting legacy mapping values abort without modifying ciphertext`() = runBlocking {
        val sourceKey = stringPreferencesKey("sources_sealed_v1")
        val parkedKey = stringPreferencesKey("sources_sealed_v1_unreadable")
        val legacy = """[{"id":"source-a","name":"Local","type":"M3U","url":"https://example.test","manualEpgMappings":{"source-a::1":"guide-a"}}]"""
        for (invalid in listOf(legacy.replace("\"guide-a\"", "123"), legacy)) {
            dataStoreFor(1).edit {
                it.clear()
                it[sourceKey] = Base64.getEncoder().encodeToString(vault.seal(invalid.toByteArray(), "nuvio-live/sources/v1/profile:1"))
                it[parkedKey] = Base64.getEncoder().encodeToString(vault.seal(legacy.replace("guide-a", "guide-b").toByteArray(), "nuvio-live/sources/v1/profile:1"))
            }
            val before = dataStoreFor(1).data.first()
            assertThrows(IptvException::class.java) { runBlocking { newStore().update { emptyList() } } }
            assertEquals(before, dataStoreFor(1).data.first())
        }
    }

    private fun source(id: String, channelId: String, guideId: String) = IptvSource(
        id = id,
        name = id,
        type = IptvSourceType.M3U,
        url = "https://example.test/$id.m3u",
        manualEpgMappings = mapOf(channelId to guideId)
    )

    private fun newStore() = IptvSourcesStore(factory, profileManager, vault)

    private fun dataStoreFor(profileId: Int): DataStore<Preferences> = dataStores.getOrPut(profileId) {
        val job = SupervisorJob()
        jobs[profileId] = job
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { File(directory, "profile-$profileId.preferences_pb") }
        )
    }

    private suspend fun closeStores() {
        jobs.values.forEach { it.cancelAndJoin() }
        jobs.clear()
        dataStores.clear()
    }
}