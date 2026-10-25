package com.nuvio.tv.data.iptv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.ProviderException
import javax.crypto.KeyGenerator

class ResilientKeyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val aad = "purpose".toByteArray()
    private val plain = "http://server/user/pass".toByteArray()

    private fun softwareKey() = SoftwareAesGcmKey(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())

    /** Behaves like a box whose Keystore throws on every call. */
    private object BrokenKeystore : KeyEncryptionKey {
        override fun encrypt(plain: ByteArray, associatedData: ByteArray): ByteArray = throw ProviderException("Keystore broken")
        override fun decrypt(blob: ByteArray, associatedData: ByteArray): ByteArray = throw ProviderException("Keystore broken")
    }

    @Test
    fun `working keystore is used and no fallback key is created`() {
        val file = File(tmp.root, "fallback.bin")
        val key = ResilientKey(softwareKey()) { create -> softwareKeyFile(file, create) }
        assertArrayEquals(plain, key.decrypt(key.encrypt(plain, aad), aad))
        assertFalse(file.exists())
    }

    @Test
    fun `broken keystore falls back and data stays readable after restart`() {
        val file = File(tmp.root, "fallback.bin")
        val first = ResilientKey(BrokenKeystore) { create -> softwareKeyFile(file, create) }
        val sealed = first.encrypt(plain, aad)
        assertTrue(file.exists())
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("pass"))
        // A new process: the keystore is still broken, the fallback key file is reloaded.
        val second = ResilientKey(BrokenKeystore) { create -> softwareKeyFile(file, create) }
        assertArrayEquals(plain, second.decrypt(sealed, aad))
    }

    @Test
    fun `data written with the fallback key is readable once the keystore works again`() {
        val file = File(tmp.root, "fallback.bin")
        val sealed = ResilientKey(BrokenKeystore) { create -> softwareKeyFile(file, create) }.encrypt(plain, aad)
        val healed = ResilientKey(softwareKey()) { create -> softwareKeyFile(file, create) }
        assertArrayEquals(plain, healed.decrypt(sealed, aad))
    }

    @Test
    fun `tampered data is still rejected`() {
        val file = File(tmp.root, "fallback.bin")
        val key = ResilientKey(BrokenKeystore) { create -> softwareKeyFile(file, create) }
        val sealed = key.encrypt(plain, aad).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertThrows(IOException::class.java) { key.decrypt(sealed, aad) }
        assertThrows(IOException::class.java) { key.decrypt(key.encrypt(plain, aad), "other".toByteArray()) }
    }

    @Test
    fun `one keystore hiccup does not switch keys`() {
        val file = File(tmp.root, "fallback.bin")
        val real = softwareKey()
        var calls = 0
        val flaky = object : KeyEncryptionKey {
            override fun encrypt(plain: ByteArray, associatedData: ByteArray): ByteArray {
                if (calls++ == 0) throw ProviderException("busy")
                return real.encrypt(plain, associatedData)
            }
            override fun decrypt(blob: ByteArray, associatedData: ByteArray) = real.decrypt(blob, associatedData)
        }
        val key = ResilientKey(flaky) { create -> softwareKeyFile(file, create) }
        assertArrayEquals(plain, real.decrypt(key.encrypt(plain, aad), aad))
        assertFalse(file.exists())
    }

    @Test
    fun `keystore data stays readable after the keystore failed to encrypt`() {
        val file = File(tmp.root, "fallback.bin")
        val real = softwareKey()
        val sealedEarlier = real.encrypt(plain, aad)
        val encryptBroken = object : KeyEncryptionKey {
            override fun encrypt(plain: ByteArray, associatedData: ByteArray): ByteArray = throw ProviderException("broken")
            override fun decrypt(blob: ByteArray, associatedData: ByteArray) = real.decrypt(blob, associatedData)
        }
        val key = ResilientKey(encryptBroken) { create -> softwareKeyFile(file, create) }
        key.encrypt(plain, aad) // switches to the fallback key
        assertArrayEquals(plain, key.decrypt(sealedEarlier, aad))
    }
}
