package com.nuvio.tv.data.iptv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/** AES-256-GCM key generated inside the Android Keystore; the key material never enters app memory. */
class AndroidKeystoreKey(private val alias: String) : KeyEncryptionKey {

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray, associatedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(associatedData)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "Unexpected IV size" }
        return iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray, associatedData: ByteArray): ByteArray {
        if (blob.size < IV_BYTES + TAG_BYTES) throw IOException("Ciphertext too short")
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

/**
 * Holds every key Nuvio Live uses:
 *  - a non-exportable Android Keystore key that seals saved sources (URLs, logins), and
 *  - a random cache data key, stored only wrapped by that Keystore key, for the encrypted
 *    playlist/guide cache (software AES is needed there for speed on large files).
 */
@Singleton
class IptvVault internal constructor(
    private val kek: KeyEncryptionKey,
    private val keyDir: File
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        kek = AndroidKeystoreKey(KEYSTORE_ALIAS),
        keyDir = File(context.noBackupFilesDir, "nuvio_live")
    )

    @Volatile
    private var dataKey: SecretKey? = null

    fun seal(plain: ByteArray, purpose: String): ByteArray = kek.encrypt(plain, purpose.toByteArray())

    fun open(blob: ByteArray, purpose: String): ByteArray = kek.decrypt(blob, purpose.toByteArray())

    /** Cache data key; regenerated (and the old cache discarded by the caller) if it can't be unwrapped. */
    @Synchronized
    fun cacheKey(): SecretKey {
        dataKey?.let { return it }
        val file = File(keyDir, DATA_KEY_FILE)
        val existing = if (file.exists()) {
            runCatching { SecretKeySpec(kek.decrypt(file.readBytes(), DATA_KEY_AAD), "AES") }
                .onFailure { Log.w(TAG, "Cache key could not be unwrapped; creating a new one") }
                .getOrNull()
        } else null
        val key = existing ?: run {
            val raw = ByteArray(32).also(SecureRandom()::nextBytes)
            keyDir.mkdirs()
            val tmp = File(keyDir, "$DATA_KEY_FILE.tmp")
            tmp.writeBytes(kek.encrypt(raw, DATA_KEY_AAD))
            if (!tmp.renameTo(file)) throw IOException("Could not store cache key")
            SecretKeySpec(raw, "AES").also { raw.fill(0) }
        }
        dataKey = key
        return key
    }

    companion object {
        private const val TAG = "IptvVault"
        private const val KEYSTORE_ALIAS = "nuvio_live_kek_v1"
        private const val DATA_KEY_FILE = "cache_key.bin"
        private val DATA_KEY_AAD = "nuvio-live/cache-key/v1".toByteArray()
    }
}

/**
 * Encrypted on-disk cache. File names are HMAC-SHA256(cache key, logical key) so neither the
 * credentials inside a playlist URL nor a guessable hash of them ever appear on disk.
 */
class IptvFileCache(
    private val dir: File,
    private val keyProvider: () -> SecretKey
) {
    private fun fileName(key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyProvider().encoded, "HmacSHA256"))
        return mac.doFinal(key.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun fileFor(key: String) = File(dir, fileName(key) + ".enc")

    fun ageMs(key: String, nowMs: Long = System.currentTimeMillis()): Long? {
        val file = fileFor(key)
        return if (file.exists()) nowMs - file.lastModified() else null
    }

    /** Writes through the encrypting stream into a temp file, then atomically replaces the entry. */
    fun write(key: String, block: (OutputStream) -> Unit) {
        dir.mkdirs()
        val name = fileName(key)
        val target = File(dir, "$name.enc")
        val tmp = File(dir, "$name.tmp")
        try {
            SegmentedAesGcm.encryptingStream(tmp.outputStream().buffered(), keyProvider(), name.toByteArray())
                .use(block)
            if (!tmp.renameTo(target)) throw IOException("Could not store cache entry")
        } finally {
            tmp.delete()
        }
    }

    /** Decrypting stream, or null when absent. A file that fails authentication is deleted. */
    fun open(key: String): InputStream? {
        val name = fileName(key)
        val file = File(dir, "$name.enc")
        if (!file.exists()) return null
        return try {
            SegmentedAesGcm.decryptingStream(file.inputStream().buffered(), keyProvider(), name.toByteArray())
        } catch (e: IOException) {
            file.delete()
            null
        }
    }

    fun <T> read(key: String, block: (InputStream) -> T): T? {
        val stream = open(key) ?: return null
        return try {
            stream.use(block)
        } catch (e: IOException) {
            fileFor(key).delete()
            throw e
        }
    }

    /** Marks an entry fresh again (the server said it hasn't changed). */
    fun touch(key: String) {
        fileFor(key).setLastModified(System.currentTimeMillis())
    }

    internal fun writeValidators(key: String, validators: Validators) {
        if (validators.etag == null && validators.lastModified == null) {
            fileFor(META_PREFIX + key).delete()
            return
        }
        val text = listOf(validators.etag.orEmpty(), validators.lastModified.orEmpty()).joinToString("\n")
        write(META_PREFIX + key) { it.write(text.toByteArray()) }
    }

    internal fun readValidators(key: String): Validators? =
        runCatching { read(META_PREFIX + key) { String(it.readBytes()) } }.getOrNull()?.let { text ->
            val lines = text.split("\n")
            Validators(lines.getOrNull(0)?.ifEmpty { null }, lines.getOrNull(1)?.ifEmpty { null })
        }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val META_PREFIX = "meta|"
    }
}
