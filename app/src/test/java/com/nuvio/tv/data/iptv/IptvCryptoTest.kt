package com.nuvio.tv.data.iptv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class IptvCryptoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun roundTrip(size: Int) {
        val key = newKey()
        val plain = ByteArray(size).also(SecureRandom()::nextBytes)
        val sealed = SegmentedAesGcm.encrypt(plain, key, "aad".toByteArray())
        val opened = SegmentedAesGcm.decryptingStream(sealed.inputStream(), key, "aad".toByteArray()).use { it.readBytes() }
        assertArrayEquals(plain, opened)
    }

    @Test
    fun `segmented stream round trips at segment boundaries`() {
        val s = SegmentedAesGcm.SEGMENT_SIZE
        listOf(0, 1, s - 1, s, s + 1, 2 * s, 3 * s + 17).forEach(::roundTrip)
    }

    @Test
    fun `segmented stream detects tampering, truncation and wrong context`() {
        val key = newKey()
        val plain = ByteArray(SegmentedAesGcm.SEGMENT_SIZE * 2 + 5).also(SecureRandom()::nextBytes)
        val sealed = SegmentedAesGcm.encrypt(plain, key, "a".toByteArray())

        val flipped = sealed.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        assertThrows(IOException::class.java) {
            SegmentedAesGcm.decryptingStream(flipped.inputStream(), key, "a".toByteArray()).use { it.readBytes() }
        }
        // Cut exactly after the first full segment: must not be accepted as a complete file.
        val truncated = sealed.copyOf(11 + SegmentedAesGcm.SEGMENT_SIZE + 16)
        assertThrows(IOException::class.java) {
            SegmentedAesGcm.decryptingStream(truncated.inputStream(), key, "a".toByteArray()).use { it.readBytes() }
        }
        assertThrows(IOException::class.java) {
            SegmentedAesGcm.decryptingStream(sealed.inputStream(), key, "b".toByteArray()).use { it.readBytes() }
        }
        assertThrows(IOException::class.java) {
            SegmentedAesGcm.decryptingStream(sealed.inputStream(), newKey(), "a".toByteArray()).use { it.readBytes() }
        }
    }

    @Test
    fun `ciphertext does not contain the plaintext`() {
        val secret = "http://p.example/get.php?username=alice&password=hunter2".toByteArray()
        val sealed = SegmentedAesGcm.encrypt(secret, newKey())
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("hunter2"))
    }

    @Test
    fun `software key wraps with associated data`() {
        val kek = SoftwareAesGcmKey(newKey())
        val blob = kek.encrypt("secret".toByteArray(), "profile:1".toByteArray())
        assertEquals("secret", String(kek.decrypt(blob, "profile:1".toByteArray())))
        assertThrows(Exception::class.java) { kek.decrypt(blob, "profile:2".toByteArray()) }
    }

    @Test
    fun `vault keeps a wrapped cache key and file cache stays encrypted`() {
        val kek = SoftwareAesGcmKey(newKey())
        val keyDir = tmp.newFolder("keys")
        val vault = IptvVault(kek, keyDir)
        val key1 = vault.cacheKey()
        // A fresh vault instance (app restart) unwraps the same key.
        assertArrayEquals(key1.encoded, IptvVault(kek, keyDir).cacheKey().encoded)
        assertFalse(keyDir.listFiles()!!.single().readBytes().contentEquals(key1.encoded))

        val cacheDir = tmp.newFolder("cache")
        val cache = IptvFileCache(cacheDir, vault::cacheKey)
        val logicalKey = "m3u|http://p.example/get.php?username=alice&password=hunter2"
        cache.write(logicalKey) { it.write("#EXTM3U\nsecret-stream".toByteArray()) }

        val files = cacheDir.listFiles()!!
        assertEquals(1, files.size)
        assertFalse(files[0].name.contains("hunter2"))
        assertFalse(String(files[0].readBytes(), Charsets.ISO_8859_1).contains("secret-stream"))
        assertEquals("#EXTM3U\nsecret-stream", cache.read(logicalKey) { String(it.readBytes()) })
        assertTrue(cache.ageMs(logicalKey)!! >= 0)
        assertNull(cache.read("missing") { it.readBytes() })
    }
}
